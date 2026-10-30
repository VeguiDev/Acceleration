/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.apdp;

import com.google.gson.JsonObject;
import com.velocitypowered.proxy.apdp.config.ApdpConfig;
import com.velocitypowered.proxy.apdp.config.AtomicFile;
import com.velocitypowered.proxy.apdp.identity.Authority;
import com.velocitypowered.proxy.apdp.identity.Membership;
import com.velocitypowered.proxy.apdp.identity.NodeIdentity;
import com.velocitypowered.proxy.apdp.identity.Signed;
import com.velocitypowered.proxy.apdp.model.DiscoveredCatalog;
import com.velocitypowered.proxy.apdp.model.Publication;
import com.velocitypowered.proxy.apdp.model.Topology;
import com.velocitypowered.proxy.apdp.protocol.Wire;
import com.velocitypowered.proxy.apdp.transport.NoiseChannel;
import com.velocitypowered.proxy.util.AddressUtil;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Isolated APDP control service. No Minecraft channels, transfers or socket registration. */
public final class ApdpService implements AutoCloseable {
  private static final Logger LOGGER = LogManager.getLogger(ApdpService.class);
  private final Path directory;
  private final ApdpConfig config;
  private final Supplier<Set<String>> destinations;
  private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2,
      task -> Thread.ofPlatform().daemon().name("apdp-maintenance").unstarted(task));
  private final Semaphore requests = new Semaphore(64);
  private final Semaphore handshakes = new Semaphore(16);
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private final Map<UUID, Session> children = new ConcurrentHashMap<>();
  private final Map<UUID, Signed> exports = new ConcurrentHashMap<>();
  private final DiscoveredCatalog catalog = new DiscoveredCatalog();
  private final AtomicBoolean connecting = new AtomicBoolean();
  private final Map<UUID, JsonObject> pending = new HashMap<>();
  private final Map<UUID, Signed> invitations = new HashMap<>();
  private volatile Local state;
  private volatile ApdpConfig.Settings settings;
  private NodeIdentity identity;
  private java.nio.channels.FileChannel lockChannel;
  private java.nio.channels.FileLock stateLock;
  private Authority authority;
  private ServerSocket listener;
  private volatile Session parent;
  private volatile int depth;
  private volatile boolean closed;
  private volatile Signed published;
  private volatile Signed snapshot;
  private volatile String fingerprint = "";
  private volatile String health = "disabled";
  private volatile long policyRevision;
  private volatile Signed policy;
  private volatile Set<UUID> revoked = Set.of();
  private volatile Set<UUID> disabledInviters = Set.of();
  private volatile java.util.function.BiFunction<UUID, JsonObject, JsonObject> commandHandler;

  /** Installs the version-one ADCR neighbor protocol, separate from authority operations. */
  public void commandHandler(java.util.function.BiFunction<UUID, JsonObject, JsonObject> handler) {
    commandHandler = java.util.Objects.requireNonNull(handler);
  }

  /** Only live, mutually advertised direct neighbors accept command RPCs. No retries. */
  public JsonObject commandRequest(UUID peer, JsonObject request) throws IOException {
    Session session = commandSession(peer);
    return session.request("COMMAND_RPC", request);
  }

  /** Reports the negotiated capability of a currently authenticated neighbor. */
  public boolean commandPeer(UUID peer) {
    try {
      commandSession(peer);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  public UUID commandParent() {
    return parent == null ? null : parent.peer;
  }

  /** Head-only signed ADCR transfer authorization, independent of authority database RPCs. */
  public Signed signCommandIntent(JsonObject intent) throws GeneralSecurityException, IOException {
    if (state == null || !identity.nodeId().equals(state.head)) {
      throw new IOException("Only head may authorize distributed transfers");
    }
    return Signed.sign(intent, identity.signing().getPrivate());
  }

  /** Verifies the pinned head signature; the caller must validate the intent's complete schema. */
  public JsonObject verifyCommandIntent(Signed intent) throws GeneralSecurityException, IOException {
    if (state == null) {
      throw new IOException("APDP disabled");
    }
    return intent.verify(state.root);
  }

  private Session commandSession(UUID peer) throws IOException {
    Session session = parent != null && parent.peer.equals(peer) ? parent : children.get(peer);
    if (session == null || session.stopped || !session.commands || commandHandler == null
        || revoked.contains(peer)) {
      throw new IOException("ADCR neighbor unavailable");
    }
    return session;
  }

  /** Public membership and pending enrollment metadata only; no bearer or private key. */
  public record Local(UUID network, UUID head, String root, Signed member, Signed parent,
                      Signed ticket, UUID enrollment, long revision, long snapshotRevision) { }

  /** Typed control frame. Sequence is per Noise connection; IDs correlate bounded RPCs. */
  private record Frame(String type, UUID id, long sequence, JsonObject payload) { }

  /** Creates a disabled service with a live supplier of locally registered destination names. */
  public ApdpService(Path workingDirectory, Supplier<Set<String>> destinations) {
    directory = workingDirectory.toAbsolutePath().resolve("apdp-state");
    config = new ApdpConfig(workingDirectory.resolve("apdp.toml"));
    this.destinations = destinations;
  }

  public DiscoveredCatalog catalog() {
    return catalog;
  }

  public UUID localNode() {
    return identity == null ? null : identity.nodeId();
  }

  public UUID head() {
    return state == null ? null : state.head;
  }

  public int depth() {
    return depth;
  }

  public int weight() {
    return settings == null || settings.weight() < 0 ? depth : settings.weight();
  }

  /** Starts configured state only. Ordinary installations create no identity or control listener. */
  public void start() throws IOException, GeneralSecurityException {
    if (Files.exists(directory.resolve("local.json"))) {
      if (!Files.exists(directory.resolve("identity.bin"))) {
        throw new IOException("Enrolled APDP identity is missing; refusing to regenerate keys");
      }
      identity = NodeIdentity.loadOrCreate(directory);
      lockState();
      state = Wire.GSON.fromJson(Wire.parse(AtomicFile.read(directory.resolve("local.json"),
          Wire.MAX)), Local.class);
      settings = config.load();
      validateLocal();
      if (identity.nodeId().equals(state.head)) {
        authority = new Authority(identity, directory.resolve("authority.json"), false,
            settings.maxNodes(), settings.maxDepth());
      }
      loadMap("pending.json", pending, JsonObject.class);
      loadMap("invitations.json", invitations, Signed.class);
      if (Files.exists(directory.resolve("policy.json"))) {
        applyPolicy(Signed.decode(Wire.parse(AtomicFile.read(directory.resolve("policy.json"),
            Wire.MAX))));
      }
      listen();
    }
    timer.scheduleWithFixedDelay(this::tick, 1, 2, TimeUnit.SECONDS);
    timer.scheduleWithFixedDelay(this::watchdog, 1, 1, TimeUnit.SECONDS);
    String tokenFile = System.getenv("APDP_JOIN_TOKEN_FILE");
    if (tokenFile != null && !tokenFile.isBlank()) {
      // A file input avoids environment values, console history and command-event exposure.
      if (state == null || state.member == null) {
        join(new String(AtomicFile.read(Path.of(tokenFile), 32768),
            java.nio.charset.StandardCharsets.UTF_8).strip());
      }
    }
  }

  private void validateLocal() throws IOException, GeneralSecurityException {
    if (state.network == null || state.head == null || state.root == null || state.revision < 0) {
      throw new IOException("Invalid APDP local state");
    }
    if (state.member != null) {
      Membership member = Membership.verify(state.member, state.root);
      if (!member.node().equals(identity.nodeId()) || !member.network().equals(state.network)
          || !member.noise().equals(Wire.b64(NoiseChannel.publicKey(identity)))
          || !member.signing().equals(Wire.b64(identity.signing().getPublic().getEncoded()))) {
        throw new IOException("APDP membership does not match persistent identity");
      }
    }
  }

  private <T> void loadMap(String file, Map<UUID, T> target, Class<T> type) throws IOException {
    if (Files.exists(directory.resolve(file))) {
      JsonObject object = Wire.parse(AtomicFile.read(directory.resolve(file), Wire.MAX));
      for (String key : object.keySet()) {
        target.put(UUID.fromString(key), Wire.GSON.fromJson(object.get(key), type));
      }
    }
  }

  private synchronized void save(Local replacement) throws IOException {
    AtomicFile.write(directory.resolve("local.json"), Wire.encode(replacement), state == null);
    state = replacement;
  }

  /** Initializes a head explicitly; never silently replaces existing membership or keys. */
  public synchronized void init(String name) throws IOException, GeneralSecurityException {
    if (state != null || Files.exists(directory.resolve("authority.json"))) {
      throw new IOException("APDP is already initialized");
    }
    checkName(name);
    identity = NodeIdentity.loadOrCreate(directory);
    lockState();
    config.create(name);
    settings = config.load();
    authority = new Authority(identity, directory.resolve("authority.json"), true,
        settings.maxNodes(), settings.maxDepth());
    Authority.State initial = authority.snapshot();
    save(new Local(initial.network(), identity.nodeId(), authority.root(),
        initial.members().get(identity.nodeId()), null, null, null, 0, 0));
    settings = config.load();
    listen();
    health = "head";
  }

  private static void checkName(String name) throws IOException {
    if (!name.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
      throw new IOException("Name must contain lowercase letters, digits, _ or - (max 64)");
    }
  }

  private void listen() throws IOException {
    listener = new ServerSocket();
    InetSocketAddress bind = AddressUtil.parseAddress(settings.listen());
    listener.bind(bind, 32);
    if (bind.getPort() == 0) {
      String fixed = hostPort(bind.getHostString(), listener.getLocalPort());
      config.edit(value -> value.set("listen", fixed));
      settings = config.load();
    }
    workers.submit(() -> {
      while (!closed) {
        try {
          Socket socket = listener.accept();
          if (sockets.size() >= settings.maxPeers() + 16 || !handshakes.tryAcquire()) {
            socket.close();
            continue;
          }
          sockets.add(socket);
          workers.submit(() -> accept(socket));
        } catch (IOException e) {
          if (!closed) {
            health = "listener-failed";
          }
          break;
        }
      }
    });
    health = authority == null ? "waiting-for-parent" : "head";
  }

  private String endpoint() throws IOException {
    if (!settings.advertise().isBlank()) {
      AddressUtil.parseAddress(settings.advertise());
      return settings.advertise();
    }
    InetSocketAddress bind = (InetSocketAddress) listener.getLocalSocketAddress();
    if (bind.getAddress().isAnyLocalAddress()) {
      throw new IOException("Set advertise (or APDP_ADVERTISE_ADDRESS) for wildcard listeners");
    }
    return hostPort(bind.getHostString(), bind.getPort());
  }

  private static String hostPort(String host, int port) {
    return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
  }

  /** Creates a one-enrollment token. Caller must deliver it privately out of band. */
  public String invite(int seconds, boolean delegate)
      throws IOException, GeneralSecurityException {
    if (state == null || state.member == null) {
      throw new IOException("Not enrolled");
    }
    // Refresh permission generation so a re-enabled issuer never emits obsolete invitations.
    Signed refreshed = Signed.decode(control("REFRESH", new JsonObject()).verify(state.root)
        .getAsJsonObject("member"));
    synchronized (this) {
      save(new Local(state.network, state.head, state.root, refreshed, state.parent,
          state.ticket, state.enrollment, state.revision, state.snapshotRevision));
    }
    if (state == null || state.member == null || disabledInviters.contains(identity.nodeId())
        || revoked.contains(identity.nodeId()) || !Membership.verify(state.member,
        state.root).canInvite() || seconds < 1 || seconds > 86400 || invitations.size() >= 4096) {
      throw new IOException("Invitation not permitted or invitation limit reached");
    }
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    UUID id = UUID.randomUUID();
    Authority.Ticket ticket = new Authority.Ticket(1, state.network, state.root, identity.nodeId(),
        endpoint(), id, System.currentTimeMillis() + seconds * 1000L,
        Wire.b64(MessageDigest.getInstance("SHA-256").digest(random)), state.member, delegate);
    Signed signed = Signed.sign(ticket, identity.signing().getPrivate());
    synchronized (this) {
      Map<UUID, Signed> candidate = new HashMap<>(invitations);
      candidate.put(id, signed);
      AtomicFile.write(directory.resolve("invitations.json"), Wire.encode(candidate), false);
      invitations.put(id, signed);
    }
    JsonObject token = new JsonObject();
    token.add("ticket", Wire.object(signed));
    token.addProperty("secret", Wire.b64(random));
    return "APDP1." + Wire.b64(Wire.encode(token));
  }

  /** Validates the out-of-band trust anchor before persisting anything or opening a socket. */
  public void join(String code) throws IOException, GeneralSecurityException {
    if (!code.startsWith("APDP1.") || code.length() > 32768) {
      throw new IOException("Invalid invitation format");
    }
    JsonObject token = Wire.parse(Wire.unb64(code.substring(6)));
    Wire.fields(token, "ticket", "secret");
    Signed ticketSigned = Signed.decode(token.getAsJsonObject("ticket"));
    JsonObject untrusted = Wire.parse(Wire.unb64(ticketSigned.body()));
    String root = Wire.string(untrusted, "root", 128);
    Signed parentAssertion = Signed.decode(untrusted.getAsJsonObject("parent"));
    Membership issuer = Membership.verify(parentAssertion, root);
    Authority.Ticket ticket = Authority.ticket(ticketSigned, issuer.signing());
    String secret = Wire.string(token, "secret", 64);
    byte[] raw = Wire.unb64(secret);
    if (raw.length != 32 || !MessageDigest.isEqual(Wire.unb64(ticket.secretHash()),
        MessageDigest.getInstance("SHA-256").digest(raw))) {
      throw new IOException("Invalid invitation secret");
    }
    synchronized (this) {
      if (state != null && state.member != null) {
        throw new IOException("Already enrolled; use parent reconnect instead");
      }
      if (state == null) {
        if (ticket.expires() < System.currentTimeMillis()) {
          throw new IOException("Invitation expired");
        }
        identity = NodeIdentity.loadOrCreate(directory);
        lockState();
        String name = "proxy-" + identity.nodeId().toString().substring(0, 8);
        Path configFile = directory.getParent().resolve("apdp.toml");
        if (!Files.exists(configFile)) {
          config.create(name);
        }
        config.edit(value -> value.set("parent-address", ticket.endpoint()));
        Membership headMember = issuer;
        // The head ID is authenticated by the authority response during enrollment.
        save(new Local(ticket.network(), headMember.node(), root, null, parentAssertion,
            ticketSigned, UUID.randomUUID(), 0, 0));
        settings = config.load();
        listen();
      } else if (!state.ticket.equals(ticketSigned)) {
        throw new IOException("Different enrollment already pending");
      }
    }
    if (!connecting.compareAndSet(false, true)) {
      throw new IOException("An APDP connection attempt is already running; retry shortly");
    }
    try {
      connect(secret);
    } finally {
      connecting.set(false);
    }
  }

  private JsonObject specification() {
    JsonObject object = new JsonObject();
    object.addProperty("node", identity.nodeId().toString());
    object.addProperty("noise", Wire.b64(NoiseChannel.publicKey(identity)));
    object.addProperty("signing", Wire.b64(identity.signing().getPublic().getEncoded()));
    object.addProperty("request", state.enrollment.toString());
    return object;
  }

  private synchronized void lockState() throws IOException {
    if (stateLock != null) {
      return;
    }
    lockChannel = java.nio.channels.FileChannel.open(directory.resolve("service.lock"),
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
    try {
      stateLock = lockChannel.tryLock();
    } catch (java.nio.channels.OverlappingFileLockException e) {
      throw new IOException("Another APDP instance owns this state");
    }
    if (stateLock == null) {
      throw new IOException("Another APDP instance owns this state");
    }
  }

  private void connect(String secret) throws IOException, GeneralSecurityException {
    Membership enrolledParent = Membership.verify(state.parent, state.root);
    Socket socket = new Socket();
    sockets.add(socket);
    boolean installed = false;
    NoiseChannel channel = null;
    var timeout = timer.schedule(() -> closeSocket(socket), 15, TimeUnit.SECONDS);
    try {
      socket.connect(AddressUtil.parseAddress(settings.parentAddress()), 5000);
      boolean enrollment = state.member == null;
      DataOutputStream prefix = new DataOutputStream(socket.getOutputStream());
      prefix.writeInt(0x41504431);
      prefix.writeLong(state.network.getMostSignificantBits());
      prefix.writeLong(state.network.getLeastSignificantBits());
      prefix.writeByte(enrollment ? 1 : 0);
      prefix.flush();
      channel = new NoiseChannel(socket, identity, Wire.unb64(enrolledParent.noise()),
          state.network + "/" + (enrollment ? "enroll" : "session"));
      Session session = new Session(channel, enrolledParent.node(), true);
      JsonObject hello = new JsonObject();
      hello.addProperty("version", 1);
      session.send("HELLO", null, hello);
      JsonObject auth = new JsonObject();
      if (enrollment) {
        auth.add("ticket", Wire.object(state.ticket));
        auth.add("specification", specification());
        auth.add("proof", Wire.object(Signed.sign(specification(), identity.signing().getPrivate())));
        if (secret != null) {
          auth.addProperty("secret", secret);
        }
      } else {
        auth.add("member", Wire.object(state.member));
      }
      session.send("AUTH", null, auth);
      Frame reply = session.read();
      if (!reply.type.equals("AUTH")) {
        throw new IOException("APDP authentication rejected");
      }
      Wire.fields(reply.payload, "approval", "depth");
      Signed approval = Signed.decode(reply.payload.getAsJsonObject("approval"));
      JsonObject decision = approval.verify(state.root);
      if (!Wire.string(decision, "binding", 64).equals(channel.binding())
          || !Wire.string(decision, "child", 36).equals(identity.nodeId().toString())
          || !Wire.string(decision, "parent", 36).equals(enrolledParent.node().toString())) {
        throw new IOException("APDP approval does not match this handshake");
      }
      Signed member = Signed.decode(decision.getAsJsonObject("member"));
      Membership verified = Membership.verify(member, state.root);
      if (!verified.noise().equals(Wire.b64(NoiseChannel.publicKey(identity)))
          || !verified.signing().equals(Wire.b64(identity.signing().getPublic().getEncoded()))
          || !verified.network().equals(state.network)) {
        throw new IOException("Incorrect enrolled identity");
      }
      depth = (int) Wire.integer(reply.payload, "depth", 1, settings.maxDepth());
      if (depth != Wire.integer(decision, "depth", 1, settings.maxDepth())) {
        throw new IOException("Unauthenticated depth");
      }
      UUID headId = UUID.fromString(Wire.string(decision, "head", 36));
      if (depth < 1 || depth > settings.maxDepth()) {
        throw new IOException("APDP depth limit exceeded");
      }
      synchronized (this) {
        save(new Local(state.network, headId, state.root, member, state.parent,
            null, null, state.revision, state.snapshotRevision));
        validateLocal();
        parent = session;
        fingerprint = "";
      }
      health = "connected";
      installed = true;
      workers.submit(session::run);
    } finally {
      timeout.cancel(false);
      if (!installed) {
        socket.close();
        sockets.remove(socket);
        if (channel != null) {
          channel.destroy();
        }
      }
    }
  }

  private void accept(Socket socket) {
    NoiseChannel channel = null;
    Session accepted = null;
    boolean installed = false;
    var timeout = timer.schedule(() -> closeSocket(socket), 15, TimeUnit.SECONDS);
    try {
      if (state.member == null) {
        throw new IOException("Parent not enrolled");
      }
      if (depth + 1 > settings.maxDepth() || children.size() >= settings.maxPeers()) {
        throw new IOException("APDP admission limit reached");
      }
      socket.setSoTimeout(15000);
      DataInputStream prefix = new DataInputStream(socket.getInputStream());
      if (prefix.readInt() != 0x41504431
          || !new UUID(prefix.readLong(), prefix.readLong()).equals(state.network)) {
        throw new IOException("Wrong APDP network or version");
      }
      int purpose = prefix.readUnsignedByte();
      if (purpose > 1) {
        throw new IOException("Invalid APDP purpose");
      }
      channel = new NoiseChannel(socket, identity, null,
          state.network + "/" + (purpose == 1 ? "enroll" : "session"));
      Session session = new Session(channel, null, false);
      accepted = session;
      Frame hello = session.read();
      Wire.fields(hello.payload, "version");
      if (!hello.type.equals("HELLO") || Wire.integer(hello.payload, "version", 1, 1) != 1) {
        throw new IOException("Unsupported APDP version");
      }
      Frame auth = session.read();
      if (!auth.type.equals("AUTH")) {
        throw new IOException("Expected APDP authentication");
      }
      Signed assertion;
      if (purpose == 1) {
        if (auth.payload.has("secret")) {
          Wire.fields(auth.payload, "ticket", "specification", "proof", "secret");
        } else {
          Wire.fields(auth.payload, "ticket", "specification", "proof");
        }
        Signed ticketSigned = Signed.decode(auth.payload.getAsJsonObject("ticket"));
        Authority.Ticket ticket = Authority.ticket(ticketSigned,
            Wire.b64(identity.signing().getPublic().getEncoded()));
        if (!ticket.network().equals(state.network) || !ticket.issuer().equals(identity.nodeId())) {
          throw new IOException("Wrong invitation parent");
        }
        JsonObject spec = auth.payload.getAsJsonObject("specification");
        Wire.fields(spec, "node", "noise", "signing", "request");
        UUID node = UUID.fromString(Wire.string(spec, "node", 36));
        if (!Wire.string(spec, "noise", 64).equals(channel.remoteKey())
            || !Signed.decode(auth.payload.getAsJsonObject("proof"))
                .verify(Wire.string(spec, "signing", 128)).equals(spec)) {
          throw new IOException("Enrollment identity proof failed");
        }
        JsonObject sanitized = new JsonObject();
        sanitized.add("ticket", Wire.object(ticketSigned));
        sanitized.add("specification", spec);
        synchronized (this) {
          JsonObject prior = pending.get(node);
          if (prior == null || !prior.equals(sanitized)) {
            String secret = Wire.string(auth.payload, "secret", 64);
            byte[] raw = Wire.unb64(secret);
            if (raw.length != 32 || !MessageDigest.isEqual(Wire.unb64(ticket.secretHash()),
                MessageDigest.getInstance("SHA-256").digest(raw))) {
              throw new IOException("Invitation secret rejected");
            }
            if (pending.size() >= 4096) {
              throw new IOException("Enrollment resource limit reached");
            }
            Map<UUID, JsonObject> candidate = new HashMap<>(pending);
            candidate.put(node, sanitized);
            AtomicFile.write(directory.resolve("pending.json"), Wire.encode(candidate), false);
            pending.put(node, sanitized);
          }
        }
        JsonObject enrollment = control("ENROLL", sanitized).verify(state.root);
        assertion = Signed.decode(enrollment.getAsJsonObject("member"));
      } else {
        Wire.fields(auth.payload, "member");
        assertion = Signed.decode(auth.payload.getAsJsonObject("member"));
      }
      Membership member = Membership.verify(assertion, state.root);
      if (!member.network().equals(state.network) || !member.parent().equals(identity.nodeId())
          || member.node().equals(identity.nodeId()) || !member.noise().equals(channel.remoteKey())
          || children.size() >= settings.maxPeers() || depth + 1 > settings.maxDepth()) {
        throw new IOException("APDP peer not authorized");
      }
      JsonObject request = new JsonObject();
      request.addProperty("child", member.node().toString());
      request.addProperty("binding", channel.binding());
      Signed approval = control("AUTHORIZE", request);
      JsonObject approved = approval.verify(state.root);
      Membership current = Membership.verify(Signed.decode(approved.getAsJsonObject("member")),
          state.root);
      if (!current.noise().equals(channel.remoteKey())) {
        throw new IOException("Stale APDP key");
      }
      session.peer = member.node();
      if (children.putIfAbsent(member.node(), session) != null) {
        throw new IOException("Duplicate active APDP identity");
      }
      JsonObject response = new JsonObject();
      response.add("approval", Wire.object(approval));
      response.addProperty("depth", Wire.integer(approved, "depth", 1, settings.maxDepth()));
      session.send("AUTH", null, response);
      installed = true;
      workers.submit(session::run);
      if (snapshot != null) {
        session.send("TOPOLOGY_SNAPSHOT", null, Wire.object(snapshot));
      }
      if (policy != null) {
        session.send("POLICY", null, Wire.object(policy));
      }
    } catch (Exception e) {
      // Never log a received frame, exception text or token-bearing authentication payload.
      LOGGER.debug("APDP inbound handshake rejected ({})", e.getClass().getSimpleName());
    } finally {
      timeout.cancel(false);
      handshakes.release();
      if (!installed) {
        if (accepted != null && accepted.peer != null) {
          children.remove(accepted.peer, accepted);
        }
        try {
          socket.close();
        } catch (IOException ignored) {
          // Already disconnected.
        }
        sockets.remove(socket);
        if (channel != null) {
          channel.destroy();
        }
      }
    }
  }

  private Signed control(String operation, JsonObject payload)
      throws IOException, GeneralSecurityException {
    Authority.Request request = new Authority.Request(UUID.randomUUID(), identity.nodeId(),
        System.currentTimeMillis() + 30000, operation, payload);
    Signed signed = Signed.sign(request, identity.signing().getPrivate());
    Signed response = relay(signed);
    JsonObject verified = response.verify(state.root);
    if (!Wire.string(verified, "request", 36).equals(request.id().toString())) {
      throw new IOException("Mismatched authority response");
    }
    return response;
  }

  private Signed relay(Signed request) throws IOException, GeneralSecurityException {
    if (authority != null) {
      return authority.execute(request);
    }
    Session upstream = parent;
    if (upstream == null) {
      throw new IOException("Head unavailable; fresh authorization is required");
    }
    return Signed.decode(upstream.request("CONTROL", Wire.object(request)));
  }

  private final class Session {
    private final NoiseChannel channel;
    private volatile UUID peer;
    private final boolean upstream;
    private final Map<UUID, CompletableFuture<JsonObject>> awaiting = new ConcurrentHashMap<>();
    private long sent;
    private long received;
    private volatile long lastReceived = System.nanoTime();
    private volatile long writeStarted;
    private volatile boolean stopped;
    private volatile boolean commands;

    private Session(NoiseChannel channel, UUID peer, boolean upstream) {
      this.channel = channel;
      this.peer = peer;
      this.upstream = upstream;
    }

    private synchronized void send(String type, UUID id, JsonObject payload)
        throws IOException, GeneralSecurityException {
      if (stopped) {
        throw new IOException("APDP peer disconnected");
      }
      // Gson omits null fields; an all-zero UUID denotes an unsolicited frame.
      writeStarted = System.nanoTime();
      try {
        channel.send(Wire.encode(new Frame(type, id == null ? new UUID(0, 0) : id, ++sent, payload)));
      } finally {
        writeStarted = 0;
      }
    }

    private Frame read() throws IOException, GeneralSecurityException {
      JsonObject object = Wire.parse(channel.receive());
      Wire.fields(object, "type", "id", "sequence", "payload");
      Wire.integer(object, "sequence", 1, Long.MAX_VALUE);
      Wire.string(object, "type", 32);
      Wire.string(object, "id", 36);
      Frame frame = Wire.GSON.fromJson(object, Frame.class);
      if (frame.id == null || frame.payload == null || frame.sequence != ++received
          || frame.type == null || frame.type.length() > 32) {
        throw new IOException("Invalid APDP frame sequence");
      }
      lastReceived = System.nanoTime();
      return frame;
    }

    private JsonObject request(String type, JsonObject payload) throws IOException {
      if (awaiting.size() >= 32) {
        throw new IOException("APDP RPC limit reached");
      }
      UUID id = UUID.randomUUID();
      CompletableFuture<JsonObject> response = new CompletableFuture<>();
      awaiting.put(id, response);
      try {
        send(type, id, payload);
        return response.get(12, TimeUnit.SECONDS);
      } catch (Exception e) {
        throw new IOException("APDP authority request failed");
      } finally {
        awaiting.remove(id);
      }
    }

    private void run() {
      try {
        while (!closed && !stopped) {
          Frame frame = read();
          switch (frame.type) {
            case "COMMAND_RPC":
              if (!commands || commandHandler == null || !requests.tryAcquire()) {
                throw new IOException("Unnegotiated or excessive ADCR request");
              }
              workers.submit(() -> {
                try {
                  send("RESULT", frame.id, commandHandler.apply(peer, frame.payload));
                } catch (Exception e) {
                  try {
                    send("ERROR", frame.id, new JsonObject());
                  } catch (Exception ignored) {
                    stop();
                  }
                } finally {
                  requests.release();
                }
              });
              break;
            case "RESULT":
            case "ERROR":
              CompletableFuture<JsonObject> future = awaiting.get(frame.id);
              if (future != null) {
                if (frame.type.equals("ERROR")) {
                  future.completeExceptionally(new IOException("APDP request rejected"));
                } else {
                  future.complete(frame.payload);
                }
              }
              break;
            case "CONTROL":
              if (upstream || !requests.tryAcquire()) {
                throw new IOException("Invalid or excessive control request");
              }
              workers.submit(() -> {
                try {
                  send("RESULT", frame.id, Wire.object(relay(Signed.decode(frame.payload))));
                } catch (Exception e) {
                  try {
                    send("ERROR", frame.id, new JsonObject());
                  } catch (Exception ignored) {
                    stop();
                  }
                } finally {
                  requests.release();
                }
              });
              break;
            case "NODE_ANNOUNCE":
            case "UPDATE":
              if (upstream) {
                throw new IOException("Announcement must travel upward");
              }
              Signed candidate = Signed.decode(frame.payload);
              Publication publication = Publication.verify(candidate, state.root);
              if (!publication.node().equals(peer)) {
                throw new IOException("Publication identity mismatch");
              }
              Publication.validateBranch(candidate, state.root, state.network, identity.nodeId(),
                  settings.maxNodes(), settings.maxDepth() - depth);
              for (Map.Entry<UUID, Signed> sibling : exports.entrySet()) {
                if (!sibling.getKey().equals(peer)
                    && Publication.verify(sibling.getValue(), state.root).name()
                        .equals(publication.name())) {
                  throw new IOException("Duplicate sibling namespace");
                }
              }
              Signed old = exports.get(peer);
              if (old != null && Publication.verify(old, state.root).revision()
                  >= publication.revision()) {
                if (!old.equals(candidate)) {
                  send("RESYNC", null, new JsonObject());
                }
                break;
              }
              rememberOrigins(candidate);
              exports.put(peer, candidate);
              fingerprint = "";
              break;
            case "TOPOLOGY_SNAPSHOT":
              if (!upstream) {
                throw new IOException("Snapshot must travel downward");
              }
              receiveSnapshot(Signed.decode(frame.payload));
              break;
            case "POLICY":
              if (!upstream) {
                throw new IOException("Policy must travel downward");
              }
              applyPolicy(Signed.decode(frame.payload));
              break;
            case "RESYNC":
              if (upstream) {
                if (published != null) {
                  send("NODE_ANNOUNCE", null, Wire.object(published));
                }
              } else if (snapshot != null) {
                send("TOPOLOGY_SNAPSHOT", null, Wire.object(snapshot));
              }
              break;
            case "WITHDRAW":
              if (upstream) {
                throw new IOException("Invalid withdrawal direction");
              }
              exports.remove(peer);
              fingerprint = "";
              break;
            case "HEARTBEAT":
              commands = frame.payload.has("adcr")
                  && Wire.integer(frame.payload, "adcr", 1, 1) == 1;
              break;
            default:
              throw new IOException("Unexpected APDP message");
          }
        }
      } catch (Exception e) {
        LOGGER.debug("APDP peer disconnected ({})", e.getClass().getSimpleName());
      } finally {
        stop();
        if (upstream) {
          if (parent == this) {
            parent = null;
            health = "waiting-for-parent";
            catalog.markStale();
          }
        } else if (children.remove(peer, this)) {
          exports.remove(peer);
          fingerprint = "";
        }
        channel.destroy();
        sockets.removeIf(Socket::isClosed);
      }
    }

    private void stop() {
      stopped = true;
      try {
        channel.close();
      } catch (IOException ignored) {
        // Already closed.
      }
      for (CompletableFuture<JsonObject> future : awaiting.values()) {
        future.completeExceptionally(new IOException("APDP disconnected"));
      }
    }
  }

  private void tick() {
    if (closed || state == null) {
      return;
    }
    try {
      settings = config.load();
      if (revoked.contains(identity.nodeId())) {
        health = "revoked";
        return;
      }
      if (authority != null && authority.snapshot().revision() > policyRevision) {
        Authority.State data = authority.snapshot();
        Set<UUID> denied = new java.util.HashSet<>(data.revokedNodes());
        Set<UUID> disabled = new java.util.HashSet<>();
        boolean changed;
        do {
          changed = false;
          for (Signed assertion : data.members().values()) {
            Membership member = Membership.verify(assertion, state.root);
            if (!member.canInvite()) {
              disabled.add(member.node());
            }
            if (denied.contains(member.parent()) && denied.add(member.node())) {
              changed = true;
            }
          }
        } while (changed);
        JsonObject payload = new JsonObject();
        payload.addProperty("network", state.network.toString());
        payload.addProperty("revision", data.revision());
        payload.add("revoked", Wire.GSON.toJsonTree(denied));
        payload.add("disabled", Wire.GSON.toJsonTree(disabled));
        applyPolicy(Signed.sign(payload, identity.signing().getPrivate()));
      }
      if (authority == null && parent == null && connecting.compareAndSet(false, true)) {
        workers.submit(() -> {
          try {
            connect(null);
          } catch (Exception e) {
            health = "waiting-for-parent (authorization unavailable or rejected)";
          } finally {
            connecting.set(false);
          }
        });
      }
      if (state.member == null) {
        return;
      }
      // Existing authenticated child sessions are retained during temporary head loss.
      List<Session> sessions = new ArrayList<>(children.values());
      if (parent != null) {
        sessions.add(parent);
      }
      for (Session session : sessions) {
        if (System.nanoTime() - session.lastReceived > TimeUnit.SECONDS.toNanos(30)) {
          session.stop();
        } else {
          try {
            JsonObject capabilities = new JsonObject();
            if (commandHandler != null) {
              capabilities.addProperty("adcr", 1);
            }
            session.send("HEARTBEAT", null, capabilities);
          } catch (Exception e) {
            session.stop();
          }
        }
      }
      publish();
    } catch (Exception e) {
      health = "configuration or publication rejected (" + e.getClass().getSimpleName() + ")";
    }
  }

  private static void closeSocket(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // Deadline enforcement is best effort after a simultaneous disconnect.
    }
  }

  private void watchdog() {
    List<Session> sessions = new ArrayList<>(children.values());
    if (parent != null) {
      sessions.add(parent);
    }
    long now = System.nanoTime();
    for (Session session : sessions) {
      if ((session.writeStarted != 0 && now - session.writeStarted > TimeUnit.SECONDS.toNanos(10))
          || now - session.lastReceived > TimeUnit.SECONDS.toNanos(30)) {
        session.stop();
      }
    }
  }

  private synchronized void publish() throws IOException, GeneralSecurityException {
    List<String> leaves = new ArrayList<>();
    List<Signed> descendants = new ArrayList<>();
    Set<String> names = new java.util.HashSet<>();
    for (Signed child : exports.values()) {
      Publication publication = Publication.verify(child, state.root);
      if (containsRevoked(child)) {
        continue;
      }
      names.add(publication.name());
      if (settings.publication() == Topology.Mode.TRANSPARENT
          || (settings.publication() == Topology.Mode.EXPOSED
              && settings.exposed().contains(publication.name()))) {
        descendants.add(child);
      }
    }
    for (String name : new java.util.TreeSet<>(destinations.get())) {
      if (!settings.proxyDestinations().contains(name) && !names.contains(name)
          && (settings.publication() == Topology.Mode.TRANSPARENT
              || (settings.publication() == Topology.Mode.EXPOSED
                  && settings.exposed().contains(name)))) {
        leaves.add(name);
      }
    }
    descendants.sort(java.util.Comparator.comparing(Signed::body));
    String value = Wire.b64(MessageDigest.getInstance("SHA-256").digest(Wire.encode(
        List.of(settings.name(), leaves, descendants))));
    if (value.equals(fingerprint)) {
      return;
    }
    long revision = state.revision + 1;
    Signed candidate = Signed.sign(new Publication(identity.nodeId(), state.member,
        settings.name(), revision, leaves, descendants), identity.signing().getPrivate());
    final Topology graph = authority == null ? null : Publication.topology(candidate, state.root,
        state.network, settings.maxNodes(), settings.maxDepth());
    save(new Local(state.network, state.head, state.root, state.member, state.parent,
        state.ticket, state.enrollment, revision, state.snapshotRevision));
    published = candidate;
    fingerprint = value;
    if (authority != null) {
      JsonObject payload = new JsonObject();
      payload.addProperty("network", state.network.toString());
      payload.addProperty("revision", revision);
      payload.add("publication", Wire.object(candidate));
      snapshot = Signed.sign(payload, identity.signing().getPrivate());
      catalog.replace(graph);
      broadcastSnapshot();
    } else if (parent != null) {
      parent.send("NODE_ANNOUNCE", null, Wire.object(candidate));
    }
  }

  private record Origin(long revision, String hash) { }

  private synchronized void rememberOrigins(Signed candidate)
      throws IOException, GeneralSecurityException {
    Path path = directory.resolve("origins.json");
    Map<String, Origin> origins = new java.util.HashMap<>();
    if (Files.exists(path)) {
      JsonObject stored = Wire.parse(AtomicFile.read(path, Wire.MAX));
      for (String key : stored.keySet()) {
        JsonObject value = stored.getAsJsonObject(key);
        origins.put(key, new Origin(Wire.integer(value, "revision", 1, Long.MAX_VALUE),
            Wire.string(value, "hash", 64)));
      }
    }
    Map<String, Origin> previous = Map.copyOf(origins);
    rememberOrigin(candidate, origins, 0);
    if (origins.size() > settings.maxNodes()) {
      throw new IOException("Origin history limit exceeded");
    }
    if (!origins.equals(previous)) {
      AtomicFile.write(path, Wire.encode(origins), false);
    }
  }

  private void rememberOrigin(Signed candidate, Map<String, Origin> origins, int level)
      throws IOException, GeneralSecurityException {
    if (level > settings.maxDepth()) {
      throw new IOException("Origin depth exceeded");
    }
    Publication publication = Publication.verify(candidate, state.root);
    String hash = Wire.b64(MessageDigest.getInstance("SHA-256").digest(Wire.encode(candidate)));
    Origin old = origins.get(publication.node().toString());
    if (old != null && (old.revision > publication.revision()
        || (old.revision == publication.revision() && !old.hash.equals(hash)))) {
      throw new IOException("Replayed or conflicting origin publication");
    }
    origins.put(publication.node().toString(), new Origin(publication.revision(), hash));
    for (Signed child : publication.children()) {
      rememberOrigin(child, origins, level + 1);
    }
  }

  private void receiveSnapshot(Signed candidate) throws IOException, GeneralSecurityException {
    JsonObject payload = candidate.verify(state.root);
    Wire.fields(payload, "network", "revision", "publication");
    if (!Wire.string(payload, "network", 36).equals(state.network.toString())) {
      throw new IOException("Foreign APDP snapshot");
    }
    long revision = Wire.integer(payload, "revision", 1, Long.MAX_VALUE);
    if (revision < state.snapshotRevision) {
      return;
    }
    Topology graph = Publication.topology(Signed.decode(payload.getAsJsonObject("publication")),
        state.root, state.network, settings.maxNodes(), settings.maxDepth());
    if (!graph.head().equals(state.head)) {
      throw new IOException("Unexpected APDP head");
    }
    synchronized (this) {
      if (revision > state.snapshotRevision) {
        save(new Local(state.network, state.head, state.root, state.member, state.parent,
            state.ticket, state.enrollment, state.revision, revision));
      }
      snapshot = candidate;
      catalog.replace(graph);
    }
    broadcastSnapshot();
  }

  private void broadcastSnapshot() {
    for (Session session : children.values()) {
      try {
        session.send("TOPOLOGY_SNAPSHOT", null, Wire.object(snapshot));
      } catch (Exception e) {
        session.stop();
      }
    }
  }

  private boolean containsRevoked(Signed publication) throws IOException, GeneralSecurityException {
    return containsRevoked(publication, 0);
  }

  private boolean containsRevoked(Signed publication, int level)
      throws IOException, GeneralSecurityException {
    if (level > settings.maxDepth()) {
      throw new IOException("Publication depth exceeded");
    }
    Publication value = Publication.verify(publication, state.root);
    if (revoked.contains(value.node())) {
      return true;
    }
    for (Signed child : value.children()) {
      if (containsRevoked(child, level + 1)) {
        return true;
      }
    }
    return false;
  }

  private synchronized void applyPolicy(Signed candidate)
      throws IOException, GeneralSecurityException {
    JsonObject payload = candidate.verify(state.root);
    Wire.fields(payload, "network", "revision", "revoked", "disabled");
    long revision = Wire.integer(payload, "revision", 1, Long.MAX_VALUE);
    if (!Wire.string(payload, "network", 36).equals(state.network.toString())) {
      throw new IOException("Foreign policy");
    }
    if (revision <= policyRevision) {
      return;
    }
    Set<UUID> denied = new java.util.HashSet<>();
    Set<UUID> disabled = new java.util.HashSet<>();
    for (var id : payload.getAsJsonArray("revoked")) {
      denied.add(UUID.fromString(id.getAsString()));
    }
    for (var id : payload.getAsJsonArray("disabled")) {
      disabled.add(UUID.fromString(id.getAsString()));
    }
    AtomicFile.write(directory.resolve("policy.json"), Wire.encode(candidate), false);
    policyRevision = revision;
    policy = candidate;
    revoked = Set.copyOf(denied);
    disabledInviters = Set.copyOf(disabled);
    fingerprint = "";
    for (Session session : children.values()) {
      try {
        session.send("POLICY", null, Wire.object(candidate));
      } catch (Exception e) {
        session.stop();
      }
      if (revoked.contains(session.peer)) {
        session.stop();
        exports.remove(session.peer);
      }
    }
    if (revoked.contains(identity.nodeId())) {
      catalog.markStale();
      children.values().forEach(Session::stop);
      reconnect();
    }
  }

  /** Returns secret-free runtime diagnostics. */
  public String status() {
    return "APDP " + health + " node=" + localNode() + " network="
        + (state == null ? "none" : state.network) + " head=" + head() + " depth=" + depth
        + " weight=" + weight() + " children=" + children.size();
  }

  /** Lists authenticated adjacent identities, without descendant endpoints. */
  public List<String> peers() {
    List<String> lines = new ArrayList<>();
    if (parent != null) {
      lines.add("parent " + parent.peer);
    }
    children.keySet().stream().sorted().forEach(id -> lines.add("child " + id));
    return List.copyOf(lines);
  }

  /** Lists only local invitation IDs, never bearer material. */
  public List<String> invitationList() {
    synchronized (this) {
      return invitations.keySet().stream().sorted().map(UUID::toString).toList();
    }
  }

  /** Revokes a locally issued invitation through the head. */
  public void revokeInvite(UUID id) throws IOException, GeneralSecurityException {
    Signed invitation;
    synchronized (this) {
      invitation = invitations.get(id);
    }
    if (invitation == null) {
      throw new IOException("Unknown local invitation");
    }
    JsonObject payload = new JsonObject();
    payload.add("ticket", Wire.object(invitation));
    control("REVOKE_INVITE", payload);
  }

  /** Asks the head to revoke a node and its dependent enrollment branch. */
  public void revokeNode(UUID id) throws IOException, GeneralSecurityException {
    JsonObject payload = new JsonObject();
    payload.addProperty("node", id.toString());
    control("REVOKE_NODE", payload);
    Session direct = children.get(id);
    if (direct != null) {
      direct.stop();
    }
    exports.remove(id);
    fingerprint = "";
  }

  /** Changes enrollment delegation without revoking already enrolled children. */
  public void setInvite(UUID id, boolean allow) throws IOException, GeneralSecurityException {
    JsonObject payload = new JsonObject();
    payload.addProperty("node", id.toString());
    payload.addProperty("allow", allow);
    control("SET_INVITE", payload);
  }

  /** Reports the pinned parent identity separately from its operational endpoint. */
  public String parentInfo() throws IOException, GeneralSecurityException {
    if (state == null || state.parent == null) {
      return "No parent (head or disabled)";
    }
    return "Parent " + Membership.verify(state.parent, state.root).node()
        + " address=" + settings.parentAddress() + " connected=" + (parent != null);
  }

  /** Updates only the parent endpoint, preserving all identity pins and enrollment. */
  public void parentAddress(String address) throws IOException {
    if (state == null || state.parent == null) {
      throw new IOException("No enrolled parent");
    }
    AddressUtil.parseAddress(address);
    config.edit(value -> value.set("parent-address", address));
    settings = config.load();
  }

  /** Disconnects the upstream channel so maintenance can request fresh head authorization. */
  public void reconnect() {
    Session current = parent;
    if (current != null) {
      current.stop();
    }
  }

  /** Requests a full authoritative snapshot and republishes this origin's current export. */
  public void resync() throws IOException, GeneralSecurityException {
    fingerprint = "";
    if (parent != null) {
      parent.send("RESYNC", null, new JsonObject());
    }
  }

  @Override
  public void close() {
    closed = true;
    timer.shutdownNow();
    if (listener != null) {
      try {
        listener.close();
      } catch (IOException ignored) {
        // Already closed.
      }
    }
    for (Socket socket : sockets) {
      try {
        socket.close();
      } catch (IOException ignored) {
        // Already closed.
      }
    }
    workers.shutdownNow();
    try {
      if (!workers.awaitTermination(15, TimeUnit.SECONDS)) {
        return; // Retain exclusive ownership if a worker can still mutate persistent state.
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    try {
      if (stateLock != null && stateLock.isValid()) {
        stateLock.release();
      }
      if (lockChannel != null) {
        lockChannel.close();
      }
    } catch (IOException ignored) {
      // Process termination releases locks as well.
    }
  }
}

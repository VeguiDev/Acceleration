package chain;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

/** Compiled against the unmodified upstream API by the socket integration harness. */
public final class FixturePlugin {
  private final ProxyServer proxy;

  @Inject
  public FixturePlugin(ProxyServer proxy) {
    this.proxy = proxy;
  }

  @Subscribe
  public void initialize(ProxyInitializeEvent event) {
    proxy.getCommandManager().register("chain-local-switch", (SimpleCommand) invocation -> {
      if (invocation.source() instanceof Player player) {
        player.createConnectionRequest(proxy.getServer("second").orElseThrow()).connect();
      }
    });
  }

  @Subscribe
  public void login(PostLoginEvent event) {
    System.out.println("CHAIN_EVENT PostLogin " + event.getPlayer().getUsername());
  }

  @Subscribe
  public void choose(PlayerChooseInitialServerEvent event) {
    System.out.println("CHAIN_EVENT ChooseInitial");
  }

  @Subscribe
  public void preConnect(ServerPreConnectEvent event) {
    System.out.println("CHAIN_EVENT PreConnect");
  }

  @Subscribe
  public void connected(ServerConnectedEvent event) {
    System.out.println("CHAIN_EVENT Connected " + event.getServer().getServerInfo().getName());
  }

  @Subscribe
  public void disconnect(DisconnectEvent event) {
    System.out.println("CHAIN_EVENT Disconnect");
  }
}

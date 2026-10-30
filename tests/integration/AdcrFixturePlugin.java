import com.google.inject.Inject;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;

public final class AdcrFixturePlugin {
  private final ProxyServer server;
  private final String node = Path.of("").toAbsolutePath().getFileName().toString();

  @Inject
  public AdcrFixturePlugin(ProxyServer server) {
    this.server = server;
  }

  @Subscribe
  public void connected(com.velocitypowered.api.event.player.ServerPostConnectEvent event) {
    System.out.println("ADCR_CONNECTED " + node + " "
        + event.getPlayer().getCurrentServer().orElseThrow().getServerInfo().getName());
  }

  @Subscribe
  public void initialize(ProxyInitializeEvent event) {
    register("nearest", false);
    if (node.equals("head")) {
      register("ancestor", false);
      register("blocked", false);
      server.getCommandManager().register(server.getCommandManager().metaBuilder("raw-owner")
          .plugin(this).build(), (RawCommand) invocation -> mark("raw-owner"));
      server.getCommandManager().register(new BrigadierCommand(BrigadierCommand
          .literalArgumentBuilder("brigadier-owner").executes(context -> {
            mark("brigadier-owner");
            return 1;
          }).build()));
      server.getCommandManager().register(new BrigadierCommand(BrigadierCommand
          .literalArgumentBuilder("explicit-forward").executes(context -> {
            mark("explicit-forward");
            return BrigadierCommand.FORWARD;
          }).build()));
    } else {
      register("blocked", true);
    }
  }

  private void register(String alias, boolean denied) {
    server.getCommandManager().register(server.getCommandManager().metaBuilder(alias)
        .plugin(this).build(), new SimpleCommand() {
          public void execute(Invocation invocation) {
            mark(alias);
          }

          public boolean hasPermission(Invocation invocation) {
            return !denied;
          }

          public java.util.List<String> suggest(Invocation invocation) {
            return java.util.List.of(alias + "-offer");
          }
        });
  }

  private void mark(String alias) {
    System.out.println("ADCR_EXECUTED " + node + " " + alias);
  }
}

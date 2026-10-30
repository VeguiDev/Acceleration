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

package com.velocitypowered.proxy.adcr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.proxy.command.VelocityCommandManager;
import com.velocitypowered.proxy.event.VelocityEventManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LocalCommandRegistryTest {
  private VelocityCommandManager manager() {
    return new VelocityCommandManager(mock(VelocityEventManager.class), mock(PluginManager.class));
  }

  @Test
  void tracksEveryFamilyWithoutCallingPluginCode() {
    VelocityCommandManager manager = manager();
    SimpleCommand simple = mock(SimpleCommand.class);
    RawCommand raw = mock(RawCommand.class);
    manager.register(manager.metaBuilder("spawn").aliases("s").build(), simple);
    manager.register(manager.metaBuilder("raw").build(), raw);
    manager.register(new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("tree")));
    Map<String, LocalCommandRegistry.Entry> aliases = manager.getLocalRegistry().snapshot().aliases();
    assertEquals(4, aliases.size());
    assertEquals(LocalCommandRegistry.Kind.SIMPLE, aliases.get("spawn").kind());
    assertEquals(aliases.get("spawn"), aliases.get("s"));
    assertEquals(LocalCommandRegistry.Kind.RAW, aliases.get("raw").kind());
    assertEquals(LocalCommandRegistry.Kind.BRIGADIER, aliases.get("tree").kind());
    verifyNoInteractions(simple, raw);
    assertThrows(UnsupportedOperationException.class, aliases::clear);
  }

  @Test
  void replacementAndPartialRemovalDescribeOnlyEffectiveAliases() {
    VelocityCommandManager manager = manager();
    CommandMeta first = manager.metaBuilder("spawn").aliases("s").build();
    manager.register(first, mock(SimpleCommand.class));
    LocalCommandRegistry.Snapshot before = manager.getLocalRegistry().snapshot();
    manager.register(manager.metaBuilder("spawn").build(), mock(RawCommand.class));
    LocalCommandRegistry.Snapshot after = manager.getLocalRegistry().snapshot();
    assertTrue(after.revision() > before.revision());
    assertNotEquals(before.aliases().get("spawn").registration(),
        after.aliases().get("spawn").registration());
    assertEquals(List.of("s"), after.aliases().get("s").aliases());
    manager.unregister(first);
    assertEquals(List.of("spawn"), manager.getLocalRegistry().snapshot().aliases().keySet().stream().toList());
    manager.unregister("SPAWN");
    assertTrue(manager.getLocalRegistry().snapshot().aliases().isEmpty());
    assertEquals(2, before.aliases().size());
  }

  @Test
  void includesBrigadierLiteralMissingFromMetaAndCanRemoveIt() {
    VelocityCommandManager manager = manager();
    CommandMeta meta = manager.metaBuilder("alias").build();
    manager.register(meta, new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("actual")));
    assertEquals(2, manager.getLocalRegistry().snapshot().aliases().size());
    manager.unregister(meta);
    assertFalse(manager.hasCommand("actual"));
    assertTrue(manager.getLocalRegistry().snapshot().aliases().isEmpty());
  }

  @Test
  void observerFailureIsIsolatedAndSubscriptionCanBeClosed() throws Exception {
    VelocityCommandManager manager = manager();
    List<Long> updates = new ArrayList<>();
    manager.getLocalRegistry().listen(update -> {
      throw new IllegalStateException();
    });
    AutoCloseable subscription = manager.getLocalRegistry().listen(update -> updates.add(update.revision()));
    manager.register(manager.metaBuilder("one").build(), mock(SimpleCommand.class));
    subscription.close();
    manager.unregister("one");
    assertEquals(List.of(1L), updates);
    assertEquals(2, manager.getLocalRegistry().snapshot().revision());
  }

  @Test
  void observersRunOutsideTheCommandWriteLock() {
    VelocityCommandManager manager = manager();
    CompletableFuture<Void> completed = new CompletableFuture<>();
    manager.getLocalRegistry().listen(update -> {
      try {
        CompletableFuture.runAsync(() -> manager.unregister("missing"))
            .get(2, TimeUnit.SECONDS);
        completed.complete(null);
      } catch (Exception error) {
        completed.completeExceptionally(error);
      }
    });
    manager.register(manager.metaBuilder("test").build(), mock(SimpleCommand.class));
    assertTrue(completed.isDone());
    assertFalse(completed.isCompletedExceptionally());
  }

  @Test
  void noOpRemovalDoesNotAdvanceRevision() {
    VelocityCommandManager manager = manager();
    manager.unregister("missing");
    assertEquals(0, manager.getLocalRegistry().snapshot().revision());
    manager.register(manager.metaBuilder("same").build(), mock(SimpleCommand.class));
    long revision = manager.getLocalRegistry().snapshot().revision();
    manager.register(manager.metaBuilder("same").build(), mock(SimpleCommand.class));
    assertEquals(revision + 1, manager.getLocalRegistry().snapshot().revision());
  }
}

package dev.eldencraft.bridge.client;

import java.lang.reflect.Proxy;
import java.util.Optional;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** Exercise the actual command requirement with vanilla serverless sources. No world is started. */
public final class LabCommandsConformance {
  private static int checks;

  private static void check(boolean value) {
    checks++;
    if (!value) throw new AssertionError("Lab command check " + checks);
  }

  // No server, world or player constructor runs; only the command's actual
  // singleplayer/publication/player-count/world-name reads are exercised.
  private static final class LocalServer extends IntegratedServer {
    boolean solo, published;
    int players;
    WorldData data;

    private LocalServer() {
      super(null, null, null, null, null, Optional.empty(), null, null);
      throw new AssertionError("fixture constructor must never run");
    }

    @Override
    public boolean isSingleplayer() {
      return solo;
    }

    @Override
    public boolean isPublished() {
      return published;
    }

    @Override
    public int getPlayerCount() {
      return players;
    }

    @Override
    public WorldData getWorldData() {
      return data;
    }
  }

  private static <T> T fixture(Class<T> type) throws Exception {
    var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }

  public static void main(String[] args) throws Exception {
    net.minecraft.SharedConstants.tryDetectVersion();
    net.minecraft.server.Bootstrap.bootStrap();
    check(EldenCraftWorld.supportedName("EldenCraft"));
    check(EldenCraftWorld.supportedName("EldenCraft Passthrough Lab"));
    check(!EldenCraftWorld.supportedName("Survival"));
    check(!EldenCraftWorld.supportedName(null));
    check(!LabCommands.allowed(null));
    check(!LabCommands.offlineAllowed(null));
    var unlock = LabCommands.unlockAllCommand().build();
    check(unlock.getName().equals("unlockall"));
    var validation =
        new CommandSourceStack(
            CommandSource.NULL,
            Vec3.ZERO,
            Vec2.ZERO,
            null,
            PermissionSet.NO_PERMISSIONS,
            Component.literal("validation"),
            null);
    check(validation.getServer() == null && !LabCommands.allowed(validation));
    check(!unlock.canUse(validation));
    // Even administrator permissions cannot turn a validation source into a live lab.
    var administrator = validation.withPermission(PermissionSet.ALL_PERMISSIONS);
    check(!LabCommands.allowed(administrator));
    check(!unlock.canUse(administrator));
    check(!LabCommands.allowed(administrator.withSuppressedOutput()));
    var local = fixture(LocalServer.class);
    local.solo = true;
    local.players = 1;
    local.data =
        (WorldData)
            Proxy.newProxyInstance(
                WorldData.class.getClassLoader(),
                new Class<?>[] {WorldData.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("getLevelName")) return "New World";
                  throw new AssertionError("unexpected world read: " + method.getName());
                });
    var player = fixture(ServerPlayer.class);
    var renamedWorld =
        new CommandSourceStack(
            CommandSource.NULL,
            Vec3.ZERO,
            Vec2.ZERO,
            null,
            PermissionSet.NO_PERMISSIONS,
            local,
            player);
    check(local.getWorldData().getLevelName().equals("New World"));
    check(unlock.canUse(renamedWorld));
    var dispatcher = new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();
    dispatcher.register(LabCommands.unlockAllCommand());
    check(dispatcher.parse("unlockall", renamedWorld).getContext().getNodes().size() == 1);
    local.players = 0;
    check(unlock.canUse(renamedWorld));
    check(!LabCommands.offlineAllowed(renamedWorld));
    local.players = 1;
    check(LabCommands.offlineAllowed(renamedWorld));
    local.published = true;
    check(!unlock.canUse(renamedWorld));
    local.published = false;
    local.players = 2;
    check(!unlock.canUse(renamedWorld));
    local.players = 1;
    local.solo = false;
    check(!unlock.canUse(renamedWorld));
    boolean denied = true;
    for (int i = 0; i < 100; i++) denied &= !LabCommands.allowed(administrator);
    check(denied);
    System.out.println("LabCommandsConformance: " + checks + " checks passed");
  }
}

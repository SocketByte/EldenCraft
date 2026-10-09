package dev.eldencraft.bridge.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;

/** Explicit development supplies for the existing disposable, unshared lab save. */
public final class LabCommands {
  private static PendingUnlock pendingUnlock;

  private record PendingUnlock(
      CommandSourceStack source,
      String id,
      long pid,
      long session,
      String character,
      long deadline) {}

  private LabCommands() {}

  public static void initialize() {
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, registries, selection) ->
            dispatcher.register(
                Commands.literal("eldencraft")
                    .requires(LabCommands::allowed)
                    .then(
                        Commands.literal("material")
                            .executes(context -> material(context.getSource())))
                    .then(
                        Commands.literal("kit")
                            .then(
                                Commands.literal("ranged")
                                    .executes(context -> ranged(context.getSource())))
                            .then(
                                Commands.literal("flight")
                                    .executes(context -> flight(context.getSource())))
                            .then(
                                Commands.literal("nether")
                                    .executes(context -> netherKit(context.getSource()))))
                    .then(
                        Commands.literal("nether")
                            .then(
                                Commands.literal("portal")
                                    .executes(context -> netherPortal(context.getSource())))
                            .then(
                                Commands.literal("close")
                                    .executes(context -> netherClose(context.getSource()))))));
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, registries, selection) -> dispatcher.register(unlockAllCommand()));
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
        server -> tickUnlockAll());
  }

  static LiteralArgumentBuilder<CommandSourceStack> unlockAllCommand() {
    return Commands.literal("unlockall")
        .requires(LabCommands::unlockVisible)
        .executes(context -> unlockAll(context.getSource()));
  }

  static boolean allowed(CommandSourceStack source) {
    // Vanilla also evaluates command requirements with serverless validation
    // sources. Deny those before inspecting the live singleplayer lab.
    if (source == null) return false;
    if (dev.eldencraft.bridge.CampaignConfig.current().enabled()
        && !dev.eldencraft.bridge.CampaignConfig.current().debugKits()) return false;
    return offlineAllowed(source)
        && EldenCraftWorld.supportedName(source.getServer().getWorldData().getLevelName());
  }

  static boolean offlineAllowed(CommandSourceStack source) {
    return unlockVisible(source) && source.getServer().getPlayerCount() == 1;
  }

  private static boolean unlockVisible(CommandSourceStack source) {
    if (source == null) return false;
    var server = source.getServer();
    // The initial command tree can be sent before PlayerList has added the
    // joining local player. Visibility cannot depend on a full count yet.
    return server != null
        && server.isSingleplayer()
        && !server.isPublished()
        && server.getPlayerCount() <= 1
        && source.getPlayer() != null;
  }

  private static int unlockAll(CommandSourceStack source) {
    if (!offlineAllowed(source)) {
      source.sendFailure(Component.literal("Use /unlockall in a private singleplayer session."));
      return 0;
    }
    if (pendingUnlock != null) {
      source.sendFailure(Component.literal("A map and grace unlock request is already pending."));
      return 0;
    }
    var host = CampaignBridge.snapshot();
    if (host == null) {
      source.sendFailure(
          Component.literal("Load Elden Ring with the offline campaign bridge active first."));
      return 0;
    }
    String id = CampaignBridge.requestUnlockAll(host);
    if (id == null) {
      source.sendFailure(Component.literal("Could not send the map and grace unlock request."));
      return 0;
    }
    pendingUnlock =
        new PendingUnlock(
            source,
            id,
            host.pid(),
            host.session(),
            host.character(),
            System.nanoTime() + 10_000_000_000L);
    source.sendSuccess(
        () -> Component.literal("Revealing the full map and unlocking all Sites of Grace..."),
        false);
    return 1;
  }

  private static void tickUnlockAll() {
    var pending = pendingUnlock;
    if (pending == null) return;
    if (!offlineAllowed(pending.source())) {
      pendingUnlock = null;
      return;
    }
    var host = CampaignBridge.snapshot();
    if (host != null
        && (host.pid() != pending.pid()
            || host.session() != pending.session()
            || !host.character().equals(pending.character()))) {
      pendingUnlock = null;
      pending
          .source()
          .sendFailure(Component.literal("Elden Ring character changed; grace unlock cancelled."));
      return;
    }
    if (host != null && host.ack() != null && host.ack().id().equals(pending.id())) {
      pendingUnlock = null;
      var ack = host.ack();
      if (ack.status().equals("graces_unlocked")) {
        pending
            .source()
            .sendSuccess(
                () ->
                    Component.literal(
                        "Unlocked all "
                            + ack.amount()
                            + " Sites of Grace and revealed the full map. Reopen the map to see"
                            + " it."),
                false);
      } else if (ack.status().equals("graces_partial")) {
        pending
            .source()
            .sendFailure(
                Component.literal(
                    "Unlocked "
                        + ack.amount()
                        + " Sites of Grace, but some map or grace flags are unavailable. Try again"
                        + " when the world is fully loaded."));
      } else {
        pending
            .source()
            .sendFailure(
                Component.literal(
                    "The native game rejected the grace unlock request. Try again in the loaded"
                        + " offline world."));
      }
      return;
    }
    if (System.nanoTime() >= pending.deadline()) {
      pendingUnlock = null;
      pending
          .source()
          .sendFailure(
              Component.literal(
                  "No grace unlock confirmation received. Check Elden Ring's map before"
                      + " retrying."));
    }
  }

  /** Reports what the ground under the player and the hidden terrain they look at are made of. */
  private static int material(CommandSourceStack source) {
    if (!allowed(source)) return 0;
    var player = source.getPlayer();
    var level = player.level();
    int ground = dev.eldencraft.bridge.TerrainMaterials.ground();
    source.sendSuccess(
        () ->
            Component.literal(
                ground > 0
                    ? "Ground: hit material "
                        + ground
                        + " ("
                        + dev.eldencraft.bridge.TerrainMaterials.name(ground)
                        + ")"
                    : "Ground: unknown (stand on Elden Ring ground)"),
        false);
    var eye = player.getEyePosition();
    var end = eye.add(player.getViewVector(1).scale(6));
    var hit =
        level.clip(
            new net.minecraft.world.level.ClipContext(
                eye,
                end,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                player));
    if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
        || !level
            .getBlockState(hit.getBlockPos())
            .is(dev.eldencraft.bridge.SharedWorldBlocks.TERRAIN)) {
      source.sendSuccess(
          () -> Component.literal("Look at Elden Ring terrain within 6 blocks to inspect it."),
          false);
      return 1;
    }
    var r = dev.eldencraft.bridge.TerrainMaterials.resolve(hit.getBlockPos().asLong());
    var id = dev.eldencraft.bridge.TerrainMaterials.blockId(r);
    source.sendSuccess(
        () ->
            Component.literal(
                "Target: hit material "
                    + r.hit()
                    + " ("
                    + dev.eldencraft.bridge.TerrainMaterials.name(r.hit())
                    + "), body material "
                    + (r.body() == dev.eldencraft.bridge.TerrainMaterials.NO_BODY
                        ? "per-triangle"
                        : r.body())
                    + ", from "
                    + r.source()
                    + " -> "
                    + (id == null ? "not mineable" : id)
                    + ". Override in config/"
                    + MaterialConfig.FILE
                    + "."),
        false);
    return 1;
  }

  private static int ranged(CommandSourceStack source) {
    return grant(
        source,
        new ItemStack[] {
          new ItemStack(Items.BOW),
          new ItemStack(Items.CROSSBOW),
          new ItemStack(Items.ARROW, 64),
          new ItemStack(Items.ENDER_PEARL, 16)
        },
        "Added a bow, crossbow, 64 arrows and 16 ender pearls.");
  }

  private static int flight(CommandSourceStack source) {
    var rockets = new ItemStack(Items.FIREWORK_ROCKET, 64);
    rockets.set(DataComponents.FIREWORKS, new Fireworks(1, List.of()));
    return grant(
        source,
        new ItemStack[] {new ItemStack(Items.ELYTRA), rockets},
        "Added an elytra and 64 flight rockets. Equip the elytra in your chest slot; press Space in"
            + " the air to glide, then use a rocket to boost.");
  }

  private static int netherKit(CommandSourceStack source) {
    return grant(
        source,
        new ItemStack[] {new ItemStack(Items.OBSIDIAN, 10), new ItemStack(Items.FLINT_AND_STEEL)},
        "Added 10 obsidian and a flint and steel. Build a 4x5 frame, light it, and stand in the"
            + " portal.");
  }

  /** Builds and lights a portal frame on the ground four blocks ahead, for testing the Nether. */
  private static int netherPortal(CommandSourceStack source) {
    if (!allowed(source)) return 0;
    var player = source.getPlayer();
    var level = player.level();
    if (!level.dimension().equals(dev.eldencraft.bridge.SharedWorldBlocks.DIMENSION)
        || !SharedWorldClient.serverActive()) {
      source.sendFailure(Component.literal("Stand in Elden Ring with the bridge active first."));
      return 0;
    }
    var look = player.getLookAngle();
    double length = Math.hypot(look.x, look.z);
    if (length < 1e-3) {
      look = new net.minecraft.world.phys.Vec3(0, 0, 1);
      length = 1;
    }
    double fx = look.x / length, fz = look.z / length;
    boolean spansX = Math.abs(fz) >= Math.abs(fx);
    int cx = (int) Math.floor(player.getX() + fx * 4),
        cz = (int) Math.floor(player.getZ() + fz * 4),
        y0 = (int) Math.floor(player.getY());
    int ground = Integer.MIN_VALUE;
    var cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
    for (int a = -1; a <= 2; a++) {
      int x = spansX ? cx + a : cx, z = spansX ? cz : cz + a;
      for (int y = y0 + 3; y >= y0 - 6; y--) {
        cursor.set(x, y, z);
        var state = level.getBlockState(cursor);
        boolean floor =
            state.is(dev.eldencraft.bridge.SharedWorldBlocks.TERRAIN)
                ? !dev.eldencraft.bridge.SharedTerrain.outline(cursor).isEmpty()
                : !state.isAir();
        if (floor) {
          ground = Math.max(ground, y);
          break;
        }
      }
    }
    if (ground == Integer.MIN_VALUE) {
      source.sendFailure(Component.literal("No sampled ground four blocks ahead."));
      return 0;
    }
    for (int a = -1; a <= 2; a++)
      for (int h = 0; h <= 4; h++) {
        var at =
            spansX
                ? new net.minecraft.core.BlockPos(cx + a, ground + h, cz)
                : new net.minecraft.core.BlockPos(cx, ground + h, cz + a);
        boolean frame = a == -1 || a == 2 || h == 0 || h == 4;
        level.setBlock(
            at,
            frame
                ? net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState()
                : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
            3);
      }
    var inside = new net.minecraft.core.BlockPos(cx, ground + 1, cz);
    var shape =
        net.minecraft.world.level.portal.PortalShape.findEmptyPortalShape(
            level,
            inside,
            spansX ? net.minecraft.core.Direction.Axis.X : net.minecraft.core.Direction.Axis.Z);
    if (shape.isEmpty()) {
      source.sendFailure(Component.literal("Built the frame, but it could not be lit."));
      return 0;
    }
    shape.get().createPortalBlocks(level);
    level.playSound(
        null,
        inside,
        net.minecraft.sounds.SoundEvents.FLINTANDSTEEL_USE,
        net.minecraft.sounds.SoundSource.BLOCKS,
        1,
        1);
    source.sendSuccess(
        () -> Component.literal("A lit nether portal stands ahead. Stand in it for two seconds."),
        false);
    return 1;
  }

  private static int netherClose(CommandSourceStack source) {
    if (!allowed(source)) return 0;
    boolean was = WorldNether.closeNow(source.getServer());
    source.sendSuccess(
        () ->
            Component.literal(
                was
                    ? "The Nether recedes; every block it changed is back."
                    : "The Nether is not open."),
        false);
    return 1;
  }

  private static int grant(CommandSourceStack source, ItemStack[] supplies, String message) {
    // Recheck at execution; a command parsed before opening LAN is not permission.
    if (!allowed(source)) return 0;
    var player = source.getPlayer();
    var inventory = player.getInventory();
    int[] empty = new int[supplies.length];
    int found = 0;
    for (int slot = 0; slot < 36 && found < empty.length; slot++)
      if (inventory.getItem(slot).isEmpty()) empty[found++] = slot;
    if (found < empty.length) {
      source.sendFailure(
          Component.literal("Free " + supplies.length + " inventory slots for this kit."));
      return 0;
    }
    for (int i = 0; i < supplies.length; i++) inventory.setItem(empty[i], supplies[i]);
    player.containerMenu.broadcastChanges();
    source.sendSuccess(() -> Component.literal(message + " Existing items were preserved."), false);
    return 1;
  }
}

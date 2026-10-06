package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.HostStepMotion;
import dev.eldencraft.bridge.WorldOrigin;
import dev.eldencraft.bridge.client.mixin.EntityMovementSoundInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Runs client-only vanilla sound emission for host displacement; no guest position or input writes.
 */
public final class HostFootsteps {
  private static final HostStepMotion MOTION = new HostStepMotion();
  private static Object player, world;

  /** Torrent: metres since the last hoofbeat, and whether the last tick was airborne. */
  private static double stride;

  private static boolean airborne;

  private HostFootsteps() {}

  public static void tick(Minecraft client) {
    var host =
        HostController.damageSnapshot(client); // Strict 250 ms freshness and foreground gate.
    var origin = SharedWorldClient.origin();
    boolean mounted = WorldTorrent.clientTorrent() != null;
    if (host == null
        || origin == null
        || !SharedWorldClient.active()
        || client.player == null
        || !client.player.isAlive()
        || client.player.isSpectator()
        || !client.player.noPhysics
        || client.gui.screen() != null
        || client.player.isPassenger()
        || !mounted && (client.player.isCrouching() || host.held(HostState.SNEAK))) {
      close();
      return;
    }
    if (player != client.player || world != client.level) {
      MOTION.clear();
      player = client.player;
      world = client.level;
    }
    var feet = SharedWorldClient.toGuestPhysical(host.feet().x(), host.feet().y(), host.feet().z());
    if (feet == null) {
      close();
      return;
    }
    var delta =
        MOTION.update(
            new HostStepMotion.Sample(
                new HostStepMotion.Context(
                    host.publisherPid(), host.mapId(), origin.epoch(), origin.anchorId()),
                host.hostFrame(),
                host.timestampMillis(),
                feet,
                host.grounded()));
    if (mounted) {
      hooves(client, host, delta);
      return;
    }
    stride = 0;
    airborne = false;
    if (delta == null) return;
    var position = client.player.getOnPos();
    var state = client.level.getBlockState(position);
    // Elden Ring ground sounds like what Elden Ring says it is made of.
    if (state.isAir() || state.is(dev.eldencraft.bridge.SharedWorldBlocks.TERRAIN))
      state = groundSound(state);
    // Entity's own 0.6-distance accumulator, step threshold, block sound and
    // Player water/combination-step rules remain the actual vanilla implementation.
    ((EntityMovementSoundInvoker) client.player)
        .eldencraft$movementSound(
            Entity.MovementEmission.SOUNDS, new Vec3(delta.x(), 0, delta.z()), position, state);
  }

  /** Vanilla horse sounds at Torrent's pace: a gallop every few strides, a walk step, a landing. */
  private static void hooves(Minecraft client, HostState.Snapshot host, WorldOrigin.Vec delta) {
    var p = client.player;
    if (!host.grounded()) {
      airborne = true;
      return;
    }
    if (airborne) {
      airborne = false;
      client.level.playLocalSound(
          p.getX(), p.getY(), p.getZ(), SoundEvents.HORSE_LAND, SoundSource.NEUTRAL, .4f, 1, false);
    }
    if (delta == null) return;
    boolean gallop = host.movementSpeed() > 7;
    stride += Math.hypot(delta.x(), delta.z());
    if (stride < (gallop ? 2.6 : 1.4)) return;
    stride = 0;
    client.level.playLocalSound(
        p.getX(),
        p.getY(),
        p.getZ(),
        gallop ? SoundEvents.HORSE_GALLOP : SoundEvents.HORSE_STEP,
        SoundSource.NEUTRAL,
        gallop ? .3f : .25f,
        1,
        false);
  }

  public static void close() {
    stride = 0;
    airborne = false;
    MOTION.clear();
    player = null;
    world = null;
  }

  private static net.minecraft.world.level.block.state.BlockState groundSound(
      net.minecraft.world.level.block.state.BlockState fallback) {
    int hit = dev.eldencraft.bridge.TerrainMaterials.ground();
    if (hit <= 0) return fallback;
    var id =
        dev.eldencraft.bridge.TerrainMaterials.blockId(
            new dev.eldencraft.bridge.TerrainMaterials.Resolution(
                hit, dev.eldencraft.bridge.TerrainMaterials.NO_BODY, "ground"));
    if (id == null) return fallback;
    var block =
        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
            net.minecraft.resources.Identifier.tryParse(id));
    return block == null ? fallback : block.defaultBlockState();
  }
}

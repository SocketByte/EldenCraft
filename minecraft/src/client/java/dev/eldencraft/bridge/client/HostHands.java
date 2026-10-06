package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.HostHandMotion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;

/** Changes only vanilla's camera-lag inputs; use, swing, equip and arm animation stay vanilla. */
public final class HostHands {
  private static final HostHandMotion MOTION = new HostHandMotion();
  private static Object player, level;
  private static long pid, map;

  private HostHands() {}

  public static void extract(LocalPlayer owner, FirstPersonHandsAndItemsRenderState state) {
    var mc = Minecraft.getInstance();
    var host = HostController.damageSnapshot(mc);
    var camera = HostController.frame();
    if (owner != mc.player
        || camera == null
        || !camera.firstPerson()
        || host == null
        || !SharedWorldClient.active()) {
      MOTION.reset();
      player = level = null;
      return;
    }
    if (player != owner || level != mc.level || pid != host.publisherPid() || map != host.mapId())
      MOTION.reset();
    player = owner;
    level = mc.level;
    pid = host.publisherPid();
    map = host.mapId();
    var pose = MOTION.update(System.nanoTime(), camera.yaw(), camera.pitch());
    if (pose != null) {
      state.viewYRot = pose.yaw();
      state.viewXRot = pose.pitch();
      state.yBob = pose.bobYaw();
      state.xBob = pose.bobPitch();
    }
  }
}

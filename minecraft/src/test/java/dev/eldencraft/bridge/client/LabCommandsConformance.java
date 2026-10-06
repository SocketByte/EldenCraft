package dev.eldencraft.bridge.client;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** Exercise the actual command requirement with vanilla serverless sources. No world is started. */
public final class LabCommandsConformance {
  private static int checks;

  private static void check(boolean value) {
    checks++;
    if (!value) throw new AssertionError("Lab command check " + checks);
  }

  public static void main(String[] args) {
    check(EldenCraftWorld.supportedName("EldenCraft"));
    check(EldenCraftWorld.supportedName("EldenCraft Passthrough Lab"));
    check(!EldenCraftWorld.supportedName("Survival"));
    check(!EldenCraftWorld.supportedName(null));
    check(!LabCommands.allowed(null));
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
    // Even administrator permissions cannot turn a validation source into a live lab.
    var administrator = validation.withPermission(PermissionSet.ALL_PERMISSIONS);
    check(!LabCommands.allowed(administrator));
    check(!LabCommands.allowed(administrator.withSuppressedOutput()));
    boolean denied = true;
    for (int i = 0; i < 100; i++) denied &= !LabCommands.allowed(administrator);
    check(denied);
    System.out.println("LabCommandsConformance: " + checks + " checks passed");
  }
}

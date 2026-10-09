package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.CombatEventState;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** ECCB v1 observation of real vanilla combat. Does not deal damage or mutate item durability. */
public final class CombatPublisher {
  private static final CombatEventState EVENTS = new CombatEventState();
  private static SharedMemory memory;
  private static MethodHandle tickCount;
  private static Object player, world;
  private static long hostPid, mapId, session = Math.max(1, System.nanoTime()), frame, sequence;
  private static boolean active, failed;

  private CombatPublisher() {}

  private static boolean usable(ItemStack item) {
    return !item.isEmpty() && !item.isBroken();
  }

  private static boolean melee(ItemStack item) {
    return usable(item) && item.has(DataComponents.WEAPON);
  }

  private static boolean refresh(Minecraft client) {
    var host = HostController.combatSnapshot(client);
    boolean valid =
        host != null
            && client.player != null
            && client.player.isAlive()
            && !client.player.isSpectator();
    if (!valid) {
      if (active) {
        session++;
        EVENTS.reset();
      }
      active = false;
      player = world = null;
      return false;
    }
    if (!active
        || player != client.player
        || world != client.level
        || hostPid != host.publisherPid()
        || mapId != host.mapId()) {
      session++;
      EVENTS.reset();
      player = client.player;
      world = client.level;
      hostPid = host.publisherPid();
      mapId = host.mapId();
    }
    active = true;
    return true;
  }

  public static void beginAttack() {
    Minecraft client = Minecraft.getInstance();
    boolean valid = refresh(client);
    EVENTS.begin(
        valid && client.gui.screen() == null,
        valid && melee(client.player.getMainHandItem()),
        valid ? client.player.getAttackStrengthScale(0.5f) : 0);
  }

  public static void completedSwing(boolean accepted) {
    if (EVENTS.complete(accepted)) {
      org.slf4j.LoggerFactory.getLogger("eldencraft_combat")
          .info("Vanilla fully charged melee swing accepted: sequence={}", EVENTS.sequence());
      publish(Minecraft.getInstance());
    }
  }

  public static void publish(Minecraft client) {
    refresh(client);
    if (failed) return;
    try {
      if (memory == null) {
        memory = SharedMemory.create("Local\\EldenCraftCombat", 256);
        var linker = Linker.nativeLinker();
        var kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
        tickCount =
            linker.downcallHandle(
                kernel.find("GetTickCount64").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG));
      }
      ItemStack selected = active ? client.player.getMainHandItem() : ItemStack.EMPTY;
      boolean gui = active && client.gui.screen() != null;
      ItemStack used =
          active && client.player.isUsingItem() ? client.player.getUseItem() : ItemStack.EMPTY;
      // ECCB v1 flag0x4 is a guard request. Since0.6.1 it waits for vanilla's actual raise
      // delay/cooldown.
      boolean shield =
          usable(used)
              && used.has(DataComponents.BLOCKS_ATTACKS)
              && client.player.isBlocking()
              && !client.player.getCooldowns().isOnCooldown(used);
      int flags =
          (active ? 1 : 0)
              | (active && melee(selected) ? 2 : 0)
              | (shield ? 4 | 8 : 0)
              | (gui ? 16 : 0);
      byte[] id =
          BuiltInRegistries.ITEM
              .getKey(selected.getItem())
              .toString()
              .getBytes(StandardCharsets.UTF_8);
      if (id.length > 128) {
        flags = 0;
        id = "minecraft:air".getBytes(StandardCharsets.UTF_8);
      }
      ByteBuffer b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
      b.putInt(0, 0x42434345)
          .putInt(4, 1)
          .putLong(8, sequence + 2)
          .putLong(16, ++frame)
          .putLong(24, (long) tickCount.invokeExact());
      b.putInt(32, (int) ProcessHandle.current().pid())
          .putInt(36, flags)
          .putLong(40, EVENTS.sequence());
      b.putInt(48, id.length)
          .putInt(52, selected.getDamageValue())
          .putInt(56, selected.getMaxDamage())
          .putFloat(60, EVENTS.charge());
      b.position(64);
      b.put(id);
      b.putLong(192, session);
      var segment = memory.segment;
      segment.set(ValueLayout.JAVA_LONG, 8, sequence + 1);
      VarHandle.fullFence();
      var source = MemorySegment.ofArray(b.array());
      MemorySegment.copy(source, 0, segment, 0, 8);
      MemorySegment.copy(source, 16, segment, 16, 240);
      VarHandle.fullFence();
      sequence += 2;
      segment.set(ValueLayout.JAVA_LONG, 8, sequence);
      VarHandle.fullFence();
    } catch (Throwable error) {
      if (error instanceof VirtualMachineError fatal) throw fatal;
      failed = true;
      close();
      org.slf4j.LoggerFactory.getLogger("eldencraft_combat")
          .warn("Combat observations disabled: {}", error.getClass().getSimpleName());
    }
  }

  public static void close() {
    if (memory != null) {
      memory.close();
      memory = null;
    }
    EVENTS.reset();
  }
}

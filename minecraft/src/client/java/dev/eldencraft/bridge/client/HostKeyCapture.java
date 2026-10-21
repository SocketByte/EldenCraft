package dev.eldencraft.bridge.client;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;

/**
 * Lets Controls bind any key. The host forwards only its sampled gameplay buttons into Minecraft
 * screens, so while a Key Binds entry waits for a key this reads the keyboard directly: the first
 * new press reaches the screen as the physical key (SDL scancode) it is in the active Windows
 * layout. Mouse buttons keep arriving as host clicks, which the screen binds itself.
 */
final class HostKeyCapture {
  /** After a key is bound, its own host button must not also close or drive the screen. */
  private static final long SETTLE_NANOS = 300_000_000L;

  private static MethodHandle keyState, mapKey;
  private static boolean unavailable;
  private static boolean[] held;
  private static long settleUntil;

  private HostKeyCapture() {}

  /**
   * Every routed screen update. Returns true while host keys must not act on the screen: while an
   * entry waits for a key, and briefly after one was bound.
   */
  static boolean route(Screen screen) {
    boolean waiting = screen instanceof KeyBindsScreen keys && keys.selectedKey != null;
    if (!waiting || !initialize()) {
      held = null;
      return System.nanoTime() < settleUntil;
    }
    var now = new boolean[256];
    for (int vk = 8; vk < now.length; vk++) now[vk] = usage(vk) != 0 && down(vk);
    var before = held;
    held = now;
    // Keys already down when the entry was selected do not count.
    if (before == null) return true;
    for (int vk = 8; vk < now.length; vk++) {
      if (!now[vk] || before[vk]) continue;
      // Escape clears the binding, as in vanilla.
      screen.keyPressed(new KeyEvent(usage(vk), 0, 0));
      held = null;
      settleUntil = System.nanoTime() + SETTLE_NANOS;
      return true;
    }
    return true;
  }

  private static boolean initialize() {
    if (keyState != null) return true;
    if (unavailable) return false;
    try {
      var linker = Linker.nativeLinker();
      var user32 = SymbolLookup.libraryLookup("user32", Arena.global());
      keyState =
          linker.downcallHandle(
              user32.find("GetAsyncKeyState").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT));
      mapKey =
          linker.downcallHandle(
              user32.find("MapVirtualKeyW").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
      return true;
    } catch (Throwable e) {
      if (e instanceof VirtualMachineError fatal) throw fatal;
      unavailable = true;
      return false;
    }
  }

  private static boolean down(int vk) {
    try {
      return (short) keyState.invokeExact(vk) < 0;
    } catch (Throwable e) {
      if (e instanceof VirtualMachineError fatal) throw fatal;
      return false;
    }
  }

  /** The SDL scancode (USB HID usage) of a virtual key in the active layout; 0 to ignore it. */
  static int usage(int vk) {
    // Mouse buttons arrive as clicks; the generic modifiers duplicate their sided keys.
    if (vk <= 0x06 || vk >= 0x10 && vk <= 0x12) return 0;
    // Layout-independent keys whose scancode Windows reports without its E0 prefix (the
    // navigation block would otherwise read as the numpad), or as several bytes (Pause).
    int fixed =
        switch (vk) {
          case 0x13 -> 72;
          case 0x21 -> 75;
          case 0x22 -> 78;
          case 0x23 -> 77;
          case 0x24 -> 74;
          case 0x25 -> 80;
          case 0x26 -> 82;
          case 0x27 -> 79;
          case 0x28 -> 81;
          case 0x2C -> 70;
          case 0x2D -> 73;
          case 0x2E -> 76;
          case 0x5B -> 227;
          case 0x5C -> 231;
          case 0x5D -> 101;
          case 0x6F -> 84;
          case 0x90 -> 83;
          case 0xA3 -> 228;
          case 0xA5 -> 230;
          default -> 0;
        };
    if (fixed != 0) return fixed;
    int scancode;
    try {
      // MAPVK_VK_TO_VSC_EX: extended keys carry an E0 prefix in the high byte.
      scancode = (int) mapKey.invokeExact(vk, 4);
    } catch (Throwable e) {
      if (e instanceof VirtualMachineError fatal) throw fatal;
      return 0;
    }
    return usageOfScancode(scancode);
  }

  /** PC set-1 scancode (E0-prefixed when extended) to USB HID usage. */
  static int usageOfScancode(int scancode) {
    boolean extended = (scancode & 0xFF00) == 0xE000;
    int code = scancode & 0xFF;
    if (extended)
      return switch (code) {
        case 0x1C -> 88;
        case 0x1D -> 228;
        case 0x35 -> 84;
        case 0x37 -> 70;
        case 0x38 -> 230;
        case 0x45 -> 83;
        case 0x47 -> 74;
        case 0x48 -> 82;
        case 0x49 -> 75;
        case 0x4B -> 80;
        case 0x4D -> 79;
        case 0x4F -> 77;
        case 0x50 -> 81;
        case 0x51 -> 78;
        case 0x52 -> 73;
        case 0x53 -> 76;
        case 0x5B -> 227;
        case 0x5C -> 231;
        case 0x5D -> 101;
        default -> 0;
      };
    if ((scancode & 0xFF00) != 0) return 0;
    return switch (code) {
      case 0x01 -> 41;
      case 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A -> 30 + code - 0x02;
      case 0x0B -> 39;
      case 0x0C -> 45;
      case 0x0D -> 46;
      case 0x0E -> 42;
      case 0x0F -> 43;
      case 0x10 -> 20;
      case 0x11 -> 26;
      case 0x12 -> 8;
      case 0x13 -> 21;
      case 0x14 -> 23;
      case 0x15 -> 28;
      case 0x16 -> 24;
      case 0x17 -> 12;
      case 0x18 -> 18;
      case 0x19 -> 19;
      case 0x1A -> 47;
      case 0x1B -> 48;
      case 0x1C -> 40;
      case 0x1D -> 224;
      case 0x1E -> 4;
      case 0x1F -> 22;
      case 0x20 -> 7;
      case 0x21 -> 9;
      case 0x22 -> 10;
      case 0x23 -> 11;
      case 0x24 -> 13;
      case 0x25 -> 14;
      case 0x26 -> 15;
      case 0x27 -> 51;
      case 0x28 -> 52;
      case 0x29 -> 53;
      case 0x2A -> 225;
      case 0x2B -> 49;
      case 0x2C -> 29;
      case 0x2D -> 27;
      case 0x2E -> 6;
      case 0x2F -> 25;
      case 0x30 -> 5;
      case 0x31 -> 17;
      case 0x32 -> 16;
      case 0x33 -> 54;
      case 0x34 -> 55;
      case 0x35 -> 56;
      case 0x36 -> 229;
      case 0x37 -> 85;
      case 0x38 -> 226;
      case 0x39 -> 44;
      case 0x3A -> 57;
      case 0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x40, 0x41, 0x42, 0x43, 0x44 -> 58 + code - 0x3B;
      case 0x45 -> 83;
      case 0x46 -> 71;
      case 0x47 -> 95;
      case 0x48 -> 96;
      case 0x49 -> 97;
      case 0x4A -> 86;
      case 0x4B -> 92;
      case 0x4C -> 93;
      case 0x4D -> 94;
      case 0x4E -> 87;
      case 0x4F -> 89;
      case 0x50 -> 90;
      case 0x51 -> 91;
      case 0x52 -> 98;
      case 0x53 -> 99;
      case 0x56 -> 100;
      case 0x57 -> 68;
      case 0x58 -> 69;
      case 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E -> 104 + code - 0x64;
      case 0x76 -> 115;
      default -> 0;
    };
  }
}

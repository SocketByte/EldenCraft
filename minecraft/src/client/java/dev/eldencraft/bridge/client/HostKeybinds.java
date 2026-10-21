package dev.eldencraft.bridge.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Publishes the player's Minecraft bindings for every key the native host samples, so a binding
 * changed in Controls is the key Elden Ring reads. Native keeps its built-in keys until the first
 * publication. Escape stays fixed, as it is in vanilla.
 */
public final class HostKeybinds {
  public static final String FILE = "keybinds.json";

  private static final KeyMapping.Category CATEGORY =
      KeyMapping.Category.register(Identifier.fromNamespaceAndPath("eldencraft_bridge", "host"));

  /** Elden Ring's own interaction: ladders, doors, items, levers and Sites of Grace. */
  public static final KeyMapping INTERACT =
      KeyMappingHelper.registerKeyMapping(
          new KeyMapping("key.eldencraft_bridge.interact", InputConstants.KEY_R, CATEGORY));

  public static final KeyMapping TORRENT =
      KeyMappingHelper.registerKeyMapping(
          new KeyMapping("key.eldencraft_bridge.torrent", InputConstants.KEY_Y, CATEGORY));

  private static String published;

  private HostKeybinds() {}

  public static void initialize() {
    ClientTickEvents.END_CLIENT_TICK.register(HostKeybinds::tick);
  }

  private static void tick(Minecraft client) {
    var options = client.options;
    var keys = new JsonObject();
    put(keys, "attack", options.keyAttack);
    put(keys, "use", options.keyUse);
    put(keys, "inventory", options.keyInventory);
    for (int slot = 0; slot < 9; slot++)
      put(keys, "hotbar_" + (slot + 1), options.keyHotbarSlots[slot]);
    put(keys, "jump", options.keyJump);
    put(keys, "sneak", options.keyShift);
    put(keys, "sprint", options.keySprint);
    put(keys, "forward", options.keyUp);
    put(keys, "backward", options.keyDown);
    put(keys, "left", options.keyLeft);
    put(keys, "right", options.keyRight);
    put(keys, "drop", options.keyDrop);
    put(keys, "swap_hands", options.keySwapOffhand);
    put(keys, "torrent", TORRENT);
    put(keys, "interact", INTERACT);
    put(keys, "perspective", options.keyTogglePerspective);
    var json = new JsonObject();
    json.addProperty("version", 1);
    json.add("keys", keys);
    // Rewritten only on a change; native rereads the file when it changes.
    String text = json.toString();
    if (text.equals(published)) return;
    published = text;
    CampaignBridge.publishState(FILE, json);
  }

  /**
   * Keys are SDL scancodes (USB HID usages, so physical positions) and mouse buttons SDL's 1-based
   * numbers; native resolves them to the keys of the active Windows layout.
   */
  private static void put(JsonObject keys, String action, KeyMapping mapping) {
    var entry = new JsonObject();
    var key = KeyMappingHelper.getBoundKeyOf(mapping);
    if (mapping.isUnbound()) entry.addProperty("type", "none");
    else {
      entry.addProperty("type", key.getType() == InputConstants.Type.MOUSE ? "mouse" : "key");
      entry.addProperty("code", key.getValue());
    }
    keys.add(action, entry);
  }
}

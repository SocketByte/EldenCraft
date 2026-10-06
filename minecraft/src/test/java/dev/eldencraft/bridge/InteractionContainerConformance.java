package dev.eldencraft.bridge;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuConstructor;
import net.minecraft.world.inventory.PlayerEnderChestContainer;

/** Exact native owner, server admission and vanilla inventory/menu APIs; no game is started. */
public final class InteractionContainerConformance {
  private static int checks;
  private static final String VALID =
      """
      {"version":1,"pid":123,"session":456,"seq":8,"timestamp_ms":1000,"active":true,
       "menu":{"token":2,"kind":"grace","title":"Site of Grace","choices":[
         {"id":10,"text":"Ender Chest","enabled":true,"action":"ender_chest"},
         {"id":99,"text":"Leave","enabled":true}]}}
      """;

  public static void main(String[] args) throws Exception {
    var player = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    var owner = new InteractionContainerLease(123, 456, 2, player, -1);
    var snapshot = decode(VALID);
    var context = new InteractionContainerLease.OpenContext(true, true, true, true, true, true);
    check(
        owner.canOpen(snapshot, player, 10, context, 1000),
        "current typed grace action admits server open");
    check(
        !owner.canOpen(snapshot, player, 99, context, 1000),
        "ordinary native row cannot open a chest");
    check(
        !owner.canOpen(snapshot, UUID.randomUUID(), 10, context, 1000),
        "foreign player cannot inherit grace action");
    check(!owner.canOpen(snapshot, player, 10, context, 1500), "stale host cannot open inventory");
    check(!owner.canOpen(snapshot, player, 10, context, 999), "future host cannot open inventory");
    for (String changed :
        new String[] {
          VALID.replace("\"pid\":123", "\"pid\":124"),
          VALID.replace("\"session\":456", "\"session\":457"),
          VALID.replace("\"token\":2", "\"token\":3"),
          VALID.replace("\"active\":true", "\"active\":false"),
          VALID.replace("\"enabled\":true,\"action\"", "\"enabled\":false,\"action\""),
          VALID.replace(",\"action\":\"ender_chest\"", "")
        })
      check(
          !owner.canOpen(decode(changed), player, 10, context, 1000),
          "changed native ownership revokes open");
    for (int denied = 0; denied < 6; denied++) {
      var flags = new boolean[] {true, true, true, true, true, true};
      flags[denied] = false;
      check(
          !owner.canOpen(
              snapshot,
              player,
              10,
              new InteractionContainerLease.OpenContext(
                  flags[0], flags[1], flags[2], flags[3], flags[4], flags[5]),
              1000),
          "online, foreign world, unpaired player, death or existing container denies opening");
    }
    check(!owner.owns(player, 7), "pending request owns no vanilla container");
    var opened = owner.opened(7);
    check(opened.owns(player, 7), "exact player and vanilla sync ID own opened container");
    check(
        !opened.owns(player, 8) && !opened.owns(UUID.randomUUID(), 7),
        "foreign container/player cannot be closed");
    check(
        !opened.canOpen(snapshot, player, 10, context, 1000),
        "active container cannot double-open");
    check(
        !opened.current(decode(VALID.replace("\"token\":2", "\"token\":3")), player, 10, 1000),
        "new grace token revokes existing container lease");
    check(
        Player.class.getMethod("getEnderChestInventory").getReturnType()
            == PlayerEnderChestContainer.class,
        "pinned player exposes actual persistent vanilla Ender Chest inventory");
    check(
        ChestMenu.class
                .getMethod("threeRows", int.class, Inventory.class, Container.class)
                .getReturnType()
            == ChestMenu.class,
        "pinned menu can bind three rows directly to the real server inventory");
    check(
        ServerPlayer.class
                .getMethod("openMenu", net.minecraft.world.MenuProvider.class)
                .getReturnType()
            == java.util.OptionalInt.class,
        "vanilla server open supplies actual synchronized container ID");
    check(
        SimpleMenuProvider.class.getConstructor(MenuConstructor.class, Component.class) != null,
        "vanilla provider supplies ordinary translated chest screen");
    System.out.println(
        "PASS: "
            + checks
            + " interaction container checks (lease/admission and pinned vanilla APIs).");
  }

  private static InteractionProtocol.Snapshot decode(String value) throws Exception {
    return InteractionProtocol.decode(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void check(boolean value, String detail) {
    if (!value) throw new AssertionError(detail);
    checks++;
  }
}

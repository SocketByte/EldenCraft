package dev.eldencraft.bridge.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.WorldDataConfiguration;

/** Exercises startup dispatch and vanilla creation settings without running either game. */
public final class EldenCraftWorldConformance {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static final class Saves implements EldenCraftWorld.Worlds {
    private final Set<String> folders;
    final List<String> calls = new ArrayList<>();
    boolean failOpen;

    Saves(String... folders) {
      this.folders = Set.of(folders);
    }

    @Override
    public boolean exists(String folder) {
      return folders.contains(folder);
    }

    @Override
    public void open(String folder) {
      calls.add("open:" + folder);
      if (failOpen) throw new IllegalStateException("Save cannot be loaded");
    }

    @Override
    public void create() {
      calls.add("create:EldenCraft");
    }
  }

  public static void main(String[] args) throws Exception {
    checks += AchievementToastsConformance.verify();
    var firstRun = new EldenCraftWorld.Startup(null, null);
    var empty = new Saves();
    firstRun.tick(false, false, empty);
    firstRun.tick(false, false, empty);
    check(empty.calls.isEmpty(), "Wait for startup overlays and first-run screens to finish");
    firstRun.tick(true, false, empty);
    check(
        empty.calls.equals(List.of("create:EldenCraft")),
        "First launch creates and enters the dedicated world without a host or launcher marker");
    firstRun.tick(true, false, empty);
    firstRun.tick(false, true, empty);
    firstRun.tick(true, false, empty);
    check(empty.calls.size() == 1, "Returning to the title screen must not trigger another load");

    var existing = new Saves("EldenCraft", "Survival");
    new EldenCraftWorld.Startup(null, null).tick(true, false, existing);
    check(
        existing.calls.equals(List.of("open:EldenCraft")),
        "Later launches open the existing save instead of replacing it");

    var legacy = new Saves("EldenCraft Passthrough Lab", "Survival");
    new EldenCraftWorld.Startup(null, null).tick(true, false, legacy);
    check(
        legacy.calls.equals(List.of("open:EldenCraft Passthrough Lab")),
        "An earlier dedicated save is reused");

    var both = new Saves("EldenCraft", "EldenCraft Passthrough Lab");
    new EldenCraftWorld.Startup(null, null).tick(true, false, both);
    check(both.calls.equals(List.of("open:EldenCraft")), "Prefer the current dedicated save");

    var unrelated = new Saves("Survival");
    new EldenCraftWorld.Startup(null, null).tick(true, false, unrelated);
    check(
        unrelated.calls.equals(List.of("create:EldenCraft")),
        "An unrelated world is neither opened nor overwritten");

    var quickPlay = new EldenCraftWorld.Startup(null, null);
    var otherSession = new Saves();
    quickPlay.tick(false, true, otherSession);
    quickPlay.tick(true, false, otherSession);
    check(
        otherSession.calls.isEmpty(),
        "Do not take over an already joined game or reconnect on exit");

    var disabled = new Saves("EldenCraft");
    new EldenCraftWorld.Startup("0", null).tick(true, false, disabled);
    check(disabled.calls.isEmpty(), "Explicit automatic-opening opt-out is respected");
    var creationDisabled = new Saves();
    new EldenCraftWorld.Startup(null, "0").tick(true, false, creationDisabled);
    check(creationDisabled.calls.isEmpty(), "Explicit automatic-creation opt-out is respected");
    var openOnly = new Saves("EldenCraft");
    new EldenCraftWorld.Startup(null, "0").tick(true, false, openOnly);
    check(
        openOnly.calls.equals(List.of("open:EldenCraft")),
        "Creation opt-out still opens an existing save");

    var broken = new Saves("EldenCraft");
    broken.failOpen = true;
    var failedLoad = new EldenCraftWorld.Startup(null, null);
    boolean rejected = false;
    try {
      failedLoad.tick(true, false, broken);
    } catch (IllegalStateException expected) {
      rejected = true;
    }
    check(rejected, "Let vanilla report a world-loading failure");
    failedLoad.tick(true, false, broken);
    check(broken.calls.size() == 1, "A failed load must not loop or replace the existing save");

    var settings = EldenCraftWorld.settings();
    check(settings.levelName().equals("EldenCraft"), "The save uses the dedicated world name");
    check(settings.gameType() == GameType.SURVIVAL, "Inventory and items use survival rules");
    check(
        settings.difficultySettings().difficulty() == Difficulty.NORMAL, "Hostile spawn eggs work");
    check(settings.allowCommands(), "Dedicated EldenCraft commands are enabled");
    check(
        settings.dataConfiguration().equals(WorldDataConfiguration.DEFAULT),
        "Mod data packs are loaded by vanilla");
    check(!EldenCraftWorld.options().generateStructures(), "Do not generate unrelated structures");
    check(!EldenCraftWorld.options().generateBonusChest(), "Do not inject starter items");
    System.out.println("EldenCraftWorldConformance: " + checks + " checks passed");
  }
}

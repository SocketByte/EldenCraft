package dev.eldencraft.bridge.client;

import dev.eldencraft.bridge.InteractionProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Host identity, menu continuity and malformed publications never produce a purchase authority. */
public final class CampaignBridgeConformance {
  private static int checks;
  private static final String VALID =
      """
      {"version":1,"pid":123,"session":456,"seq":1,"timestamp_ms":1000,
       "active":true,"character":"slot-0-character-1","runes":12000,
       "hp":414,"max_hp":414,"stamina":96,"max_stamina":96,
       "defeated":["margit"],"merchant":{"id":"100000","name":"Merchant","token":"456-1"},
       "ack":{"id":"purchase-1","status":"debited","amount":400},
       "guard_seq":3,"guard_damage":12.5,
       "damage_events":[{"seq":1,"raw_damage":6,"blocked":true},{"seq":2,"raw_damage":8,"blocked":false}]}
      """;

  public static void main(String[] args) throws Exception {
    var s = decode(VALID);
    String boss =
        "{\"id\":\"slot-0-boss-1\",\"name\":\"Margit, the Fell Omen\",\"hp\":4000,\"max_hp\":6000}";
    String bossFrame =
        VALID.replace("\"version\":1", "\"version\":1,\"bosses_active\":[" + boss + "]");
    var bosses = decode(bossFrame).activeBosses();
    check(
        bosses.size() == 1
            && bosses.getFirst().name().equals("Margit, the Fell Omen")
            && bosses.getFirst().hp() == 4000,
        "native visible gauge includes name and real HP");
    check(s.activeBosses().isEmpty(), "older publishers cannot fabricate boss gauges");
    try {
      bosses.clear();
      throw new AssertionError("Mutable boss observations");
    } catch (UnsupportedOperationException expected) {
      checks++;
    }
    reject(bossFrame.replace("\"hp\":4000", "\"hp\":7000"));
    reject(bossFrame.replace("\"max_hp\":6000", "\"max_hp\":0"));
    reject(bossFrame.replace("\"name\":\"Margit, the Fell Omen\"", "\"name\":\"\""));
    reject(bossFrame.replace("[" + boss + "]", "[" + boss + "," + boss + "]"));
    reject(
        bossFrame.replace(
            "[" + boss + "]",
            "[" + String.join(",", java.util.Collections.nCopies(5, boss)) + "]"));
    check(s.runes() == 12000 && s.defeated().contains("margit"), "native wallet and victory");
    check(
        s.merchant().token().equals("456-1") && s.ack().amount() == 400, "menu and debit identity");
    check(
        s.guardSeq() == 3 && s.guardDamage() == 12.5,
        "zero-HP guard expenditure survives transport");
    check(
        s.damageEvents().size() == 2
            && s.damageEvents().get(0).blocked()
            && s.damageEvents().get(1).rawDamage() == 8,
        "individual native hits preserve durability evidence");
    check(
        decode(
                    VALID
                        .replace("\"raw_damage\":8", "\"raw_damage\":1e15")
                        .replace("\"guard_damage\":12.5", "\"guard_damage\":1e20"))
                .damageEvents()
                .get(1)
                .rawDamage()
            == 1e15,
        "valid extreme incoming-scale settings preserve transport authority");
    try {
      s.damageEvents().clear();
      throw new AssertionError("Mutable damage events");
    } catch (UnsupportedOperationException expected) {
      checks++;
    }
    check(CampaignBridge.fresh(s, 1000) && CampaignBridge.fresh(s, 2499), "fresh menu authority");
    check(
        !CampaignBridge.fresh(s, 2500) && !CampaignBridge.fresh(s, 999),
        "stale and future observations revoke");
    try {
      s.defeated().add("godrick");
      throw new AssertionError("Mutable victory set");
    } catch (UnsupportedOperationException expected) {
      checks++;
    }
    check(
        !CampaignBridge.fresh(decode(VALID.replace("\"active\":true", "\"active\":false")), 1000),
        "inactive publication revokes");
    check(
        !CampaignBridge.fresh(decode(VALID.replace("\"hp\":414", "\"hp\":0")), 1000),
        "native death revokes");
    graceIdentity();
    check(
        decode(VALID.replace("\"runes\":12000", "\"runes\":0")).runes() == 0,
        "zero wallet is valid");
    var enriched =
        decode(
            VALID
                .replace("\"hp\":414", "\"hp\":0")
                .replace("\"active\":true", "\"active\":false")
                .replace(
                    "\"version\":1",
                    "\"version\":1,\"dead\":true,\"experience_seq\":3,\"experience_total\":12"));
    check(
        enriched.dead() && enriched.experienceSeq() == 3 && enriched.experienceTotal() == 12,
        "death and native kill XP are explicit observations");
    check(
        s.lootSeq() == 0 && s.lootEvents().isEmpty(), "older publishers report no ordinary kills");
    String lootFrame =
        VALID.replace(
            "\"version\":1",
            "\"version\":1,\"loot_seq\":9,"
                + "\"loot_events\":[{\"seq\":8,\"max_hp\":219},{\"seq\":9,\"max_hp\":2889}]");
    var loot = decode(lootFrame);
    check(
        loot.lootSeq() == 9
            && loot.lootEvents()
                .equals(
                    java.util.List.of(
                        new dev.eldencraft.bridge.CampaignLoot.Kill(8, 219),
                        new dev.eldencraft.bridge.CampaignLoot.Kill(9, 2889))),
        "ordinary kills carry their sequence and native maximum HP");
    try {
      loot.lootEvents().clear();
      throw new AssertionError("Mutable loot events");
    } catch (UnsupportedOperationException expected) {
      checks++;
    }
    String placed =
        lootFrame.replace(
            "\"max_hp\":2889}", "\"max_hp\":2889,\"map\":7,\"position\":[1.5,-2,3.25]}");
    var kill = decode(placed).lootEvents().get(1);
    check(
        kill.map() == 7
            && kill.position().equals(new dev.eldencraft.bridge.WorldOrigin.Vec(1.5, -2, 3.25))
            && decode(placed).lootEvents().get(0).position() == null,
        "a kill in the live shared world carries its region death point");
    reject(placed.replace("\"map\":7,", ""));
    reject(placed.replace(",\"position\":[1.5,-2,3.25]", ""));
    reject(placed.replace("[1.5,-2,3.25]", "[1.5,-2]"));
    reject(placed.replace("[1.5,-2,3.25]", "[1.5,-2,\"3\"]"));
    reject(placed.replace("[1.5,-2,3.25]", "[1.5,-2,1e300]"));
    reject(placed.replace("\"map\":7", "\"map\":-1"));
    reject(lootFrame.replace("\"loot_seq\":9", "\"loot_seq\":8"));
    reject(lootFrame.replace("\"seq\":8,\"max_hp\"", "\"seq\":9,\"max_hp\""));
    reject(lootFrame.replace("\"max_hp\":219", "\"max_hp\":0"));
    reject(lootFrame.replace("\"max_hp\":219", "\"max_hp\":2.5"));
    reject(lootFrame.replace("\"loot_seq\":9", "\"loot_seq\":-1"));
    reject(
        lootFrame
            .replace("\"loot_events\":[", "\"loot_events\":{\"x\":[")
            .replace("2889}]", "2889}]}"));
    reject(
        VALID.replace(
            "\"version\":1",
            "\"version\":1,\"loot_seq\":100,\"loot_events\":["
                + String.join(
                    ",",
                    java.util.stream.IntStream.rangeClosed(1, 65)
                        .mapToObj(i -> "{\"seq\":" + i + ",\"max_hp\":100}")
                        .toList())
                + "]"));
    for (String invalid :
        new String[] {
          VALID.replace("\"version\":1", "\"version\":2"),
          VALID.replace("\"pid\":123", "\"pid\":0"),
          VALID.replace("\"session\":456", "\"session\":0"),
          VALID.replace("\"seq\":1", "\"seq\":1.5"),
          VALID.replace("\"runes\":12000", "\"runes\":-1"),
          VALID.replace("\"runes\":12000", "\"runes\":1000000000"),
          VALID.replace("\"hp\":414", "\"hp\":415"),
          VALID.replace("\"stamina\":96", "\"stamina\":97"),
          VALID.replace("[\"margit\"]", "[\"margit\",\"margit\"]"),
          VALID.replace("\"active\":true", "\"active\":1"),
          VALID.replace("\"version\":1", "\"version\":1,\"version\":1"),
          VALID.replace("\"guard_damage\":12.5", "\"guard_damage\":1e309"),
          VALID.replace("\"amount\":400", "\"amount\":\"400\""),
          VALID.replace("\"seq\":2,\"raw_damage\"", "\"seq\":1,\"raw_damage\""),
          VALID.replace("\"raw_damage\":8", "\"raw_damage\":-1"),
          VALID.replace("\"blocked\":true", "\"blocked\":1"),
          VALID.replace("\"raw_damage\":8", "\"raw_damage\":1e309"),
          VALID.replace("\"version\":1", "\"version\":1,\"dead\":true"),
          VALID.replace("\"version\":1", "\"version\":1,\"experience_seq\":-1"),
          VALID.replace("\"version\":1", "\"version\":1,\"experience_total\":9000000000000001"),
          VALID + "{}"
        }) reject(invalid);
    reject(" ".repeat(131073));
    reject(
        VALID.replace(
            "\"damage_events\":[",
            "\"damage_events\":["
                + "{\"seq\":100,\"raw_damage\":1,\"blocked\":false},".repeat(64)));
    try {
      CampaignBridge.decode(new byte[] {(byte) 0xc3, (byte) 0x28});
      throw new AssertionError("Invalid UTF-8 accepted");
    } catch (IOException expected) {
      checks++;
    }
    System.out.println("PASS: " + checks + " campaign transport checks");
  }

  private static CampaignBridge.Snapshot decode(String text) throws IOException {
    return CampaignBridge.decode(text.getBytes(StandardCharsets.UTF_8));
  }

  private static void graceIdentity() throws Exception {
    String grace =
        """
        {"version":1,"pid":123,"session":987,"seq":8,"timestamp_ms":1000,"active":true,
         "menu":{"token":2,"kind":"grace","title":"Site of Grace","choices":[
          {"id":10,"text":"Ender Chest","enabled":true,"action":"ender_chest"}]}}
        """;
    var interaction = InteractionProtocol.decode(grace.getBytes(StandardCharsets.UTF_8));
    String resting =
        VALID
            .replace("\"active\":true", "\"active\":false,\"identity_ready\":true")
            .replace("\"hp\":414", "\"hp\":0");
    var host = decode(resting);
    var identity = CampaignBridge.graceIdentity(host, interaction, 1000);
    check(
        identity != null
            && identity.pid() == 123
            && identity.session() == 456
            && identity.character().equals("slot-0-character-1")
            && !CampaignBridge.fresh(host, 1000),
        "verified resting identity opens grace inventory without granting campaign gameplay");
    check(
        CampaignBridge.graceIdentity(decode(VALID), interaction, 1000) != null,
        "active older host retains backward-compatible grace identity");
    for (String denied :
        new String[] {
          resting.replace(",\"identity_ready\":true", ""),
          resting.replace("\"identity_ready\":true", "\"identity_ready\":false"),
          resting.replace("\"identity_ready\":true", "\"identity_ready\":true,\"dead\":true"),
          resting.replace("slot-0-character-1", "unloaded"),
          resting.replace("slot-0-character-1", " "),
          resting.replace("\"pid\":123", "\"pid\":124")
        })
      check(
          CampaignBridge.graceIdentity(decode(denied), interaction, 1000) == null,
          "unverified, dead, unloaded or foreign retained identity cannot own a chest");
    for (String denied :
        new String[] {
          grace.replace("\"active\":true", "\"active\":false"),
          grace.replace("\"timestamp_ms\":1000", "\"timestamp_ms\":500"),
          grace.replace("\"enabled\":true", "\"enabled\":false"),
          grace.replace(",\"action\":\"ender_chest\"", ""),
          grace
              .replace(",\"action\":\"ender_chest\"", "")
              .replace("\"kind\":\"grace\"", "\"kind\":\"npc\"")
        })
      check(
          CampaignBridge.graceIdentity(
                  host, InteractionProtocol.decode(denied.getBytes(StandardCharsets.UTF_8)), 1000)
              == null,
          "identity alone cannot grant an inactive, stale or ordinary menu inventory access");
    check(
        CampaignBridge.graceIdentity(host, interaction, 999) == null
            && CampaignBridge.graceIdentity(host, interaction, 1500) == null
            && CampaignBridge.graceIdentity(host, null, 1000) == null,
        "future, expired or missing interaction revokes resting identity");
    var laterInteraction =
        InteractionProtocol.decode(
            grace
                .replace("\"timestamp_ms\":1000", "\"timestamp_ms\":2500")
                .getBytes(StandardCharsets.UTF_8));
    check(
        CampaignBridge.graceIdentity(host, laterInteraction, 2500) == null,
        "fresh grace cannot revive an expired campaign identity");
    reject(resting.replace("\"identity_ready\":true", "\"identity_ready\":1"));
  }

  private static void reject(String text) throws Exception {
    try {
      decode(text);
      throw new AssertionError("Malformed campaign JSON accepted");
    } catch (IOException | IllegalArgumentException expected) {
      checks++;
    }
  }

  private static void check(boolean condition, String detail) {
    if (!condition) throw new AssertionError(detail);
    checks++;
  }
}

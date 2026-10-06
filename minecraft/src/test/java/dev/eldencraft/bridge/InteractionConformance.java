package dev.eldencraft.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Real-row authority, attachment freshness and menu geometry. */
public final class InteractionConformance {
  private static int checks;
  private static final String VALID =
      """
      {"version":1,"pid":123,"session":456,"seq":8,"timestamp_ms":1000,"active":true,
       "prompt":{"token":1,"text_id":4000,"text":"Open door","enabled":true},
       "menu":{"token":2,"kind":"grace","title":"Site of Grace","choices":[
         {"id":10,"text":"Pass time","enabled":true},
         {"id":12,"text":"Unavailable","enabled":false},
         {"id":99,"text":"Leave","enabled":true}]},
       "subtitle":{"text":"Welcome, traveller."},
       "input":{"seq":21,"buttons":4,"pressed":4}}
      """;

  public static void main(String[] args) throws Exception {
    var s = decode(VALID);
    var chest = decode(VALID.replace("\"id\":10,", "\"id\":10,\"action\":\"ender_chest\","));
    check(
        chest.menu().choices().getFirst().action().equals("ender_chest")
            && s.menu().choices().getFirst().action().isEmpty(),
        "typed Ender Chest action survives while ordinary native rows remain unmodified");
    reject(VALID.replace("\"id\":10,", "\"id\":10,\"action\":\"open_inventory\","));
    reject(VALID.replace("\"id\":10,", "\"id\":10,\"action\":true,"));
    reject(
        VALID
            .replace("\"id\":10,", "\"id\":10,\"action\":\"ender_chest\",")
            .replace("\"kind\":\"grace\"", "\"kind\":\"npc\""));
    check(
        s.prompt().text().equals("Open door") && s.menu().choices().get(0).id() == 10,
        "native prompt and non-contiguous ESD row IDs survive");
    check(InteractionProtocol.selectable(s, 2, 10), "enabled current native row can be selected");
    check(
        !InteractionProtocol.selectable(s, 1, 10)
            && !InteractionProtocol.selectable(s, 2, 12)
            && !InteractionProtocol.selectable(s, 2, 11),
        "stale, disabled and invented choices reject");
    check(
        InteractionProtocol.fresh(s, 1000, 123) && InteractionProtocol.fresh(s, 1499, 123),
        "fresh matching host owns interaction");
    check(
        !InteractionProtocol.fresh(s, 999, 123)
            && !InteractionProtocol.fresh(s, 1500, 123)
            && !InteractionProtocol.fresh(s, 1000, 124),
        "future, stale and foreign host revoke");
    check(
        !InteractionProtocol.fresh(
            decode(VALID.replace("\"active\":true", "\"active\":false")), 1000, 123),
        "unfocused host cannot drive UI");
    check(
        decode(VALID.replace("\"seq\":21", "\"seq\":0")).input().sequence() == 0,
        "inactive input baseline permits readiness handshake");
    var dialog =
        decode(
            VALID
                .replace("\"kind\":\"grace\"", "\"kind\":\"dialog\"")
                .replace("Site of Grace", "Confirm this decision.\\n" + "Message ".repeat(100)));
    check(
        dialog.menu().title().contains("\n") && dialog.menu().title().length() > 256,
        "real long multiline dialog questions are preserved");
    try {
      s.menu().choices().clear();
      throw new AssertionError("Mutable choices");
    } catch (UnsupportedOperationException expected) {
      checks++;
    }
    for (String invalid :
        new String[] {
          VALID.replace("\"version\":1", "\"version\":2"),
          VALID.replace("\"pid\":123", "\"pid\":0"),
          VALID.replace("\"seq\":8", "\"seq\":8.5"),
          VALID.replace("\"kind\":\"grace\"", "\"kind\":\"levelling\""),
          VALID.replace("\"id\":99", "\"id\":10"),
          VALID.replace("\"buttons\":4", "\"buttons\":4096"),
          VALID.replace("\"pressed\":4", "\"pressed\":-1"),
          VALID.replace("\"enabled\":true", "\"enabled\":1"),
          VALID.replace("\"token\":2", "\"token\":0"),
          VALID + "{}"
        }) reject(invalid);
    reject(" ".repeat(InteractionProtocol.MAX_BYTES + 1));
    check(
        InteractionProtocol.unusedProgression("Level Up")
            && InteractionProtocol.unusedProgression("Add charge to flask")
            && !InteractionProtocol.unusedProgression("Talk")
            && !InteractionProtocol.unusedProgression("Pass time"),
        "unused progression never enters replacement menu");
    check(
        InteractionProtocol.unusedProgression(s.menu(), "Upgrade flask")
            && InteractionProtocol.unusedProgression(s.menu(), "Memorize spell")
            && !InteractionProtocol.unusedProgression(
                decode(VALID.replace("\"kind\":\"grace\"", "\"kind\":\"npc\"")).menu(),
                "Ask about the flask")
            && !InteractionProtocol.unusedProgression(
                decode(VALID.replace("\"kind\":\"grace\"", "\"kind\":\"dialog\"")).menu(),
                "Accept flask"),
        "flask-related NPC and quest choices remain available");
    for (int w : new int[] {160, 320, 640, 1920})
      for (int h : new int[] {120, 240, 480, 1080}) {
        var layout = InteractionLayout.create(w, h, 64);
        check(
            layout.x() >= 0
                && layout.y() >= 0
                && layout.x() + layout.width() <= w
                && layout.y() + layout.height() <= h,
            "menu stays in viewport");
        check(
            layout.rowY(layout.rows() - 1) + 20 <= layout.y() + layout.height() - 28,
            "choice rows cannot overlap footer");
        var dialogLayout = InteractionLayout.create(w, h, 2, 80, 9);
        check(
            dialogLayout.bodyHeight() <= Math.max(0, h - 100)
                && dialogLayout.rowY(dialogLayout.rows() - 1) + 20
                    <= dialogLayout.y() + dialogLayout.height() - 28,
            "long dialog body and choices stay above footer");
      }
    var health = new InteractionFeedHealth();
    check(
        health.observe(123, true, false, 0) == InteractionFeedHealth.Change.NONE
            && health.observe(123, true, false, 1_999_999_999L)
                == InteractionFeedHealth.Change.NONE,
        "brief startup and publication gaps stay quiet");
    check(
        health.observe(123, true, false, 2_000_000_000L) == InteractionFeedHealth.Change.UNAVAILABLE
            && health.observe(123, true, false, 8_000_000_000L)
                == InteractionFeedHealth.Change.NONE,
        "sustained missing interaction feed reports once");
    check(
        health.observe(0, false, false, 9_000_000_000L) == InteractionFeedHealth.Change.NONE
            && health.observe(123, true, false, 10_000_000_000L)
                == InteractionFeedHealth.Change.NONE,
        "focus loss neither reports failure nor repeats a known outage");
    check(
        health.observe(123, true, true, 11_000_000_000L) == InteractionFeedHealth.Change.RESTORED
            && health.observe(123, true, true, 12_000_000_000L)
                == InteractionFeedHealth.Change.NONE,
        "interaction recovery reports once");
    check(
        health.observe(123, true, false, 13_000_000_000L) == InteractionFeedHealth.Change.NONE
            && health.observe(0, false, false, 14_000_000_000L) == InteractionFeedHealth.Change.NONE
            && health.observe(123, true, false, 20_000_000_000L)
                == InteractionFeedHealth.Change.NONE,
        "background time cannot inflate an unreported foreground outage");
    check(
        health.observe(124, true, false, 21_000_000_000L) == InteractionFeedHealth.Change.NONE
            && health.observe(124, true, false, 23_000_000_000L)
                == InteractionFeedHealth.Change.UNAVAILABLE,
        "new native publisher receives its own bounded availability diagnostic");
    System.out.println("PASS: " + checks + " interaction checks");
  }

  private static InteractionProtocol.Snapshot decode(String text) throws IOException {
    return InteractionProtocol.decode(text.getBytes(StandardCharsets.UTF_8));
  }

  private static void reject(String text) throws IOException {
    try {
      decode(text);
      throw new AssertionError("Invalid interaction accepted");
    } catch (IOException | IllegalArgumentException expected) {
      checks++;
    }
  }

  private static void check(boolean condition, String detail) {
    if (!condition) throw new AssertionError(detail);
    checks++;
  }
}

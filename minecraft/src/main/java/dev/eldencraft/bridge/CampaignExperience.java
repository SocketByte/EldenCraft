package dev.eldencraft.bridge;

/** Saved acknowledgement of confirmed native kills, independent of rune currency and levels. */
public final class CampaignExperience {
  public static final String PREFIX = "eldencraft.xp.";
  private static final long MAX_COUNTER = 9_000_000_000_000_000L;

  public record Cursor(long session, long sequence, long total) {
    public String tag() {
      return PREFIX + session + "." + sequence + "." + total;
    }
  }

  public record Update(Cursor cursor, int award, boolean changed) {}

  private CampaignExperience() {}

  public static Cursor parse(String tag) {
    if (!tag.startsWith(PREFIX)) throw new IllegalArgumentException("Not a campaign XP receipt");
    String[] fields = tag.substring(PREFIX.length()).split("\\.", -1);
    if (fields.length != 3) throw new IllegalArgumentException("Invalid campaign XP receipt");
    try {
      Cursor value =
          new Cursor(
              Long.parseLong(fields[0]), Long.parseLong(fields[1]), Long.parseLong(fields[2]));
      validate(value);
      return value;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("Invalid campaign XP receipt", error);
    }
  }

  public static Update advance(Cursor previous, long session, long sequence, long total) {
    Cursor observed = new Cursor(session, sequence, total);
    validate(observed);
    if (previous == null || previous.session() != session)
      return new Update(observed, 0, true); // A new attachment establishes a fresh baseline.
    validate(previous);
    if (sequence < previous.sequence()
        || total < previous.total()
        || (sequence == previous.sequence() && total != previous.total()))
      throw new IllegalArgumentException("Native XP counter regressed or changed without a kill");
    if (sequence == previous.sequence()) return new Update(previous, 0, false);
    long delta = total - previous.total();
    int award = (int) Math.min(1_000_000L, delta);
    long credited = previous.total() + award;
    // Large legitimate batches are paid in bounded installments. Keep the previous sequence
    // until its complete cumulative amount is saved, allowing recovery after a disconnect.
    Cursor next = new Cursor(session, credited == total ? sequence : previous.sequence(), credited);
    return new Update(next, award, true);
  }

  private static void validate(Cursor value) {
    if (value.session() < 1
        || value.sequence() < 0
        || value.sequence() > MAX_COUNTER
        || value.total() < 0
        || value.total() > MAX_COUNTER)
      throw new IllegalArgumentException("Campaign XP receipt outside bounds");
  }
}

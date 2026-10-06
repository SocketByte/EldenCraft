package dev.eldencraft.bridge;

import java.util.*;

/** A bounded, one-shot presentation queue. Native acceptance is required before feedback exists. */
public final class ConfirmedFeedback<T> {
  private record Pending<T>(long millis, T value) {}

  private final LinkedHashMap<Long, Pending<T>> pending = new LinkedHashMap<>();
  private long session;

  public boolean offer(long expected, long sequence, long millis, T value) {
    if (expected <= 0 || sequence <= 0 || millis < 0 || value == null) return false;
    enter(expected);
    if (pending.size() >= 96 || pending.containsKey(sequence)) return false;
    pending.put(sequence, new Pending<>(millis, value));
    return true;
  }

  public List<T> resolve(
      long expected, long now, List<WorldProtocol.Acknowledgement> acknowledgements) {
    enter(expected);
    var accepted = new ArrayList<T>();
    pending.entrySet().removeIf(e -> now < e.getValue().millis || now - e.getValue().millis > 1000);
    for (var ack : acknowledgements) {
      var entry = pending.remove(ack.sequence());
      if (entry != null && ack.result() == 1 && Float.isFinite(ack.delta()) && ack.delta() > 0)
        accepted.add(entry.value);
    }
    return List.copyOf(accepted);
  }

  private void enter(long expected) {
    if (expected != session) {
      pending.clear();
      session = expected;
    }
  }

  public void clear() {
    pending.clear();
    session = 0;
  }
}

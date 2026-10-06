package dev.eldencraft.bridge;

import java.util.ArrayDeque;

/** Bounded one-shot client/server handoff. No event survives a session change or a 250 ms gap. */
public final class ProxyEvents<T> {
  private record Event<T>(long session, long nanos, T value) {}

  private final ArrayDeque<Event<T>> events = new ArrayDeque<>();

  public synchronized boolean offer(long session, long nanos, T value) {
    if (session <= 0 || value == null || events.size() >= 32) return false;
    events.addLast(new Event<>(session, nanos, value));
    return true;
  }

  public synchronized T poll(long session, long now) {
    while (!events.isEmpty()) {
      var event = events.removeFirst();
      long age = now - event.nanos;
      if (event.session == session && age >= 0 && age < 250_000_000L) return event.value;
    }
    return null;
  }

  public synchronized void clear() {
    events.clear();
  }
}

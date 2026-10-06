package dev.eldencraft.bridge;

import com.google.gson.*;
import java.security.SecureRandom;
import java.util.*;

/**
 * Server-produced damage outbox. Sessions are acknowledged before any mutation can be forwarded.
 */
public final class WorldEvents {
  private long session = random(), sequence, epoch, pid, map, frame;
  private boolean initialized, ready;
  private final ArrayList<JsonObject> pending = new ArrayList<>();

  private static long random() {
    return new SecureRandom().nextLong() & Long.MAX_VALUE;
  }

  public synchronized void reset() {
    session = Math.max(1, random());
    sequence = 0;
    pending.clear();
    ready = initialized = false;
    frame = 0;
  }

  public synchronized void update(WorldProtocol.Host host, long now) {
    if (host == null || !host.active()) {
      if (initialized) reset();
      return;
    }
    if (!initialized || pid != host.pid() || epoch != host.epoch() || map != host.map()) {
      reset();
      initialized = true;
      pid = host.pid();
      epoch = host.epoch();
      map = host.map();
    }
    if (host.frame() < frame || host.guestSession() == session && host.ack() > sequence) {
      reset();
      return;
    }
    frame = host.frame();
    ready = host.guestSession() == session;
    pending.removeIf(
        e ->
            now - e.get("time_ms").getAsLong() > 1000
                || ready && e.get("seq").getAsLong() <= host.ack());
  }

  public synchronized long session() {
    return session;
  }

  public synchronized boolean ready() {
    return ready;
  }

  public synchronized boolean capacity() {
    return ready && pending.size() < 96;
  }

  public synchronized float pending(long id, long generation) {
    float amount = 0;
    for (var e : pending)
      if (e.get("target").getAsLong() == id && e.get("generation").getAsLong() == generation)
        amount += e.get("damage").getAsFloat();
    return amount;
  }

  public synchronized boolean add(long expected, JsonObject receipt) {
    return addSequence(expected, receipt) > 0;
  }

  public synchronized long addSequence(long expected, JsonObject receipt) {
    if (expected != session || !ready || pending.size() >= 128) return 0;
    var copy = receipt.deepCopy();
    copy.addProperty("seq", ++sequence);
    pending.add(copy);
    return sequence;
  }

  public synchronized JsonArray snapshot() {
    var result = new JsonArray();
    for (var e : pending) result.add(e.deepCopy());
    return result;
  }
}

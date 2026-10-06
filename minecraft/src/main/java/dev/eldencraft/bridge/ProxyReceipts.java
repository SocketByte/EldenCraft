package dev.eldencraft.bridge;

import java.util.*;

/**
 * Bounded cross-thread outbox with an explicit empty-session handshake; never replays an old host
 * epoch.
 */
public final class ProxyReceipts {
  private long session = Math.max(1, System.nanoTime()), nextSequence, pid, epoch, map, hostFrame;
  private boolean ready, initialized;
  private final ArrayList<ProxyProtocol.Receipt> pending = new ArrayList<>();

  public synchronized void reset() {
    session++;
    pending.clear();
    nextSequence = 0;
    hostFrame = 0;
    ready = false;
    initialized = false;
  }

  public synchronized void update(ProxyProtocol.Frame host) {
    if (host == null || !host.ready()) {
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
    if (host.frame() < hostFrame || ready && host.ackSession() != session) {
      reset();
      return;
    }
    hostFrame = host.frame();
    ready = host.ackSession() == session;
    if (ready) {
      if (host.ackSequence() > nextSequence) {
        reset();
        return;
      }
      pending.removeIf(r -> r.sequence() <= host.ackSequence());
    }
  }

  public synchronized long session() {
    return session;
  }

  public synchronized boolean ready() {
    return ready;
  }

  public synchronized boolean matches(long expectedSession, long expectedFrame) {
    return ready && session == expectedSession && hostFrame == expectedFrame;
  }

  public synchronized boolean matchesSession(long expectedSession) {
    return ready && session == expectedSession;
  }

  public record View(
      long session, long hostFrame, boolean ready, List<ProxyProtocol.Receipt> pending) {
    public float pendingDamage(long handle, long generation) {
      float damage = 0;
      for (var r : pending)
        if (r.handle() == handle && r.generation() == generation) damage += r.damage();
      return damage;
    }
  }

  public synchronized View view() {
    return new View(session, hostFrame, ready, List.copyOf(pending));
  }

  public synchronized boolean capacity() {
    return ready && pending.size() <= ProxyProtocol.MAX_RECEIPTS - ProxyProtocol.MAX_TARGETS;
  }

  public synchronized List<ProxyProtocol.Receipt> snapshot() {
    return List.copyOf(pending);
  }

  public synchronized float pendingDamage(long handle, long generation) {
    float damage = 0;
    for (var r : pending)
      if (r.handle() == handle && r.generation() == generation) damage += r.damage();
    return damage;
  }

  public synchronized boolean add(long expectedSession, ProxyProtocol.Receipt receipt) {
    if (!ready || expectedSession != session || pending.size() >= ProxyProtocol.MAX_RECEIPTS)
      return false;
    pending.add(
        new ProxyProtocol.Receipt(
            ++nextSequence,
            receipt.handle(),
            receipt.generation(),
            receipt.targetFrame(),
            receipt.millis(),
            receipt.attackId(),
            receipt.damage(),
            receipt.wearBefore(),
            receipt.wearAfter(),
            receipt.playerMaxHp(),
            receipt.item()));
    return true;
  }
}

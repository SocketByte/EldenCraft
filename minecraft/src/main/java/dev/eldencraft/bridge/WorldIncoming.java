package dev.eldencraft.bridge;

/** Consume-before-apply high-water, scoped to a single host/guest incarnation. */
public final class WorldIncoming {
  private record Context(long pid, long session, long epoch) {}

  private record State(Context context, long ack) {}

  // Immutable publication prevents the render thread from observing a new
  // context together with the preceding server context's ACK.
  private volatile State state;

  public void enter(long pid, long session, long epoch) {
    if (pid <= 0 || pid > 0xffff_ffffL || session <= 0 || epoch <= 0)
      throw new IllegalArgumentException("Incoming context");
    var context = new Context(pid, session, epoch);
    var current = state;
    if (current == null || !current.context.equals(context)) state = new State(context, 0);
  }

  public boolean consume(long sequence) {
    var current = state;
    if (current == null || sequence <= current.ack) return false;
    state = new State(current.context, sequence);
    return true;
  }

  public long ack(long pid, long session, long epoch) {
    var current = state;
    return current != null && current.context.equals(new Context(pid, session, epoch))
        ? current.ack
        : 0;
  }

  public void clear() {
    state = null;
  }

  public static long nativeHandle(String source) {
    if (source == null || source.isEmpty() || source.length() > 19) return 0;
    for (int i = 0; i < source.length(); i++)
      if (source.charAt(i) < '0' || source.charAt(i) > '9') return 0;
    try {
      return Math.max(0, Long.parseLong(source));
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }
}

package dev.eldencraft.bridge;

/**
 * Bounds invariants for short/tall/rectangular recipients, without launching or changing either
 * game.
 */
public final class ProxyShapeConformance {
  private static int checks;

  private static void check(boolean value, String label) {
    checks++;
    if (!value) throw new AssertionError(label);
  }

  public static void main(String[] args) {
    var tall = new ProxyShape(2, 6, 3);
    check(
        tall.width() == 3 && tall.entityHeight() == 6,
        "real entity dimensions cover tall rectangular recipient");
    check(
        tall.sampleY(-10, .5) == -7,
        "damage indicators sample actual middle rather than default 0.9m");
    check(
        tall.sampleX(10, -.5) == 9 && tall.sampleX(10, .5) == 11,
        "X endpoints preserve narrow side");
    check(
        tall.sampleZ(10, -.5) == 8.5 && tall.sampleZ(10, .5) == 11.5,
        "Z endpoints preserve wider side");
    var shortShape = new ProxyShape(.25, .5, .25);
    check(
        shortShape.entityHeight() == .5f && shortShape.sampleY(60, .5) == 60.25,
        "small entity emission is not human-sized");
    for (var shape :
        new ProxyShape[] {tall, shortShape, new ProxyShape(64, 64, 1), new ProxyShape(1, 2, 64)}) {
      boolean contained = true;
      for (int n = 0; n <= 100; n++) {
        double f = n / 100.0, offset = f - .5;
        contained &=
            shape.sampleX(0, offset) >= -shape.x() / 2 && shape.sampleX(0, offset) <= shape.x() / 2;
        contained &= shape.sampleY(0, f) >= 0 && shape.sampleY(0, f) <= shape.height();
        contained &=
            shape.sampleZ(0, offset) >= -shape.z() / 2 && shape.sampleZ(0, offset) <= shape.z() / 2;
      }
      check(contained, "all sampled emitter positions stay inside each target shape");
    }
    for (double bad :
        new double[] {0, -1, 65, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      try {
        new ProxyShape(bad, 1, 1);
        throw new AssertionError("bad X");
      } catch (IllegalArgumentException expected) {
        checks++;
      }
      try {
        new ProxyShape(1, bad, 1);
        throw new AssertionError("bad height");
      } catch (IllegalArgumentException expected) {
        checks++;
      }
      try {
        new ProxyShape(1, 1, bad);
        throw new AssertionError("bad Z");
      } catch (IllegalArgumentException expected) {
        checks++;
      }
    }
    System.out.println(
        "Proxy shape conformance: " + checks + " checks passed (pure geometry only).");
  }
}

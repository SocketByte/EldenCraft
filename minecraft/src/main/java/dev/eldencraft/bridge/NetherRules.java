package dev.eldencraft.bridge;

/**
 * The Nether's layout, shared by the integrated server (real blocks, lava damage), the client
 * (particles) and the compositor effect (EcHell* in EldenCraftPassthrough.fx), which paints the
 * same blocks onto Elden Ring's own terrain. Every function takes integer block coordinates
 * relative to the published grid origin and uses 32-bit integer hashing plus float arithmetic, so
 * the HLSL copy evaluates the same values. Keep the two in step: the effect's comments name the
 * matching Java method.
 */
public final class NetherRules {
  /** Real Minecraft blocks only replace sampled terrain this close to the portal. */
  public static final int REAL_RADIUS = 16, MAX_REAL_BLOCKS = 1200, COLUMNS_PER_TICK = 12;

  /**
   * A block takes the sampled surface cell itself when the surface lies in its lower 70%, so it is
   * sunk into Elden Ring's ground rather than floating above it (at most 0.3 m proud). Shared by
   * placement on hidden terrain (ShadowTerrainBlock) and the Nether's ground.
   */
  public static final double SINK = .7;

  /** Ticks inside a lit portal before the world turns (vanilla survival waits 80). */
  public static final int TRAVEL_TICKS = 40;

  /** Published radius meaning "everything": a different map, or the spread has finished. */
  public static final float EVERYWHERE = 100_000f;

  public static final int GROUND_NETHERRACK = 0,
      GROUND_CRIMSON = 1,
      GROUND_SOUL = 2,
      GROUND_MAGMA = 3,
      GROUND_LAVA = 4,
      GROUND_LAVA_RIM = 5;

  private NetherRules() {}

  /** lowbias32 over (x, z, seed); EcHellHash in the effect. */
  public static int hash(int x, int z, int seed) {
    int h = x * 0x8da6b343 ^ z * 0xd8163841 ^ seed * 0x9e3779b9;
    h ^= h >>> 16;
    h *= 0x7feb352d;
    h ^= h >>> 15;
    h *= 0x846ca68b;
    h ^= h >>> 16;
    return h;
  }

  /** [0, 1) from the top 24 bits; EcHellHash01. */
  public static float hash01(int x, int z, int seed) {
    return (hash(x, z, seed) >>> 8) * (1f / 16777216f);
  }

  /**
   * Smooth value noise on a lattice of {@code scale} blocks, evaluated at a block centre;
   * EcHellNoise. A multiply by the reciprocal (not a division) matches the effect compiler's
   * arithmetic.
   */
  public static float noise(int x, int z, float scale, int seed) {
    float inverse = 1f / scale, fx = (x + .5f) * inverse, fz = (z + .5f) * inverse;
    int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
    float tx = fx - ix, tz = fz - iz;
    tx = tx * tx * (3 - 2 * tx);
    tz = tz * tz * (3 - 2 * tz);
    float a = hash01(ix, iz, seed),
        b = hash01(ix + 1, iz, seed),
        c = hash01(ix, iz + 1, seed),
        d = hash01(ix + 1, iz + 1, seed);
    float top = a + (b - a) * tx, bottom = c + (d - c) * tx;
    return top + (bottom - top) * tz;
  }

  /**
   * Ragged distance of a block from the spread centre (cx, cz, grid-relative): the front is a noisy
   * circle that advances block by block; EcHellRagged.
   */
  public static float ragged(int x, int z, float cx, float cz) {
    float dx = x + .5f - cx, dz = z + .5f - cz;
    return (float) Math.sqrt(dx * dx + dz * dz) + (noise(x, z, 6, 3) - .5f) * 8;
  }

  /**
   * Radius in metres reached {@code seconds} after the Nether opened; EcHellRadius on the CPU side.
   */
  public static float radius(double seconds) {
    if (!(seconds > 0)) return 1.5f;
    // A slow creep for the first minute (0.4 m/s), then it gathers pace.
    double r = 1.5 + .4 * seconds + (seconds > 60 ? .04 * (seconds - 60) * (seconds - 60) : 0);
    // Past Elden Ring's draw distance (about eight minutes in) the whole world has turned.
    return r > 8000 ? EVERYWHERE : (float) r;
  }

  /** Real blocks follow the same front but stop at {@link #REAL_RADIUS}. */
  public static float realRadius(double seconds) {
    return Math.min(REAL_RADIUS + .5f, radius(seconds));
  }

  /**
   * What a block of ground becomes; EcHellGround. Lava pools keep a safe circle around the portal
   * (when the centre is known) so arriving never lands the player in lava.
   */
  public static int ground(int x, int z, boolean centred, float cx, float cz) {
    float lava = noise(x, z, 7, 5);
    float dx = x + .5f - cx, dz = z + .5f - cz;
    boolean safe = centred && dx * dx + dz * dz < 36;
    // About 6% of the ground is lava, ringed by magma: dangerous, never a wall.
    if (!safe && lava > .83f) return GROUND_LAVA;
    if (!safe && lava > .78f) return GROUND_LAVA_RIM;
    float biome = noise(x, z, 9, 11);
    if (biome < .25f) return GROUND_CRIMSON;
    if (biome > .80f) return GROUND_SOUL;
    return hash01(x, z, 23) < .035f ? GROUND_MAGMA : GROUND_NETHERRACK;
  }

  /** Lava that burns: one block inside the painted pool edge, so a toe on the rim is only magma. */
  public static boolean deepLava(int x, int z, boolean centred, float cx, float cz) {
    if (ground(x, z, centred, cx, cz) != GROUND_LAVA) return false;
    for (int dx = -1; dx <= 1; dx++)
      for (int dz = -1; dz <= 1; dz++)
        if (ground(x + dx, z + dz, centred, cx, cz) < GROUND_LAVA) return false;
    return true;
  }

  /**
   * Which cell a Nether block replaces, given the sampled surface height inside the surface cell
   * (0..1): that cell when the surface is in its top 30%, otherwise the cell below, so a cube is
   * never more than 0.3 m proud of Elden Ring's ground (a step the player walks over). 0 or -1.
   */
  public static int surfaceOffset(double topFraction) {
    return topFraction >= SINK ? 0 : -1;
  }
}

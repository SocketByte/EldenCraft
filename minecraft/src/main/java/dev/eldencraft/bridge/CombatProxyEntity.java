package dev.eldencraft.bridge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A non-saving unarmored damage recipient, never a replacement Minecraft player or hostile AI. */
public final class CombatProxyEntity extends LivingEntity {
  public long hostHandle, hostGeneration, hostEpoch;
  public long worldHandle, worldGeneration, worldEpoch;
  private ProxyShape hostShape;
  private EntityDimensions hostDimensions;

  public CombatProxyEntity(EntityType<? extends LivingEntity> type, Level level) {
    super(type, level);
    noPhysics = true;
    setNoGravity(true);
    setSilent(true);
    setInvisible(true);
  }

  @Override
  public HumanoidArm getMainArm() {
    return HumanoidArm.RIGHT;
  }

  @Override
  protected EntityDimensions getDefaultDimensions(Pose pose) {
    // Entity construction can ask for dimensions before this subclass has received a target.
    return hostDimensions == null ? super.getDefaultDimensions(pose) : hostDimensions;
  }

  public void setHostBounds(AABB box) {
    var next = new ProxyShape(box.getXsize(), box.getYsize(), box.getZsize());
    if (!next.equals(hostShape)) {
      hostShape = next;
      hostDimensions = EntityDimensions.scalable(next.width(), next.entityHeight());
      refreshDimensions(); // Updates Entity's cached width/height/eye height; noPhysics prevents
      // terrain relocation.
    }
    setPos((box.minX + box.maxX) * .5, box.minY, (box.minZ + box.maxZ) * .5);
    setBoundingBox(
        box); // Preserve a non-square host box rather than expanding its attackable area.
  }

  @Override
  public double getX(double offset) {
    return hostShape == null ? super.getX(offset) : hostShape.sampleX(getX(), offset);
  }

  @Override
  public double getY(double fraction) {
    return hostShape == null ? super.getY(fraction) : hostShape.sampleY(getY(), fraction);
  }

  @Override
  public double getZ(double offset) {
    return hostShape == null ? super.getZ(offset) : hostShape.sampleZ(getZ(), offset);
  }

  @Override
  public boolean isPushable() {
    return false;
  }

  @Override
  public boolean isPushedByFluid() {
    return false;
  }

  @Override
  public void travel(Vec3 input) {
    setDeltaMovement(Vec3.ZERO);
  }

  @Override
  public void push(Entity other) {}

  @Override
  protected void pushEntities() {}

  @Override
  public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
    boolean melee = ProxyCombatAuthority.permitDamage(this, source);
    Object world = melee ? null : WorldDamageAuthority.begin(level, this, source);
    if (!melee && world == null) return false;
    float before = getHealth();
    boolean accepted = super.hurtServer(level, source, amount);
    float loss = before - getHealth();
    if (accepted && Float.isFinite(loss) && loss > 0) {
      if (melee) ProxyCombatAuthority.record(this, loss);
      else WorldDamageAuthority.finish(world, this, loss);
    }
    return accepted;
  }
}

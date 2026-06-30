package com.legendaryderp.hitmarkermod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import com.legendaryderp.hitmarkermod.HitMarkerMod;
import com.legendaryderp.hitmarkermod.client.HitMarkerRenderer;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命中检测事件处理器。
 *
 * 检测路径：
 *   1) AttackEntityEvent（客户端线程）→ 近战即时反馈
 *   2) LivingHurtEvent（单人模式服务端线程）→ 所有玩家造成的伤害
 *   3) LivingDeathEvent（单人模式）→ 击杀
 *   4) EntityJoinWorldEvent（客户端）→ 追踪抛射物
 *   5) ClientTick（客户端）→ 钓鱼竿 caughtEntity + 抛射物消失后定点血量检测
 *
 * 关键：血量轮询不扫全场，只查"被抛射物/鱼竿标记过的实体"，
 *       避免怪物摔伤/他人攻击等非玩家伤害触发假阳性。
 */
@SideOnly(Side.CLIENT)
public class CommonEventHandler {

    private final Random random = new Random();
    private final ConcurrentHashMap<Integer, Long> entityHitTimestamps = new ConcurrentHashMap<>();
    private static final long INVINCIBLE_FRAME = 500;

    // ── 抛射物追踪 ──
    private static class TrackedProjectile {
        final int entityId;
        double lastX, lastY, lastZ;
        long createdAt;
        final boolean isZeroDamage; // 雪球/蛋/药水等（可能有伤害药水，但箭肯定有伤害）
        TrackedProjectile(Entity e) {
            this.entityId = e.getEntityId();
            this.lastX = e.posX; this.lastY = e.posY; this.lastZ = e.posZ;
            this.createdAt = System.currentTimeMillis();
            this.isZeroDamage = e instanceof EntityThrowable && !(e instanceof EntityArrow);
        }
    }
    private final ConcurrentHashMap<Integer, TrackedProjectile> trackedProjectiles = new ConcurrentHashMap<>();
    private static final long PROJ_TIMEOUT = 10000;

    // ── 定点血量轮询（只追踪被抛射物标记过的实体） ──
    private static class HealthWatch {
        float lastHealth;
        long flaggedAt;     // 被标记的时间（抛射物消失于附近）
        long updatedAt;     // 上次健康检查时间
        boolean hitTriggered; // 本次标记周期是否已触发过
        HealthWatch(float h) {
            this.lastHealth = h;
            long now = System.currentTimeMillis();
            this.flaggedAt = now;
            this.updatedAt = now;
            this.hitTriggered = false;
        }
    }
    private final ConcurrentHashMap<Integer, HealthWatch> watchedHealth = new ConcurrentHashMap<>();
    private static final long WATCH_TIMEOUT = 4000;
    private static final long CONFIDENCE_WINDOW = 300; // 被标记后300ms内的掉血才算可信

    // ── 钓鱼竿 ──
    private int bobberId = -1;

    // ── 击杀音效防抖 ──
    private long lastKillSoundTime = 0;
    private static final long KILL_SOUND_COOLDOWN = 800;

    // ══════════════════════════════════════════════════════════════
    //  近战（客户端线程，即时）
    // ══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (event.entityPlayer == null || event.target == null || !event.target.isEntityAlive()) return;
        if (!isClientPlayer(event.entityPlayer)) return;

        int targetId = event.target.getEntityId();
        if (!shouldTriggerHitMarker(targetId)) return;

        triggerHitEffects(targetId);
    }

    // ══════════════════════════════════════════════════════════════
    //  受击事件（单人模式核心——服务端线程，名字比较）
    // ══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (event.entity == null || event.source == null || !event.entity.isEntityAlive()) return;

        EntityPlayer attacker = getPlayerAttacker(event.source);
        if (attacker == null || !isClientPlayerByName(attacker)) return;

        int targetId = event.entity.getEntityId();
        if (!shouldTriggerHitMarker(targetId)) return;

        entityHitTimestamps.put(targetId, System.currentTimeMillis());

        HitMarkerRenderer.showHitMarker();
        if (event.ammount > 0.5F) HitMarkerRenderer.showDamageNumber(event.ammount);
        playRandomHitSound();

        // 粒子
        EntityLivingBase living = (EntityLivingBase) event.entity;
        spawnHitParticlesAt(living.posX, living.posY + living.height / 2.0, living.posZ,
                30, HitMarkerMod.config.hitBloodIntensity);

        // 预判击杀（血量低于伤害）
        float hpAfter = living.getHealth() - event.ammount;
        if (hpAfter <= 0.0F) {
            HitMarkerRenderer.showKillMarker();
            playKillSound();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  击杀事件（单人模式兜底）
    // ══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.entity == null || event.source == null) return;

        EntityPlayer killer = getPlayerAttacker(event.source);
        if (killer != null && isClientPlayerByName(killer)) {
            HitMarkerRenderer.showKillMarker();
            playKillSound();
        }

        entityHitTimestamps.remove(event.entity.getEntityId());
    }

    // ══════════════════════════════════════════════════════════════
    //  抛射物追踪（多人模式）
    // ══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (!event.world.isRemote) return; // 只收客户端事件
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        Entity e = event.entity;

        // 箭矢
        if (e instanceof EntityArrow) {
            EntityArrow a = (EntityArrow) e;
            if (a.shootingEntity == mc.thePlayer ||
                (a.shootingEntity != null && a.shootingEntity.getUniqueID().equals(mc.thePlayer.getUniqueID()))) {
                trackedProjectiles.put(e.getEntityId(), new TrackedProjectile(e));
            }
            return;
        }

        // 雪球/蛋/药水/末影珍珠
        if (e instanceof EntityThrowable) {
            EntityThrowable t = (EntityThrowable) e;
            EntityLivingBase thrower = t.getThrower();
            if (thrower == mc.thePlayer ||
                (thrower != null && thrower.getUniqueID().equals(mc.thePlayer.getUniqueID()))) {
                trackedProjectiles.put(e.getEntityId(), new TrackedProjectile(e));
            }
            return;
        }

        // 鱼钩
        if (e instanceof EntityFishHook) {
            EntityFishHook f = (EntityFishHook) e;
            if (f.angler == mc.thePlayer) {
                bobberId = e.getEntityId();
                trackedProjectiles.put(e.getEntityId(), new TrackedProjectile(e));
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  客户端 Tick
    // ══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) { watchedHealth.clear(); return; }

        long now = System.currentTimeMillis();

        // ── 抛射物消失检测 → 标记附近实体 ──
        trackedProjectiles.values().removeIf(tp -> {
            Entity proj = mc.theWorld.getEntityByID(tp.entityId);
            if (proj == null || proj.isDead) {
                flagNearbyEntities(tp.lastX, tp.lastY, tp.lastZ, 4.0, tp.isZeroDamage);
                return true;
            }
            if (now - tp.createdAt > PROJ_TIMEOUT) return true;
            tp.lastX = proj.posX; tp.lastY = proj.posY; tp.lastZ = proj.posZ;
            return false;
        });

        // ── 钓鱼竿 caughtEntity ──
        if (bobberId != -1) {
            Entity be = mc.theWorld.getEntityByID(bobberId);
            if (be instanceof EntityFishHook) {
                EntityFishHook fh = (EntityFishHook) be;
                if (fh.caughtEntity != null && fh.caughtEntity.isEntityAlive()) {
                    Entity caught = fh.caughtEntity;
                    int cid = caught.getEntityId();
                    if (shouldTriggerHitMarker(cid)) {
                        entityHitTimestamps.put(cid, now);
                        HitMarkerRenderer.showHitMarker();
                        playRandomHitSound();
                        spawnHitParticlesAt(caught.posX, caught.posY + caught.height / 2.0,
                                caught.posZ, 20, HitMarkerMod.config.hitBloodIntensity);
                        bobberId = -1;
                    }
                }
            } else {
                bobberId = -1;
            }
        }

        // ── 定点血量轮询（只查被标记过的实体） ──
        for (java.util.Map.Entry<Integer, HealthWatch> entry : watchedHealth.entrySet()) {
            HealthWatch watch = entry.getValue();
            if (now - watch.updatedAt > WATCH_TIMEOUT) continue;

            Entity entity = mc.theWorld.getEntityByID(entry.getKey());
            if (!(entity instanceof EntityLivingBase) || entity.isDead) continue;

            EntityLivingBase living = (EntityLivingBase) entity;
            float curHealth = living.getHealth();
            float diff = watch.lastHealth - curHealth;

            // 本轮已触发过 → 只更新血量，不触发
            if (watch.hitTriggered) {
                watch.lastHealth = curHealth;
                watch.updatedAt = now;
                continue;
            }

            long timeSinceFlag = now - watch.flaggedAt;

            if (diff >= 0.5f) {
                // 甄别：只有被标记后 CONFIDENCE_WINDOW 内的掉血才认作命中
                if (timeSinceFlag <= CONFIDENCE_WINDOW) {
                    if (shouldTriggerHitMarker(entry.getKey())) {
                        entityHitTimestamps.put(entry.getKey(), now);
                        HitMarkerRenderer.showHitMarker();
                        HitMarkerRenderer.showDamageNumber(diff);
                        playRandomHitSound();
                        spawnHitParticlesAt(living.posX, living.posY + living.height / 2.0,
                                living.posZ, 25, HitMarkerMod.config.hitBloodIntensity);
                    }
                    if (curHealth <= 0.0F) {
                        HitMarkerRenderer.showKillMarker();
                        playKillSound();
                    }
                }
                // 不管认不认，标记已触发，避免重复检查
                watch.hitTriggered = true;
            }

            // 超时窗口关闭 → 标记已触发
            if (timeSinceFlag > CONFIDENCE_WINDOW) {
                watch.hitTriggered = true;
            }

            watch.lastHealth = curHealth;
            watch.updatedAt = now;
        }

        // 清理超时/死亡
        watchedHealth.entrySet().removeIf(e -> {
            if (now - e.getValue().updatedAt > WATCH_TIMEOUT) return true;
            Entity ent = mc.theWorld.getEntityByID(e.getKey());
            return ent == null || ent.isDead;
        });
    }

    // ══════════════════════════════════════════════════════════════
    //  辅助
    // ══════════════════════════════════════════════════════════════

    /** 抛射物消失点附近实体加入血量监控 */
    private void flagNearbyEntities(double x, double y, double z, double radius, boolean isZeroDamage) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;
        net.minecraft.util.AxisAlignedBB box = net.minecraft.util.AxisAlignedBB.fromBounds(
                x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        for (EntityLivingBase target : mc.theWorld.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            if (target == mc.thePlayer) continue;
            int tid = target.getEntityId();
            double dist = target.getDistance(x, y, z);

            // 无伤害抛射物（雪球/蛋）：消失时离实体 ≤ 1.5 格 → 直接当命中
            if (isZeroDamage && dist <= 1.5 && shouldTriggerHitMarker(tid)) {
                entityHitTimestamps.put(tid, System.currentTimeMillis());
                HitMarkerRenderer.showHitMarker();
                playRandomHitSound();
                spawnHitParticlesAt(target.posX,
                        target.posY + target.height / 2.0,
                        target.posZ,
                        20, HitMarkerMod.config.hitBloodIntensity);
            }

            // 加入血量监控（有伤害抛射物后续血量轮询检测）
            HealthWatch existing = watchedHealth.get(tid);
            if (existing == null) {
                watchedHealth.put(tid, new HealthWatch(target.getHealth()));
            } else {
                long now = System.currentTimeMillis();
                existing.flaggedAt = now;
                existing.updatedAt = now;
                existing.hitTriggered = false;
                existing.lastHealth = target.getHealth();
            }
        }
    }

    private boolean shouldTriggerHitMarker(int entityId) {
        Long last = entityHitTimestamps.get(entityId);
        long now = System.currentTimeMillis();
        return last == null || (now - last >= INVINCIBLE_FRAME);
    }

    /** 名字比较——单人模式服务端线程安全 */
    private boolean isClientPlayerByName(EntityPlayer player) {
        if (player == null) return false;
        EntityPlayer clientPlayer = Minecraft.getMinecraft().thePlayer;
        return clientPlayer != null && clientPlayer.getName() != null
                && clientPlayer.getName().equals(player.getName());
    }

    /** 实体比较——仅限客户端线程 */
    private boolean isClientPlayer(EntityPlayer player) {
        return player != null && player.equals(Minecraft.getMinecraft().thePlayer);
    }

    /** 从DamageSource提取玩家攻击者 */
    private EntityPlayer getPlayerAttacker(net.minecraft.util.DamageSource source) {
        if (source == null) return null;
        try {
            Entity direct = source.getEntity();
            if (direct instanceof EntityPlayer)
                return (EntityPlayer) direct;
            if (direct instanceof EntityArrow) {
                EntityArrow arrow = (EntityArrow) direct;
                if (arrow.shootingEntity instanceof EntityPlayer)
                    return (EntityPlayer) arrow.shootingEntity;
            }
            if (direct instanceof EntityThrowable) {
                EntityThrowable t = (EntityThrowable) direct;
                EntityLivingBase thrower = t.getThrower();
                if (thrower instanceof EntityPlayer)
                    return (EntityPlayer) thrower;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void triggerHitEffects(int entityId) {
        entityHitTimestamps.put(entityId, System.currentTimeMillis());
        try {
            HitMarkerRenderer.showHitMarker();
            playRandomHitSound();
            spawnHitParticlesAt(
                    Minecraft.getMinecraft().theWorld.getEntityByID(entityId).posX,
                    Minecraft.getMinecraft().theWorld.getEntityByID(entityId).posY
                        + Minecraft.getMinecraft().theWorld.getEntityByID(entityId).height / 2.0,
                    Minecraft.getMinecraft().theWorld.getEntityByID(entityId).posZ,
                    30, HitMarkerMod.config.hitBloodIntensity);
        } catch (Exception e) {
            HitMarkerMod.logger.error("Hit effects failed: {}", e.getMessage());
        }
    }

    private void playRandomHitSound() {
        try {
            if (!HitMarkerMod.config.enableHitSounds) return;
            String[] sounds = { HitMarkerMod.HIT1_SOUND, HitMarkerMod.HIT2_SOUND, HitMarkerMod.HIT3_SOUND };
            Minecraft.getMinecraft().thePlayer.playSound(
                    sounds[random.nextInt(sounds.length)],
                    HitMarkerMod.config.soundVolume, 1.0F);
        } catch (Exception ignored) {}
    }

    private void playKillSound() {
        try {
            if (!HitMarkerMod.config.enableKillSound) return;
            long now = System.currentTimeMillis();
            if (now - lastKillSoundTime < KILL_SOUND_COOLDOWN) return;
            lastKillSoundTime = now;
            Minecraft.getMinecraft().thePlayer.playSound(
                    HitMarkerMod.KILL_SOUND,
                    HitMarkerMod.config.soundVolume, 1.0F);
        } catch (Exception ignored) {}
    }

    private void spawnHitParticlesAt(double x, double y, double z, int count, double intensity) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;
        for (int i = 0; i < count; i++) {
            double ox = (mc.theWorld.rand.nextDouble() - 0.5) * 1.2;
            double oy = (mc.theWorld.rand.nextDouble() - 0.5) * 1.2;
            double oz = (mc.theWorld.rand.nextDouble() - 0.5) * 1.2;
            mc.theWorld.spawnParticle(net.minecraft.util.EnumParticleTypes.BLOCK_CRACK,
                    x + ox, y + oy, z + oz,
                    ox * 0.15 * intensity, oy * 0.15 * intensity, oz * 0.15 * intensity,
                    net.minecraft.block.Block.getStateId(
                            net.minecraft.init.Blocks.redstone_block.getDefaultState()));
        }
    }
}

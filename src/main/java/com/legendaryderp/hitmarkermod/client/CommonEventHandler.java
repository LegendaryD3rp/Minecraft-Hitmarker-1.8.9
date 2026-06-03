package com.legendaryderp.hitmarkermod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.ArrowLooseEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
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
 * 架构（三种路径，确保单/多人全覆盖）：
 *
 *   ┌─ 路径一：近战（单/多人通用，客户端线程）
 *   │  AttackEntityEvent
 *   │    → 即时标识 + 音效 + 粒子 + 血量记录
 *   │    → ClientTick 血量轮询补伤害数字 + 击杀标识
 *   │
 *   ├─ 路径二：远程抛射物（多人，客户端线程）
 *   │  EntityJoinWorldEvent(客户端) + ClientTick 抛射物追踪
 *   │    → 命中时记录血量 → 下 tick 血量轮询
 *   │
 *   └─ 路径三：远程抛射物（单人，服务端线程 → volatile → 客户端线程）
 *      LivingHurtEvent(服务端)
 *        → volatile pendingSPHit/pendingSPDamage/pendingSPKill
 *        → ClientTick 消费标记 → 标识 + 数字 + 击杀 + 音效 + 粒子
 *      +
 *      EntityJoinWorldEvent(客户端+服务端双保险) 兜底
 *
 * 击杀标识（全模式通用）：
 *   ClientTick 血量轮询 curHealth <= 0.0F → showKillMarker()
 *   原版方式，最可靠，唯一改动是修复了单人抛射物命中检测。
 */
@SideOnly(Side.CLIENT)
public class CommonEventHandler {

    private final Random random = new Random();

    // ── 命中防刷 ──
    private final ConcurrentHashMap<Integer, Long> entityHitTimestamps = new ConcurrentHashMap<>();
    private static final long INVINCIBLE_FRAME = 500;

    // ── 血量追踪 ──
    private static class HealthRecord {
        float preHealth;
        long timestampMs;
        boolean damageShown;
        boolean fromExplicitAttack;

        HealthRecord(float preHealth, boolean fromExplicitAttack) {
            this.preHealth = preHealth;
            this.timestampMs = System.currentTimeMillis();
            this.damageShown = false;
            this.fromExplicitAttack = fromExplicitAttack;
        }
    }

    private final ConcurrentHashMap<Integer, HealthRecord> trackedHealth = new ConcurrentHashMap<>();
    private static final long HEALTH_TRACK_TIMEOUT_MS = 3000;

    // ── 箭矢追踪 ──
    private int trackedArrowId = -1;
    private long lastArrowShotTime = 0;
    private double lastArrowX, lastArrowY, lastArrowZ;

    // ── 通用抛射物追踪 ──
    private static class TrackedProjectile {
        final int entityId;
        final long trackedSince;
        double lastX, lastY, lastZ;

        TrackedProjectile(Entity entity) {
            this.entityId = entity.getEntityId();
            this.trackedSince = System.currentTimeMillis();
            this.lastX = entity.posX;
            this.lastY = entity.posY;
            this.lastZ = entity.posZ;
        }
    }

    private final ConcurrentHashMap<Integer, TrackedProjectile> trackedProjectiles = new ConcurrentHashMap<>();
    private static final long PROJECTILE_TIMEOUT_MS = 8000;

    // ── 单人模式跨线程传递（volatile 标记） ──
    private volatile boolean pendingSPHit = false;
    private volatile float pendingSPDamage = 0F;
    private volatile boolean pendingSPKill = false;
    private volatile double pendingSPX = 0;
    private volatile double pendingSPY = 0;
    private volatile double pendingSPZ = 0;

    // ══════════════════════════════════════════════════════════════
    //  事件处理器
    // ══════════════════════════════════════════════════════════════

    // ────── 路径一：近战（单/多人通用） ──────

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (event.entityPlayer == null || event.target == null || !event.target.isEntityAlive()) return;
        if (!isClientPlayer(event.entityPlayer)) return;

        int targetId = event.target.getEntityId();
        if (!shouldTriggerHitMarker(targetId)) return;

        if (event.target instanceof EntityLivingBase) {
            trackedHealth.put(targetId,
                    new HealthRecord(((EntityLivingBase) event.target).getHealth(), true));
        }

        entityHitTimestamps.put(targetId, System.currentTimeMillis());
        HitMarkerRenderer.showHitMarker();
        playRandomHitSound();
        spawnHitParticles(targetId);
    }

    // ────── 弓箭射出 ──────

    @SubscribeEvent
    public void onArrowLoose(ArrowLooseEvent event) {
        if (event.entityPlayer == null) return;
        if (!isClientPlayer(event.entityPlayer)) return;
        lastArrowShotTime = System.currentTimeMillis();
        trackedArrowId = -1;
    }

    // ────── 路径二/三：抛射物进入世界 ──────

    /**
     * 侦测本玩家发射的抛射物。
     *
     * 多人：仅在客户端线程触发（event.world.isRemote == true）
     * 单人：服务端和客户端都会触发，两侧都接受（双保险）
     *       实体 ID 服务端与客户端保持一致，ConcurrentHashMap 防重。
     */
    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.entity == null) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        // 多人：只处理客户端事件
        // 单人：两侧都处理（服务端优先捕获，客户端做追踪更新）
        boolean isServerSide = !event.world.isRemote;
        if (!mc.isIntegratedServerRunning() && isServerSide) return;

        Entity e = event.entity;

        // ── 箭矢 ──
        if (e instanceof EntityArrow) {
            EntityArrow arrow = (EntityArrow) e;
            try {
                if (arrow.shootingEntity == mc.thePlayer
                        || (arrow.shootingEntity != null
                            && arrow.shootingEntity.getUniqueID().equals(mc.thePlayer.getUniqueID()))) {
                    if (trackedArrowId == -1) {
                        trackedArrowId = e.getEntityId();
                        lastArrowX = e.posX;
                        lastArrowY = e.posY;
                        lastArrowZ = e.posZ;
                    }
                }
            } catch (Exception ignored) {}
            return;
        }

        // ── 雪球/蛋/药水等抛射物 ──
        if (e instanceof EntityThrowable) {
            EntityThrowable th = (EntityThrowable) e;
            try {
                EntityLivingBase thrower = th.getThrower();
                if (thrower == mc.thePlayer
                        || (thrower != null
                            && thrower.getUniqueID().equals(mc.thePlayer.getUniqueID()))) {
                    if (!trackedProjectiles.containsKey(e.getEntityId())) {
                        trackedProjectiles.put(e.getEntityId(), new TrackedProjectile(e));
                    }
                }
            } catch (Exception ignored) {}
            return;
        }

        // ── 鱼钩 ──
        if (e instanceof EntityFishHook) {
            if (e.getDistanceToEntity(mc.thePlayer) < 3.0
                    && !trackedProjectiles.containsKey(e.getEntityId())) {
                trackedProjectiles.put(e.getEntityId(), new TrackedProjectile(e));
            }
        }
    }

    // ────── 路径三：单人抛射物命中（LivingHurtEvent） ──────

    /**
     * 受击事件（单人模式核心命中检测）。
     *
     * 服务端线程触发。不直接调渲染 API，用 volatile 标记传递给客户端 tick。
     * 覆盖：抛射物（雪球、弓箭、鱼竿、蛋、药水、末影珍珠）、近战兜底。
     */
    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        if (!mc.isIntegratedServerRunning()) return; // 仅单人模式
        if (event.entity == null || event.source == null) return;

        // 找到攻击者：先试 getSourceOfDamage()（抛射物/间接伤害）
        // 再试 getEntity()（直接近战伤害）
        Entity attacker = event.source.getSourceOfDamage();
        if (attacker == null) attacker = event.source.getEntity();
        if (attacker == null || !attacker.getUniqueID().equals(mc.thePlayer.getUniqueID())) return;

        // 自伤跳过
        if (event.entity.getUniqueID().equals(mc.thePlayer.getUniqueID())) return;

        int targetId = event.entity.getEntityId();
        if (!shouldTriggerHitMarker(targetId)) return;

        entityHitTimestamps.put(targetId, System.currentTimeMillis());

        // volatile 标记 → 下个客户端 tick 消费
        pendingSPHit = true;
        pendingSPDamage = event.ammount;
        pendingSPKill = (((EntityLivingBase) event.entity).getHealth() - event.ammount) <= 0.0F;
        pendingSPX = event.entity.posX;
        pendingSPY = event.entity.posY + event.entity.height / 2.0;
        pendingSPZ = event.entity.posZ;

        // 同时记录血量，供血量轮询补伤害数字/击杀标识（双保险）
        if (event.entity instanceof EntityLivingBase) {
            trackedHealth.put(targetId,
                    new HealthRecord(((EntityLivingBase) event.entity).getHealth(), false));
        }
    }

    // ────── 客户端 tick 核心处理 ──────

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            trackedHealth.clear();
            return;
        }

        long now = System.currentTimeMillis();

        // ────── 1. 消费单人模式 volatile 标记 ──────
        if (pendingSPHit) {
            pendingSPHit = false;
            HitMarkerRenderer.showHitMarker();
            if (pendingSPDamage > 0.5F) {
                HitMarkerRenderer.showDamageNumber(pendingSPDamage);
            }
            if (pendingSPKill) {
                HitMarkerRenderer.showKillMarker();
            }
            playRandomHitSound();
            spawnHitParticlesAt(pendingSPX, pendingSPY, pendingSPZ, 30,
                    HitMarkerMod.config.hitBloodIntensity, false);

            if (pendingSPKill && HitMarkerMod.config.enableKillSound) {
                try {
                    mc.thePlayer.playSound(HitMarkerMod.KILL_SOUND,
                            HitMarkerMod.config.soundVolume, 1.0F);
                } catch (Exception ignored) {}
            }
        }

        // ────── 2. 箭矢追踪 ──────
        if (trackedArrowId == -1 && now - lastArrowShotTime < 2000) {
            for (Entity e : mc.theWorld.loadedEntityList) {
                if (e instanceof EntityArrow && !e.isDead
                        && e.getDistanceToEntity(mc.thePlayer) < 15.0) {
                    try {
                        EntityArrow arrow = (EntityArrow) e;
                        if (arrow.shootingEntity == mc.thePlayer
                                || (arrow.shootingEntity != null
                                    && arrow.shootingEntity.getUniqueID().equals(mc.thePlayer.getUniqueID()))) {
                            trackedArrowId = e.getEntityId();
                            lastArrowX = e.posX;
                            lastArrowY = e.posY;
                            lastArrowZ = e.posZ;
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        if (trackedArrowId != -1) {
            Entity arrow = mc.theWorld.getEntityByID(trackedArrowId);
            if (arrow == null || arrow.isDead) {
                double ax = arrow != null ? arrow.posX : lastArrowX;
                double ay = arrow != null ? arrow.posY : lastArrowY;
                double az = arrow != null ? arrow.posZ : lastArrowZ;
                trackNearbyTargets(ax, ay, az, 3.0);
                trackedArrowId = -1;
            } else {
                lastArrowX = arrow.posX;
                lastArrowY = arrow.posY;
                lastArrowZ = arrow.posZ;
                if (now - lastArrowShotTime > 500
                        && Math.abs(arrow.motionX) < 0.001
                        && Math.abs(arrow.motionY) < 0.001
                        && Math.abs(arrow.motionZ) < 0.001) {
                    trackedArrowId = -1;
                }
            }
        }

        // ────── 3. 通用抛射物追踪 ──────
        trackedProjectiles.values().removeIf(tp -> {
            Entity proj = mc.theWorld.getEntityByID(tp.entityId);
            if (proj == null || proj.isDead) {
                trackNearbyTargets(tp.lastX, tp.lastY, tp.lastZ, 4.0);
                return true;
            }
            if (now - tp.trackedSince > PROJECTILE_TIMEOUT_MS) return true;
            tp.lastX = proj.posX;
            tp.lastY = proj.posY;
            tp.lastZ = proj.posZ;
            return false;
        });

        // ────── 4. 血量轮询（伤害数字 + 击杀标识兜底） ──────
        for (java.util.Map.Entry<Integer, HealthRecord> entry : trackedHealth.entrySet()) {
            HealthRecord record = entry.getValue();
            if (record.damageShown) continue;
            if (now - record.timestampMs > HEALTH_TRACK_TIMEOUT_MS) {
                record.damageShown = true;
                continue;
            }

            Entity entity = mc.theWorld.getEntityByID(entry.getKey());
            if (entity instanceof EntityLivingBase) {
                float curHealth = ((EntityLivingBase) entity).getHealth();
                float damage = record.preHealth - curHealth;
                if (damage >= 0.5f) {
                    record.damageShown = true;
                    HitMarkerRenderer.showDamageNumber(damage);
                    // AttackEntityEvent/LivingHurtEvent 已触发标识+音效+粒子
                    // 只有血量轮询的才补全套（抛射物追踪命中）
                    if (!record.fromExplicitAttack) {
                        HitMarkerRenderer.showHitMarker();
                        playRandomHitSound();
                        spawnHitParticles(entry.getKey());
                    }
                    if (curHealth <= 0.0F) {
                        HitMarkerRenderer.showKillMarker();
                    }
                }
            }
        }

        trackedHealth.entrySet().removeIf(e -> e.getValue().damageShown);
    }

    // ══════════════════════════════════════════════════════════════
    //  辅助方法
    // ══════════════════════════════════════════════════════════════

    /** 在指定位置附近搜索实体并记录血量 */
    private void trackNearbyTargets(double x, double y, double z, double radius) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;
        net.minecraft.util.AxisAlignedBB box = net.minecraft.util.AxisAlignedBB.fromBounds(
                x - radius, y - radius, z - radius,
                x + radius, y + radius, z + radius);
        for (EntityLivingBase target : mc.theWorld.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            if (target != mc.thePlayer && !trackedHealth.containsKey(target.getEntityId())) {
                trackedHealth.put(target.getEntityId(),
                        new HealthRecord(target.getHealth(), false));
            }
        }
    }

    /** 防刷 */
    private boolean shouldTriggerHitMarker(int entityId) {
        Long lastHit = entityHitTimestamps.get(entityId);
        long now = System.currentTimeMillis();
        return (lastHit == null) || (now - lastHit >= INVINCIBLE_FRAME);
    }

    /** 判断是否为当前客户端玩家 */
    private boolean isClientPlayer(EntityPlayer player) {
        return player != null && player == Minecraft.getMinecraft().thePlayer;
    }

    /** 播放随机命中音效 */
    private void playRandomHitSound() {
        try {
            if (!HitMarkerMod.config.enableHitSounds) return;
            String[] sounds = { HitMarkerMod.HIT1_SOUND, HitMarkerMod.HIT2_SOUND, HitMarkerMod.HIT3_SOUND };
            String sound = sounds[random.nextInt(sounds.length)];
            Minecraft.getMinecraft().thePlayer.playSound(sound,
                    HitMarkerMod.config.soundVolume, 1.0F);
        } catch (Exception ignored) {}
    }

    /** 在指定坐标生成粒子 */
    private void spawnHitParticlesAt(double x, double y, double z, int count, double intensity, boolean unused) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;

        for (int i = 0; i < count; i++) {
            double ox = (mc.theWorld.rand.nextDouble() - 0.5) * 1.2;
            double oy = (mc.theWorld.rand.nextDouble() - 0.5) * 1.2;
            double oz = (mc.theWorld.rand.nextDouble() - 0.5) * 1.2;
            mc.theWorld.spawnParticle(
                    net.minecraft.util.EnumParticleTypes.BLOCK_CRACK,
                    x + ox, y + oy, z + oz,
                    ox * 0.15 * intensity,
                    oy * 0.15 * intensity,
                    oz * 0.15 * intensity,
                    net.minecraft.block.Block.getStateId(
                            net.minecraft.init.Blocks.redstone_block.getDefaultState()));
        }
    }

    /** 在目标位置生成命中粒子 */
    private void spawnHitParticles(int entityId) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || !HitMarkerMod.config.enableHitBlood) return;
        Entity target = mc.theWorld.getEntityByID(entityId);
        if (!(target instanceof EntityLivingBase)) return;
        EntityLivingBase living = (EntityLivingBase) target;
        spawnHitParticlesAt(living.posX, living.posY + living.height / 2.0, living.posZ,
                30, HitMarkerMod.config.hitBloodIntensity, false);
    }
}

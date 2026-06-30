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
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import com.legendaryderp.hitmarkermod.HitMarkerMod;
import com.legendaryderp.hitmarkermod.client.HitMarkerRenderer;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多人模式兼容的事件处理器。
 *
 * 设计原则：不依赖 LivingHurtEvent / LivingDeathEvent（服务端事件，客户端不触发）。
 * 所有命中检测通过实体追踪 + 血量轮询实现。
 *
 * 近战：    AttackEntityEvent(标识+音效+粒子) → ClientTickEvent血量轮询(伤害数字)
 * 弓箭/雪球/蛋/药水/末影珍珠：
 *            EntityJoinWorldEvent 侦测抛射物 → ClientTickEvent追踪命中 →
 *            命中时记录附近实体血量 → 下一 tick 血量轮询(标识+音效+粒子+数字)
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
        boolean fromExplicitAttack; // true=近战(AttackEntityEvent), false=远程(抛射物追踪)

        HealthRecord(float preHealth, boolean fromExplicitAttack) {
            this.preHealth = preHealth;
            this.timestampMs = System.currentTimeMillis();
            this.damageShown = false;
            this.fromExplicitAttack = fromExplicitAttack;
        }
    }

    private final ConcurrentHashMap<Integer, HealthRecord> trackedHealth = new ConcurrentHashMap<>();
    private static final long HEALTH_TRACK_TIMEOUT_MS = 3000;

    // ── 箭矢追踪（保留原有方式 + EntityJoinWorldEvent 增强） ──
    private int trackedArrowId = -1;
    private long lastArrowShotTime = 0;
    private double lastArrowX, lastArrowY, lastArrowZ;

    // ── 通用抛射物追踪（雪球、蛋、药水、末影珍珠、鱼钩等） ──
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

    // ══════════════════════════════════════════════════════════════
    //  事件处理器
    // ══════════════════════════════════════════════════════════════

    /**
     * 近战攻击（多人可用）。
     * 触发命中标识 + 音效 + 粒子 + 血量追踪(显式，仅产生伤害数字)。
     */
    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (event.entityPlayer == null || event.target == null || !event.target.isEntityAlive()) return;
        if (!isClientPlayer(event.entityPlayer)) return;

        int targetId = event.target.getEntityId();
        if (!shouldTriggerHitMarker(targetId)) return;

        // 记录攻击前血量
        if (event.target instanceof EntityLivingBase) {
            trackedHealth.put(targetId,
                    new HealthRecord(((EntityLivingBase) event.target).getHealth(), true));
        }

        entityHitTimestamps.put(targetId, System.currentTimeMillis());
        HitMarkerRenderer.showHitMarker();
        playRandomHitSound();
        spawnHitParticles(targetId);
    }

    /**
     * 弓箭射出（多人可用）。
     * 仅标记需要追踪，实际箭矢寻找交给 EntityJoinWorldEvent + ClientTickEvent。
     */
    @SubscribeEvent
    public void onArrowLoose(ArrowLooseEvent event) {
        if (event.entityPlayer == null) return;
        if (!isClientPlayer(event.entityPlayer)) return;
        lastArrowShotTime = System.currentTimeMillis();
        trackedArrowId = -1;
    }

    /**
     * 实体进入世界（多人可用）。
     * 侦测本玩家发射的抛射物：箭矢、雪球、蛋、药水、末影珍珠、鱼钩。
     */
    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.entity == null || !event.world.isRemote) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        Entity e = event.entity;

        // ── 箭矢 ──
        if (e instanceof EntityArrow) {
            EntityArrow arrow = (EntityArrow) e;
            try {
                if (arrow.shootingEntity == mc.thePlayer && trackedArrowId == -1) {
                    trackedArrowId = e.getEntityId();
                    lastArrowX = e.posX;
                    lastArrowY = e.posY;
                    lastArrowZ = e.posZ;
                }
            } catch (Exception ignored) {}
            return;
        }

        // ── 抛射物（雪球、蛋、药水、末影珍珠等） ──
        if (e instanceof EntityThrowable) {
            EntityThrowable th = (EntityThrowable) e;
            try {
                EntityLivingBase thrower = th.getThrower();
                if (thrower == mc.thePlayer && !trackedProjectiles.containsKey(e.getEntityId())) {
                    trackedProjectiles.put(e.getEntityId(), new TrackedProjectile(e));
                }
            } catch (Exception ignored) {}
            return;
        }

        // ── 鱼钩（EntityFishHook 不继承 EntityThrowable） ──
        if (e instanceof EntityFishHook) {
            // 1.8.9 多人中鱼钩的 angler 字段不通过 NBT 同步，
            // 用距离判断：投出瞬间鱼钩在玩家附近
            if (e.getDistanceToEntity(mc.thePlayer) < 3.0
                    && !trackedProjectiles.containsKey(e.getEntityId())) {
                trackedProjectiles.put(e.getEntityId(), new TrackedProjectile(e));
            }
        }
    }

    /**
     * 每 tick 处理：箭矢追踪 → 抛射物追踪 → 血量轮询。
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            trackedHealth.clear();
            return;
        }

        long now = System.currentTimeMillis();

        // ────── 1. 箭矢追踪（扫描 + EntityJoinWorldEvent 双保险） ──────
        if (trackedArrowId == -1 && now - lastArrowShotTime < 2000) {
            for (Entity e : mc.theWorld.loadedEntityList) {
                if (e instanceof EntityArrow && !e.isDead
                        && e.getDistanceToEntity(mc.thePlayer) < 15.0) {
                    EntityArrow arrow = (EntityArrow) e;
                    try {
                        if (arrow.shootingEntity == mc.thePlayer) {
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

            if (arrow == null) {
                trackNearbyTargets(lastArrowX, lastArrowY, lastArrowZ, 3.0);
                trackedArrowId = -1;
            } else if (arrow.isDead) {
                trackNearbyTargets(arrow.posX, arrow.posY, arrow.posZ, 3.0);
                trackedArrowId = -1;
            } else {
                lastArrowX = arrow.posX;
                lastArrowY = arrow.posY;
                lastArrowZ = arrow.posZ;
                // 扎地判定：飞行 500ms 后 motion 归零
                if (now - lastArrowShotTime > 500
                        && Math.abs(arrow.motionX) < 0.001
                        && Math.abs(arrow.motionY) < 0.001
                        && Math.abs(arrow.motionZ) < 0.001) {
                    trackedArrowId = -1;
                }
            }
        }

        // ────── 2. 通用抛射物追踪 ──────
        trackedProjectiles.values().removeIf(tp -> {
            Entity proj = mc.theWorld.getEntityByID(tp.entityId);

            if (proj == null || proj.isDead) {
                // 抛射物消失 → 落点附近实体血量追踪（药水范围 4 格）
                trackNearbyTargets(tp.lastX, tp.lastY, tp.lastZ, 4.0);
                return true;
            }
            // 超时清理（防止野指针累积）
            if (now - tp.trackedSince > PROJECTILE_TIMEOUT_MS) {
                return true;
            }
            // 更新位置
            tp.lastX = proj.posX;
            tp.lastY = proj.posY;
            tp.lastZ = proj.posZ;
            return false;
        });

        // ────── 3. 血量轮询 ──────
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
                    if (record.fromExplicitAttack) {
                        // 近战：标识/音效/粒子已在 AttackEntityEvent 触发，此处只补伤害数字
                        HitMarkerRenderer.showDamageNumber(damage);
                    } else {
                        // 远程：首次触发完整命中反馈
                        HitMarkerRenderer.showHitMarker();
                        HitMarkerRenderer.showDamageNumber(damage);
                        playRandomHitSound();
                        spawnHitParticles(entry.getKey());
                    }
                }
            }
        }

        // 清理已处理或超时的记录
        trackedHealth.entrySet().removeIf(e -> e.getValue().damageShown);
    }

    // ══════════════════════════════════════════════════════════════
    //  辅助方法
    // ══════════════════════════════════════════════════════════════

    /** 在指定位置附近记录实体当前血量（用于远程抛射物命中后的检测） */
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

    /** 防刷：同一实体短时间内不重复触发 */
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
        if (!HitMarkerMod.config.enableHitSounds) return;
        try {
            String[] sounds = { HitMarkerMod.HIT1_SOUND, HitMarkerMod.HIT2_SOUND, HitMarkerMod.HIT3_SOUND };
            String sound = sounds[random.nextInt(sounds.length)];
            float vol = HitMarkerMod.config.soundVolume;
            Minecraft.getMinecraft().thePlayer.playSound(sound, vol, 1.0F);
        } catch (Exception ignored) {}
    }

    /** 在目标位置生成命中粒子 */
    private void spawnHitParticles(int entityId) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || !HitMarkerMod.config.enableHitBlood) return;
        Entity target = mc.theWorld.getEntityByID(entityId);
        if (!(target instanceof EntityLivingBase)) return;

        EntityLivingBase living = (EntityLivingBase) target;
        double x = living.posX;
        double y = living.posY + living.height / 2.0;
        double z = living.posZ;

        double velScale = HitMarkerMod.config.hitBloodIntensity;
        for (int i = 0; i < 30; i++) {
            double ox = (mc.theWorld.rand.nextDouble() - 0.5) * 1.2;
            double oy = (mc.theWorld.rand.nextDouble() - 0.5) * 1.2;
            double oz = (mc.theWorld.rand.nextDouble() - 0.5) * 1.2;
            mc.theWorld.spawnParticle(
                    net.minecraft.util.EnumParticleTypes.BLOCK_CRACK,
                    x + ox, y + oy, z + oz,
                    ox * 0.15 * velScale,
                    oy * 0.15 * velScale,
                    oz * 0.15 * velScale,
                    net.minecraft.block.Block.getStateId(
                            net.minecraft.init.Blocks.redstone_block.getDefaultState()));
        }
    }

    /** 触发击杀效果 */
    private void triggerKillEffects() {
        if (HitMarkerMod.config.enableKillSound) {
            try {
                Minecraft.getMinecraft().thePlayer.playSound(HitMarkerMod.KILL_SOUND,
                        HitMarkerMod.config.soundVolume, 1.0F);
            } catch (Exception ignored) {}
        }
        if (HitMarkerMod.config.enableKillBlood) {
            Minecraft mc = Minecraft.getMinecraft();
            Entity target = mc.getRenderViewEntity();
            if (target != null) {
                spawnBlockBreakParticlesAt(mc.theWorld,
                        target.posX, target.posY + target.height / 2.0, target.posZ,
                        net.minecraft.init.Blocks.redstone_block);
            }
        }
        HitMarkerRenderer.showKillMarker();
    }

    /** 击杀粒子（全屏红石爆破特效） */
    private void spawnBlockBreakParticlesAt(net.minecraft.world.World world,
                                             double x, double y, double z,
                                             net.minecraft.block.Block block) {
        double velScale = HitMarkerMod.config.killBloodIntensity;
        for (int i = 0; i < 60; i++) {
            double ox = (world.rand.nextDouble() - 0.5) * 1.5;
            double oy = (world.rand.nextDouble() - 0.5) * 1.5;
            double oz = (world.rand.nextDouble() - 0.5) * 1.5;
            world.spawnParticle(
                    net.minecraft.util.EnumParticleTypes.BLOCK_CRACK,
                    x + ox, y + oy, z + oz,
                    ox * 0.2 * velScale,
                    oy * 0.2 * velScale,
                    oz * 0.2 * velScale,
                    net.minecraft.block.Block.getStateId(block.getDefaultState()));
        }
    }
}

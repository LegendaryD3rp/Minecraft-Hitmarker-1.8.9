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
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EntityDamageSourceIndirect;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import com.legendaryderp.hitmarkermod.HitMarkerMod;
import com.legendaryderp.hitmarkermod.client.HitMarkerRenderer;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 命中检测事件处理器。
 *
 * ──────────────────────────────────────────────────────────
 *  体系架构
 * ──────────────────────────────────────────────────────────
 *
 *  单人模式（IntegratedServer）
 *   ┌─────────────────────────────────────┐
 *   │ 服务端线程                           │
 *   │  LivingHurtEvent → 入队 hitQueue    │
 *   │  LivingDeathEvent → 直接击杀效果    │
 *   │  EntityJoinWorldEvent → 入队        │
 *   └──────────┬──────────────────────────┘
 *              │ ConcurrentLinkedQueue
 *              ▼
 *   ┌─────────────────────────────────────┐
 *   │ 客户端 tick（onClientTick）          │
 *   │  处理 hitQueue → 渲染 + 血量记录    │
 *   │  处理抛弃射物追踪 → 命中检测        │
 *   │  血量轮询 → 伤害数字 + 击杀标识     │
 *   └─────────────────────────────────────┘
 *
 *  多人模式（纯客户端）
 *   ┌─────────────────────────────────────┐
 *   │ EntityJoinWorldEvent(客户端)         │
 *   │ ClientTickEvent 扫描                │
 *   │ KillChatListener 击杀解析           │
 *   └─────────────────────────────────────┘
 *
 * ──────────────────────────────────────────────────────────
 *  三条核心检测路径
 * ──────────────────────────────────────────────────────────
 *
 *  [路径一] 近战（单/多人通用）
 *     AttackEntityEvent → 即时标识+音效 → 血量轮询补数字
 *
 *  [路径二] 远程抛射物（单人）
 *     LivingHurtEvent(服务端) → hitQueue → onClientTick
 *     + EntityJoinWorldEvent(服务端, 兜底)
 *
 *  [路径三] 远程抛射物（多人）
 *     EntityJoinWorldEvent(客户端) + 扫描 → 命中检测 → 血量轮询
 *
 *  [击杀]
 *     单人：LivingDeathEvent(服务端) 即时触发
 *     多人：KillChatListener + 血量轮询
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

    // ── 单人模式跨线程传递队列 ──
    /** 服务端线程入队，客户端 tick 出队处理。线程安全。 */
    private final ConcurrentLinkedQueue<HitEntry> pendingSingleplayerHits = new ConcurrentLinkedQueue<>();

    private static class HitEntry {
        final int entityId;
        final float damage;
        final double x, y, z;            // 受击者坐标（粒子用）
        final boolean isKill;              // 是否击杀
        HitEntry(int entityId, float damage, double x, double y, double z, boolean isKill) {
            this.entityId = entityId;
            this.damage = damage;
            this.x = x;
            this.y = y;
            this.z = z;
            this.isKill = isKill;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  事件处理器
    // ══════════════════════════════════════════════════════════════

    // ──── 路径一：近战（单/多人通用） ────

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

    // ──── 弓箭射出（辅助标记） ────

    @SubscribeEvent
    public void onArrowLoose(ArrowLooseEvent event) {
        if (event.entityPlayer == null) return;
        if (!isClientPlayer(event.entityPlayer)) return;
        lastArrowShotTime = System.currentTimeMillis();
        trackedArrowId = -1;
    }

    // ──── 路径二：单人抛射物 + 路径三：多人抛射物 ────

    /**
     * 实体进入世界。
     *
     * 单人模式：服务端线程。用于抛射物飞行轨迹追踪，
     *         配合 LivingHurtEvent 作为双重命中检测兜底。
     * 多人模式：客户端线程。作为抛射物追踪的主要入口。
     */
    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.entity == null) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        // 确定模式及处理哪一侧的事件
        boolean isSingleplayer = mc.isIntegratedServerRunning();
        boolean isServerSide = !event.world.isRemote;

        // 单人：只处理服务端事件；多人：只处理客户端事件
        if (isSingleplayer) {
            if (!isServerSide) return;
        } else {
            if (isServerSide) return;
        }

        Entity e = event.entity;

        // ── 箭矢 ──
        if (e instanceof EntityArrow) {
            EntityArrow arrow = (EntityArrow) e;
            try {
                if (isMyArrow(arrow, mc) && trackedArrowId == -1) {
                    trackedArrowId = e.getEntityId();
                }
            } catch (Exception ignored) {}
            return;
        }

        // ── 雪球/蛋等抛射物 ──
        if (e instanceof EntityThrowable) {
            EntityThrowable th = (EntityThrowable) e;
            try {
                if (isMyThrowable(th, mc) && !trackedProjectiles.containsKey(e.getEntityId())) {
                    trackedProjectiles.put(e.getEntityId(), new TrackedProjectile(e));
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

    // ──── 单人模式：抛射物命中（LivingHurtEvent） ────

    /**
     * 受击事件（单人模式核心命中检测）。
     *
     * 服务端线程触发。不直接调渲染 API，改为入队
     * pendingSingleplayerHits 由 onClientTick 处理。
     *
     * 覆盖范围（单人）：
     *   - 抛射物（雪球、弓箭、鱼竿、蛋、药水、末影珍珠等）
     *   - 近战（作为 AttackEntityEvent 兜底）
     *   - 爆炸、摔落、火焰等各类伤害源
     */
    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        if (!mc.isIntegratedServerRunning()) return; // 仅单人模式
        if (event.entity == null || event.source == null) return;

        // 不是本玩家攻击的 → 跳过
        if (event.source.getSourceOfDamage() == null) return;
        if (!event.source.getSourceOfDamage().getUniqueID().equals(mc.thePlayer.getUniqueID())) return;

        // 自伤 → 跳过
        if (event.entity.getUniqueID().equals(mc.thePlayer.getUniqueID())) return;

        int targetId = event.entity.getEntityId();
        if (!shouldTriggerHitMarker(targetId)) return;

        entityHitTimestamps.put(targetId, System.currentTimeMillis());

        // 判断是否为击杀（受击后血量 ≤ 0）
        float hpAfterHit = ((EntityLivingBase) event.entity).getHealth() - event.ammount;
        boolean isKill = hpAfterHit <= 0.0F;

        // 入队 → onClientTick 处理渲染
        pendingSingleplayerHits.add(new HitEntry(
                targetId,
                event.ammount,
                event.entity.posX,
                event.entity.posY + event.entity.height / 2.0,
                event.entity.posZ,
                isKill
        ));

        // 记录血量用于后续伤害数字
        if (event.entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) event.entity;
            trackedHealth.put(targetId,
                    new HealthRecord(living.getHealth(), false));
        }
    }

    // ──── 单人模式：击杀检测 ────

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        if (!mc.isIntegratedServerRunning()) return; // 仅单人模式
        if (event.source == null) return;
        if (event.source.getSourceOfDamage() == null) return;
        if (!event.source.getSourceOfDamage().getUniqueID().equals(mc.thePlayer.getUniqueID())) return;

        // 入队击杀（onClientTick 处理渲染）
        pendingSingleplayerHits.add(new HitEntry(
                event.entity.getEntityId(),
                0F,
                event.entity.posX,
                event.entity.posY + event.entity.height / 2.0,
                event.entity.posZ,
                true
        ));

        // 清除血量记录（已死无需轮询）
        trackedHealth.remove(event.entity.getEntityId());
    }

    // ──── 客户 tick：核心处理 ────

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            trackedHealth.clear();
            return;
        }

        long now = System.currentTimeMillis();

        // ────── 1. 处理单人模式待处理命中队列 ──────
        HitEntry he;
        while ((he = pendingSingleplayerHits.poll()) != null) {
            HitMarkerRenderer.showHitMarker();
            if (he.damage > 0.5F) {
                HitMarkerRenderer.showDamageNumber(he.damage);
            }
            if (he.isKill) {
                HitMarkerRenderer.showKillMarker();
            }
            playRandomHitSound();
            spawnHitParticlesAt(he.x, he.y, he.z,
                    he.isKill ? 60 : 30,
                    he.isKill ? HitMarkerMod.config.killBloodIntensity
                              : HitMarkerMod.config.hitBloodIntensity,
                    he.isKill);

            if (he.isKill && HitMarkerMod.config.enableKillSound) {
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
                        if (isMyArrow((EntityArrow) e, mc)) {
                            trackedArrowId = e.getEntityId();
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        if (trackedArrowId != -1) {
            Entity arrow = mc.theWorld.getEntityByID(trackedArrowId);

            if (arrow == null || arrow.isDead) {
                double aX = arrow != null ? arrow.posX : 0;
                double aY = arrow != null ? arrow.posY : 0;
                double aZ = arrow != null ? arrow.posZ : 0;
                trackNearbyTargets(aX, aY, aZ, 3.0);
                trackedArrowId = -1;
            } else {
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
            if (now - tp.trackedSince > PROJECTILE_TIMEOUT_MS) {
                return true;
            }
            tp.lastX = proj.posX;
            tp.lastY = proj.posY;
            tp.lastZ = proj.posZ;
            return false;
        });

        // ────── 4. 血量轮询 ──────
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
                    // AttackEntityEvent 已显示了标识和音效，这里补伤害数字
                    // 其余来源（抛射物追踪命中）补所有效果
                    // LivingHurtEvent 已在队列处理完了，这里补血量轮询捕获的
                    if (!record.fromExplicitAttack) {
                        HitMarkerRenderer.showDamageNumber(damage);
                    } else {
                        HitMarkerRenderer.showDamageNumber(damage);
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

    /** 判断箭矢是否为本玩家射出 */
    private boolean isMyArrow(EntityArrow arrow, Minecraft mc) {
        if (arrow.shootingEntity == mc.thePlayer) return true;
        if (arrow.shootingEntity != null && mc.thePlayer != null) {
            return arrow.shootingEntity.getUniqueID().equals(mc.thePlayer.getUniqueID());
        }
        return false;
    }

    /** 判断抛射物是否为本玩家投出 */
    private boolean isMyThrowable(EntityThrowable th, Minecraft mc) {
        EntityLivingBase thrower = th.getThrower();
        if (thrower == mc.thePlayer) return true;
        if (thrower != null && mc.thePlayer != null) {
            return thrower.getUniqueID().equals(mc.thePlayer.getUniqueID());
        }
        return false;
    }

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
    private void spawnHitParticlesAt(double x, double y, double z, int count, double intensity, boolean red) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;
        // 不检查 enableHitBlood/killBlood，此处由调用者控制

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
                            (red ? net.minecraft.init.Blocks.redstone_block
                                 : net.minecraft.init.Blocks.redstone_block)
                                    .getDefaultState()));
        }
    }

    /** 在目标位置生成命中粒子（按实体 ID 查坐标） */
    private void spawnHitParticles(int entityId) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || !HitMarkerMod.config.enableHitBlood) return;
        Entity target = mc.theWorld.getEntityByID(entityId);
        if (!(target instanceof EntityLivingBase)) return;

        EntityLivingBase living = (EntityLivingBase) target;
        spawnHitParticlesAt(
                living.posX,
                living.posY + living.height / 2.0,
                living.posZ,
                30,
                HitMarkerMod.config.hitBloodIntensity,
                false);
    }
}

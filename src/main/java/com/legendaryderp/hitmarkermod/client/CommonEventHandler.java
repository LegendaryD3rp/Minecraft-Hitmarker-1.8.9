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
 * 分三路检测（由简到繁）：
 *
 * ── 近战（单/多人通用） ──
 *   AttackEntityEvent（客户端即时触发）
 *   → 命中标识 + 音效 + 粒子 + 血量记录
 *   → 下一 tick 血量轮询补伤害数字 + 击杀标识
 *
 * ── 远程抛射物（多人） ──
 *   EntityJoinWorldEvent（客户端）侦测本玩家投出的抛射物
 *   + ClientTickEvent 扫描（双保险）
 *   → ClientTickEvent 追踪命中（抛射物消失/扎地）
 *   → 命中的落点附近记录目标血量
 *   → 下一 tick 血量轮询：标识 + 音效 + 粒子 + 数字 + 击杀
 *
 * ── 远程抛射物（单人） ──
 *   EntityJoinWorldEvent（服务端线程）侦测本玩家投出的抛射物
 *   注意：单人模式下服务端实体（EntityPlayerMP）与客户端实体
 *   （EntityPlayerSP）非同一实例，必须用 UUID 比较
 *   → 后续流程同多人（ClientTickEvent 追踪 + 血量轮询）
 *
 * ── 击杀标识 ──
 *   单人：LivingDeathEvent（服务端线程）+ 血量轮询
 *   多人：KillChatListener（解析聊天消息）+ 血量轮询
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

    // ══════════════════════════════════════════════════════════════
    //  事件处理器
    // ══════════════════════════════════════════════════════════════

    /**
     * 近战攻击（单/多人通用）。
     */
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

    /**
     * 弓箭射出。
     */
    @SubscribeEvent
    public void onArrowLoose(ArrowLooseEvent event) {
        if (event.entityPlayer == null) return;
        if (!isClientPlayer(event.entityPlayer)) return;
        lastArrowShotTime = System.currentTimeMillis();
        trackedArrowId = -1;
    }

    /**
     * 实体进入世界。
     *
     * 侦测本玩家发射的抛射物。
     *
     * 单人模式下此事件仅在服务端线程触发（event.world.isRemote == false），
     * 必须用 UUID 比较玩家身份。
     */
    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.entity == null) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        // ── 判断应处理服务端还是客户端的事件 ──
        // 多人：只处理客户端事件（event.world.isRemote == true）
        // 单人：只处理服务端事件（event.world.isRemote == false，客户端不会触发此事件）
        boolean isSingleplayer = mc.isIntegratedServerRunning();
        boolean isServerSide = !event.world.isRemote;
        if (isSingleplayer) {
            if (!isServerSide) return; // 单人模式只处理服务端事件
        } else {
            if (isServerSide) return;  // 多人模式只处理客户端事件
        }

        Entity e = event.entity;

        // ── 箭矢 ──
        if (e instanceof EntityArrow) {
            EntityArrow arrow = (EntityArrow) e;
            try {
                if (isMyArrow(arrow, mc) && trackedArrowId == -1) {
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

    /**
     * 死亡事件（单人模式击杀检测）。
     * 服务端线程触发，用于击杀标识 + 击杀音效 + 击杀粒子。
     */
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        if (!mc.isIntegratedServerRunning()) return; // 仅单人模式
        if (event.source == null) return;

        Entity killer = event.source.getSourceOfDamage();
        if (killer == null) return;
        if (!killer.getUniqueID().equals(mc.thePlayer.getUniqueID())) return;

        triggerKillEffects();

        // 清除该实体的血量记录（已死，无需再轮询）
        trackedHealth.remove(event.entity.getEntityId());
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
                    try {
                        if (isMyArrow((EntityArrow) e, mc)) {
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
                        HitMarkerRenderer.showDamageNumber(damage);
                    } else {
                        HitMarkerRenderer.showHitMarker();
                        HitMarkerRenderer.showDamageNumber(damage);
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

    /** 判断箭矢是否为本玩家射出（兼容单/多人，引用 + UUID 双保险） */
    private boolean isMyArrow(EntityArrow arrow, Minecraft mc) {
        if (arrow.shootingEntity == mc.thePlayer) return true;
        if (arrow.shootingEntity != null && mc.thePlayer != null) {
            return arrow.shootingEntity.getUniqueID().equals(mc.thePlayer.getUniqueID());
        }
        return false;
    }

    /** 判断抛射物是否为本玩家投出（兼容单/多人） */
    private boolean isMyThrowable(EntityThrowable th, Minecraft mc) {
        EntityLivingBase thrower = th.getThrower();
        if (thrower == mc.thePlayer) return true;
        if (thrower != null && mc.thePlayer != null) {
            return thrower.getUniqueID().equals(mc.thePlayer.getUniqueID());
        }
        // 鱼钩等 thrower 可能为 null，用距离判断（已在调用处处理）
        return false;
    }

    /** 在指定位置附近记录实体当前血量 */
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

    /** 触发击杀效果（标识 + 音效 + 粒子） */
    private void triggerKillEffects() {
        HitMarkerRenderer.showKillMarker();

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
    }

    /** 击杀粒子 */
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

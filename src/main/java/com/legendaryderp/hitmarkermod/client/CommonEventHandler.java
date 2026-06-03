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
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 命中检测事件处理器。
 *
 * 架构概览：
 *
 *   [近战] AttackEntityEvent(客户端) → 即时标识+音效+粒子 → 血量轮询补数字+击杀
 *   [单人抛射物] LivingHurtEvent(服务端) → ConcurrentLinkedQueue → ClientTick 出队渲染
 *   [多人抛射物] EntityJoinWorldEvent(客户端) → 追踪轨迹 → trackNearbyTargets → 血量轮询
 *   [单人+多人兜底] ClientTick 血量轮询 → 伤害数字 + 击杀标识 + 击杀音效
 *   [钓鱼竿] ClientTick 扫描 EntityFishHook.caughtEntity
 *   [击杀] 单人: LivingDeathEvent → 入队 + 血量轮询兜底
 *           多人: KillChatListener(聊天解析) + 血量轮询
 *
 * 线程安全：服务端事件(单人)仅入 ConcurrentLinkedQueue，所有渲染均在客户端线程执行。
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

    // ── 钓鱼竿追踪 ──
    private int trackedBobberId = -1;

    // ── 单人模式跨线程传递队列 ──
    private static class HitEntry {
        final int entityId;
        final float damage;
        final double x, y, z;
        final boolean isKill;
        HitEntry(int id, float dmg, double px, double py, double pz, boolean kill) {
            entityId = id; damage = dmg; x = px; y = py; z = pz; isKill = kill;
        }
    }

    private final ConcurrentLinkedQueue<HitEntry> pendingSingleplayerHits = new ConcurrentLinkedQueue<>();

    // ══════════════════════════════════════════════════════════════
    //  事件处理器
    // ══════════════════════════════════════════════════════════════

    // ──── 近战（单/多人通用） ────

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

    // ──── 弓箭射出 ────

    @SubscribeEvent
    public void onArrowLoose(ArrowLooseEvent event) {
        if (event.entityPlayer == null) return;
        if (!isClientPlayer(event.entityPlayer)) return;
        lastArrowShotTime = System.currentTimeMillis();
        trackedArrowId = -1;
    }

    // ──── 抛射物进入世界（多人追踪 + 单人兜底） ────

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.entity == null) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        // 多人只收客户端事件，单人两侧都收
        boolean isServerSide = !event.world.isRemote;
        if (!mc.isIntegratedServerRunning() && isServerSide) return;

        Entity e = event.entity;

        // 箭矢
        if (e instanceof EntityArrow) {
            EntityArrow arrow = (EntityArrow) e;
            try {
                if (arrow.shootingEntity == mc.thePlayer || (arrow.shootingEntity != null
                        && arrow.shootingEntity.getUniqueID().equals(mc.thePlayer.getUniqueID()))) {
                    if (trackedArrowId == -1) {
                        trackedArrowId = e.getEntityId();
                        lastArrowX = e.posX; lastArrowY = e.posY; lastArrowZ = e.posZ;
                    }
                }
            } catch (Exception ignored) {}
            return;
        }

        // 雪球/蛋/药水等
        if (e instanceof EntityThrowable) {
            EntityThrowable th = (EntityThrowable) e;
            try {
                EntityLivingBase thrower = th.getThrower();
                if (thrower == mc.thePlayer || (thrower != null
                        && thrower.getUniqueID().equals(mc.thePlayer.getUniqueID()))) {
                    if (!trackedProjectiles.containsKey(e.getEntityId())) {
                        trackedProjectiles.put(e.getEntityId(), new TrackedProjectile(e));
                    }
                }
            } catch (Exception ignored) {}
            return;
        }

        // 鱼钩
        if (e instanceof EntityFishHook) {
            EntityFishHook bobber = (EntityFishHook) e;
            if (bobber.angler == mc.thePlayer) {
                trackedBobberId = e.getEntityId();
                if (!trackedProjectiles.containsKey(e.getEntityId())) {
                    trackedProjectiles.put(e.getEntityId(), new TrackedProjectile(e));
                }
            }
        }
    }

    // ──── 单人抛射物命中（LivingHurtEvent） ────

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        if (!mc.isIntegratedServerRunning()) return;
        if (event.entity == null || event.source == null) return;

        // 找攻击者：EntityDamageSourceIndirect.getSourceOfDamage() 返回 indirectEntity（抛射物的投掷者）
        // EntityDamageSource.getEntity() 返回 damageSourceEntity（直接攻击者）
        Entity attacker = event.source.getSourceOfDamage();
        if (attacker == null) attacker = event.source.getEntity();
        if (attacker == null || !attacker.getUniqueID().equals(mc.thePlayer.getUniqueID())) return;

        // 自伤跳过
        if (event.entity.getUniqueID().equals(mc.thePlayer.getUniqueID())) return;

        int targetId = event.entity.getEntityId();
        if (!shouldTriggerHitMarker(targetId)) return;

        entityHitTimestamps.put(targetId, System.currentTimeMillis());

        float damage = event.ammount;
        float hpAfter = ((EntityLivingBase) event.entity).getHealth() - damage;
        boolean isKill = hpAfter <= 0.0F;

        // 入队 → onClientTick 出队渲染（线程安全）
        pendingSingleplayerHits.add(new HitEntry(
                targetId, damage,
                event.entity.posX,
                event.entity.posY + event.entity.height / 2.0,
                event.entity.posZ,
                isKill));

        // 记录血量用于血量轮询兜底
        if (event.entity instanceof EntityLivingBase) {
            trackedHealth.put(targetId,
                    new HealthRecord(((EntityLivingBase) event.entity).getHealth(), false));
        }
    }

    // ──── 单人击杀（LivingDeathEvent） ────

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        if (!mc.isIntegratedServerRunning()) return;
        if (event.entity == null || event.source == null) return;

        // 找攻击者（同 LivingHurtEvent 逻辑）
        Entity attacker = event.source.getSourceOfDamage();
        if (attacker == null) attacker = event.source.getEntity();
        if (attacker == null || !attacker.getUniqueID().equals(mc.thePlayer.getUniqueID())) return;

        // 入队击杀（血量轮询也会检测到，但增加了即时性）
        pendingSingleplayerHits.add(new HitEntry(
                event.entity.getEntityId(), 0F,
                event.entity.posX,
                event.entity.posY + event.entity.height / 2.0,
                event.entity.posZ,
                true));
    }

    // ──── 客户端 tick 核心处理 ────

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) { trackedHealth.clear(); return; }

        long now = System.currentTimeMillis();

        // ────── 1. 处理单人模式命中队列 ──────
        HitEntry he;
        while ((he = pendingSingleplayerHits.poll()) != null) {
            HitMarkerRenderer.showHitMarker();
            if (he.damage > 0.5F) {
                HitMarkerRenderer.showDamageNumber(he.damage);
            }
            if (he.isKill) {
                HitMarkerRenderer.showKillMarker();
                playKillSound(mc);
            }
            playRandomHitSound();
            spawnHitParticlesAt(he.x, he.y, he.z, he.isKill ? 60 : 30,
                    he.isKill ? HitMarkerMod.config.killBloodIntensity
                              : HitMarkerMod.config.hitBloodIntensity);
        }

        // ────── 2. 钓鱼竿 caughtEntity 检测 ──────
        if (trackedBobberId != -1) {
            Entity bobber = mc.theWorld.getEntityByID(trackedBobberId);
            if (bobber instanceof EntityFishHook) {
                EntityFishHook fh = (EntityFishHook) bobber;
                if (fh.caughtEntity != null && fh.caughtEntity.isEntityAlive()) {
                    Entity caught = fh.caughtEntity;
                    int cid = caught.getEntityId();
                    if (shouldTriggerHitMarker(cid)) {
                        entityHitTimestamps.put(cid, now);
                        HitMarkerRenderer.showHitMarker();
                        playRandomHitSound();
                        spawnHitParticlesAt(caught.posX, caught.posY + caught.height / 2.0,
                                caught.posZ, 20, HitMarkerMod.config.hitBloodIntensity);
                        trackedBobberId = -1; // 防重复
                    }
                }
            } else {
                trackedBobberId = -1;
            }
        }

        // ────── 3. 箭矢追踪 ──────
        if (trackedArrowId == -1 && now - lastArrowShotTime < 2000) {
            for (Entity e : mc.theWorld.loadedEntityList) {
                if (e instanceof EntityArrow && !e.isDead && e.getDistanceToEntity(mc.thePlayer) < 15.0) {
                    try {
                        EntityArrow arrow = (EntityArrow) e;
                        if (arrow.shootingEntity == mc.thePlayer || (arrow.shootingEntity != null
                                && arrow.shootingEntity.getUniqueID().equals(mc.thePlayer.getUniqueID()))) {
                            trackedArrowId = e.getEntityId();
                            lastArrowX = e.posX; lastArrowY = e.posY; lastArrowZ = e.posZ;
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
                lastArrowX = arrow.posX; lastArrowY = arrow.posY; lastArrowZ = arrow.posZ;
                if (now - lastArrowShotTime > 500
                        && Math.abs(arrow.motionX) < 0.001
                        && Math.abs(arrow.motionY) < 0.001
                        && Math.abs(arrow.motionZ) < 0.001) {
                    trackedArrowId = -1;
                }
            }
        }

        // ────── 4. 通用抛射物追踪 ──────
        trackedProjectiles.values().removeIf(tp -> {
            Entity proj = mc.theWorld.getEntityByID(tp.entityId);
            if (proj == null || proj.isDead) {
                trackNearbyTargets(tp.lastX, tp.lastY, tp.lastZ, 4.0);
                return true;
            }
            if (now - tp.trackedSince > PROJECTILE_TIMEOUT_MS) return true;
            tp.lastX = proj.posX; tp.lastY = proj.posY; tp.lastZ = proj.posZ;
            return false;
        });

        // ────── 5. 血量轮询（伤害数字 + 击杀标识兜底） ──────
        for (java.util.Map.Entry<Integer, HealthRecord> entry : trackedHealth.entrySet()) {
            HealthRecord record = entry.getValue();
            if (record.damageShown) continue;
            if (now - record.timestampMs > HEALTH_TRACK_TIMEOUT_MS) {
                record.damageShown = true; continue;
            }

            Entity entity = mc.theWorld.getEntityByID(entry.getKey());
            if (entity instanceof EntityLivingBase) {
                float curHealth = ((EntityLivingBase) entity).getHealth();
                float damage = record.preHealth - curHealth;
                if (damage >= 0.5f) {
                    record.damageShown = true;
                    HitMarkerRenderer.showDamageNumber(damage);
                    // AttackEntityEvent/LivingHurtEvent 已触发标识+音效+粒子
                    // 血量轮询捕获的（抛射物追踪命中/兜底）补全套
                    if (!record.fromExplicitAttack) {
                        HitMarkerRenderer.showHitMarker();
                        playRandomHitSound();
                        spawnHitParticles(entry.getKey());
                    }
                    if (curHealth <= 0.0F) {
                        HitMarkerRenderer.showKillMarker();
                        playKillSound(mc);
                    }
                }
            }
        }

        trackedHealth.entrySet().removeIf(e -> e.getValue().damageShown);
    }

    // ══════════════════════════════════════════════════════════════
    //  辅助方法
    // ══════════════════════════════════════════════════════════════

    private void trackNearbyTargets(double x, double y, double z, double radius) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;
        net.minecraft.util.AxisAlignedBB box = net.minecraft.util.AxisAlignedBB.fromBounds(
                x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        for (EntityLivingBase target : mc.theWorld.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            if (target != mc.thePlayer && !trackedHealth.containsKey(target.getEntityId())) {
                trackedHealth.put(target.getEntityId(), new HealthRecord(target.getHealth(), false));
            }
        }
    }

    private boolean shouldTriggerHitMarker(int entityId) {
        Long lastHit = entityHitTimestamps.get(entityId);
        long now = System.currentTimeMillis();
        return (lastHit == null) || (now - lastHit >= INVINCIBLE_FRAME);
    }

    private boolean isClientPlayer(EntityPlayer player) {
        return player != null && player == Minecraft.getMinecraft().thePlayer;
    }

    private void playRandomHitSound() {
        try {
            if (!HitMarkerMod.config.enableHitSounds) return;
            String[] sounds = { HitMarkerMod.HIT1_SOUND, HitMarkerMod.HIT2_SOUND, HitMarkerMod.HIT3_SOUND };
            String sound = sounds[random.nextInt(sounds.length)];
            Minecraft.getMinecraft().thePlayer.playSound(sound, HitMarkerMod.config.soundVolume, 1.0F);
        } catch (Exception ignored) {}
    }

    private void playKillSound(Minecraft mc) {
        try {
            if (!HitMarkerMod.config.enableKillSound) return;
            mc.thePlayer.playSound(HitMarkerMod.KILL_SOUND, HitMarkerMod.config.soundVolume, 1.0F);
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

    private void spawnHitParticles(int entityId) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || !HitMarkerMod.config.enableHitBlood) return;
        Entity target = mc.theWorld.getEntityByID(entityId);
        if (!(target instanceof EntityLivingBase)) return;
        EntityLivingBase living = (EntityLivingBase) target;
        spawnHitParticlesAt(living.posX, living.posY + living.height / 2.0, living.posZ,
                30, HitMarkerMod.config.hitBloodIntensity);
    }
}

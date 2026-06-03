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
 * 命中检测事件处理器 — 纯客户端方案。
 *
 * 核心原则：所有检测都跑在 CLIENT 线程，绝对不碰服务端事件。
 *
 *   [近战]         AttackEntityEvent(客户端) → 即时全套反馈
 *   [抛射物]       EntityJoinWorldEvent(客户端) → 追踪 → 消失时+血量检测
 *   [血量轮询]     每 tick 扫附近实体 health 变化 → 抛射物命中+远程击杀兜底
 *   [钓鱼竿]       每 tick 扫 EntityFishHook.caughtEntity
 *   [伤害数字]     血量轮询计算 damage = prevHealth - curHealth
 */
@SideOnly(Side.CLIENT)
public class CommonEventHandler {

    private final Random random = new Random();

    // ── 命中防刷 ──
    private final ConcurrentHashMap<Integer, Long> entityHitTimestamps = new ConcurrentHashMap<>();
    private static final long INVINCIBLE_FRAME = 500;

    // ── 血量追踪（纯客户端轮询） ──
    private static class HealthRecord {
        float lastKnownHealth;
        boolean alive;
        float lastDamageShown;

        HealthRecord(float health) {
            this.lastKnownHealth = health;
            this.alive = true;
            this.lastDamageShown = -1;
        }
    }

    private final ConcurrentHashMap<Integer, HealthRecord> trackedHealth = new ConcurrentHashMap<>();

    // ── 抛射物追踪 ──
    private static class TrackedProjectile {
        final int entityId;
        double lastX, lastY, lastZ;
        long createdAt;

        TrackedProjectile(Entity e) {
            this.entityId = e.getEntityId();
            this.lastX = e.posX; this.lastY = e.posY; this.lastZ = e.posZ;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private final ConcurrentHashMap<Integer, TrackedProjectile> trackedProjectiles = new ConcurrentHashMap<>();
    private static final long PROJ_TIMEOUT = 10000;

    // ── 钓鱼竿 ──
    private int bobberId = -1;

    // ── 上次血量轮询帧 ──
    private long lastHealthScan = 0;

    // ══════════════════════════════════════════════════════════════
    //  事件处理器（全部纯客户端）
    // ══════════════════════════════════════════════════════════════

    // ──── 近战 ────

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (!isMe(event.entityPlayer)) return;
        if (event.target == null || !event.target.isEntityAlive()) return;
        int tid = event.target.getEntityId();
        if (!okToHit(tid)) return;

        entityHitTimestamps.put(tid, System.currentTimeMillis());

        if (event.target instanceof EntityLivingBase) {
            float health = ((EntityLivingBase) event.target).getHealth();
            trackedHealth.put(tid, new HealthRecord(health));
        }

        showAll();
        spawnHitParticles(event.target.posX,
                event.target.posY + event.target.height / 2.0,
                event.target.posZ,
                30, HitMarkerMod.config.hitBloodIntensity);
    }

    // ──── 射箭 ────

    @SubscribeEvent
    public void onArrowLoose(ArrowLooseEvent event) {
        if (!isMe(event.entityPlayer)) return;
        // 射箭后清理上次追踪，避免残留
        // 新箭会在 EntityJoinWorldEvent 中捕获
    }

    // ──── 抛射物进入世界（纯客户端） ────

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.world.isRemote == false) return; // 只收客户端事件
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
    //  客户端 Tick — 核心检测
    // ══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) { trackedHealth.clear(); return; }

        long now = System.currentTimeMillis();

        // ────── 1. 抛射物追踪：检测消失 ──────
        trackedProjectiles.values().removeIf(tp -> {
            Entity proj = mc.theWorld.getEntityByID(tp.entityId);
            if (proj == null || proj.isDead) {
                // 抛射物消失/命中 → 扫描周围实体
                scanForHitTargets(mc, tp.lastX, tp.lastY, tp.lastZ, 4.0, now);
                return true;
            }
            // 超时清理
            if (now - tp.createdAt > PROJ_TIMEOUT) return true;
            tp.lastX = proj.posX; tp.lastY = proj.posY; tp.lastZ = proj.posZ;
            return false;
        });

        // ────── 2. 钓鱼竿 caughtEntity ──────
        if (bobberId != -1) {
            Entity be = mc.theWorld.getEntityByID(bobberId);
            if (be instanceof EntityFishHook) {
                EntityFishHook fh = (EntityFishHook) be;
                if (fh.caughtEntity != null && fh.caughtEntity.isEntityAlive()) {
                    Entity caught = fh.caughtEntity;
                    int cid = caught.getEntityId();
                    if (okToHit(cid)) {
                        entityHitTimestamps.put(cid, now);
                        showAll();
                        spawnHitParticles(caught.posX, caught.posY + caught.height / 2.0,
                                caught.posZ, 20, HitMarkerMod.config.hitBloodIntensity);
                        bobberId = -1;
                    }
                }
            } else {
                bobberId = -1;
            }
        }

        // ────── 3. 全量血量轮询（每 50ms 一次，约每 tick） ──────
        if (now - lastHealthScan < 50) return;
        lastHealthScan = now;

        // 收集附近所有活着的实体
        for (Entity e : mc.theWorld.loadedEntityList) {
            if (e == mc.thePlayer) continue;
            if (!(e instanceof EntityLivingBase)) continue;
            if (e.isDead) continue;
            if (e.getDistanceToEntity(mc.thePlayer) > 60.0) continue;

            EntityLivingBase living = (EntityLivingBase) e;
            int eid = e.getEntityId();
            float curHealth = living.getHealth();
            HealthRecord rec = trackedHealth.get(eid);

            if (rec == null) {
                // 首次见到 → 记录血量
                trackedHealth.put(eid, new HealthRecord(curHealth));
                continue;
            }

            if (!rec.alive && living.isEntityAlive()) {
                // 复活了（如刷怪笼连续生成）
                rec.lastKnownHealth = curHealth;
                rec.alive = true;
                rec.lastDamageShown = -1;
                continue;
            }

            if (!living.isEntityAlive()) {
                rec.alive = false;
                continue;
            }

            float diff = rec.lastKnownHealth - curHealth;
            if (diff >= 0.5f && Math.abs(diff - rec.lastDamageShown) > 0.1f) {
                // 血量下降 → 命中！
                rec.lastDamageShown = diff;
                rec.lastKnownHealth = curHealth;

                if (okToHit(eid)) {
                    entityHitTimestamps.put(eid, now);
                    HitMarkerRenderer.showHitMarker();
                    HitMarkerRenderer.showDamageNumber(diff);
                    playRandomHitSound();
                    spawnHitParticles(living.posX, living.posY + living.height / 2.0,
                            living.posZ, 25, HitMarkerMod.config.hitBloodIntensity);
                }

                if (curHealth <= 0.0F) {
                    HitMarkerRenderer.showKillMarker();
                    playKillSound(mc);
                }
            }

            // 定期更新记录的血量（处理自然回血等）
            if (curHealth != rec.lastKnownHealth) {
                rec.lastKnownHealth = curHealth;
            }
        }

        // 清理死亡已久的实体记录
        trackedHealth.entrySet().removeIf(e -> {
            Entity ent = mc.theWorld.getEntityByID(e.getKey());
            return ent == null || ent.isDead;
        });
    }

    // ══════════════════════════════════════════════════════════════
    //  辅助方法
    // ══════════════════════════════════════════════════════════════

    /** 抛射物消失点扫描附近实体，建立血量追踪 */
    private void scanForHitTargets(Minecraft mc, double x, double y, double z, double radius, long now) {
        net.minecraft.util.AxisAlignedBB box = net.minecraft.util.AxisAlignedBB.fromBounds(
                x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        for (EntityLivingBase target : mc.theWorld.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            if (target == mc.thePlayer) continue;
            if (!target.isEntityAlive()) continue;
            int tid = target.getEntityId();
            if (!trackedHealth.containsKey(tid)) {
                trackedHealth.put(tid, new HealthRecord(target.getHealth()));
            }
        }
    }

    private boolean isMe(EntityPlayer p) {
        return p != null && p == Minecraft.getMinecraft().thePlayer;
    }

    private boolean okToHit(int entityId) {
        Long last = entityHitTimestamps.get(entityId);
        long now = System.currentTimeMillis();
        return last == null || (now - last >= INVINCIBLE_FRAME);
    }

    private void showAll() {
        HitMarkerRenderer.showHitMarker();
        playRandomHitSound();
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

    private void playKillSound(Minecraft mc) {
        try {
            if (!HitMarkerMod.config.enableKillSound) return;
            mc.thePlayer.playSound(HitMarkerMod.KILL_SOUND,
                    HitMarkerMod.config.soundVolume, 1.0F);
        } catch (Exception ignored) {}
    }

    private void spawnHitParticles(double x, double y, double z, int count, double intensity) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || !HitMarkerMod.config.enableHitBlood) return;
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

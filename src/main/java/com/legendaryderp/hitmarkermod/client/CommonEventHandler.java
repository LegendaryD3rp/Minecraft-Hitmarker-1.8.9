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
 * 双路检测：
 *   路一【单人模式】— LivingHurtEvent（服务端线程）直接触发。
 *       关键：服务端玩家（EntityPlayerMP）与客户端玩家（EntityPlayerSP）的
 *       entityId 不同，不能用 Entity.equals() 比较。改用名字比较。
 *   路二【多人模式】— EntityJoinWorldEvent 抛射物追踪 + 血量轮询。
 *   路三【近战】    — AttackEntityEvent（客户端线程）即时触发。
 *   路四【钓鱼竿】  — 每 tick 查 EntityFishHook.caughtEntity。
 */
@SideOnly(Side.CLIENT)
public class CommonEventHandler {

    private final Random random = new Random();
    private final ConcurrentHashMap<Integer, Long> entityHitTimestamps = new ConcurrentHashMap<>();
    private static final long INVINCIBLE_FRAME = 500;

    // ── 多人：抛射物追踪 ──
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

    // ── 多人：血量轮询 ──
    private final ConcurrentHashMap<Integer, Float> trackedHealth = new ConcurrentHashMap<>();
    private long lastHealthScan = 0;

    // ── 钓鱼竿 ──
    private int bobberId = -1;

    // ── 击杀音效防抖 ──
    private long lastKillSoundTime = 0;
    private static final long KILL_SOUND_COOLDOWN = 800;

    // ══════════════════════════════════════════════════════════════
    //  路一：近战（客户端线程，即时）
    // ══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (event.entityPlayer == null || event.target == null || !event.target.isEntityAlive()) return;
        if (!isClientPlayer(event.entityPlayer)) return; // 客户端线程，entityId 比较 OK

        int targetId = event.target.getEntityId();
        if (!shouldTriggerHitMarker(targetId)) return;

        triggerHitEffects(targetId, event.target.getName(), 0);
    }

    // ══════════════════════════════════════════════════════════════
    //  路二：受击事件（单人模式核心——服务端线程）
    //  修复要点：服务端玩家是 EntityPlayerMP，客户端玩家是 EntityPlayerSP，
    //  两者 entityId 不同。不能用 entityId 比较，改用名字。
    // ══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (event.entity == null || event.source == null || !event.entity.isEntityAlive()) return;

        EntityPlayer attacker = getAttackerFromDamageSource(event.source);
        // 关键修复：用名字比较（单人模式唯一玩家，安全）
        if (attacker == null || !isClientPlayerByName(attacker)) return;

        int targetId = event.entity.getEntityId();
        if (!shouldTriggerHitMarker(targetId)) return;

        triggerHitEffects(targetId, event.entity.getName(), event.ammount);
    }

    // ══════════════════════════════════════════════════════════════
    //  路二（续）：击杀事件
    // ══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.entity == null || event.source == null) return;

        EntityPlayer killer = getAttackerFromDamageSource(event.source);
        if (killer != null && isClientPlayerByName(killer)) {
            triggerKillEffects();
        }

        entityHitTimestamps.remove(event.entity.getEntityId());
    }

    // ══════════════════════════════════════════════════════════════
    //  路三：抛射物追踪（多人模式）
    // ══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (!event.world.isRemote) return; // 只收客户端事件→多人追踪用
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

        // 雪球/蛋/药水
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
    //  客户端 Tick：多人血量轮询 + 钓鱼竿
    // ══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) { trackedHealth.clear(); return; }

        long now = System.currentTimeMillis();

        // ── 抛射物消失检测 ──
        trackedProjectiles.values().removeIf(tp -> {
            Entity proj = mc.theWorld.getEntityByID(tp.entityId);
            if (proj == null || proj.isDead) {
                scanHealth(tp.lastX, tp.lastY, tp.lastZ, 4.0);
                return true;
            }
            if (now - tp.createdAt > PROJ_TIMEOUT) return true;
            tp.lastX = proj.posX; tp.lastY = proj.posY; tp.lastZ = proj.posZ;
            return false;
        });

        // ── 钓鱼竿 ──
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
                        spawnHitParticles(caught.posX, caught.posY + caught.height / 2.0,
                                caught.posZ, 20, HitMarkerMod.config.hitBloodIntensity);
                        bobberId = -1;
                    }
                }
            } else {
                bobberId = -1;
            }
        }

        // ── 血量轮询（多人模式兜底，每 50ms） ──
        if (now - lastHealthScan < 50) return;
        lastHealthScan = now;

        for (Entity e : mc.theWorld.loadedEntityList) {
            if (e == mc.thePlayer || !(e instanceof EntityLivingBase) || e.isDead) continue;
            if (e.getDistanceToEntity(mc.thePlayer) > 60.0) continue;

            EntityLivingBase living = (EntityLivingBase) e;
            int eid = e.getEntityId();
            float curHealth = living.getHealth();
            Float prevHealth = trackedHealth.get(eid);

            if (prevHealth == null) {
                trackedHealth.put(eid, curHealth);
                continue;
            }

            float diff = prevHealth - curHealth;
            if (diff >= 0.5f) {
                if (shouldTriggerHitMarker(eid)) {
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

            trackedHealth.put(eid, curHealth);
        }

        trackedHealth.keySet().removeIf(id -> {
            Entity ent = mc.theWorld.getEntityByID(id);
            return ent == null || ent.isDead;
        });
    }

    // ══════════════════════════════════════════════════════════════
    //  辅助
    // ══════════════════════════════════════════════════════════════

    private void scanHealth(double x, double y, double z, double radius) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;
        net.minecraft.util.AxisAlignedBB box = net.minecraft.util.AxisAlignedBB.fromBounds(
                x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        for (EntityLivingBase target : mc.theWorld.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            if (target == mc.thePlayer) continue;
            if (!trackedHealth.containsKey(target.getEntityId())) {
                trackedHealth.put(target.getEntityId(), target.getHealth());
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

    private EntityPlayer getAttackerFromDamageSource(net.minecraft.util.DamageSource source) {
        if (source == null) return null;
        try {
            // 直接玩家攻击
            if (source.getEntity() instanceof EntityPlayer)
                return (EntityPlayer) source.getEntity();

            // 抛射物攻击
            if (source.getEntity() instanceof EntityArrow) {
                EntityArrow arrow = (EntityArrow) source.getEntity();
                if (arrow.shootingEntity instanceof EntityPlayer)
                    return (EntityPlayer) arrow.shootingEntity;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void triggerHitEffects(int entityId, String targetName, float damage) {
        entityHitTimestamps.put(entityId, System.currentTimeMillis());
        try {
            HitMarkerRenderer.showHitMarker();
            if (damage > 0.5F) HitMarkerRenderer.showDamageNumber(damage);
            playRandomHitSound();
            spawnHitParticles(entityId);
        } catch (Exception e) {
            HitMarkerMod.logger.error("Hit effects failed: {}", e.getMessage());
        }
    }

    private void triggerKillEffects() {
        try {
            HitMarkerRenderer.showKillMarker();
            playKillSound(Minecraft.getMinecraft());
        } catch (Exception e) {
            HitMarkerMod.logger.error("Kill effects failed: {}", e.getMessage());
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

    private void playKillSound(Minecraft mc) {
        try {
            if (!HitMarkerMod.config.enableKillSound) return;
            long now = System.currentTimeMillis();
            if (now - lastKillSoundTime < KILL_SOUND_COOLDOWN) return;
            lastKillSoundTime = now;
            mc.thePlayer.playSound(HitMarkerMod.KILL_SOUND,
                    HitMarkerMod.config.soundVolume, 1.0F);
        } catch (Exception ignored) {}
    }

    private void spawnHitParticles(int entityId) {
        if (!HitMarkerMod.config.enableHitBlood) return;
        try {
            Entity target = Minecraft.getMinecraft().theWorld.getEntityByID(entityId);
            if (target == null) return;
            spawnHitParticles(target.posX, target.posY + target.height / 2.0, target.posZ,
                    30, HitMarkerMod.config.hitBloodIntensity);
        } catch (Exception e) {
            HitMarkerMod.logger.error("Hit particles failed: {}", e.getMessage());
        }
    }

    private void spawnHitParticles(double x, double y, double z, int count, double intensity) {
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

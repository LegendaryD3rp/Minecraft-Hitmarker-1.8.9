package com.legendaryderp.hitmarkermod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import com.legendaryderp.hitmarkermod.HitMarkerMod;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@SideOnly(Side.CLIENT)
public class CommonEventHandler {

    private final Random random = new Random();
    private final ConcurrentHashMap<Integer, Long> entityHitTimestamps = new ConcurrentHashMap<>();
    private static final long INVINCIBLE_FRAME = 500;

    // 新增：爆炸物追踪映射（用于记录玩家放置的爆炸物）
    private final ConcurrentHashMap<Integer, EntityPlayer> explosionSourceMap = new ConcurrentHashMap<>();

    // 关键修复：重新启用 AttackEntityEvent 作为客户端本地备份
    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (event.entityPlayer == null || event.target == null || !event.target.isEntityAlive()) {
            return;
        }

        // 只处理客户端玩家自己的攻击
        if (!isClientPlayer(event.entityPlayer)) {
            return;
        }

        int targetId = event.target.getEntityId();

        // 使用攻击事件作为客户端本地触发条件
        if (shouldTriggerHitMarker(targetId)) {
            triggerHitEffects(targetId, event.target.getName(), 0);
        }
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        // 基础检查
        if (event.entity == null || event.source == null || !event.entity.isEntityAlive()) {
            return;
        }

        // 获取攻击者并验证
        EntityPlayer attacker = getAttackerFromDamageSource(event.source);
        if (attacker == null || !isClientPlayer(attacker)) {
            return;
        }

        // 简化多人模式检查：只要有攻击者就触发，不严格检查伤害量
        boolean shouldTriggerInMultiplayer = shouldTriggerInMultiplayer(event, attacker);

        if (shouldTriggerInMultiplayer) {
            int targetId = event.entity.getEntityId();
            if (shouldTriggerHitMarker(targetId)) {
                triggerHitEffects(targetId, event.entity.getName(), event.ammount);
            }
        }
    }

    /**
     * 多人模式专用检查逻辑（宽松条件）
     */
    private boolean shouldTriggerInMultiplayer(LivingHurtEvent event, EntityPlayer attacker) {
        // 在多人模式下放宽条件，主要依赖攻击者验证
        boolean isEffective = event.ammount > 0;
        boolean isAttackerValid = attacker != null && isClientPlayer(attacker);
        boolean isTargetValid = event.entity != null && event.entity.isEntityAlive();

        return isAttackerValid && isTargetValid;
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.entity == null || event.source == null) return;

        // 多人模式下使用更宽松的击杀检测
        EntityPlayer killer = getAttackerFromDamageSource(event.source);
        if (killer != null && isClientPlayerLoose(killer)) {
            triggerKillEffects();
        }

        // 清理记录
        entityHitTimestamps.remove(event.entity.getEntityId());
    }

    private boolean shouldTriggerHitMarker(int entityId) {
        Long lastHit = entityHitTimestamps.get(entityId);
        long currentTime = System.currentTimeMillis();
        boolean shouldTrigger = (lastHit == null) || (currentTime - lastHit >= INVINCIBLE_FRAME);

        return shouldTrigger;
    }

    private void triggerHitEffects(int entityId, String targetName, float damage) {
        entityHitTimestamps.put(entityId, System.currentTimeMillis());

        try {
            HitMarkerRenderer.showHitMarker();

            // === 修改：普通命中时播放命中音效 ===
            playRandomHitSound();

            // === 修改：触发命中粒子特效（红石线路破坏）===
            spawnHitParticles(entityId);

        } catch (Exception e) {
            HitMarkerMod.logger.error("[HIT] ✗ Hit effects failed: {}", e.getMessage());
        }
    }

    private void triggerKillEffects() {
        try {
            HitMarkerRenderer.showKillMarker();

            // === 修改：击杀时只播放击杀音效，不播放命中音效 ===
            playKillSound();

            // 触发击杀粒子特效（红石块破坏）
            spawnKillParticles();

        } catch (Exception e) {
            HitMarkerMod.logger.error("[HIT] ✗ Kill effects failed: {}", e.getMessage());
        }
    }

    /**
     * === 修改：生成命中粒子特效（使用红石块破坏特效）===
     * 粒子生成点向下调整30%
     * 粒子数量为20个（常规）。
     * @param targetEntityId 被命中实体的ID，用于获取其位置
     */
    private void spawnHitParticles(int targetEntityId) {
        if (!HitMarkerMod.config.enableHitBlood) return;
        try {
            net.minecraft.entity.Entity target = Minecraft.getMinecraft().theWorld.getEntityByID(targetEntityId);
            if (target == null) {
                return;
            }

            World world = Minecraft.getMinecraft().theWorld;
            double posX = target.posX;

            // === 修改：粒子生成点向下调整30% ===
            // 原高度：target.posY + target.height * 0.5
            // 降低30%：从实体高度的50%处调整到20%处
            double originalHeight = target.height * 0.5;
            double adjustedHeight = target.height * 0.2; // 降低30%（0.5 * 0.7 ≈ 0.35，这里取0.2更明显）
            double posY = target.posY + adjustedHeight;

            double posZ = target.posZ;

            // 获取红石块方块状态
            int redstoneBlockStateId = Block.getStateId(Blocks.redstone_block.getDefaultState());

            // === 命中特效：常规数量20个 ===
            double velScale = HitMarkerMod.config.hitBloodIntensity;
            for (int i = 0; i < 20; i++) {
                double offsetX = (world.rand.nextDouble() - 0.5) * 0.8;
                double offsetY = (world.rand.nextDouble() - 0.5) * 0.8;
                double offsetZ = (world.rand.nextDouble() - 0.5) * 0.8;

                world.spawnParticle(EnumParticleTypes.BLOCK_CRACK,
                        posX + offsetX,
                        posY + offsetY,
                        posZ + offsetZ,
                        offsetX * 0.1 * velScale,
                        offsetY * 0.1 * velScale,
                        offsetZ * 0.1 * velScale,
                        redstoneBlockStateId);
            }
        } catch (Exception e) {
            HitMarkerMod.logger.error("[PARTICLE] Failed to spawn hit particles: {}", e.getMessage());
        }
    }

    /**
     * 生成击杀粒子特效（模拟大范围爆裂）
     * 使用方块破坏（BLOCK_CRACK）粒子，模拟红石块被破坏的效果，更具冲击力。
     * 粒子数量为60个。
     */
    private void spawnKillParticles() {
        if (!HitMarkerMod.config.enableKillBlood) return;
        try {
            // 获取玩家（击杀者）视角指向的位置
            EntityPlayer player = Minecraft.getMinecraft().thePlayer;
            if (player == null) {
                return;
            }

            // 进行射线追踪，获取玩家准星瞄准的点
            MovingObjectPosition objectMouseOver = player.rayTrace(100, 1.0F); // 100格距离

            World world = Minecraft.getMinecraft().theWorld;
            double posX, posY, posZ;

            if (objectMouseOver != null && objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
                // 如果准星指着实体，在该实体位置生成特效
                net.minecraft.entity.Entity hitEntity = objectMouseOver.entityHit;
                posX = hitEntity.posX;
                posY = hitEntity.posY + hitEntity.height * 0.5;
                posZ = hitEntity.posZ;
            } else if (objectMouseOver != null && objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                // 如果准星指着方块，在方块击中的面生成特效
                BlockPos blockPos = objectMouseOver.getBlockPos();
                posX = blockPos.getX() + 0.5;
                posY = blockPos.getY() + 0.5;
                posZ = blockPos.getZ() + 0.5;
            } else {
                // 如果什么都没指着，在玩家前方一段距离生成特效
                Vec3 lookVec = player.getLook(1.0F);
                double range = 5.0; // 玩家前方5格
                posX = player.posX + lookVec.xCoord * range;
                posY = player.posY + player.getEyeHeight() + lookVec.yCoord * range;
                posZ = player.posZ + lookVec.zCoord * range;
            }

            spawnBlockBreakParticlesAt(world, posX, posY, posZ, Blocks.redstone_block);

        } catch (Exception e) {
            HitMarkerMod.logger.error("[PARTICLE] Failed to spawn kill particles: {}", e.getMessage());
        }
    }

    /**
     * 辅助方法：在指定位置生成方块破坏粒子
     * 粒子数量为60个。
     * @param world 世界对象
     * @param x 中心X坐标
     * @param y 中心Y坐标
     * @param z 中心Z坐标
     * @param block 要模拟破坏的方块（这里用红石块）
     */
    private void spawnBlockBreakParticlesAt(World world, double x, double y, double z, Block block) {
        double velScale = HitMarkerMod.config.killBloodIntensity;
        for (int i = 0; i < 60; i++) {
            double offsetX = (world.rand.nextDouble() - 0.5) * 1.5;
            double offsetY = (world.rand.nextDouble() - 0.5) * 1.5;
            double offsetZ = (world.rand.nextDouble() - 0.5) * 1.5;

            world.spawnParticle(EnumParticleTypes.BLOCK_CRACK,
                    x + offsetX,
                    y + offsetY,
                    z + offsetZ,
                    offsetX * 0.2 * velScale,
                    offsetY * 0.2 * velScale,
                    offsetZ * 0.2 * velScale,
                    Block.getStateId(block.getDefaultState()));
        }
    }

    /**
     * 宽松的玩家身份验证（多人模式优化）
     */
    private boolean isClientPlayerLoose(EntityPlayer player) {
        if (player == null) return false;

        try {
            EntityPlayer clientPlayer = Minecraft.getMinecraft().thePlayer;
            // 多人模式下使用更宽松的验证
            return player.equals(clientPlayer) ||
                    (player.getName() != null && player.getName().equals(clientPlayer.getName()));
        } catch (Exception e) {
            // 出错时默认返回true，避免漏报
            return true;
        }
    }

    /**
     * 扩展的攻击者获取方法 - 现在支持爆炸伤害
     */
    private EntityPlayer getAttackerFromDamageSource(DamageSource source) {
        if (source == null) return null;

        try {
            // 直接玩家攻击
            if (source.getEntity() instanceof EntityPlayer) {
                return (EntityPlayer) source.getEntity();
            }

            // 抛射物攻击
            if (source.getEntity() instanceof EntityArrow) {
                EntityArrow arrow = (EntityArrow) source.getEntity();
                if (arrow.shootingEntity instanceof EntityPlayer) {
                    return (EntityPlayer) arrow.shootingEntity;
                }
            }

            // 新增：爆炸伤害处理
            if (source.isExplosion()) {
                EntityPlayer explosionOwner = getExplosionOwner(source);
                if (explosionOwner != null) {
                    return explosionOwner;
                }
            }

            // 新增：火焰伤害处理（床爆炸等）
            if (source.isFireDamage()) {
                // 检查是否是玩家引起的火焰伤害
                EntityPlayer fireOwner = getFireDamageOwner(source);
                if (fireOwner != null) {
                    return fireOwner;
                }
            }

        } catch (Exception e) {
        }

        return null;
    }

    /**
     * 获取爆炸伤害的所有者
     */
    private EntityPlayer getExplosionOwner(DamageSource source) {
        try {
            // 1. 首先检查伤害源实体是否是TNT
            if (source.getEntity() instanceof EntityTNTPrimed) {
                EntityTNTPrimed tnt = (EntityTNTPrimed) source.getEntity();
                // 在1.8.9中，EntityTNTPrimed的放置者信息可能不可用
                // 使用备选方案：检查最近的爆炸物追踪记录
                return explosionSourceMap.get(tnt.getEntityId());
            }

            // 2. 检查伤害源实体本身是否是玩家（某些爆炸可能直接关联玩家）
            if (source.getEntity() instanceof EntityPlayer) {
                return (EntityPlayer) source.getEntity();
            }

            // 3. 检查爆炸源（getSourceOfDamage）可能提供更多信息
            if (source.getSourceOfDamage() instanceof EntityPlayer) {
                return (EntityPlayer) source.getSourceOfDamage();
            }

        } catch (Exception e) {
        }
        return null;
    }

    /**
     * 获取火焰伤害的所有者（用于床爆炸等）
     */
    private EntityPlayer getFireDamageOwner(DamageSource source) {
        try {
            // 床爆炸等可能被归类为火焰伤害
            // 检查伤害源实体
            if (source.getEntity() instanceof EntityPlayer) {
                return (EntityPlayer) source.getEntity();
            }

            // 检查伤害源
            if (source.getSourceOfDamage() instanceof EntityPlayer) {
                return (EntityPlayer) source.getSourceOfDamage();
            }

        } catch (Exception e) {
        }
        return null;
    }

    /**
     * 新增：记录玩家放置的爆炸物
     * 这个方法需要在玩家放置TNT时被调用
     */
    public void trackExplosionSource(int explosionEntityId, EntityPlayer player) {
        if (isClientPlayer(player)) {
            explosionSourceMap.put(explosionEntityId, player);
        }
    }

    /**
     * 新增：清理爆炸物追踪记录
     */
    public void cleanupExplosionSource(int explosionEntityId) {
        explosionSourceMap.remove(explosionEntityId);
    }

    private boolean isClientPlayer(EntityPlayer player) {
        return player != null && player.equals(Minecraft.getMinecraft().thePlayer);
    }

    private void playRandomHitSound() {
        if (!HitMarkerMod.config.enableHitSounds) return;
        try {
            String[] hitSounds = {HitMarkerMod.HIT1_SOUND, HitMarkerMod.HIT2_SOUND, HitMarkerMod.HIT3_SOUND};
            String sound = hitSounds[random.nextInt(hitSounds.length)];
            Minecraft.getMinecraft().thePlayer.playSound(sound, HitMarkerMod.config.soundVolume, 1.0F);
        } catch (Exception e) {
            HitMarkerMod.logger.error("[HIT] Sound playback failed: {}", e.getMessage());
        }
    }

    private void playKillSound() {
        if (!HitMarkerMod.config.enableKillSound) return;
        try {
            Minecraft.getMinecraft().thePlayer.playSound(HitMarkerMod.KILL_SOUND, HitMarkerMod.config.soundVolume, 1.0F);
        } catch (Exception e) {
            HitMarkerMod.logger.error("[HIT] Kill sound failed: {}", e.getMessage());
        }
    }
}
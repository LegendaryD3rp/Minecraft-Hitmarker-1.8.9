package com.legendaryderp.hitmarkermod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import com.legendaryderp.hitmarkermod.HitMarkerMod;
import org.lwjgl.opengl.GL11;

import java.util.concurrent.CopyOnWriteArrayList;

@SideOnly(Side.CLIENT)
public class HitMarkerRenderer {

    // ── 命中指示器 ──
    private static long hitMarkerTime = 0;
    private static final long DURATION = 300;
    private static long killMarkerTime = 0;
    private static final long KILL_DURATION = 500;
    private static boolean isKillMarker = false;

    // ── 伤害数字 ──
    private static final CopyOnWriteArrayList<DamageNumber> damageNumbers = new CopyOnWriteArrayList<DamageNumber>();

    private static class DamageNumber {
        final int damage;
        final long spawnTime;       // System.currentTimeMillis
        int lifetimeTicks;          // 剩余 ticks

        DamageNumber(int damage, int lifetimeTicks) {
            this.damage = damage;
            this.spawnTime = System.currentTimeMillis();
            this.lifetimeTicks = lifetimeTicks;
        }
    }

    // ── 公共 API ──
    public static void showHitMarker() {
        hitMarkerTime = System.currentTimeMillis();
        isKillMarker = false;
    }

    public static void showKillMarker() {
        killMarkerTime = System.currentTimeMillis();
        isKillMarker = true;
    }

    /** 添加一个伤害数字（在 hit 时调用） */
    public static void showDamageNumber(float damage) {
        if (!HitMarkerMod.config.enableDamageNumbers) return;
        int dmg = Math.round(damage);
        if (dmg <= 0) return;
        damageNumbers.add(new DamageNumber(dmg, HitMarkerMod.config.dmgNumDuration));
    }

    // ── 渲染入口 ──
    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent event) {
        if (event.type == RenderGameOverlayEvent.ElementType.CROSSHAIRS) {
            renderHitMarker(event);
        }
        if (event.type == RenderGameOverlayEvent.ElementType.TEXT) {
            renderHitMarker(event);
        }
    }

    /** 在 Post.ALL 阶段渲染伤害数字（确保在其他 GUI 元素之后绘制） */
    @SubscribeEvent
    public void onRenderOverlayPost(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        renderDamageNumbers();
    }

    // ══════════════════════════════════════
    //  命中指示器渲染（已有逻辑）
    // ══════════════════════════════════════
    private void renderHitMarker(RenderGameOverlayEvent event) {
        long now = System.currentTimeMillis();
        long killElapsed = now - killMarkerTime;
        boolean showingKill = killElapsed < KILL_DURATION;
        long hitElapsed = now - hitMarkerTime;
        boolean showingHit = hitElapsed < DURATION && !showingKill;

        if (showingKill) {
            renderKillMarker(event, killElapsed);
        } else if (showingHit) {
            renderNormalHitMarker(event, hitElapsed);
        }
    }

    private void renderNormalHitMarker(RenderGameOverlayEvent event, long elapsed) {
        ScaledResolution res = new ScaledResolution(Minecraft.getMinecraft());
        int cx = res.getScaledWidth() / 2, cy = res.getScaledHeight() / 2;
        renderMarker(cx, cy, elapsed, DURATION, false);
    }

    private void renderKillMarker(RenderGameOverlayEvent event, long elapsed) {
        ScaledResolution res = new ScaledResolution(Minecraft.getMinecraft());
        int cx = res.getScaledWidth() / 2, cy = res.getScaledHeight() / 2;
        renderMarker(cx, cy, elapsed, KILL_DURATION, true);
    }

    private void renderMarker(int cx, int cy, long elapsed, long duration, boolean isKill) {
        float lineLen = isKill ? HitMarkerMod.config.killSize : HitMarkerMod.config.hitSize;
        float thickness = lineLen * 0.35f;
        float gap = isKill ? lineLen * 0.35f : lineLen * 0.7f;
        float taper = lineLen * 0.25f;

        float configAlpha = isKill ? HitMarkerMod.config.killAlpha : HitMarkerMod.config.hitAlpha;
        float alpha = (1.0f - (float) elapsed / duration) * configAlpha;
        float translateDist = isKill ? lineLen * 0.3f : 0.0f;

        float r = (isKill ? HitMarkerMod.config.killColorR : HitMarkerMod.config.hitColorR) / 255.0f;
        float g = (isKill ? HitMarkerMod.config.killColorG : HitMarkerMod.config.hitColorG) / 255.0f;
        float b = (isKill ? HitMarkerMod.config.killColorB : HitMarkerMod.config.hitColorB) / 255.0f;

        // 用 GlStateManager 管理状态（避免 glPushAttrib 导致 GlStateManager 缓存不同步）
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GlStateManager.color(r, g, b, alpha);
        GL11.glLineWidth(thickness);

        float d = 0.7071f;
        drawLine(cx, cy, d, -d, gap, lineLen, taper, translateDist);
        drawLine(cx, cy, d, d, gap, lineLen, taper, translateDist);
        drawLine(cx, cy, -d, d, gap, lineLen, taper, translateDist);
        drawLine(cx, cy, -d, -d, gap, lineLen, taper, translateDist);

        // 恢复 GL 状态
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
    }

    private void drawLine(float cx, float cy, float dx, float dy, float gap, float len, float taper, float trans) {
        float ox = dx * trans, oy = dy * trans;
        float sx = cx + dx * gap + ox, sy = cy + dy * gap + oy;
        float ex = cx + dx * (gap + len) + ox, ey = cy + dy * (gap + len) + oy;
        GL11.glBegin(GL11.GL_LINE_STRIP);
        GL11.glVertex2f(sx, sy);
        GL11.glVertex2f(sx + dx * (len - taper) * 0.5f, sy + dy * (len - taper) * 0.5f);
        GL11.glVertex2f(ex, ey);
        GL11.glEnd();
    }

    // ══════════════════════════════════════
    //  伤害数字渲染（新增）
    // ══════════════════════════════════════
    private void renderDamageNumbers() {
        if (damageNumbers.isEmpty()) return;
        if (!HitMarkerMod.config.enableDamageNumbers) {
            damageNumbers.clear();
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer fr = mc.fontRendererObj;
        ScaledResolution res = new ScaledResolution(mc);
        int baseX = res.getScaledWidth() / 2 + HitMarkerMod.config.dmgNumXOffset;
        int baseY = res.getScaledHeight() / 2 + HitMarkerMod.config.dmgNumYOffset;

        float scale = HitMarkerMod.config.dmgNumScale;
        float floatSpeed = HitMarkerMod.config.dmgNumFloatSpeed;
        int colorR = HitMarkerMod.config.dmgNumColorR;
        int colorG = HitMarkerMod.config.dmgNumColorG;
        int colorB = HitMarkerMod.config.dmgNumColorB;

        // 用 GlStateManager 管理状态（避免 glPushAttrib 导致缓存不同步）
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableDepth();

        for (DamageNumber dn : damageNumbers) {
            int ageTicks = HitMarkerMod.config.dmgNumDuration - dn.lifetimeTicks;
            float progress = (float) ageTicks / HitMarkerMod.config.dmgNumDuration;

            int yOff = (int) (ageTicks * floatSpeed);

            // 淡出：最后 30% 时间逐渐透明
            float alpha = 1.0f;
            if (progress > 0.7f) {
                alpha = 1.0f - (progress - 0.7f) / 0.3f;
            }
            if (alpha < 0) alpha = 0;

            String text = String.valueOf(dn.damage);
            int textWidth = fr.getStringWidth(text);

            // 合成 ARGB 颜色（drawString 的 color 参数是 ARGB 格式）
            int mainColor = ((int) (alpha * 255) << 24) | (colorR << 16) | (colorG << 8) | colorB;
            int shadowColor = ((int) (alpha * 255 * 0.6f) << 24);

            GlStateManager.pushMatrix();
            GlStateManager.translate((float)baseX, (float)(baseY + yOff), 0.0F);
            GlStateManager.scale(scale, scale, 1.0f);

            // FontRenderer.drawString 会自行管理纹理绑定
            fr.drawString(text, -textWidth / 2 + 1, 1, shadowColor);
            fr.drawString(text, -textWidth / 2, 0, mainColor);

            GlStateManager.popMatrix();
        }

        // 恢复 GL 状态
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();

        // 清理过期
        for (DamageNumber dn : damageNumbers) {
            dn.lifetimeTicks--;
        }
        for (DamageNumber dn : damageNumbers) {
            if (dn.lifetimeTicks <= 0) {
                damageNumbers.remove(dn);
            }
        }
    }
}

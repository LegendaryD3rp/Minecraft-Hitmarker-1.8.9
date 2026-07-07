package com.legendaryderp.hitmarkermod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import com.legendaryderp.hitmarkermod.HitMarkerMod;
import org.lwjgl.opengl.GL11;

import java.util.Random;

@SideOnly(Side.CLIENT)
public class HitMarkerRenderer {

    // ── 命中指示器 ──
    private static long hitMarkerTime = 0;
    private static final long DURATION = 300;
    private static long killMarkerTime = 0;
    private static final long KILL_DURATION = 500;
    private static boolean isKillMarker = false;

    // ── 公共 API ──
    public static void showHitMarker() {
        hitMarkerTime = System.currentTimeMillis();
        isKillMarker = false;
    }

    public static void showKillMarker() {
        killMarkerTime = System.currentTimeMillis();
        isKillMarker = true;
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
        renderMarker(cx, cy, elapsed, DURATION, false, hitMarkerTime);
    }

    private void renderKillMarker(RenderGameOverlayEvent event, long elapsed) {
        ScaledResolution res = new ScaledResolution(Minecraft.getMinecraft());
        int cx = res.getScaledWidth() / 2, cy = res.getScaledHeight() / 2;
        renderMarker(cx, cy, elapsed, KILL_DURATION, true, killMarkerTime);
    }

    private void renderMarker(int cx, int cy, long elapsed, long duration, boolean isKill, long startTime) {
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

        // ── 随机旋转（同一次命中角度固定，不同次随机） ──
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, cy, 0.0F);
        if (HitMarkerMod.config.enableRandomRotate && HitMarkerMod.config.randomRotateStrength > 0) {
            Random rng = new Random(startTime);
            float angle = (rng.nextBoolean() ? 1.0F : -1.0F) * (5.0F + rng.nextFloat() * 15.0F);
            GlStateManager.rotate(angle, 0.0F, 0.0F, 1.0F);
        }
        GlStateManager.translate(-cx, -cy, 0.0F);

        // 用 GlStateManager 管理状态（避免 glPushAttrib 导致 GlStateManager 缓存不同步）
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);

        float d = 0.7071f;

        // ── 第一遍：边框 ──
        if (HitMarkerMod.config.enableBorder && HitMarkerMod.config.borderWidth > 0) {
            float br = (isKill ? HitMarkerMod.config.killBorderColorR : HitMarkerMod.config.borderColorR) / 255.0f;
            float bg = (isKill ? HitMarkerMod.config.killBorderColorG : HitMarkerMod.config.borderColorG) / 255.0f;
            float bb = (isKill ? HitMarkerMod.config.killBorderColorB : HitMarkerMod.config.borderColorB) / 255.0f;
            GlStateManager.color(br, bg, bb, alpha);
            GL11.glLineWidth(thickness + HitMarkerMod.config.borderWidth * 2);
            drawLine(cx, cy, d, -d, gap, lineLen, taper, translateDist);
            drawLine(cx, cy, d, d, gap, lineLen, taper, translateDist);
            drawLine(cx, cy, -d, d, gap, lineLen, taper, translateDist);
            drawLine(cx, cy, -d, -d, gap, lineLen, taper, translateDist);
        }

        // ── 第二遍：主色 ──
        GlStateManager.color(r, g, b, alpha);
        GL11.glLineWidth(thickness);
        drawLine(cx, cy, d, -d, gap, lineLen, taper, translateDist);
        drawLine(cx, cy, d, d, gap, lineLen, taper, translateDist);
        drawLine(cx, cy, -d, d, gap, lineLen, taper, translateDist);
        drawLine(cx, cy, -d, -d, gap, lineLen, taper, translateDist);

        // 恢复 GL 状态
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.popMatrix();
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
}

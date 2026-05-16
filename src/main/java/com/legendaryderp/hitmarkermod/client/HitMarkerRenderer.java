package com.legendaryderp.hitmarkermod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import com.legendaryderp.hitmarkermod.HitMarkerMod;
import org.lwjgl.opengl.GL11;

@SideOnly(Side.CLIENT)
public class HitMarkerRenderer {

    // 调试控制
    private static final boolean DEBUG_MODE = false;

    // 命中指示器状态
    private static long hitMarkerTime = 0;
    private static final long DURATION = 300; // 300毫秒显示时间

    // 击杀指示器状态
    private static long killMarkerTime = 0;
    private static final long KILL_DURATION = 500; // 击杀显示500毫秒，更显眼

    // 当前显示状态
    private static boolean isKillMarker = false;

    // 渲染器初始化状态
    private boolean initialized = false;

    public void initialize() {
        if (initialized) return;
        initialized = true;
        HitMarkerMod.logger.info("HitMarkerRenderer initialized");
    }

    public static void showHitMarker() {
      //  if (DEBUG_MODE) HitMarkerMod.logger.info("[DEBUG] showHitMarker() called");
        hitMarkerTime = System.currentTimeMillis();
        isKillMarker = false;
    }

    public static void showKillMarker() {
     //   if (DEBUG_MODE) HitMarkerMod.logger.info("[DEBUG] showKillMarker() called - KILL CONFIRMED!");
        killMarkerTime = System.currentTimeMillis();
        isKillMarker = true;
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent event) {
        // 关键调试：记录所有渲染事件
        //     if (DEBUG_MODE) HitMarkerMod.logger.info("[DEBUG] RenderGameOverlayEvent triggered, type: {}", event.type);

        // 尝试不同的事件类型
        if (event.type == RenderGameOverlayEvent.ElementType.CROSSHAIRS) {
            renderHitMarker(event);
        }

        // 也可以尝试其他事件类型
        if (event.type == RenderGameOverlayEvent.ElementType.TEXT) {
            // 在TEXT层也尝试渲染
            renderHitMarker(event);
        }
    }

    private void renderHitMarker(RenderGameOverlayEvent event) {
        long currentTime = System.currentTimeMillis();

        // 检查击杀指示器（优先级更高）
        long killElapsed = currentTime - killMarkerTime;
        boolean showingKill = killElapsed < KILL_DURATION;

        // 检查普通命中指示器
        long hitElapsed = currentTime - hitMarkerTime;
        boolean showingHit = hitElapsed < DURATION && !showingKill;

      //  if (DEBUG_MODE) {
          //  HitMarkerMod.logger.info("[DEBUG] Kill: {}ms (showing: {}), Hit: {}ms (showing: {})",
                //    killElapsed, showingKill, hitElapsed, showingHit);
        //}

        if (showingKill) {
          //  if (DEBUG_MODE) HitMarkerMod.logger.info("[DEBUG] Rendering KILL marker!");
            renderKillMarker(event, killElapsed);
        } else if (showingHit) {
          //  if (DEBUG_MODE) HitMarkerMod.logger.info("[DEBUG] Rendering hit marker!");
            renderNormalHitMarker(event, hitElapsed);
        }
    }

    private void renderNormalHitMarker(RenderGameOverlayEvent event, long elapsed) {
        // 获取屏幕分辨率
        ScaledResolution res = new ScaledResolution(Minecraft.getMinecraft());
        int centerX = res.getScaledWidth() / 2;
        int centerY = res.getScaledHeight() / 2;

        // 保存当前OpenGL状态
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();

        try {
            // 绘制白色普通命中指示器
            renderMarker(centerX, centerY, elapsed, DURATION, false);

        } finally {
            // 恢复OpenGL状态
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private void renderKillMarker(RenderGameOverlayEvent event, long elapsed) {
        // 获取屏幕分辨率
        ScaledResolution res = new ScaledResolution(Minecraft.getMinecraft());
        int centerX = res.getScaledWidth() / 2;
        int centerY = res.getScaledHeight() / 2;

        // 保存当前OpenGL状态
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();

        try {
            // 绘制红色击杀指示器
            renderMarker(centerX, centerY, elapsed, KILL_DURATION, true);

        } finally {
            // 恢复OpenGL状态
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private void renderMarker(int centerX, int centerY, long elapsed, long duration, boolean isKill) {
        // 从 config 读取参数
        float lineLength = isKill ? HitMarkerMod.config.killSize : HitMarkerMod.config.hitSize;
        float lineThickness = lineLength * 0.35f;
        float gapSize = isKill ? lineLength * 0.35f : lineLength * 0.7f;
        float taperLength = lineLength * 0.25f;

        // 计算透明度 — 基础衰减 × config alpha
        float configAlpha = isKill ? HitMarkerMod.config.killAlpha : HitMarkerMod.config.hitAlpha;
        float alpha = (1.0f - (float)elapsed / duration) * configAlpha;

        // 新增：击杀时向外平移30%的距离
        float translateDistance = isKill ? lineLength * 0.3f : 0.0f;

        // 设置OpenGL状态
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // 启用抗锯齿以获得更平滑的线条
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);

        // 设置颜色 — 从 config 读取 RGB 值
        float r, g, b;
        if (isKill) {
            r = HitMarkerMod.config.killColorR / 255.0f;
            g = HitMarkerMod.config.killColorG / 255.0f;
            b = HitMarkerMod.config.killColorB / 255.0f;
        } else {
            r = HitMarkerMod.config.hitColorR / 255.0f;
            g = HitMarkerMod.config.hitColorG / 255.0f;
            b = HitMarkerMod.config.hitColorB / 255.0f;
        }
        GL11.glColor4f(r, g, b, alpha);

        // 绘制四个方向的线条
        GL11.glLineWidth(lineThickness);

        // 计算45度方向的分量
        float dir45X = 0.7071f;  // cos(45°)
        float dir45Y = 0.7071f;  // sin(45°)

        // 绘制45度方向的四条线条，传递平移距离
        drawDirectionalLine(centerX, centerY, dir45X, -dir45Y, gapSize, lineLength, taperLength, translateDistance); // 右上
        drawDirectionalLine(centerX, centerY, dir45X, dir45Y, gapSize, lineLength, taperLength, translateDistance);   // 右下
        drawDirectionalLine(centerX, centerY, -dir45X, dir45Y, gapSize, lineLength, taperLength, translateDistance);  // 左下
        drawDirectionalLine(centerX, centerY, -dir45X, -dir45Y, gapSize, lineLength, taperLength, translateDistance); // 左上

        if (DEBUG_MODE) {
            String markerType = isKill ? "KILL" : "HIT";
            HitMarkerMod.logger.info("[DEBUG] Rendered {} marker at ({}, {}) size={} alpha={} rgb=({},{},{})", 
                    markerType, centerX, centerY, lineLength, alpha, 
                    isKill ? HitMarkerMod.config.killColorR : HitMarkerMod.config.hitColorR,
                    isKill ? HitMarkerMod.config.killColorG : HitMarkerMod.config.hitColorG,
                    isKill ? HitMarkerMod.config.killColorB : HitMarkerMod.config.hitColorB);
        }
    }

    /**
    /**
     * 绘制一个方向的线条（新增平移参数）
     * @param centerX 中心点X坐标
     * @param centerY 中心点Y坐标
     * @param dirX 方向X分量（-1, 0, 1）
     * @param dirY 方向Y分量（-1, 0, 1）
     * @param gap 中心间隙距离
     * @param length 线条总长度
     * @param taper 线条末端锥形长度
     * @param translateDistance 平移距离（击杀时使用）
     */
    private void drawDirectionalLine(float centerX, float centerY, float dirX, float dirY, float gap, float length, float taper, float translateDistance) {
        // 计算平移偏移量：沿着线条方向移动[1,4](@ref)
        float offsetX = dirX * translateDistance;
        float offsetY = dirY * translateDistance;

        // 计算线条起始点和结束点（应用平移）
        float startX = centerX + dirX * gap + offsetX;
        float startY = centerY + dirY * gap + offsetY;
        float endX = centerX + dirX * (gap + length) + offsetX;
        float endY = centerY + dirY * (gap + length) + offsetY;

        // 使用GL_LINE_STRIP绘制带有轻微锥形的线条
        GL11.glBegin(GL11.GL_LINE_STRIP);

        // 线条起始点
        GL11.glVertex2f(startX, startY);

        // 线条中间点（轻微弯曲，模拟圆角）
        float midX = startX + dirX * (length - taper) * 0.5f;
        float midY = startY + dirY * (length - taper) * 0.5f;
        GL11.glVertex2f(midX, midY);

        // 线条结束点
        GL11.glVertex2f(endX, endY);

        GL11.glEnd();
    }
}
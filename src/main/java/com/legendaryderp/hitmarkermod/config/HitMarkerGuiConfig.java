package com.legendaryderp.hitmarkermod.config;

import com.legendaryderp.hitmarkermod.HitMarkerMod;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.GuiConfigEntries;
import net.minecraftforge.fml.client.config.IConfigElement;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

public class HitMarkerGuiConfig extends GuiConfig {

    public HitMarkerGuiConfig(GuiScreen parent) {
        super(
            parent,
            buildConfigElements(),
            HitMarkerMod.MODID,
            false,
            false,
            "Hit Marker Mod - Configuration"
        );
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        if (HitMarkerMod.config != null) {
            HitMarkerMod.config.save();
            HitMarkerMod.config.reloadFromConfig();
        }
    }

    private static List<IConfigElement> buildConfigElements() {
        List<IConfigElement> elements = new ArrayList<IConfigElement>();

        if (HitMarkerMod.config == null || HitMarkerMod.config.config == null) {
            return elements;
        }

        // 清理旧的 hex 颜色字段
        if (HitMarkerMod.config.config.getCategory("visual").containsKey("hitColor")) {
            HitMarkerMod.config.config.getCategory("visual").remove("hitColor");
            HitMarkerMod.config.config.getCategory("visual").remove("killColor");
        }

        elements.add(new ConfigElement(HitMarkerMod.config.config.getCategory("audio")));
        elements.add(new VisualEntryElement(HitMarkerMod.config.config.getCategory("visual")));
        elements.add(new ConfigElement(HitMarkerMod.config.config.getCategory("effects")));
        elements.add(new ConfigElement(HitMarkerMod.config.config.getCategory(Configuration.CATEGORY_GENERAL)));

        return elements;
    }

    // ── VisualEntryElement → 用自定义 CategoryEntry 劫持点击 ──
    public static class VisualEntryElement extends ConfigElement {

        public VisualEntryElement(net.minecraftforge.common.config.ConfigCategory category) {
            super(category);
        }

        @Override
        public Class<? extends GuiConfigEntries.IConfigEntry> getConfigEntryClass() {
            return VisualCategoryEntry.class;
        }
    }

    // ── VisualCategoryEntry → buildChildScreen() 返回自定义预览页 ──
    public static class VisualCategoryEntry extends GuiConfigEntries.CategoryEntry {

        public VisualCategoryEntry(GuiConfig owningScreen, GuiConfigEntries owningEntryList,
                                   IConfigElement configElement) {
            super(owningScreen, owningEntryList, configElement);
        }

        @Override
        protected GuiScreen buildChildScreen() {
            return new HitMarkerVisualConfig(this.owningScreen);
        }
    }

    // ── HitMarkerVisualConfig → visual 子页面 + 预览盘 ──
    public static class HitMarkerVisualConfig extends GuiConfig {

        public HitMarkerVisualConfig(GuiScreen parent) {
            super(
                parent,
                new ConfigElement(HitMarkerMod.config.config.getCategory("visual")).getChildElements(),
                HitMarkerMod.MODID,
                false,
                false,
                "Visual Settings"
            );
        }

        @Override
        public void onGuiClosed() {
            super.onGuiClosed();
            if (HitMarkerMod.config != null) {
                HitMarkerMod.config.save();
                HitMarkerMod.config.reloadFromConfig();
            }
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            super.drawScreen(mouseX, mouseY, partialTicks);

            int hitR = getLiveInt("hitColorR", 255);
            int hitG = getLiveInt("hitColorG", 255);
            int hitB = getLiveInt("hitColorB", 255);
            int killR = getLiveInt("killColorR", 255);
            int killG = getLiveInt("killColorG", 0);
            int killB = getLiveInt("killColorB", 0);

            int px = this.width / 2 + 160;
            int py = this.height / 6 + 20;
            int pw = 80;
            int sw = 34;

            // hit
            drawRect(px - 4, py - 4, px + pw + 4, py + 82, 0xBB000000);
            this.fontRendererObj.drawString("\u00a7fHit Color",
                    px + (pw - fontRendererObj.getStringWidth("Hit Color")) / 2, py, 0xFFFFFF);
            drawSwatch(px + (pw - sw) / 2, py + 14, sw, hitR, hitG, hitB);
            String s1 = "\u00a77R:" + hitR + " G:" + hitG + " B:" + hitB;
            drawCenteredString(fontRendererObj, s1, px + pw / 2, py + 50, 0xAAAAAA);

            // kill
            py += 92;
            drawRect(px - 4, py - 4, px + pw + 4, py + 82, 0xBB000000);
            this.fontRendererObj.drawString("\u00a7cKill Color",
                    px + (pw - fontRendererObj.getStringWidth("Kill Color")) / 2, py, 0xFF5555);
            drawSwatch(px + (pw - sw) / 2, py + 14, sw, killR, killG, killB);
            String s2 = "\u00a77R:" + killR + " G:" + killG + " B:" + killB;
            drawCenteredString(fontRendererObj, s2, px + pw / 2, py + 50, 0xAAAAAA);
        }

        private int getLiveInt(String name, int def) {
            if (this.entryList == null || this.entryList.listEntries == null) return def;
            for (GuiConfigEntries.IConfigEntry entry : this.entryList.listEntries) {
                try {
                    if (entry.getConfigElement() != null
                            && name.equals(entry.getConfigElement().getName())) {
                        return Integer.parseInt(String.valueOf(entry.getCurrentValue()));
                    }
                } catch (Exception ignored) {}
            }
            return def;
        }

        private void drawSwatch(int x, int y, int size, int r, int g, int b) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(r / 255.0f, g / 255.0f, b / 255.0f, 1.0f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2i(x, y);
            GL11.glVertex2i(x + size, y);
            GL11.glVertex2i(x + size, y + size);
            GL11.glVertex2i(x, y + size);
            GL11.glEnd();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }
    }
}

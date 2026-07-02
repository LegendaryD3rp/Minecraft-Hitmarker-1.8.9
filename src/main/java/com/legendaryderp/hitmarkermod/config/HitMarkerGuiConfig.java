package com.legendaryderp.hitmarkermod.config;

import com.legendaryderp.hitmarkermod.HitMarkerMod;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.GuiConfigEntries;
import net.minecraftforge.fml.client.config.GuiConfigEntries.NumberSliderEntry;
import net.minecraftforge.fml.client.config.IConfigElement;
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
            HitMarkerMod.config.reloadFromConfig();
            HitMarkerMod.config.save();
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
        elements.add(new BorderEntryElement(HitMarkerMod.config.config.getCategory("border")));
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

    // ── SliderConfigElement → 强制 min=0, max=255 的 NumberSliderEntry ──
    public static class SliderConfigElement extends ConfigElement {
        public SliderConfigElement(Property prop) {
            super(prop);
        }

        @Override
        public Class<? extends GuiConfigEntries.IConfigEntry> getConfigEntryClass() {
            return NumberSliderEntry.class;
        }

        @Override
        public Object getMinValue() {
            return "0";
        }

        @Override
        public Object getMaxValue() {
            return "255";
        }
    }

    // ── HitMarkerVisualConfig → visual 子页面，R/G/B 用原生滑块，右侧预览盘 ──
    public static class HitMarkerVisualConfig extends GuiConfig {

        public HitMarkerVisualConfig(GuiScreen parent) {
            super(parent, buildVisualElements(), HitMarkerMod.MODID, false, false, "Visual Settings");
        }

        private static List<IConfigElement> buildVisualElements() {
            List<IConfigElement> list = new ArrayList<IConfigElement>();
            if (HitMarkerMod.config == null || HitMarkerMod.config.config == null) return list;
            net.minecraftforge.common.config.ConfigCategory cat = HitMarkerMod.config.config.getCategory("visual");
            for (String key : cat.keySet()) {
                Property prop = cat.get(key);
                if (key.endsWith("R") || key.endsWith("G") || key.endsWith("B")) {
                    list.add(new SliderConfigElement(prop));
                } else {
                    list.add(new ConfigElement(prop));
                }
            }
            return list;
        }

        @Override
        public void onGuiClosed() {
            super.onGuiClosed(); // NumberSliderEntry 已通过 GuiSlider 写回 Property
            if (HitMarkerMod.config != null) {
                HitMarkerMod.config.reloadFromConfig(); // Properties → Java 字段
                HitMarkerMod.config.save();             // Java 字段 → 磁盘
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
            int hitColor = 0xFF000000 | ((hitR & 0xFF) << 16) | ((hitG & 0xFF) << 8) | (hitB & 0xFF);
            drawRect(px + (pw - sw) / 2, py + 14, px + (pw + sw) / 2, py + 14 + sw, hitColor);
            drawRect(px + (pw - sw) / 2 - 1, py + 14 - 1, px + (pw + sw) / 2 + 1, py + 14, 0xFFAAAAAA);
            drawRect(px + (pw - sw) / 2 - 1, py + 14 + sw, px + (pw + sw) / 2 + 1, py + 14 + sw + 1, 0xFFAAAAAA);
            drawRect(px + (pw - sw) / 2 - 1, py + 14, px + (pw - sw) / 2, py + 14 + sw, 0xFFAAAAAA);
            drawRect(px + (pw + sw) / 2, py + 14, px + (pw + sw) / 2 + 1, py + 14 + sw, 0xFFAAAAAA);
            String s1 = "\u00a77R:" + hitR + " G:" + hitG + " B:" + hitB;
            drawCenteredString(fontRendererObj, s1, px + pw / 2, py + 50, 0xAAAAAA);

            // kill
            py += 92;
            drawRect(px - 4, py - 4, px + pw + 4, py + 82, 0xBB000000);
            this.fontRendererObj.drawString("\u00a7cKill Color",
                    px + (pw - fontRendererObj.getStringWidth("Kill Color")) / 2, py, 0xFF5555);
            int killColor = 0xFF000000 | ((killR & 0xFF) << 16) | ((killG & 0xFF) << 8) | (killB & 0xFF);
            drawRect(px + (pw - sw) / 2, py + 14, px + (pw + sw) / 2, py + 14 + sw, killColor);
            drawRect(px + (pw - sw) / 2 - 1, py + 14 - 1, px + (pw + sw) / 2 + 1, py + 14, 0xFFAAAAAA);
            drawRect(px + (pw - sw) / 2 - 1, py + 14 + sw, px + (pw + sw) / 2 + 1, py + 14 + sw + 1, 0xFFAAAAAA);
            drawRect(px + (pw - sw) / 2 - 1, py + 14, px + (pw - sw) / 2, py + 14 + sw, 0xFFAAAAAA);
            drawRect(px + (pw + sw) / 2, py + 14, px + (pw + sw) / 2 + 1, py + 14 + sw, 0xFFAAAAAA);
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

    }

    // ════════════════════════════════════════════
    //  Border — 子页面含 R/G/B 滚轮滑块 + 颜色预览
    // ════════════════════════════════════════════
    public static class BorderEntryElement extends ConfigElement {
        public BorderEntryElement(net.minecraftforge.common.config.ConfigCategory category) {
            super(category);
        }

        @Override
        public Class<? extends GuiConfigEntries.IConfigEntry> getConfigEntryClass() {
            return BorderCategoryEntry.class;
        }
    }

    public static class BorderCategoryEntry extends GuiConfigEntries.CategoryEntry {
        public BorderCategoryEntry(GuiConfig owningScreen, GuiConfigEntries owningEntryList,
                                   IConfigElement configElement) {
            super(owningScreen, owningEntryList, configElement);
        }

        @Override
        protected GuiScreen buildChildScreen() {
            return new HitMarkerBorderConfig(this.owningScreen);
        }
    }

    public static class HitMarkerBorderConfig extends GuiConfig {
        public HitMarkerBorderConfig(GuiScreen parent) {
            super(parent, buildBorderElements(), HitMarkerMod.MODID, false, false, "Border Settings");
        }

        private static List<IConfigElement> buildBorderElements() {
            List<IConfigElement> list = new ArrayList<>();
            if (HitMarkerMod.config == null || HitMarkerMod.config.config == null) return list;
            net.minecraftforge.common.config.ConfigCategory cat = HitMarkerMod.config.config.getCategory("border");
            for (String key : cat.keySet()) {
                Property prop = cat.get(key);
                if (key.endsWith("R") || key.endsWith("G") || key.endsWith("B")) {
                    list.add(new SliderConfigElement(prop));
                } else {
                    list.add(new ConfigElement(prop));
                }
            }
            return list;
        }

        @Override
        public void onGuiClosed() {
            super.onGuiClosed();
            if (HitMarkerMod.config != null) {
                HitMarkerMod.config.reloadFromConfig();
                HitMarkerMod.config.save();
            }
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            super.drawScreen(mouseX, mouseY, partialTicks);

            int bR = getLiveInt("borderColorR", 0);
            int bG = getLiveInt("borderColorG", 0);
            int bB = getLiveInt("borderColorB", 0);
            int kR = getLiveInt("killBorderColorR", 0);
            int kG = getLiveInt("killBorderColorG", 0);
            int kB = getLiveInt("killBorderColorB", 0);

            int px = this.width / 2 + 160;
            int py = this.height / 6 + 20;
            int pw = 80;
            int sw = 34;

            // border color preview
            drawRect(px - 4, py - 4, px + pw + 4, py + 82, 0xBB000000);
            this.fontRendererObj.drawString("\u00a7fBorder Color",
                    px + (pw - fontRendererObj.getStringWidth("Border Color")) / 2, py, 0xFFFFFF);
            int bColor = 0xFF000000 | ((bR & 0xFF) << 16) | ((bG & 0xFF) << 8) | (bB & 0xFF);
            drawRect(px + (pw - sw) / 2, py + 14, px + (pw + sw) / 2, py + 14 + sw, bColor);
            drawRect(px + (pw - sw) / 2 - 1, py + 14 - 1, px + (pw + sw) / 2 + 1, py + 14, 0xFFAAAAAA);
            drawRect(px + (pw - sw) / 2 - 1, py + 14 + sw, px + (pw + sw) / 2 + 1, py + 14 + sw + 1, 0xFFAAAAAA);
            drawRect(px + (pw - sw) / 2 - 1, py + 14, px + (pw - sw) / 2, py + 14 + sw, 0xFFAAAAAA);
            drawRect(px + (pw + sw) / 2, py + 14, px + (pw + sw) / 2 + 1, py + 14 + sw, 0xFFAAAAAA);
            String s1 = "\u00a77R:" + bR + " G:" + bG + " B:" + bB;
            drawCenteredString(fontRendererObj, s1, px + pw / 2, py + 50, 0xAAAAAA);

            // kill border preview
            py += 92;
            drawRect(px - 4, py - 4, px + pw + 4, py + 82, 0xBB000000);
            this.fontRendererObj.drawString("\u00a7cKill Border",
                    px + (pw - fontRendererObj.getStringWidth("Kill Border")) / 2, py, 0xFF5555);
            int kColor = 0xFF000000 | ((kR & 0xFF) << 16) | ((kG & 0xFF) << 8) | (kB & 0xFF);
            drawRect(px + (pw - sw) / 2, py + 14, px + (pw + sw) / 2, py + 14 + sw, kColor);
            drawRect(px + (pw - sw) / 2 - 1, py + 14 - 1, px + (pw + sw) / 2 + 1, py + 14, 0xFFAAAAAA);
            drawRect(px + (pw - sw) / 2 - 1, py + 14 + sw, px + (pw + sw) / 2 + 1, py + 14 + sw + 1, 0xFFAAAAAA);
            drawRect(px + (pw - sw) / 2 - 1, py + 14, px + (pw - sw) / 2, py + 14 + sw, 0xFFAAAAAA);
            drawRect(px + (pw + sw) / 2, py + 14, px + (pw + sw) / 2 + 1, py + 14 + sw, 0xFFAAAAAA);
            String s2 = "\u00a77R:" + kR + " G:" + kG + " B:" + kB;
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
    }
}

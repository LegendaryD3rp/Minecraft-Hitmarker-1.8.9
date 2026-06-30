package com.legendaryderp.hitmarkermod;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.File;

@SideOnly(Side.CLIENT)
public class HitMarkerConfig {

    public Configuration config;

    // ── general ──
    public boolean enableChatKillDetection = true;
    public boolean cancelDeathMessages = false;

    // ── audio ──
    public boolean enableHitSounds = true;
    public boolean enableKillSound = true;
    public float soundVolume = 1.0f;

    // ── visual ──
    public float hitAlpha = 1.0f;
    public int hitColorR = 255;
    public int hitColorG = 255;
    public int hitColorB = 255;
    public float hitSize = 8.0f;

    public float killAlpha = 1.0f;
    public int killColorR = 255;
    public int killColorG = 0;
    public int killColorB = 0;
    public float killSize = 12.0f;

    // ── border ──
    public boolean enableBorder = false;
    public float borderWidth = 1.5f;
    public int borderColorR = 0;
    public int borderColorG = 0;
    public int borderColorB = 0;
    public int killBorderColorR = 0;
    public int killBorderColorG = 0;
    public int killBorderColorB = 0;

    // ── effects ──
    public boolean enableHitBlood = true;
    public float hitBloodIntensity = 0.3f;
    public boolean enableKillBlood = true;
    public float killBloodIntensity = 0.5f;

    // ── damage_numbers ──
    public boolean enableDamageNumbers = true;
    public int dmgNumColorR = 255;
    public int dmgNumColorG = 255;
    public int dmgNumColorB = 255;
    public float dmgNumScale = 1.5f;
    public int dmgNumDuration = 30;           // ticks
    public float dmgNumFloatSpeed = 0.5f;     // 每 tick 上移像素
    public int dmgNumXOffset = 0;
    public int dmgNumYOffset = -20;            // 准星上方 20px

    // ── constructor ──
    public HitMarkerConfig(File configFile) {
        config = new Configuration(configFile);
        loadConfig();
    }

    public void loadConfig() {
        config.load();

        // 清理旧的 hex 颜色字段（已替换为 RGB int）
        if (config.getCategory("visual").containsKey("hitColor")) {
            config.getCategory("visual").remove("hitColor");
            config.getCategory("visual").remove("killColor");
        }

        reloadFromConfig();

        if (config.hasChanged()) {
            config.save();
        }
    }

    public void reloadFromConfig() {
        // ── general (CATEGORY_GENERAL) ──
        Property p = config.get(Configuration.CATEGORY_GENERAL, "enableChatKillDetection", true);
        p.comment = "Enable kill detection via chat messages for multiplayer compatibility";
        enableChatKillDetection = p.getBoolean();

        p = config.get(Configuration.CATEGORY_GENERAL, "cancelDeathMessages", false);
        p.comment = "Cancel vanilla death messages";
        cancelDeathMessages = p.getBoolean();

        // ── audio ──
        p = config.get("audio", "enableHitSounds", true);
        p.comment = "Play a random hit sound when you hit an enemy";
        enableHitSounds = p.getBoolean();

        p = config.get("audio", "enableKillSound", true);
        p.comment = "Play the kill sound when you kill an enemy";
        enableKillSound = p.getBoolean();

        p = config.get("audio", "soundVolume", 1.0f);
        p.comment = "Master volume for hit / kill sounds (0.0 - 1.0)";
        p.setMinValue(0.0).setMaxValue(1.0);
        soundVolume = (float) p.getDouble();

        // ── visual ──
        p = config.get("visual", "hitAlpha", 1.0f);
        p.comment = "Alpha (opacity) of the hit marker (0.0 - 1.0)";
        p.setMinValue(0.0).setMaxValue(1.0);
        hitAlpha = (float) p.getDouble();

        p = config.get("visual", "hitColorR", 255);
        p.comment = "Hit marker color — Red (0-255)";
        p.setMinValue(0).setMaxValue(255);
        hitColorR = p.getInt();

        p = config.get("visual", "hitColorG", 255);
        p.comment = "Hit marker color — Green (0-255)";
        p.setMinValue(0).setMaxValue(255);
        hitColorG = p.getInt();

        p = config.get("visual", "hitColorB", 255);
        p.comment = "Hit marker color — Blue (0-255)";
        p.setMinValue(0).setMaxValue(255);
        hitColorB = p.getInt();

        p = config.get("visual", "hitSize", 8.0f);
        p.comment = "Size of the hit marker lines in GUI pixels";
        p.setMinValue(2.0).setMaxValue(48.0);
        hitSize = (float) p.getDouble();

        p = config.get("visual", "killAlpha", 1.0f);
        p.comment = "Alpha (opacity) of the kill marker (0.0 - 1.0)";
        p.setMinValue(0.0).setMaxValue(1.0);
        killAlpha = (float) p.getDouble();

        p = config.get("visual", "killColorR", 255);
        p.comment = "Kill marker color — Red (0-255)";
        p.setMinValue(0).setMaxValue(255);
        killColorR = p.getInt();

        p = config.get("visual", "killColorG", 0);
        p.comment = "Kill marker color — Green (0-255)";
        p.setMinValue(0).setMaxValue(255);
        killColorG = p.getInt();

        p = config.get("visual", "killColorB", 0);
        p.comment = "Kill marker color — Blue (0-255)";
        p.setMinValue(0).setMaxValue(255);
        killColorB = p.getInt();

        p = config.get("visual", "killSize", 12.0f);
        p.comment = "Size of the kill marker lines in GUI pixels";
        p.setMinValue(2.0).setMaxValue(48.0);
        killSize = (float) p.getDouble();

        // ── border ──
        p = config.get("border", "enableBorder", false);
        p.comment = "Enable border/outline around hit markers";
        enableBorder = p.getBoolean();

        p = config.get("border", "borderWidth", 1.5);
        p.comment = "Border thickness in pixels";
        p.setMinValue(0.0).setMaxValue(10.0);
        borderWidth = (float) p.getDouble();

        p = config.get("border", "borderColorR", 0);
        p.comment = "Border color - Red (0-255)";
        p.setMinValue(0).setMaxValue(255);
        borderColorR = p.getInt();

        p = config.get("border", "borderColorG", 0);
        p.comment = "Border color - Green (0-255)";
        p.setMinValue(0).setMaxValue(255);
        borderColorG = p.getInt();

        p = config.get("border", "borderColorB", 0);
        p.comment = "Border color - Blue (0-255)";
        p.setMinValue(0).setMaxValue(255);
        borderColorB = p.getInt();

        p = config.get("border", "killBorderColorR", 0);
        p.comment = "Kill border color - Red (0-255)";
        p.setMinValue(0).setMaxValue(255);
        killBorderColorR = p.getInt();

        p = config.get("border", "killBorderColorG", 0);
        p.comment = "Kill border color - Green (0-255)";
        p.setMinValue(0).setMaxValue(255);
        killBorderColorG = p.getInt();

        p = config.get("border", "killBorderColorB", 0);
        p.comment = "Kill border color - Blue (0-255)";
        p.setMinValue(0).setMaxValue(255);
        killBorderColorB = p.getInt();

        // ── effects ──
        p = config.get("effects", "enableHitBlood", true);
        p.comment = "Show blood particles when hitting an enemy";
        enableHitBlood = p.getBoolean();

        p = config.get("effects", "hitBloodIntensity", 0.3f);
        p.comment = "Intensity of hit blood particles (0.0 - 1.0)";
        p.setMinValue(0.0).setMaxValue(1.0);
        hitBloodIntensity = (float) p.getDouble();

        p = config.get("effects", "enableKillBlood", true);
        p.comment = "Show blood particles when killing an enemy";
        enableKillBlood = p.getBoolean();

        p = config.get("effects", "killBloodIntensity", 0.5f);
        p.comment = "Intensity of kill blood particles (0.0 - 1.0)";
        p.setMinValue(0.0).setMaxValue(1.0);
        killBloodIntensity = (float) p.getDouble();

        // ── damage_numbers ──
        p = config.get("damage_numbers", "enableDamageNumbers", true);
        p.comment = "Show floating damage numbers on hit";
        enableDamageNumbers = p.getBoolean();

        p = config.get("damage_numbers", "dmgNumColorR", 255);
        p.comment = "Damage number red (0-255)";
        p.setMinValue(0).setMaxValue(255);
        dmgNumColorR = p.getInt();

        p = config.get("damage_numbers", "dmgNumColorG", 255);
        p.comment = "Damage number green (0-255)";
        p.setMinValue(0).setMaxValue(255);
        dmgNumColorG = p.getInt();

        p = config.get("damage_numbers", "dmgNumColorB", 255);
        p.comment = "Damage number blue (0-255)";
        p.setMinValue(0).setMaxValue(255);
        dmgNumColorB = p.getInt();

        p = config.get("damage_numbers", "dmgNumScale", 1.5f);
        p.comment = "Damage number text scale (0.5 - 5.0)";
        p.setMinValue(0.5).setMaxValue(5.0);
        dmgNumScale = (float) p.getDouble();

        p = config.get("damage_numbers", "dmgNumDuration", 30);
        p.comment = "Damage number lifetime in ticks (5 - 100)";
        p.setMinValue(5).setMaxValue(100);
        dmgNumDuration = p.getInt();

        p = config.get("damage_numbers", "dmgNumFloatSpeed", 0.5f);
        p.comment = "Damage number upward float speed (0.0 - 5.0)";
        p.setMinValue(0.0).setMaxValue(5.0);
        dmgNumFloatSpeed = (float) p.getDouble();

        p = config.get("damage_numbers", "dmgNumXOffset", 0);
        p.comment = "Damage number X offset from center";
        dmgNumXOffset = p.getInt();

        p = config.get("damage_numbers", "dmgNumYOffset", -20);
        p.comment = "Damage number Y offset from center (negative=up)";
        dmgNumYOffset = p.getInt();
    }

    public void save() {
        if (config.hasChanged()) {
            config.save();
        }
    }
}

package com.legendaryderp.hitmarkermod;

import com.legendaryderp.hitmarkermod.client.CommonEventHandler;
import com.legendaryderp.hitmarkermod.client.HitMarkerRenderer;
import com.legendaryderp.hitmarkermod.client.KillChatListener;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.logging.log4j.Logger;

import java.io.File;

@Mod(modid = HitMarkerMod.MODID, name = HitMarkerMod.NAME, version = HitMarkerMod.VERSION,
        acceptedMinecraftVersions = "[1.8.9]",
        guiFactory = "com.legendaryderp.hitmarkermod.config.ModGuiFactory")
public class HitMarkerMod {
    public static final String MODID = "hitmarkermod";
    public static final String NAME = "Hit Marker Mod";
    public static final String VERSION = "3.0.0";

    public static Logger logger;

    public static final String HIT1_SOUND = "hitmarkermod:hit1";
    public static final String HIT2_SOUND = "hitmarkermod:hit2";
    public static final String HIT3_SOUND = "hitmarkermod:hit3";
    public static final String KILL_SOUND = "hitmarkermod:kill";

    public static HitMarkerConfig config;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        logger.info("Initializing Hit Marker Mod v{} for Minecraft 1.8.9", VERSION);

        File configFile = new File(event.getModConfigurationDirectory(), "hitmarkermod.cfg");
        config = new HitMarkerConfig(configFile);

        logger.info("Configuration loaded - general: chatKill={}, cancelDeath={}",
                config.enableChatKillDetection, config.cancelDeathMessages);
        logger.info("Configuration loaded - audio: hitSounds={}, killSound={}, volume={}",
                config.enableHitSounds, config.enableKillSound, config.soundVolume);
        logger.info("Configuration loaded - visual: hit(R={},G={},B={},alpha={},size={}), kill(R={},G={},B={},alpha={},size={})",
                config.hitColorR, config.hitColorG, config.hitColorB, config.hitAlpha, config.hitSize,
                config.killColorR, config.killColorG, config.killColorB, config.killAlpha, config.killSize);
        logger.info("Configuration loaded - effects: hitBlood={}/{}, killBlood={}/{}",
                config.enableHitBlood, config.hitBloodIntensity,
                config.enableKillBlood, config.killBloodIntensity);
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new CommonEventHandler());
        MinecraftForge.EVENT_BUS.register(new HitMarkerRenderer());
        MinecraftForge.EVENT_BUS.register(new KillChatListener());
        MinecraftForge.EVENT_BUS.register(this);

        logger.info("Hit Marker Mod v{} initialized successfully", VERSION);
    }

    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (MODID.equals(event.modID) && config != null) {
            config.reloadFromConfig();
            logger.info("Configuration reloaded via ConfigChangedEvent");
        }
    }
}

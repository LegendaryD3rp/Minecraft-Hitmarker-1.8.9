package com.legendaryderp.hitmarkermod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import com.legendaryderp.hitmarkermod.HitMarkerMod;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;

@SideOnly(Side.CLIENT)
public class KillChatListener {

    private final Random random = new Random();

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        if (event.type != 0) return;

        String rawMessage = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        String clientName = Minecraft.getMinecraft().thePlayer.getName();

        if (clientName == null || clientName.isEmpty()) return;

        // === 1. 最终击杀消息检测 ===
        String lowerMessage = rawMessage.toLowerCase();
        if (rawMessage.contains("最终击杀") || lowerMessage.contains("final kill")) {
            processFinalKillMessage(rawMessage, clientName);
            return; // 最终击杀消息优先级最高，匹配到就不处理其他消息
        }

        // === 原有的击杀消息检测（保持不变）===

        // 1. 检测中文击杀消息：玩家A被玩家B
        int beiIndex = rawMessage.indexOf("被");
        if (beiIndex != -1) {
            String beforeBei = rawMessage.substring(0, beiIndex);
            String playerA = extractPlayerName(beforeBei, false);

            String afterBei = rawMessage.substring(beiIndex + 1);
            String playerB = extractPlayerName(afterBei, true);

            if (playerB != null && playerA != null &&
                    playerB.equals(clientName) && !playerA.equals(clientName)) {
                triggerKillEffects();
                return;
            }
        }

        // 2. 检测英文击杀消息：玩家A by 玩家B
        int byIndex = lowerMessage.indexOf(" by ");
        if (byIndex != -1) {
            String beforeBy = rawMessage.substring(0, byIndex);
            String playerA = extractPlayerName(beforeBy, false);

            String afterBy = rawMessage.substring(byIndex + 4);
            String playerB = extractPlayerName(afterBy, true);

            if (playerB != null && playerA != null &&
                    playerB.equals(clientName) && !playerA.equals(clientName)) {
                triggerKillEffects();
                return;
            }
        }

        // 3. 检测中文弓箭命中消息：DHking142857还剩19.4生命值！
        int remainIndex = rawMessage.indexOf("还剩");
        int lifeIndex = rawMessage.indexOf("生命值");
        if (remainIndex != -1 && lifeIndex != -1 && remainIndex < lifeIndex) {
            // 提取"还剩"前面的玩家名
            String beforeRemain = rawMessage.substring(0, remainIndex);
            String playerA = extractPlayerName(beforeRemain, false);

            if (playerA != null && !playerA.equals(clientName)) {
                // 验证剩余血量格式 (数字，可能包含小数点)
                String between = rawMessage.substring(remainIndex + 2, lifeIndex).trim();
                if (isValidHpValue(between)) {
                    triggerHitEffects();
                    return;
                }
            }
        }

        // 4. 检测英文弓箭命中消息：DHking142857 is on 16.2 HP!
        int isOnIndex = lowerMessage.indexOf("is on");
        int hpIndex = lowerMessage.indexOf(" hp");

        if (isOnIndex != -1 && hpIndex != -1 && isOnIndex < hpIndex) {
            // 提取"is on"前面的玩家名
            String beforeIsOn = rawMessage.substring(0, isOnIndex);
            String playerA = extractPlayerName(beforeIsOn, false);

            if (playerA != null && !playerA.equals(clientName)) {
                // 验证剩余血量格式 (数字，可能包含小数点)
                String between = rawMessage.substring(isOnIndex + 5, hpIndex).trim();
                if (isValidHpValue(between)) {
                    triggerHitEffects();
                    return;
                }
            }
        }
    }

    /**
     * 处理最终击杀消息
     */
    private void processFinalKillMessage(String message, String clientName) {
        //  HitMarkerMod.logger.info("[FINAL-KILL] Final kill message detected: {}", message);

        // 1. 提取句首玩家A
        String playerA = extractPlayerName(message, true);

        if (playerA == null) {
            //        HitMarkerMod.logger.info("[FINAL-KILL] Could not extract player A from message start");
            return;
        }

        //   HitMarkerMod.logger.info("[FINAL-KILL] Player A (start of sentence): {}", playerA);

        // 2. 检查玩家A是否为当前玩家
        if (playerA.equals(clientName)) {
            //      HitMarkerMod.logger.info("[FINAL-KILL] Player A is the current player, not a final kill for client");
            return;
        }

        // 3. 在整条消息中查找玩家B（当前玩家）
        // 注意：这里不需要跳过玩家A，因为玩家A已经被确认不是当前玩家
        //   HitMarkerMod.logger.info("[FINAL-KILL] Searching for current player '{}' in message", clientName);

        // 4. 在消息中查找当前玩家B
        // 使用更简单的字符串包含检查，而不是正则表达式边界匹配
        if (containsMinecraftName(message, clientName)) {
            // HitMarkerMod.logger.info("[FINAL-KILL] Found current player '{}' in message, triggering final kill effects", clientName);
            triggerFinalKillEffects();
        } else {
            //   HitMarkerMod.logger.info("[FINAL-KILL] Current player '{}' not found in message", clientName);
        }
    }

    /**
     * 检查字符串中是否包含指定的Minecraft玩家名
     * 使用简单的字符串包含检查，避免正则表达式边界问题
     */
    private boolean containsMinecraftName(String text, String playerName) {
        if (text == null || playerName == null || text.isEmpty() || playerName.isEmpty()) {
            return false;
        }

        // 直接使用字符串包含检查
        int index = text.indexOf(playerName);
        if (index == -1) {
            return false;
        }

        // 检查前后字符，确保是完整的玩家名
        int nameLength = playerName.length();
        int textLength = text.length();

        // 检查名称前的字符
        if (index > 0) {
            char beforeChar = text.charAt(index - 1);
            if (isMinecraftNameChar(beforeChar)) {
                // 前一个字符是有效的玩家名字符，说明是部分匹配
                return false;
            }
        }

        // 检查名称后的字符
        if (index + nameLength < textLength) {
            char afterChar = text.charAt(index + nameLength);
            if (isMinecraftNameChar(afterChar)) {
                // 后一个字符是有效的玩家名字符，说明是部分匹配
                return false;
            }
        }

        return true;
    }

    /**
     * 检查字符是否是Minecraft玩家名字符
     */
    private boolean isMinecraftNameChar(char c) {
        return (c >= 'a' && c <= 'z') ||
                (c >= 'A' && c <= 'Z') ||
                (c >= '0' && c <= '9') ||
                c == '_';
    }

    /**
     * 触发最终击杀效果
     */
    private void triggerFinalKillEffects() {
        try {
            //HitMarkerMod.logger.info("[FINAL-KILL] Triggering final kill effects");

            // 显示最终击杀标识
            HitMarkerRenderer.showKillMarker();

            // 播放击杀音效
            playKillSound();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 从字符串中提取玩家名称
     * @param text 原始文本
     * @param fromStart 如果为true，从字符串开头提取；如果为false，从字符串结尾提取
     * @return 提取到的玩家名称，如果未找到则返回null
     */
    private String extractPlayerName(String text, boolean fromStart) {
        if (text == null || text.trim().isEmpty()) return null;

        text = text.trim();
        StringBuilder playerName = new StringBuilder();

        if (fromStart) {
            // 从开头开始匹配
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (isMinecraftNameChar(c)) {
                    playerName.append(c);
                } else if (playerName.length() > 0) {
                    // 遇到非玩家名字符，停止提取
                    break;
                }
            }
        } else {
            // 从结尾开始匹配
            for (int i = text.length() - 1; i >= 0; i--) {
                char c = text.charAt(i);
                if (isMinecraftNameChar(c)) {
                    playerName.insert(0, c);
                } else if (playerName.length() > 0) {
                    // 遇到非玩家名字符，停止提取
                    break;
                }
            }
        }

        String result = playerName.toString();
        return result.length() >= 3 && result.length() <= 16 ? result : null;
    }

    /**
     * 验证HP值是否有效（数字，可能包含小数点）
     */
    private boolean isValidHpValue(String hpStr) {
        if (hpStr == null || hpStr.trim().isEmpty()) {
            return false;
        }

        String trimmed = hpStr.trim();

        // 移除可能存在的感叹号或其他标点
        if (trimmed.endsWith("!")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        try {
            // 尝试解析为浮点数
            Float.parseFloat(trimmed);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // 原有的方法保持不变
    private void triggerKillEffects() {
        try {
            HitMarkerRenderer.showKillMarker();
            playKillSound();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void triggerHitEffects() {
        try {
            HitMarkerRenderer.showHitMarker();
            playRandomHitSound();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void playRandomHitSound() {
        if (!HitMarkerMod.config.enableHitSounds) return;
        try {
            String[] hitSounds = {HitMarkerMod.HIT1_SOUND, HitMarkerMod.HIT2_SOUND, HitMarkerMod.HIT3_SOUND};
            String sound = hitSounds[random.nextInt(hitSounds.length)];
            Minecraft.getMinecraft().thePlayer.playSound(sound, HitMarkerMod.config.soundVolume, 1.0F);
        } catch (Exception e) {
            //  HitMarkerMod.logger.error("[KILL-CHAT] Hit sound playback failed: {}", e.getMessage());
        }
    }

    private void playKillSound() {
        if (!HitMarkerMod.config.enableKillSound) return;
        try {
            Minecraft.getMinecraft().thePlayer.playSound(HitMarkerMod.KILL_SOUND, HitMarkerMod.config.soundVolume, 1.0F);
        } catch (Exception e) {
            //  HitMarkerMod.logger.error("[KILL-CHAT] Kill sound playback failed: {}", e.getMessage());
        }
    }
}
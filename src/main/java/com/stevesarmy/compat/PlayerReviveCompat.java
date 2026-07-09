package com.stevesarmy.compat;

import com.stevesarmy.StevesArmyMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class PlayerReviveCompat {

    private static final String MOD_ID = "playerrevive";
    private static Boolean loaded = null;
    private static Capability<?> bleedingCapability;
    private static Method isBleedingMethod;
    private static Field progressField;
    private static float requiredReviveProgress = 100.0f;
    private static float progressPerPlayer = 1.0f;
    private static boolean reflectionFailed = false;

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded(MOD_ID);
            if (loaded) {
                initReflection();
            }
        }
        return loaded && !reflectionFailed;
    }

    private static void initReflection() {
        try {
            Class<?> playerReviveClass = Class.forName("team.creative.playerrevive.PlayerRevive");
            Field bleedingField = playerReviveClass.getField("BLEEDING");
            bleedingCapability = (Capability<?>) bleedingField.get(null);

            Class<?> bleedingInterface = Class.forName("team.creative.playerrevive.api.IBleeding");
            isBleedingMethod = bleedingInterface.getMethod("isBleeding");

            Class<?> bleedingImpl = Class.forName("team.creative.playerrevive.cap.Bleeding");
            progressField = bleedingImpl.getDeclaredField("progress");
            progressField.setAccessible(true);

            Field configField = playerReviveClass.getField("CONFIG");
            Object config = configField.get(null);
            Field reviveConfigField = config.getClass().getField("revive");
            Object reviveConfig = reviveConfigField.get(config);
            
            Field requiredProgressField = reviveConfig.getClass().getField("requiredReviveProgress");
            requiredReviveProgress = requiredProgressField.getFloat(reviveConfig);
            
            Field progressPerPlayerField = reviveConfig.getClass().getField("progressPerPlayer");
            progressPerPlayer = progressPerPlayerField.getFloat(reviveConfig);

            StevesArmyMod.LOGGER.info("[PlayerReviveCompat] Initialized: requiredReviveProgress={}, progressPerPlayer={}", requiredReviveProgress, progressPerPlayer);
        } catch (Exception e) {
            StevesArmyMod.LOGGER.warn("[PlayerReviveCompat] Failed to reflect PlayerRevive classes: {}", e.getMessage());
            reflectionFailed = true;
        }
    }

    public static boolean isPlayerBleeding(Player player) {
        if (!isLoaded() || player == null || bleedingCapability == null) {
            return false;
        }
        try {
            LazyOptional<?> cap = player.getCapability(bleedingCapability);
            Object bleeding = cap.orElse(null);
            if (bleeding == null) return false;
            return (boolean) isBleedingMethod.invoke(bleeding);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean addReviveProgress(Player player, float amount) {
        if (!isLoaded() || player == null || bleedingCapability == null) return false;
        if (!(player instanceof ServerPlayer serverPlayer)) return false;
        
        try {
            LazyOptional<?> cap = player.getCapability(bleedingCapability);
            Object bleeding = cap.orElse(null);
            if (bleeding == null) return false;

            float currentProgress = progressField.getFloat(bleeding);
            float newProgress = currentProgress + amount;
            progressField.setFloat(bleeding, newProgress);

            if (newProgress >= requiredReviveProgress) {
                var server = serverPlayer.getServer();
                if (server != null) {
                    var commands = server.getCommands();
                    commands.performPrefixedCommand(
                        server.createCommandSourceStack(),
                        "revive " + player.getGameProfile().getName()
                    );
                    StevesArmyMod.LOGGER.info("[PlayerReviveCompat] Player {} revived by soldiers!", player.getName().getString());
                }
            }
            return true;
        } catch (Exception e) {
            StevesArmyMod.LOGGER.warn("[PlayerReviveCompat] Error adding revive progress: {}", e.getMessage());
            return false;
        }
    }

    public static float getProgressPerPlayer() {
        return progressPerPlayer;
    }
}
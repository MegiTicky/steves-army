package com.stevesarmy.compat;

import com.stevesarmy.StevesArmyMod;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

public class PlayerReviveCompat {

    private static final String MOD_ID = "playerrevive";
    private static Boolean loaded = null;
    private static Capability<?> bleedingCapability;
    private static Method isBleedingMethod;
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
            java.lang.reflect.Field bleedingField = playerReviveClass.getField("BLEEDING");
            bleedingCapability = (Capability<?>) bleedingField.get(null);
            
            Class<?> bleedingInterface = Class.forName("team.creative.playerrevive.api.IBleeding");
            isBleedingMethod = bleedingInterface.getMethod("isBleeding");
        } catch (Exception e) {
            StevesArmyMod.LOGGER.warn("[PlayerReviveCompat] Failed to reflect PlayerRevive classes: {}", e.getMessage());
            reflectionFailed = true;
        }
    }

    public static boolean isPlayerBleeding(Player player) {
        StevesArmyMod.LOGGER.info("[Respawn DEBUG] isPlayerBleeding called: isLoaded={}, reflectionFailed={}, capability={}", 
            loaded, reflectionFailed, bleedingCapability != null);
        
        if (!isLoaded() || player == null || bleedingCapability == null) {
            StevesArmyMod.LOGGER.info("[Respawn DEBUG] isPlayerBleeding: Early return false");
            return false;
        }
        try {
            LazyOptional<?> cap = player.getCapability(bleedingCapability);
            Object bleeding = cap.orElse(null);
            StevesArmyMod.LOGGER.info("[Respawn DEBUG] isPlayerBleeding: capability resolved={}", bleeding != null);
            if (bleeding == null) {
                return false;
            }
            boolean result = (boolean) isBleedingMethod.invoke(bleeding);
            StevesArmyMod.LOGGER.info("[Respawn DEBUG] isPlayerBleeding: result={}", result);
            return result;
        } catch (Exception e) {
            StevesArmyMod.LOGGER.warn("[PlayerReviveCompat] Error checking bleeding state: {}", e.getMessage());
            return false;
        }
    }
}
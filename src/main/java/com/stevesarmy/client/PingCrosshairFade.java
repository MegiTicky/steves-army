package com.stevesarmy.client;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;

public final class PingCrosshairFade {
    private static final float FADE_RADIUS = 48.0f;
    private static final float MIN_ALPHA_NORMAL = 0.50f;
    private static final float MIN_DIAMOND_ALPHA_ADS = 0.25f;
    private static final float MIN_TEXT_ALPHA_ADS = 0.0f;

    private static volatile boolean aimLookupInitialized;
    private static Method fromLocalPlayerMethod;
    private static Method isAimMethod;

    private PingCrosshairFade() {
    }

    public static Opacity calculate(Minecraft minecraft, float screenX, float screenY) {
        float centerX = minecraft.getWindow().getGuiScaledWidth() * 0.5f;
        float centerY = minecraft.getWindow().getGuiScaledHeight() * 0.5f;
        float distance = (float) Math.hypot(screenX - centerX, screenY - centerY);
        float visibility = Math.min(1.0f, distance / FADE_RADIUS);
        if (isAimingDownSights(minecraft)) {
            float diamondAlpha = MIN_DIAMOND_ALPHA_ADS
                + (1.0f - MIN_DIAMOND_ALPHA_ADS) * visibility;
            float textAlpha = MIN_TEXT_ALPHA_ADS
                + (1.0f - MIN_TEXT_ALPHA_ADS) * visibility;
            return new Opacity(diamondAlpha, textAlpha);
        }

        float alpha = MIN_ALPHA_NORMAL + (1.0f - MIN_ALPHA_NORMAL) * visibility;
        return new Opacity(alpha, alpha);
    }

    private static boolean isAimingDownSights(Minecraft minecraft) {
        initializeAimLookup();
        if (fromLocalPlayerMethod == null || isAimMethod == null || minecraft.player == null) {
            return false;
        }

        try {
            Object operator = fromLocalPlayerMethod.invoke(null, minecraft.player);
            return operator != null && Boolean.TRUE.equals(isAimMethod.invoke(operator));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    private static void initializeAimLookup() {
        if (aimLookupInitialized) return;
        synchronized (PingCrosshairFade.class) {
            if (aimLookupInitialized) return;
            try {
                Class<?> operatorClass = Class.forName(
                    "com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator");
                Class<?> localPlayerClass = Class.forName("net.minecraft.client.player.LocalPlayer");
                fromLocalPlayerMethod = operatorClass.getMethod("fromLocalPlayer", localPlayerClass);
                isAimMethod = operatorClass.getMethod("isAim");
            } catch (ReflectiveOperationException | LinkageError ignored) {
                fromLocalPlayerMethod = null;
                isAimMethod = null;
            }
            aimLookupInitialized = true;
        }
    }

    public static int withAlpha(int color, float alpha) {
        int sourceAlpha = (color >>> 24) & 0xFF;
        int adjustedAlpha = Math.round(sourceAlpha * Math.max(0.0f, Math.min(1.0f, alpha)));
        return (color & 0x00FFFFFF) | (adjustedAlpha << 24);
    }

    public record Opacity(float diamondAlpha, float textAlpha) {
    }
}

package com.stevesarmy.client;

import net.minecraftforge.common.ForgeConfigSpec;

public final class StevesArmyClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue SOLDIER_HELD_ITEM_RENDER_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue ENABLE_FIRE_TEAM_WHEEL;
    public static final ForgeConfigSpec.DoubleValue PING_SCALE;
    public static final ForgeConfigSpec.DoubleValue ENEMY_CONTACT_PING_BASELINE_SIZE;
    public static final ForgeConfigSpec.DoubleValue ENEMY_CONTACT_PING_HEIGHT_OFFSET;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("rendering");
        SOLDIER_HELD_ITEM_RENDER_DISTANCE = builder
            .comment("Maximum distance in blocks for rendering held items on Steve's Army soldiers.",
                     "Beyond this distance the full TaCZ third-person gun model is skipped to reduce render cost.",
                     "Set to 0 to hide all soldier-held items, or -1 to disable this optimization.",
                     "Default: 24 blocks")
            .defineInRange("soldierHeldItemRenderDistance", 24.0, -1.0, 128.0);
        PING_SCALE = builder
            .comment("Global scale factor for ping icons and text.",
                     "Default: 0.8")
            .defineInRange("pingScale", 0.8, 0.25, 2.0);
        ENEMY_CONTACT_PING_BASELINE_SIZE = builder
            .comment("Screen-space diameter in GUI pixels for enemy contact diamonds at 96 blocks and beyond.",
                     "Nearby on-screen contacts scale up to three times this size.",
                     "Default: 2.0 pixels")
            .defineInRange("enemyContactPingBaselineSize", 2.0, 0.5, 64.0);
        ENEMY_CONTACT_PING_HEIGHT_OFFSET = builder
            .comment("Additional world-space height in blocks above an enemy's head for the contact diamond.",
                     "Default: 0.5 blocks")
            .defineInRange("enemyContactPingHeightOffset", 0.5, 0.0, 4.0);
        builder.pop();

        builder.push("controls");
        ENABLE_FIRE_TEAM_WHEEL = builder
            .comment("Enable the fire-team selector wheel (G key).",
                     "When disabled, use the cycle fire team key binding instead.",
                     "Default: false")
            .define("enableFireTeamWheel", false);
        builder.pop();

        SPEC = builder.build();
    }

    private StevesArmyClientConfig() {
    }

    public static boolean shouldRenderHeldItem(double distanceSqr) {
        double distance = SOLDIER_HELD_ITEM_RENDER_DISTANCE.get();
        return distance < 0.0 || distanceSqr <= distance * distance;
    }
}

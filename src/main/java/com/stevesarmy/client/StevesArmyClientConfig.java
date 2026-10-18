package com.stevesarmy.client;

import net.minecraftforge.common.ForgeConfigSpec;

public final class StevesArmyClientConfig {
    public enum WheelInteractionMode { HOLD, CLICK }
    public static final ForgeConfigSpec.EnumValue<WheelInteractionMode> WHEEL_INTERACTION_MODE;
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue SOLDIER_HELD_ITEM_RENDER_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue SHOW_OWNED_SOLDIER_GLOW;
    public static final ForgeConfigSpec.BooleanValue SHOW_FRIENDLY_SOLDIER_NAME_TAGS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_FIRE_TEAM_WHEEL;
    public static final ForgeConfigSpec.BooleanValue ENABLE_VEHICLE_WHEEL;
    public static final ForgeConfigSpec.EnumValue<WheelCycleController.CycleButton> VEHICLE_WHEEL_CYCLE_BUTTON;
    public static final ForgeConfigSpec.DoubleValue PING_SCALE;
    public static final ForgeConfigSpec.DoubleValue ENEMY_CONTACT_PING_BASELINE_SIZE;
    public static final ForgeConfigSpec.DoubleValue ENEMY_CONTACT_PING_HEIGHT_OFFSET;
    public static final ForgeConfigSpec.BooleanValue FIRETEAM_HUD_ENABLED;
    public static final ForgeConfigSpec.EnumValue<FireTeamHudRenderer.Anchor> FIRETEAM_HUD_ANCHOR;
    public static final ForgeConfigSpec.IntValue FIRETEAM_HUD_OFFSET_X;
    public static final ForgeConfigSpec.IntValue FIRETEAM_HUD_OFFSET_Y;
    public static final ForgeConfigSpec.DoubleValue FIRETEAM_HUD_SCALE;
    public static final ForgeConfigSpec.IntValue FIRETEAM_HUD_WIDTH;
    public static final ForgeConfigSpec.DoubleValue FIRETEAM_HUD_OPACITY;
    public static final ForgeConfigSpec.BooleanValue FIRETEAM_HUD_SHOW_EMPTY;
    public static final ForgeConfigSpec.BooleanValue FIRETEAM_HUD_SELECTED_ONLY;
    public static final ForgeConfigSpec.BooleanValue FIRETEAM_HUD_PROGRESS;
    public static final ForgeConfigSpec.BooleanValue FIRETEAM_HUD_ETA;
    public static final ForgeConfigSpec.BooleanValue FIRETEAM_HUD_DETAILS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("rendering");
        SOLDIER_HELD_ITEM_RENDER_DISTANCE = builder
            .comment("Maximum distance in blocks for rendering held items on Steve's Army soldiers.",
                     "Beyond this distance the full TaCZ third-person gun model is skipped to reduce render cost.",
                     "Set to 0 to hide all soldier-held items, or -1 to disable this optimization.",
                     "Default: 24 blocks")
            .defineInRange("soldierHeldItemRenderDistance", 24.0, -1.0, 128.0);
        SHOW_OWNED_SOLDIER_GLOW = builder
            .comment("Show the glowing outline on soldiers owned by the local player.",
                     "Default: true")
            .define("showOwnedSoldierGlow", true);
        SHOW_FRIENDLY_SOLDIER_NAME_TAGS = builder
            .comment("Show name tags for owned and allied soldiers.",
                     "Default: true")
            .define("showFriendlySoldierNameTags", true);
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
        WHEEL_INTERACTION_MODE = builder
            .comment("HOLD: hold the wheel binding and release to select (default).",
                     "CLICK: press to open, left-click to select; Escape or the opener cancels.",
                     "Applies to command, vehicle, formation and fire-team wheels.",
                     "In CLICK mode right-click always switches command/vehicle pages.")
            .defineEnum("wheelInteractionMode", WheelInteractionMode.HOLD);
        ENABLE_FIRE_TEAM_WHEEL = builder
            .comment("Enable the fire-team selector wheel (P key, or Ctrl+Middle-click).",
                     "When disabled, use the cycle fire team key binding instead.",
                     "Default: false")
            .define("enableFireTeamWheel", false);
        ENABLE_VEHICLE_WHEEL = builder
            .comment("Enable the vehicle wheel page: hold the ping wheel key (middle-click),",
                     "then press the cycle button to switch to vehicle Mount/Dismount commands.",
                     "Default: true")
            .define("enableVehicleWheel", true);
        VEHICLE_WHEEL_CYCLE_BUTTON = builder
            .comment("Mouse button that switches between wheel pages while a wheel is open.",
                     "Right-click is the default because many mice never report a left-click",
                     "while the scroll-wheel button is held down.",
                     "Default: RIGHT")
            .defineEnum("vehicleWheelCycleButton", WheelCycleController.CycleButton.RIGHT);
        builder.pop();

        builder.push("fireteamHud");
        FIRETEAM_HUD_ENABLED = builder.define("enabled", true);
        FIRETEAM_HUD_ANCHOR = builder.defineEnum("anchor", FireTeamHudRenderer.Anchor.TOP_RIGHT);
        FIRETEAM_HUD_OFFSET_X = builder.defineInRange("offsetX", 8, -1000, 1000);
        FIRETEAM_HUD_OFFSET_Y = builder.defineInRange("offsetY", 8, -1000, 1000);
        FIRETEAM_HUD_SCALE = builder.defineInRange("scale", 0.6, 0.25, 2.0);
        FIRETEAM_HUD_WIDTH = builder.defineInRange("width", 150, 80, 500);
        FIRETEAM_HUD_OPACITY = builder.defineInRange("opacity", 0.86, 0.1, 1.0);
        FIRETEAM_HUD_SHOW_EMPTY = builder.define("showEmpty", false);
        FIRETEAM_HUD_SELECTED_ONLY = builder.define("selectedOnly", false);
        FIRETEAM_HUD_PROGRESS = builder.define("progress", true);
        FIRETEAM_HUD_ETA = builder.define("eta", true);
        FIRETEAM_HUD_DETAILS = builder.define("details", true);
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

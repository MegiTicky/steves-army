package com.stevesarmy;

import net.minecraftforge.common.ForgeConfigSpec;

public class StevesArmyConfig {
    
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;
    
    public static final ForgeConfigSpec.DoubleValue BASE_ACCURACY;
    public static final ForgeConfigSpec.DoubleValue SHOT_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue TARGET_SWITCH_IMPROVEMENT;
    public static final ForgeConfigSpec.IntValue TARGET_REEVALUATE_INTERVAL;
    public static final ForgeConfigSpec.BooleanValue SQUAD_FRIENDLY_FIRE;
    
    static {
        BUILDER.push("combat");
        
        BASE_ACCURACY = BUILDER
            .comment("Base accuracy for soldiers (0.1 to 1.0). Default 0.5 (50%).",
                     "This is the base hit chance at optimal range for a stationary, exposed target.",
                     "Actual accuracy is modified by distance, target movement, and exposure.")
            .defineInRange("baseAccuracy", 0.5, 0.1, 1.0);
        
        SHOT_THRESHOLD = BUILDER
            .comment("Minimum shot threshold to fire (0.0 to 1.0). Default 0.5 (50%).",
                     "Soldiers fire when (trackingProgress × accuracy) >= this value.",
                     "Lower values = soldiers fire sooner with less aim time.",
                     "If threshold not met, soldier will still fire as suppressive fire after full tracking.")
            .defineInRange("shotThreshold", 0.5, 0.0, 1.0);
        
        TARGET_SWITCH_IMPROVEMENT = BUILDER
            .comment("Minimum improvement to switch targets (0.0 to 1.0). Default 0.2 (20%).",
                     "A new target must have this much better hit probability to justify switching.",
                     "Prevents rapid target switching between similar-quality targets.")
            .defineInRange("targetSwitchImprovement", 0.2, 0.0, 1.0);
        
        TARGET_REEVALUATE_INTERVAL = BUILDER
            .comment("Ticks between target re-evaluation. Default 20 (1 second).",
                     "Lower values = more responsive but higher CPU usage.")
            .defineInRange("targetReevaluateInterval", 20, 5, 100);
        
        BUILDER.pop();
        
        BUILDER.push("friendly_fire");
        
        SQUAD_FRIENDLY_FIRE = BUILDER
            .comment("Enable squad-friendly fire protection for players/soldiers without a team.",
                     "When enabled, soldiers cannot damage their owner or squadmates.",
                     "For team-based protection, use: /team modify <team> friendlyfire false",
                     "Default: true (squad protection ON)")
            .define("squadFriendlyFire", true);
        
        BUILDER.pop();
        
        SPEC = BUILDER.build();
    }
    
    public static float getBaseAccuracy() {
        return BASE_ACCURACY.get().floatValue();
    }
    
    public static float getShotThreshold() {
        return SHOT_THRESHOLD.get().floatValue();
    }
    
    public static float getTargetSwitchImprovement() {
        return TARGET_SWITCH_IMPROVEMENT.get().floatValue();
    }
    
    public static int getTargetReevaluateInterval() {
        return TARGET_REEVALUATE_INTERVAL.get();
    }
    
    public static boolean getSquadFriendlyFire() {
        return SQUAD_FRIENDLY_FIRE.get();
    }
}

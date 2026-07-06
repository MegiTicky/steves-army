package com.stevesarmy;

import net.minecraftforge.common.ForgeConfigSpec;

public class StevesArmyConfig {
    
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;
    
    public static final ForgeConfigSpec.DoubleValue AIM_QUALITY_BASE_ACCURACY;
    public static final ForgeConfigSpec.DoubleValue AIM_QUALITY_THRESHOLD_SCALE;
    public static final ForgeConfigSpec.DoubleValue AIM_QUALITY_BUILD_RATE;
    public static final ForgeConfigSpec.DoubleValue AIM_QUALITY_RECOIL_SCALE;
    public static final ForgeConfigSpec.DoubleValue AIM_QUALITY_LOS_DECAY_RATE;
    public static final ForgeConfigSpec.DoubleValue AIM_QUALITY_MOVE_DECAY_RATE;
    public static final ForgeConfigSpec.DoubleValue AIM_QUALITY_TARGET_MOVE_PENALTY;
    public static final ForgeConfigSpec.DoubleValue AIM_QUALITY_SWITCH_RESET;
    public static final ForgeConfigSpec.DoubleValue TARGET_SWITCH_IMPROVEMENT;
    public static final ForgeConfigSpec.IntValue TARGET_REEVALUATE_INTERVAL;
    public static final ForgeConfigSpec.BooleanValue SQUAD_FRIENDLY_FIRE;
    public static final ForgeConfigSpec.DoubleValue THREAT_SMOOTH_BLEND_FACTOR;
    public static final ForgeConfigSpec.IntValue THREAT_SMOOTH_DECAY_TIME_MS;
    
    static {
        BUILDER.push("aim_quality");
        
        AIM_QUALITY_BASE_ACCURACY = BUILDER
            .comment("Maximum aimQuality achievable under ideal conditions (0.0 to 1.0).",
                     "Multiplied by distance, movement, and exposure factors to get targetAimQuality.",
                     "Default: 0.50 (50% max hit probability)")
            .defineInRange("baseAccuracy", 0.50, 0.1, 1.0);
        
        AIM_QUALITY_THRESHOLD_SCALE = BUILDER
            .comment("Fraction of targetAimQuality required before firing (0.0 to 1.0).",
                     "shotThreshold = max(0.15, targetAimQuality * thresholdScale).",
                     "Higher = soldier waits longer for better aim. Default: 0.35",
                     "At baseAccuracy=0.50 close range: 0.50 * 0.35 = 0.175 threshold",
                     "At baseAccuracy=0.50 long range: max(0.15, 0.25 * 0.35) = 0.15 (floor)")
            .defineInRange("thresholdScale", 0.35, 0.0, 1.0);
        
        AIM_QUALITY_BUILD_RATE = BUILDER
            .comment("How fast aimQuality approaches its target per tick (0.0 to 1.0).",
                     "Used as lerp factor: aimQuality = lerp(buildRate, aimQuality, targetAimQuality).",
                     "Higher = faster aim acquisition. Default: 0.08 (~2s to reach target)")
            .defineInRange("buildRate", 0.08, 0.01, 1.0);
        
        AIM_QUALITY_RECOIL_SCALE = BUILDER
            .comment("Per-shot penalty from gun recoil: (pitch + yaw) * scale.",
                     "AK47: pitch=0.66, yaw=0.23, scale=0.07 → 0.062 aimQuality loss per shot.",
                     "Higher = more aim degradation under sustained fire. Default: 0.07")
            .defineInRange("recoilScale", 0.07, 0.0, 1.0);
        
        AIM_QUALITY_LOS_DECAY_RATE = BUILDER
            .comment("Per-tick aimQuality decay when target is not in line-of-sight.",
                     "aimQuality drops this much per tick (20 ticks/sec) when target breaks LOS.",
                     "Default: 0.15 (reaches 0 in ~7 ticks = 0.35s)")
            .defineInRange("losDecayRate", 0.15, 0.0, 0.5);
        
        AIM_QUALITY_MOVE_DECAY_RATE = BUILDER
            .comment("Per-tick aimQuality decay while the soldier is moving.",
                     "Penalizes shooting while running. Default: 0.02 (minor effect).")
            .defineInRange("moveDecayRate", 0.02, 0.0, 0.1);
        
        AIM_QUALITY_TARGET_MOVE_PENALTY = BUILDER
            .comment("Additional per-tick aimQuality decay when the target is moving.",
                     "Makes it harder to track sprinting targets. Default: 0.05.")
            .defineInRange("targetMovePenalty", 0.05, 0.0, 0.2);
        
        AIM_QUALITY_SWITCH_RESET = BUILDER
            .comment("Proportion of aimQuality retained when switching to a new target (0.0 to 1.0).",
                     "0.0 = full reset, 1.0 = retain all aimQuality. Default: 0.30 (keep 30%).",
                     "Values < 1.0 create a small re-aiming delay on target switch.")
            .defineInRange("switchReset", 0.30, 0.0, 1.0);
        
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
        
        BUILDER.push("threat_system");
        
        THREAT_SMOOTH_BLEND_FACTOR = BUILDER
            .comment("Blend factor for smooth threat direction (0.0 to 1.0).",
                     "Higher values = faster adaptation to new threats.",
                     "0.3 = gradual (30% new, 70% history)",
                     "0.5 = balanced (50% new, 50% history)",
                     "0.7 = responsive (70% new, 30% history)",
                     "Default: 0.5 (balanced)")
            .defineInRange("smoothBlendFactor", 0.5, 0.0, 1.0);
        
        THREAT_SMOOTH_DECAY_TIME_MS = BUILDER
            .comment("Decay time for smooth threat direction in milliseconds.",
                     "After this time without threat updates, smooth direction resets.",
                     "0 = no decay (persists forever)",
                     "30000 = 30 seconds (short memory)",
                     "60000 = 60 seconds (medium memory)",
                     "120000 = 120 seconds (long memory)",
                     "Default: 60000 (60 seconds)")
            .defineInRange("smoothDecayTimeMs", 60000, 0, 300000);
        
        BUILDER.pop();
        
        SPEC = BUILDER.build();
    }
    
    public static float getAimQualityBaseAccuracy() {
        return AIM_QUALITY_BASE_ACCURACY.get().floatValue();
    }
    
    public static float getAimQualityThresholdScale() {
        return AIM_QUALITY_THRESHOLD_SCALE.get().floatValue();
    }
    
    public static float getAimQualityBuildRate() {
        return AIM_QUALITY_BUILD_RATE.get().floatValue();
    }
    
    public static float getAimQualityRecoilScale() {
        return AIM_QUALITY_RECOIL_SCALE.get().floatValue();
    }
    
    public static float getAimQualityLosDecayRate() {
        return AIM_QUALITY_LOS_DECAY_RATE.get().floatValue();
    }
    
    public static float getAimQualityMoveDecayRate() {
        return AIM_QUALITY_MOVE_DECAY_RATE.get().floatValue();
    }
    
    public static float getAimQualityTargetMovePenalty() {
        return AIM_QUALITY_TARGET_MOVE_PENALTY.get().floatValue();
    }
    
    public static float getAimQualitySwitchReset() {
        return AIM_QUALITY_SWITCH_RESET.get().floatValue();
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
    
    public static double getThreatSmoothBlendFactor() {
        return THREAT_SMOOTH_BLEND_FACTOR.get();
    }
    
    public static int getThreatSmoothDecayTimeMs() {
        return THREAT_SMOOTH_DECAY_TIME_MS.get();
    }
}

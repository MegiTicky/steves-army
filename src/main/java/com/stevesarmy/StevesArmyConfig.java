package com.stevesarmy;

import net.minecraftforge.common.ForgeConfigSpec;

public class StevesArmyConfig {
    public enum OptimizationProfile {
        COMPATIBILITY(0, "compatibility"),
        CONSERVATIVE(1, "conservative"),
        BALANCED(2, "balanced"),
        AGGRESSIVE(3, "aggressive");

        private final int level;
        private final String displayName;

        OptimizationProfile(int level, String displayName) {
            this.level = level;
            this.displayName = displayName;
        }

        public int level() {
            return level;
        }

        public String displayName() {
            return displayName;
        }

        public static OptimizationProfile fromLevel(int level) {
            return switch (level) {
                case 0 -> COMPATIBILITY;
                case 2 -> BALANCED;
                case 3 -> AGGRESSIVE;
                default -> CONSERVATIVE;
            };
        }
    }

    
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;
    
    public static final ForgeConfigSpec.DoubleValue AIM_QUALITY_BASE_ACCURACY;
    public static final ForgeConfigSpec.DoubleValue AIM_QUALITY_THRESHOLD_SCALE;
    public static final ForgeConfigSpec.DoubleValue AIM_QUALITY_SLOW_GUN_THRESHOLD_SCALE;
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
    
    public static final ForgeConfigSpec.BooleanValue TARGET_MONSTERS;
    public static final ForgeConfigSpec.BooleanValue TARGET_TARGET_ENTITIES;

    public static final ForgeConfigSpec.DoubleValue SPACING_DISTANCE;

    public static final ForgeConfigSpec.DoubleValue EXPLOSION_SUPPRESSION_STRENGTH;
    public static final ForgeConfigSpec.DoubleValue EXPLOSION_SUPPRESSION_RADIUS;
    public static final ForgeConfigSpec.DoubleValue EXPLOSION_SHELTER_FLOOR;
    public static final ForgeConfigSpec.IntValue EXPLOSION_BURST_WINDOW_MS;
    public static final ForgeConfigSpec.DoubleValue EXPLOSION_BURST_MULTIPLIER;

    public static final ForgeConfigSpec.BooleanValue VS2_COMPAT_ENABLED;
    public static final ForgeConfigSpec.BooleanValue VS2_AUTO_TRANSPORT;
    public static final ForgeConfigSpec.IntValue VS2_MAX_TRANSPORTED_SOLDIERS;

    public static final ForgeConfigSpec.IntValue OPTIMIZATION_LEVEL;

    static {
        BUILDER.push("aim_quality");
        
        AIM_QUALITY_BASE_ACCURACY = BUILDER
            .comment("Maximum aimQuality achievable under ideal conditions (0.0 to 1.0).",
                     "Multiplied by distance, movement, and exposure factors to get targetAimQuality.",
                     "Default: 0.75 (75% max hit probability)")
            .defineInRange("baseAccuracy", 0.75, 0.1, 1.0);
        
        AIM_QUALITY_THRESHOLD_SCALE = BUILDER
            .comment("Fraction of targetAimQuality required before firing (0.0 to 1.0).",
                     "Used for guns that don't need bolting (auto/semi-auto).",
                     "shotThreshold = max(0.15, targetAimQuality * thresholdScale).",
                     "Higher = soldier waits longer for better aim. Default: 0.35",
                     "At baseAccuracy=0.75 close range: 0.75 * 0.35 = 0.2625 threshold",
                     "At baseAccuracy=0.75 long range: max(0.15, 0.25 * 0.35) = 0.15 (floor)")
            .defineInRange("thresholdScale", 0.35, 0.0, 1.0);
        
        AIM_QUALITY_SLOW_GUN_THRESHOLD_SCALE = BUILDER
            .comment("Fraction of targetAimQuality required for guns that need bolting (bolt-action).",
                     "shotThreshold = max(0.15, targetAimQuality * slowGunThresholdScale).",
                     "Bolt-action rifles get a higher scale since each shot is more precious.",
                     "Default: 0.60 (at close range: 0.75 * 0.60 = 0.45 threshold)")
            .defineInRange("slowGunThresholdScale", 0.60, 0.0, 1.0);
        
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
        
        BUILDER.push("targeting");
        
        TARGET_MONSTERS = BUILDER
            .comment("Whether soldiers should target hostile mobs (zombies, skeletons, etc.).",
                     "Disable for better performance in player vs player combat scenarios.",
                     "Default: true")
            .define("targetMonsters", true);
        
        TARGET_TARGET_ENTITIES = BUILDER
            .comment("Whether soldiers should target TargetEntity (practice dummies).",
                     "Disable for better performance if not using target entities.",
                     "Default: true")
            .define("targetTargetEntities", true);
        
BUILDER.pop();

        BUILDER.push("spacing");

        SPACING_DISTANCE = BUILDER
            .comment("Minimum distance soldiers try to keep from each other while moving (blocks).",
                     "Soldiers will offset their path to avoid clustering.",
                     "Default: 3.0 blocks")
            .defineInRange("spacingDistance", 3.0, 1.0, 10.0);

        BUILDER.pop();

        BUILDER.push("explosion_suppression");

        EXPLOSION_SUPPRESSION_STRENGTH = BUILDER
            .comment("Base suppression added by an explosion at distance 0 with full exposure (0.0 to 1.0).",
                     "Default: 1.0")
            .defineInRange("explosionSuppressionStrength", 1.0, 0.0, 1.0);

        EXPLOSION_SUPPRESSION_RADIUS = BUILDER
            .comment("Maximum radius in blocks for explosion suppression effects.",
                     "Soldiers beyond this distance receive no blast suppression.",
                     "Default: 24.0 blocks")
            .defineInRange("explosionSuppressionRadius", 24.0, 1.0, 64.0);

        EXPLOSION_SHELTER_FLOOR = BUILDER
            .comment("Minimum exposure factor for soldiers in full cover (0.0 to 1.0).",
                     "0.0 = complete cover blocks all blast suppression.",
                     "0.70 = nearby explosions strongly suppress even fully covered soldiers.",
                     "Default: 0.70")
            .defineInRange("explosionShelterFloor", 0.70, 0.0, 1.0);

        EXPLOSION_BURST_WINDOW_MS = BUILDER
            .comment("Time window in milliseconds for explosion burst damping.",
                     "Explosions within this window get reduced suppression.",
                     "Default: 250 ms")
            .defineInRange("explosionBurstWindowMs", 250, 50, 2000);

        EXPLOSION_BURST_MULTIPLIER = BUILDER
            .comment("Suppression multiplier for subsequent explosions within the burst window (0.0 to 1.0).",
                     "First explosion = 1.0x, subsequent = this value.",
                     "Default: 0.35")
            .defineInRange("explosionBurstMultiplier", 0.35, 0.0, 1.0);

        BUILDER.pop();

        BUILDER.push("valkyrienskies");

        VS2_COMPAT_ENABLED = BUILDER
            .comment("Enable Valkyrien Skies 2 compatibility when the valkyrienskies mod is installed.",
                     "Soldiers avoid VS ship navigation and recover from accidental ship contact.")
            .define("enabled", true);

        VS2_AUTO_TRANSPORT = BUILDER
            .comment("Automatically mount nearby FOLLOW soldiers to Create seats on the ship their owner boards.",
                     "Mounted soldiers do not navigate, seek cover, or fight while transported.")
            .define("autoTransport", true);

        VS2_MAX_TRANSPORTED_SOLDIERS = BUILDER
            .comment("Maximum number of nearby FOLLOW soldiers automatically transported with one owner on a VS ship.")
            .defineInRange("maxTransportedSoldiers", 32, 0, 64);

        BUILDER.pop();

        BUILDER.push("performance");

        OPTIMIZATION_LEVEL = BUILDER
            .comment("Performance optimization profile: compatibility, conservative, balanced, or aggressive.",
                     "The existing numeric value is retained for config-file compatibility: 0, 1, 2, or 3.",
                     "Higher profiles reuse perception data for longer and share nearby target queries.",
                     "Compatibility keeps the existing target-query behavior; final firing validation is always exact.",
                     "Default: conservative")
            .defineInRange("optimizationLevel", 1, 0, 3);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }
    
    public static float getAimQualityBaseAccuracy() {
        return AIM_QUALITY_BASE_ACCURACY.get().floatValue();
    }
    
    public static float getAimQualityThresholdScale() {
        return AIM_QUALITY_THRESHOLD_SCALE.get().floatValue();
    }
    
    public static float getAimQualitySlowGunThresholdScale() {
        return AIM_QUALITY_SLOW_GUN_THRESHOLD_SCALE.get().floatValue();
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
    
    public static boolean shouldTargetMonsters() {
        return TARGET_MONSTERS.get();
    }
    
    public static boolean shouldTargetTargetEntities() {
        return TARGET_TARGET_ENTITIES.get();
    }

    public static double getSpacingDistance() {
        return SPACING_DISTANCE.get();
    }

    public static float getExplosionSuppressionStrength() {
        return EXPLOSION_SUPPRESSION_STRENGTH.get().floatValue();
    }

    public static float getExplosionSuppressionRadius() {
        return EXPLOSION_SUPPRESSION_RADIUS.get().floatValue();
    }

    public static float getExplosionShelterFloor() {
        return EXPLOSION_SHELTER_FLOOR.get().floatValue();
    }

    public static int getExplosionBurstWindowMs() {
        return EXPLOSION_BURST_WINDOW_MS.get();
    }

    public static float getExplosionBurstMultiplier() {
        return EXPLOSION_BURST_MULTIPLIER.get().floatValue();
    }

    public static int getOptimizationLevel() {
        return OPTIMIZATION_LEVEL.get();
    }

    public static OptimizationProfile getOptimizationProfile() {
        return OptimizationProfile.fromLevel(getOptimizationLevel());
    }

    /** Number of ticks a soldier reuses its nearby-target snapshot. */
    public static int getTargetCandidateCacheTicks() {
        return switch (getOptimizationProfile()) {
            case BALANCED -> 8;
            case AGGRESSIVE -> 12;
            default -> 5;
        };
    }

    /** Number of ticks exact positional visibility results may be reused. */
    public static int getPositionVisibilityCacheTicks() {
        return switch (getOptimizationProfile()) {
            case COMPATIBILITY -> 0;
            case BALANCED -> 2;
            case AGGRESSIVE -> 4;
            default -> 1;
        };
    }

    /** Number of ticks exact aim-point results may be reused. */
    public static int getAimPointCacheTicks() {
        return switch (getOptimizationProfile()) {
            case COMPATIBILITY -> 1;
            case BALANCED -> 2;
            case AGGRESSIVE -> 4;
            default -> 1;
        };
    }

    /** Number of ticks exact exposure results may be reused. */
    public static int getExposureCacheTicks() {
        return switch (getOptimizationProfile()) {
            case BALANCED -> 2;
            case AGGRESSIVE -> 4;
            default -> 1;
        };
    }

    public static boolean useSharedTargetQueryCache() {
        return getOptimizationProfile() != OptimizationProfile.COMPATIBILITY;
    }

    /** Number of ticks between non-urgent in-cover maintenance passes. */
    public static int getCoverMaintenanceIntervalTicks() {
        return switch (getOptimizationProfile()) {
            case BALANCED -> 2;
            case AGGRESSIVE -> 3;
            default -> 1;
        };
    }
}

package com.stevesarmy;

import net.minecraftforge.common.ForgeConfigSpec;

public class StevesArmyConfig {
    
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

    public static final ForgeConfigSpec.BooleanValue DYNAMIC_FIRING_ENABLED;
    public static final ForgeConfigSpec.DoubleValue FIRING_PERSONALITY_STRENGTH;
    public static final ForgeConfigSpec.EnumValue<SuppressedFireMode> SUPPRESSED_FIRE_MODE;
    public static final ForgeConfigSpec.DoubleValue SUPPRESSION_THRESHOLD_RELIEF;
    public static final ForgeConfigSpec.DoubleValue SUPPRESSION_THRESHOLD_TIGHTEN;
    public static final ForgeConfigSpec.DoubleValue SUPPRESSION_SIGMA_SCALE;
    public static final ForgeConfigSpec.DoubleValue FIRING_FIRETEAM_BLEND;
    public static final ForgeConfigSpec.DoubleValue FIRING_DUTY_SUPPRESSION_FACTOR;
    public static final ForgeConfigSpec.DoubleValue FIRING_MISS_STREAK_STEP;
    public static final ForgeConfigSpec.IntValue FIRING_MISS_STREAK_CAP;
    public static final ForgeConfigSpec.DoubleValue FIRING_MISS_STREAK_RECOVERY_SCALE;
    public static final ForgeConfigSpec.IntValue FIRING_MISS_STREAK_DECAY_TICKS;

    /** How a suppressed soldier reacts when returning direct fire. */
    public enum SuppressedFireMode { SPRAY, HOLD }
    public static final ForgeConfigSpec.BooleanValue SQUAD_FRIENDLY_FIRE;
    public static final ForgeConfigSpec.DoubleValue THREAT_SMOOTH_BLEND_FACTOR;
    public static final ForgeConfigSpec.IntValue THREAT_SMOOTH_DECAY_TIME_MS;
    
    public static final ForgeConfigSpec.BooleanValue TARGET_MONSTERS;
    public static final ForgeConfigSpec.BooleanValue TARGET_TARGET_ENTITIES;
    public static final ForgeConfigSpec.DoubleValue BASIC_DETECTION_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue MACHINE_GUNNER_DETECTION_DISTANCE;

    public static final ForgeConfigSpec.DoubleValue SPACING_DISTANCE;

    public static final ForgeConfigSpec.DoubleValue EXPLOSION_SUPPRESSION_STRENGTH;
    public static final ForgeConfigSpec.DoubleValue EXPLOSION_SUPPRESSION_RADIUS;
    public static final ForgeConfigSpec.DoubleValue EXPLOSION_SHELTER_FLOOR;
    public static final ForgeConfigSpec.IntValue EXPLOSION_BURST_WINDOW_MS;
    public static final ForgeConfigSpec.DoubleValue EXPLOSION_BURST_MULTIPLIER;

    public static final ForgeConfigSpec.BooleanValue GRENADES_ENABLED;
    public static final ForgeConfigSpec.IntValue GRENADE_PERSONAL_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue GRENADE_SQUAD_INTERVAL_TICKS;
    public static final ForgeConfigSpec.DoubleValue GRENADE_MIN_RANGE;
    public static final ForgeConfigSpec.DoubleValue GRENADE_MAX_RANGE;
    public static final ForgeConfigSpec.DoubleValue GRENADE_SAFETY_MARGIN;
    public static final ForgeConfigSpec.DoubleValue GRENADE_MIN_THREAT_ACCURACY;
    public static final ForgeConfigSpec.DoubleValue GRENADE_AIM_ERROR_SCALE;
    public static final ForgeConfigSpec.DoubleValue GRENADE_OVERTHROW_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue GRENADE_THROW_POWER_SCALE;

    public static final ForgeConfigSpec.BooleanValue SMOKE_DEPLOYMENT_ENABLED;
    public static final ForgeConfigSpec.IntValue SMOKE_TRIGGER_HOLD_TICKS;
    public static final ForgeConfigSpec.IntValue SMOKE_FIRETEAM_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.DoubleValue SMOKE_SCREEN_FRACTION;
    public static final ForgeConfigSpec.BooleanValue SMOKE_ADVANCE_UNDER_SMOKE;
    public static final ForgeConfigSpec.IntValue SMOKE_SCREEN_DURATION_TICKS;
    public static final ForgeConfigSpec.IntValue SMOKE_GRENADES_PER_SCREEN;
    public static final ForgeConfigSpec.DoubleValue SMOKE_FALL_RATE_MULTIPLIER;

    public static final ForgeConfigSpec.BooleanValue VS2_COMPAT_ENABLED;
    public static final ForgeConfigSpec.BooleanValue SKIN_RANDOMIZE_ON_SPAWN;
    public static final ForgeConfigSpec.BooleanValue VS2_AUTO_TRANSPORT;
    public static final ForgeConfigSpec.IntValue VS2_MAX_TRANSPORTED_SOLDIERS;
    public static final ForgeConfigSpec.IntValue VS2_DISMOUNT_REBOARD_DELAY;

    public static final ForgeConfigSpec.BooleanValue VEHICLE_CREW_ENABLED;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_CREW_SEAT_SEARCH_RADIUS;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_CREW_STATION_REACH;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_CREW_DETECTION_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_CREW_TRAVERSE_SPEED;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_CREW_BLOOM_PER_SHOT;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_CREW_BLOOM_MAX;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_CREW_BLOOM_DECAY;
    public static final ForgeConfigSpec.IntValue VEHICLE_CREW_DUTY_SCAN_INTERVAL;
    public static final ForgeConfigSpec.IntValue VEHICLE_CREW_SEAT_SCAN_INTERVAL;
    public static final ForgeConfigSpec.BooleanValue VEHICLE_CREW_SUPPRESSION_ENABLED;
    public static final ForgeConfigSpec.DoubleValue VEHICLE_CREW_SUPPRESSION_RANGE;

    public static final ForgeConfigSpec.BooleanValue VEHICLE_HANDLES_ENABLED;
    public static final ForgeConfigSpec.IntValue VEHICLE_HANDLES_DISMOUNT_GRACE;

    public static final ForgeConfigSpec.BooleanValue ARMOR_AWARENESS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue ARMOR_DETECTION_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue ARMOR_PATH_DISPLACEMENT_ENABLED;
    public static final ForgeConfigSpec.DoubleValue ARMOR_PATH_CORRIDOR_WIDTH;
    public static final ForgeConfigSpec.DoubleValue ARMOR_ENGAGEMENT_MAX_RANGE;
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> ARMOR_AT_GUN_PATTERNS;
    public static final ForgeConfigSpec.EnumValue<ArmorDoctrineOverride> ARMOR_DOCTRINE_OVERRIDE;

    public enum ArmorDoctrineOverride { AUTO, NO_AT, FORCE_AT }

    public static final ForgeConfigSpec.IntValue OPTIMIZATION_LEVEL;
    public static final ForgeConfigSpec.BooleanValue RETRY_POLICY_ENABLED;
    public static final ForgeConfigSpec.BooleanValue PERCEPTION_FRAME_ENABLED;
    public static final ForgeConfigSpec.BooleanValue PURE_EVALUATOR_SHADOW_ENABLED;
    public static final ForgeConfigSpec.BooleanValue ASYNC_COVER_SHADOW_ENABLED;
    public static final ForgeConfigSpec.BooleanValue ASYNC_COVER_PILOT_ENABLED;
    public static final ForgeConfigSpec.BooleanValue ASYNC_PATHFINDING_ENABLED;
    public static final ForgeConfigSpec.IntValue ASYNC_PATHFINDING_THREADS;
    public static final ForgeConfigSpec.IntValue ASYNC_PATHFINDING_QUEUE_CAPACITY;
    public static final ForgeConfigSpec.IntValue ASYNC_PATHFINDING_MAX_PENDING_TICKS;
    public static final ForgeConfigSpec.IntValue EXACT_PATH_VALIDATION_LIMIT;
    public static final ForgeConfigSpec.IntValue COVER_SEARCH_FAILURE_RETRY_TICKS;

    public static final ForgeConfigSpec.BooleanValue FIRETEAM_SUPPRESSION_ENABLED;
    public static final ForgeConfigSpec.DoubleValue FIRETEAM_RISE_RATE;
    public static final ForgeConfigSpec.DoubleValue FIRETEAM_FALL_RATE;
    public static final ForgeConfigSpec.DoubleValue FIRETEAM_SUPPRESSION_IMPULSE;
    public static final ForgeConfigSpec.DoubleValue FIRETEAM_CASUALTY_BUMP;
    public static final ForgeConfigSpec.DoubleValue FIRETEAM_INCOMING_PRESSURE_DECAY;
    public static final ForgeConfigSpec.DoubleValue FIRETEAM_SUPERIORITY_DAMPENING;
    public static final ForgeConfigSpec.DoubleValue FIRETEAM_SUPPRESSION_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue FIRETEAM_HEAVY_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue FIRETEAM_PEEK_BLOCK_THRESHOLD;
    public static final ForgeConfigSpec.IntValue FIRETEAM_HOLD_NOTIFY_TICKS;
    public static final ForgeConfigSpec.IntValue FIRETEAM_NOTIFY_COOLDOWN_TICKS;

    public static final ForgeConfigSpec.IntValue RECOVERY_SAFETY_PEEK_MS;
    public static final ForgeConfigSpec.DoubleValue COVER_SOFT_FACTOR;
    public static final ForgeConfigSpec.IntValue COVER_WAIT_TIMEOUT_TICKS;
    public static final ForgeConfigSpec.BooleanValue FIRETEAM_HEAVY_HOLD_REPOSITION;
    public static final ForgeConfigSpec.DoubleValue GROUP_COHESION_BAND_BLOCKS;
    public static final ForgeConfigSpec.DoubleValue GROUP_AHEAD_DWELL_MULT;
    public static final ForgeConfigSpec.DoubleValue GROUP_BEHIND_DWELL_MULT;

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

        BUILDER.push("dynamic_firing");

        DYNAMIC_FIRING_ENABLED = BUILDER
            .comment("Master switch for the dynamic firing gate: per-soldier firing personalities,",
                     "variable burst lengths/gaps, suppression-driven aim, and the missed-target",
                     "streak ratchet. When false, firing uses the exact legacy fixed pacing.",
                     "Default: true")
            .define("dynamicFiringEnabled", true);

        FIRING_PERSONALITY_STRENGTH = BUILDER
            .comment("How strongly a soldier's per-engagement personality skews pacing (0.0 to 1.0).",
                     "0.0 = all soldiers pace identically (legacy), 1.0 = full bias ranges",
                     "(burst 0.8-1.3x, gap 0.8-1.25x, threshold 0.9-1.1x, cadence 0.9-1.15x, build 0.85-1.2x).",
                     "Default: 0.6")
            .defineInRange("personalityStrength", 0.6, 0.0, 1.0);

        SUPPRESSED_FIRE_MODE = BUILDER
            .comment("Reaction of a pressured/pinned soldier returning direct fire:",
                     "SPRAY = lowers the aim gate so they shoot back sooner and less accurately.",
                     "HOLD = raises the aim gate so they stop exposing and button up.",
                     "Default: SPRAY")
            .defineEnum("suppressedFireMode", SuppressedFireMode.SPRAY);

        SUPPRESSION_THRESHOLD_RELIEF = BUILDER
            .comment("SPRAY mode: aim-gate reduction at full suppression (0.0 to 1.0).",
                     "thresholdScale *= 1 - suppression * relief. Default: 0.35")
            .defineInRange("suppressionThresholdRelief", 0.35, 0.0, 1.0);

        SUPPRESSION_THRESHOLD_TIGHTEN = BUILDER
            .comment("HOLD mode: aim-gate increase at full suppression (0.0 to 1.0).",
                     "thresholdScale *= 1 + suppression * tighten. Default: 0.40")
            .defineInRange("suppressionThresholdTighten", 0.40, 0.0, 1.0);

        SUPPRESSION_SIGMA_SCALE = BUILDER
            .comment("How much shot dispersion widens at full suppression (0.0 to 5.0).",
                     "sigma *= 1 + suppression * scale. Suppressive-discipline duty fire takes a",
                     "reduced share of the shake. The +-2.5 sigma clamp always applies. Default: 2.0")
            .defineInRange("suppressionSigmaScale", 2.0, 0.0, 5.0);

        FIRING_FIRETEAM_BLEND = BUILDER
            .comment("How much fireteam-level suppression feeds the individual's firing state (0.0 to 1.0).",
                     "effective = max(individual, blend * fireteamLevel). 0.0 = individual only.",
                     "Default: 0.5")
            .defineInRange("fireteamBlend", 0.5, 0.0, 1.0);

        FIRING_DUTY_SUPPRESSION_FACTOR = BUILDER
            .comment("Fraction of the suppression dispersion shake taken while firing under",
                     "SUPPRESSIVE discipline (0.0 to 1.0) — duty fire accepts the shake as the",
                     "price of base fire. Default: 0.3")
            .defineInRange("dutySuppressionFactor", 0.3, 0.0, 1.0);

        FIRING_MISS_STREAK_STEP = BUILDER
            .comment("Aim-gate increase per ineffective burst on the same target (0.0 to 0.5).",
                     "A burst is ineffective when the target takes no damage from it. The soldier",
                     "demands a better firing solution instead of hammering the same shot.",
                     "Default: 0.12")
            .defineInRange("missStreakStep", 0.12, 0.0, 0.5);

        FIRING_MISS_STREAK_CAP = BUILDER
            .comment("Maximum counted ineffective bursts (0 to 8). Default: 4")
            .defineInRange("missStreakCap", 4, 0, 8);

        FIRING_MISS_STREAK_RECOVERY_SCALE = BUILDER
            .comment("Extra burst recovery per ineffective burst (0.0 to 2.0): the soldier takes",
                     "time to re-aim instead of re-engaging instantly. Default: 0.5")
            .defineInRange("missStreakRecoveryScale", 0.5, 0.0, 2.0);

        FIRING_MISS_STREAK_DECAY_TICKS = BUILDER
            .comment("Ticks without shooting for one streak step to cool off (20 to 600).",
                     "Default: 100 (5 seconds per step)")
            .defineInRange("missStreakDecayTicks", 100, 20, 600);

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

        BASIC_DETECTION_DISTANCE = BUILDER
            .comment("Focused detection distance for riflemen and enemy soldiers (blocks).",
                     "Lower values reduce entity scanning work and limit long-range detection.",
                     "Default: 72.0 blocks")
            .defineInRange("basicDetectionDistance", 72.0, 1.0, 256.0);

        MACHINE_GUNNER_DETECTION_DISTANCE = BUILDER
            .comment("Focused detection distance for isolated machine gunners (blocks).",
                     "Machine gunners retain a longer detection range for area suppression.",
                     "Default: 96.0 blocks")
            .defineInRange("machineGunnerDetectionDistance", 96.0, 1.0, 256.0);
        
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

        BUILDER.push("grenades");

        GRENADES_ENABLED = BUILDER
            .comment("Allow soldiers to use supported LesRaisins Tactical Equipments explosive grenades.",
                     "Default: true")
            .define("enabled", true);

        GRENADE_PERSONAL_COOLDOWN_TICKS = BUILDER
            .comment("Minimum ticks between grenade throws by one soldier. Default: 600 (30 seconds).")
            .defineInRange("personalCooldownTicks", 600, 0, 12000);

        GRENADE_SQUAD_INTERVAL_TICKS = BUILDER
            .comment("Minimum ticks between grenade throws by one squad. Default: 160 (8 seconds).")
            .defineInRange("squadIntervalTicks", 160, 0, 12000);

        GRENADE_MIN_RANGE = BUILDER
            .comment("Minimum distance for an autonomous grenade throw. Default: 6 blocks.")
            .defineInRange("minRange", 6.0, 1.0, 64.0);

        GRENADE_MAX_RANGE = BUILDER
            .comment("Maximum distance for an autonomous grenade throw. Default: 32 blocks.",
                     "The physical range of a throw is also bounded by the grenade's",
                     "launch speed and bounce behavior; the smaller of the two applies.",
                     "The /stevesarmy grenade command uses the physical range instead.")
            .defineInRange("maxRange", 32.0, 4.0, 64.0);

        GRENADE_SAFETY_MARGIN = BUILDER
            .comment("Extra distance kept between grenade blast candidates and friendlies. Default: 2 blocks.")
            .defineInRange("safetyMargin", 2.0, 0.0, 8.0);

        GRENADE_MIN_THREAT_ACCURACY = BUILDER
            .comment("Minimum remembered threat accuracy for a hidden-target grenade throw.",
                     "Default: 0.65")
            .defineInRange("minThreatAccuracy", 0.65f, 0.0f, 1.0f);

        GRENADE_AIM_ERROR_SCALE = BUILDER
            .comment("Scales autonomous grenade throw inaccuracy. Default: 1.0.",
                     "0.0 restores deterministic aim; higher values make throws less precise.")
            .defineInRange("aimErrorScale", 1.0, 0.0, 2.0);

        GRENADE_OVERTHROW_DISTANCE = BUILDER
            .comment("Preferred horizontal distance beyond the target for autonomous grenade landings.",
                     "This helps grenades clear the near side of enemy cover. Default: 2.5 blocks.")
            .defineInRange("overthrowDistance", 2.5, 0.0, 8.0);

        GRENADE_THROW_POWER_SCALE = BUILDER
            .comment("Scales the launch speed of soldier grenade throws.",
                     "1.0 matches a player throwing at standing power; raise it to make",
                     "soldier throws travel further (e.g. 1.7 reaches roughly 50 blocks).",
                     "Default: 1.0")
            .defineInRange("throwPowerScale", 1.0, 0.1, 3.0);

        BUILDER.pop();

        BUILDER.push("smoke");

        SMOKE_DEPLOYMENT_ENABLED = BUILDER
            .comment("Allow heavily suppressed attacking fireteams to deploy a LesRaisins",
                     "smoke screen. Requires grenades.enabled and the LesRaisins Tactical",
                     "Equipments mod. Soldiers throw a smoke grenade carried in their",
                     "general inventory (lrtactical:throwable with",
                     "ThrowableId=lrtactical:smoke_grenade). Default: true")
            .define("enabled", true);

        SMOKE_TRIGGER_HOLD_TICKS = BUILDER
            .comment("How long a fireteam must stay heavily suppressed during an attack",
                     "before a member deploys smoke. Default: 60 (3 seconds).")
            .defineInRange("triggerHoldTicks", 60, 0, 1200);

        SMOKE_FIRETEAM_COOLDOWN_TICKS = BUILDER
            .comment("Minimum ticks between smoke deployments by one fireteam.",
                     "Default: 600 (30 seconds).")
            .defineInRange("fireteamCooldownTicks", 600, 0, 24000);

        SMOKE_SCREEN_FRACTION = BUILDER
            .comment("Where the smoke screen lands on the line from the fireteam centroid",
                     "to the incoming-fire position (0.0 = on the team, 1.0 = on the enemy).",
                     "Default: 1.0 (blind the enemy position directly).")
            .defineInRange("screenFraction", 1.0, 0.1, 1.0);

        SMOKE_ADVANCE_UNDER_SMOKE = BUILDER
            .comment("While a smoke screen is active, the pinned fireteam may advance",
                     "through it despite heavy suppression. Default: true")
            .define("advanceUnderSmoke", true);

        SMOKE_SCREEN_DURATION_TICKS = BUILDER
            .comment("How long a smoke screen keeps enabling the advance after the throw.",
                     "Default: 300 (15 seconds).")
            .defineInRange("screenDurationTicks", 300, 40, 1200);

        SMOKE_GRENADES_PER_SCREEN = BUILDER
            .comment("Smoke grenades thrown per deployment, spread perpendicular to the",
                     "team-to-enemy line to build a screen instead of a single puff.",
                     "Each grenade comes from a different fireteam member's inventory;",
                     "fewer are thrown if fewer members carry smoke. Default: 3.")
            .defineInRange("grenadesPerScreen", 3, 1, 6);

        SMOKE_FALL_RATE_MULTIPLIER = BUILDER
            .comment("Multiplier on the fireteam suppression fall rate while that",
                     "fireteam's smoke screen is active: smoke cuts the enemy's",
                     "observation, so pressure should drop in seconds, not half",
                     "a minute. Default: 5.0")
            .defineInRange("fallRateMultiplier", 5.0, 1.0, 20.0);

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

        VS2_DISMOUNT_REBOARD_DELAY = BUILDER
            .comment("Ticks a soldier refuses automatic re-boarding after a manual dismount",
                     "(vehicle wheel Dismount or /stevesarmy transport release).",
                     "Without this, FOLLOW soldiers on the owner's ship would instantly re-seat,",
                     "making a manual dismount impossible.",
                     "Default: 100 (5 seconds)")
            .defineInRange("dismountReboardDelay", 100, 0, 1200);

        BUILDER.pop();

        BUILDER.push("vehicleCrew");

        VEHICLE_CREW_ENABLED = BUILDER
            .comment("Enable the vehicle crew role. Requires Valkyrien Skies; tallyho provides",
                     "the hull MG and periscope stations the role can man (reflection-based,",
                     "no hard dependency). Without tallyho crew soldiers seat and stay only.")
            .define("enabled", true);

        VEHICLE_CREW_SEAT_SEARCH_RADIUS = BUILDER
            .comment("How far an unseated vehicle crew soldier looks for a free Create seat",
                     "entity to teleport to. Default: 64 blocks.")
            .defineInRange("seatSearchRadius", 64.0, 4.0, 256.0);

        VEHICLE_CREW_STATION_REACH = BUILDER
            .comment("How far a seated crew soldier may man a hull MG or periscope from its seat.",
                     "Default: 16 blocks.")
            .defineInRange("stationReach", 16.0, 2.0, 64.0);

        VEHICLE_CREW_DETECTION_DISTANCE = BUILDER
            .comment("Focused detection distance for crewed optics (hull MG / periscope),",
                     "independent of infantry detection. A hull MG engages far beyond rifle",
                     "range, so targets past normal detection distance would be aimed at but",
                     "never recognized. Default: 96 blocks.")
            .defineInRange("detectionDistance", 96.0, 16.0, 512.0);

        VEHICLE_CREW_TRAVERSE_SPEED = BUILDER
            .comment("Maximum turret/optic rotation speed in degrees per tick. The mounted gun",
                     "cannot snap-aim like an eye; tracking takes time. Default: 3.0 (60 deg/s).")
            .defineInRange("traverseSpeedDegPerTick", 3.0, 0.5, 30.0);

        VEHICLE_CREW_BLOOM_PER_SHOT = BUILDER
            .comment("Sustained-fire bloom: each shot adds this much aim deviation growth",
                     "(replaces gun recoil for a stabilized mount). Short bursts stay accurate,",
                     "long sprays degrade. Default: 0.06.")
            .defineInRange("bloomPerShot", 0.06, 0.0, 1.0);

        VEHICLE_CREW_BLOOM_MAX = BUILDER
            .comment("Maximum bloom; final sigma is multiplied by (1 + bloom). Default: 1.0 (2x spread).")
            .defineInRange("bloomMax", 1.0, 0.0, 3.0);

        VEHICLE_CREW_BLOOM_DECAY = BUILDER
            .comment("Bloom lost per tick while not firing. Default: 0.02 (full recovery in ~3s).")
            .defineInRange("bloomDecayPerTick", 0.02, 0.0, 1.0);

        VEHICLE_CREW_DUTY_SCAN_INTERVAL = BUILDER
            .comment("Ticks between scans for a free hull MG / periscope while seated. Default: 20.")
            .defineInRange("dutyScanInterval", 20, 5, 200);

        VEHICLE_CREW_SEAT_SCAN_INTERVAL = BUILDER
            .comment("Ticks between scans for a free seat while unseated. Default: 40 (2 seconds).")
            .defineInRange("seatScanInterval", 40, 5, 400);

        VEHICLE_CREW_SUPPRESSION_ENABLED = BUILDER
            .comment("Hull MG crews shift to sustained suppressive fire at the last-known position",
                     "of squad threats when no target is visible. The fired rounds already apply",
                     "suppression along their trajectory. Default: true.")
            .define("suppressionEnabled", true);

        VEHICLE_CREW_SUPPRESSION_RANGE = BUILDER
            .comment("Maximum distance of a last-known enemy position a hull MG will suppress.",
                     "Default: 128 (matches infantry suppression range).")
            .defineInRange("suppressionRange", 128.0, 16.0, 256.0);

        BUILDER.pop();

        BUILDER.push("vehicleMountHandles");

        VEHICLE_HANDLES_ENABLED = BUILDER
            .comment("Use VS Analog Warfare vehicle mount handle links for soldier vehicle",
                     "orders: wheel MOUNT prefers the handle's linked seats (creating them",
                     "when needed), wheel DISMOUNT lets soldiers out at the handle instead",
                     "of wherever they sat. Ships or seats without links fall back to the",
                     "plain free-seat behavior. Reflection-based; no hard dependency.")
            .define("enabled", true);

        VEHICLE_HANDLES_DISMOUNT_GRACE = BUILDER
            .comment("Ticks a soldier may stand at a mount handle inside the ship after a",
                     "handle dismount before ship-extraction moves it to safe ground.",
                     "Default: 100 (5 seconds).")
            .defineInRange("dismountGraceTicks", 100, 0, 1200);

        BUILDER.pop();

        BUILDER.push("armorDoctrine");

        ARMOR_AWARENESS_ENABLED = BUILDER
            .comment("Soldiers detect enemy-crewed vehicle turrets (tallyho hull MG / periscope)",
                     "as hard targets and react by role: squads without an anti-armor weapon hide,",
                     "break line of sight, and displace out of the vehicle's path; squads with an",
                     "anti-armor gun (see atGunIdPatterns) designate its carrier as the armor hunter,",
                     "everyone else suppresses the vehicle to keep its crew buttoned. Requires tallyho.",
                     "Default: true.")
            .define("enabled", true);

        ARMOR_DETECTION_DISTANCE = BUILDER
            .comment("How far a soldier notices an actively crewed enemy vehicle turret.",
                     "Vehicles are large and loud; detection is instant inside this range.",
                     "Default: 48 blocks.")
            .defineInRange("detectionDistance", 48.0, 8.0, 256.0);

        ARMOR_PATH_DISPLACEMENT_ENABLED = BUILDER
            .comment("Squads without anti-armor weapons displace away when an enemy vehicle's",
                     "projected path closes on their cover. Player orders still override.",
                     "Default: true.")
            .define("pathDisplacementEnabled", true);

        ARMOR_PATH_CORRIDOR_WIDTH = BUILDER
            .comment("Width of the corridor in front of a moving vehicle that cover positions",
                     "and soldiers avoid. Default: 12 blocks.")
            .defineInRange("pathCorridorWidth", 12.0, 2.0, 64.0);

        ARMOR_ENGAGEMENT_MAX_RANGE = BUILDER
            .comment("Maximum range at which the designated armor hunter opens fire on a",
                     "vehicle. Keep this inside the AT gun's effective range so rockets do",
                     "not sail past the hull. Default: 48 blocks.")
            .defineInRange("engagementMaxRange", 48.0, 8.0, 256.0);

        ARMOR_AT_GUN_PATTERNS = BUILDER
            .comment("Gun-ID substrings that count as anti-armor weapons. A soldier whose",
                     "current gun ID (TaCZ gun id, e.g. tacz:rpg7) contains any pattern is",
                     "designated the squad's armor hunter. Default: rpg / rocket / launcher.")
            .defineList("atGunIdPatterns",
                java.util.List.of("rpg", "rocket", "launcher"),
                entry -> entry instanceof String);

        ARMOR_DOCTRINE_OVERRIDE = BUILDER
            .comment("Force the reaction branch for testing: AUTO uses each squad's real",
                     "loadout; NO_AT makes every squad react as if it has no anti-armor",
                     "weapon; FORCE_AT makes every squad count as having anti-armor support",
                     "(riflemen will suppress the vehicle even with no hunter designated).",
                     "Default: AUTO.")
            .defineEnum("doctrineOverride", ArmorDoctrineOverride.AUTO);

        BUILDER.pop();

        BUILDER.push("skins");

        SKIN_RANDOMIZE_ON_SPAWN = BUILDER
            .comment("Give newly spawned soldiers a random skin from <game dir>/stevesarmy/skins/*.png.",
                     "PNGs use the vanilla player-skin format (64x64, or 64x32 legacy);",
                     "the filename without extension is the skin name.",
                     "Skins can also be switched per-soldier with the Skin Knife item.",
                     "Default: false")
            .define("randomizeOnSpawn", false);

        BUILDER.pop();

        BUILDER.push("performance");

        OPTIMIZATION_LEVEL = BUILDER
            .comment("Performance optimization profile: 0=compatibility, 1=conservative, 2=balanced, 3=aggressive.",
                     "Retained for config-file compatibility; soldier perception uses the compatibility cadence.",
                     "The isolated machine-gunner pipeline remains enabled independently of this setting.",
                      "Default: 3")
            .defineInRange("optimizationLevel", 3, 0, 3);

        RETRY_POLICY_ENABLED = BUILDER
            .comment("Enable tactical retry suppression, emergency cover admission, and queue aging.",
                     "This can change flank and suppression reaction timing.",
                     "Default: true")
            .define("retryPolicyEnabled", true);

        PERCEPTION_FRAME_ENABLED = BUILDER
            .comment("Enable same-tick target, smoke, and visibility perception reuse.",
                     "The frame is invalidated at tick boundaries and on relevant world/entity changes.",
                     "Default: true")
            .define("perceptionFrameEnabled", true);

        PURE_EVALUATOR_SHADOW_ENABLED = BUILDER
            .comment("Run the pure cover evaluator in shadow mode for NORMAL cover searches.",
                     "Legacy cover scoring remains authoritative; this only captures and compares results.",
                     "Default: false")
            .define("pureEvaluatorShadowEnabled", false);

        ASYNC_COVER_SHADOW_ENABLED = BUILDER
            .comment("Run the pure rifleman NORMAL cover evaluator on a bounded worker in read-only shadow mode.",
                     "Snapshot capture, live validation, pathfinding, reservations, and gameplay remain on the server thread.",
                     "Results never affect gameplay; disable this if worker diagnostics are not needed.",
                     "Default: false")
            .define("asyncCoverShadowEnabled", false);

        ASYNC_COVER_PILOT_ENABLED = BUILDER
            .comment("Use the bounded worker to select routine NORMAL rifleman cover.",
                     "Candidates are discovered and captured on the server thread; live validation, reservations, paths, and movement remain server-thread only.",
                     "Unsupported searches and any failed async step use the synchronous cover search.",
                     "Default: true")
            .define("asyncCoverPilotEnabled", true);

        ASYNC_PATHFINDING_ENABLED = BUILDER
            .comment("Run soldier A* path searches on a bounded worker pool.",
                     "World preparation, path validation, navigation, and entity movement remain on the server thread.",
                     "Default: true")
            .define("asyncPathfindingEnabled", true);

        ASYNC_PATHFINDING_THREADS = BUILDER
            .comment("Maximum worker threads used for soldier A* path searches.",
                     "Start with 2; more threads can increase contention and stale work.",
                     "Default: 2")
            .defineInRange("asyncPathfindingThreads", 2, 1, 8);

        ASYNC_PATHFINDING_QUEUE_CAPACITY = BUILDER
            .comment("Maximum queued soldier path searches before new requests use synchronous fallback.",
                     "Default: 16")
            .defineInRange("asyncPathfindingQueueCapacity", 16, 1, 128);

        ASYNC_PATHFINDING_MAX_PENDING_TICKS = BUILDER
            .comment("Maximum ticks a soldier may wait for an async path before it is rejected.",
                     "Default: 40")
            .defineInRange("asyncPathfindingMaxPendingTicks", 40, 5, 200);

        EXACT_PATH_VALIDATION_LIMIT = BUILDER
            .comment("Maximum exact navigation paths tested while selecting cover during one cover search.",
                     "The limit applies to GO_TO, FOLLOW, ATTACK, HOLD, and suppression cover searches.",
                     "Lower values limit server-thread spikes; the best reachable covers found within the budget remain eligible.",
                     "Default: 16")
            .defineInRange("exactPathValidationLimit", 16, 1, 50);

        COVER_SEARCH_FAILURE_RETRY_TICKS = BUILDER
            .comment("Ticks before retrying an unchanged failed non-emergency cover search.",
                     "The cooldown is bypassed when the soldier, threat, objective, relocation, or blacklist context changes.",
                     "Default: 20")
            .defineInRange("coverSearchFailureRetryTicks", 20, 1, 200);

        BUILDER.pop();

        BUILDER.push("fireteam_suppression");

        FIRETEAM_SUPPRESSION_ENABLED = BUILDER
            .comment("Enable fireteam-level suppression tracking (slow EMA of member suppression).",
                     "Default: true")
            .define("enabled", true);

        FIRETEAM_RISE_RATE = BUILDER
            .comment("EMA rise rate per tick for fireteam suppression (0.0 to 1.0).",
                     "Default: 0.01 (~11s to 90% of target)")
            .defineInRange("riseRate", 0.01, 0.0001, 1.0);

        FIRETEAM_FALL_RATE = BUILDER
            .comment("EMA fall rate per tick for fireteam suppression (0.0 to 1.0).",
                     "Default: 0.002 (~25s half-life)")
            .defineInRange("fallRate", 0.002, 0.0001, 1.0);

        FIRETEAM_SUPPRESSION_IMPULSE = BUILDER
            .comment("Instant impulse added per member newly suppressed (CLEAR->PRESSURED/PINNED).",
                     "Default: 0.08")
            .defineInRange("suppressionImpulse", 0.08, 0.0, 1.0);

        FIRETEAM_CASUALTY_BUMP = BUILDER
            .comment("One-time bump added when a fireteam member dies.",
                     "Default: 0.35")
            .defineInRange("casualtyBump", 0.35, 0.0, 1.0);

        FIRETEAM_INCOMING_PRESSURE_DECAY = BUILDER
            .comment("Per-tick decay multiplier for incoming fire pressure (0.8-1.0).",
                     "Lower = faster decay. Default: 0.97")
            .defineInRange("incomingPressureDecay", 0.97, 0.8, 1.0);

        FIRETEAM_SUPERIORITY_DAMPENING = BUILDER
            .comment("How much fire superiority dampens suppression rise.",
                     "0.0 = no effect, 1.0 = full effect. Default: 0.7")
            .defineInRange("superiorityDampening", 0.7, 0.0, 1.0);

        FIRETEAM_SUPPRESSION_THRESHOLD = BUILDER
            .comment("Fireteam level threshold for SUPPRESSED state (0.0 to 1.0).",
                     "Default: 0.30")
            .defineInRange("suppressionThreshold", 0.30, 0.0, 1.0);

        FIRETEAM_HEAVY_THRESHOLD = BUILDER
            .comment("Fireteam level threshold for HEAVY state (0.0 to 1.0).",
                     "Default: 0.60")
            .defineInRange("heavyThreshold", 0.60, 0.0, 1.0);

        FIRETEAM_PEEK_BLOCK_THRESHOLD = BUILDER
            .comment("Fireteam level at which HOLD pressured peeks are hard-blocked.",
                     "Default: 0.45")
            .defineInRange("peekBlockThreshold", 0.45, 0.0, 1.0);

        FIRETEAM_HOLD_NOTIFY_TICKS = BUILDER
            .comment("Ticks HEAVY must be sustained before chat alert (ticks).",
                     "Default: 120 (6s)")
            .defineInRange("holdNotifyTicks", 120, 0, 10000);

        FIRETEAM_NOTIFY_COOLDOWN_TICKS = BUILDER
            .comment("Cooldown between repeated HEAVY alerts per fireteam (ticks).",
                     "Default: 600 (30s)")
            .defineInRange("notifyCooldownTicks", 600, 0, 100000);

        BUILDER.pop();

        BUILDER.push("tactical_coordination");

        RECOVERY_SAFETY_PEEK_MS = BUILDER
            .comment("After recovering from suppression in cover, soldier must complete a",
                     "safety peek (no new suppression event during the peek) before it may",
                     "start a routine reposition/advance. Max time to wait for that peek.",
                     "Default: 2000 (2s)")
            .defineInRange("recoverySafetyPeekMs", 2000, 0, 20000);

        COVER_SOFT_FACTOR = BUILDER
            .comment("Soft covering-fire requirement: when no nearby teammate is peeking/exposed",
                     "to cover, multiply the chance to start a routine reposition by this factor",
                     "(0.0 = never advance un-covered, 1.0 = no penalty). Default: 0.5")
            .defineInRange("coverSoftFactor", 0.5, 0.0, 1.0);

        COVER_WAIT_TIMEOUT_TICKS = BUILDER
            .comment("How long a soldier waits (with reduced move chance) for a teammate to",
                     "cover before advancing anyway without cover. Default: 120 (6s)")
            .defineInRange("coverWaitTimeoutTicks", 120, 0, 1000);

        FIRETEAM_HEAVY_HOLD_REPOSITION = BUILDER
            .comment("When the fireteam is HEAVILY suppressed (level >= heavyThreshold), keep",
                     "routine reposition requests pending until the team drops below HEAVY.",
                     "Default: true")
            .define("fireteamHeavyHoldReposition", true);

        GROUP_COHESION_BAND_BLOCKS = BUILDER
            .comment("Distance band (blocks) beyond the fireteam centroid that counts as",
                     "'ahead' or 'behind' the group for dynamic dwell scaling. Default: 8.0")
            .defineInRange("groupCohesionBandBlocks", 8.0, 1.0, 64.0);

        GROUP_AHEAD_DWELL_MULT = BUILDER
            .comment("Dwell multiplier when a soldier is far ahead of the group and close to",
                     "the enemy: wait for the fireteam to catch up. Default: 1.6")
            .defineInRange("groupAheadDwellMult", 1.6, 1.0, 5.0);

        GROUP_BEHIND_DWELL_MULT = BUILDER
            .comment("Dwell multiplier when a soldier is far behind the group: catch up quickly.",
                     "Default: 0.6")
            .defineInRange("groupBehindDwellMult", 0.6, 0.2, 1.0);

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

    public static boolean isDynamicFiringEnabled() {
        return DYNAMIC_FIRING_ENABLED.get();
    }

    public static float getFiringPersonalityStrength() {
        return FIRING_PERSONALITY_STRENGTH.get().floatValue();
    }

    public static SuppressedFireMode getSuppressedFireMode() {
        return SUPPRESSED_FIRE_MODE.get();
    }

    public static float getSuppressionThresholdRelief() {
        return SUPPRESSION_THRESHOLD_RELIEF.get().floatValue();
    }

    public static float getSuppressionThresholdTighten() {
        return SUPPRESSION_THRESHOLD_TIGHTEN.get().floatValue();
    }

    public static float getSuppressionSigmaScale() {
        return SUPPRESSION_SIGMA_SCALE.get().floatValue();
    }

    public static float getFiringFireteamBlend() {
        return FIRING_FIRETEAM_BLEND.get().floatValue();
    }

    public static float getFiringDutySuppressionFactor() {
        return FIRING_DUTY_SUPPRESSION_FACTOR.get().floatValue();
    }

    public static float getFiringMissStreakStep() {
        return FIRING_MISS_STREAK_STEP.get().floatValue();
    }

    public static int getFiringMissStreakCap() {
        return FIRING_MISS_STREAK_CAP.get();
    }

    public static float getFiringMissStreakRecoveryScale() {
        return FIRING_MISS_STREAK_RECOVERY_SCALE.get().floatValue();
    }

    public static int getFiringMissStreakDecayTicks() {
        return FIRING_MISS_STREAK_DECAY_TICKS.get();
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

    public static double getBasicDetectionDistance() {
        return BASIC_DETECTION_DISTANCE.get();
    }

    public static double getMachineGunnerDetectionDistance() {
        return MACHINE_GUNNER_DETECTION_DISTANCE.get();
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

    public static boolean areGrenadesEnabled() {
        return GRENADES_ENABLED.get();
    }

    public static int getGrenadePersonalCooldownTicks() {
        return GRENADE_PERSONAL_COOLDOWN_TICKS.get();
    }

    public static int getGrenadeSquadIntervalTicks() {
        return GRENADE_SQUAD_INTERVAL_TICKS.get();
    }

    public static double getGrenadeMinRange() {
        return GRENADE_MIN_RANGE.get();
    }

    public static double getGrenadeMaxRange() {
        return GRENADE_MAX_RANGE.get();
    }

    public static double getGrenadeSafetyMargin() {
        return GRENADE_SAFETY_MARGIN.get();
    }

    public static float getGrenadeMinThreatAccuracy() {
        return GRENADE_MIN_THREAT_ACCURACY.get().floatValue();
    }

    public static float getGrenadeAimErrorScale() {
        return GRENADE_AIM_ERROR_SCALE.get().floatValue();
    }

    public static double getGrenadeOverthrowDistance() {
        return GRENADE_OVERTHROW_DISTANCE.get();
    }

    public static double getGrenadeThrowPowerScale() {
        return GRENADE_THROW_POWER_SCALE.get();
    }

    public static boolean isSmokeDeploymentEnabled() {
        return SMOKE_DEPLOYMENT_ENABLED.get();
    }

    public static int getSmokeTriggerHoldTicks() {
        return SMOKE_TRIGGER_HOLD_TICKS.get();
    }

    public static int getSmokeFireteamCooldownTicks() {
        return SMOKE_FIRETEAM_COOLDOWN_TICKS.get();
    }

    public static double getSmokeScreenFraction() {
        return SMOKE_SCREEN_FRACTION.get();
    }

    public static boolean isSmokeAdvanceUnderSmoke() {
        return SMOKE_ADVANCE_UNDER_SMOKE.get();
    }

    public static int getSmokeScreenDurationTicks() {
        return SMOKE_SCREEN_DURATION_TICKS.get();
    }

    public static int getSmokeGrenadesPerScreen() {
        return SMOKE_GRENADES_PER_SCREEN.get();
    }

    public static double getSmokeFallRateMultiplier() {
        return SMOKE_FALL_RATE_MULTIPLIER.get().doubleValue();
    }

    public static boolean isSkinRandomizeOnSpawn() {
        return SKIN_RANDOMIZE_ON_SPAWN.get();
    }


    public static int getOptimizationLevel() {
        return OPTIMIZATION_LEVEL.get();
    }

    public static boolean isRetryPolicyEnabled() {
        return RETRY_POLICY_ENABLED.get();
    }

    public static boolean isPerceptionFrameEnabled() {
        return PERCEPTION_FRAME_ENABLED.get();
    }

    public static boolean isPureEvaluatorShadowEnabled() {
        return PURE_EVALUATOR_SHADOW_ENABLED.get();
    }

    public static boolean isAsyncCoverShadowEnabled() {
        return ASYNC_COVER_SHADOW_ENABLED.get();
    }

    public static boolean isAsyncCoverPilotEnabled() {
        return ASYNC_COVER_PILOT_ENABLED.get();
    }

    public static boolean isAsyncPathfindingEnabled() {
        return ASYNC_PATHFINDING_ENABLED.get();
    }

    public static int getAsyncPathfindingThreads() {
        return ASYNC_PATHFINDING_THREADS.get();
    }

    public static int getAsyncPathfindingQueueCapacity() {
        return ASYNC_PATHFINDING_QUEUE_CAPACITY.get();
    }

    public static int getAsyncPathfindingMaxPendingTicks() {
        return ASYNC_PATHFINDING_MAX_PENDING_TICKS.get();
    }

    public static int getExactPathValidationLimit() {
        return EXACT_PATH_VALIDATION_LIMIT.get();
    }

    public static int getCoverSearchFailureRetryTicks() {
        return COVER_SEARCH_FAILURE_RETRY_TICKS.get();
    }

    public static boolean isFireteamSuppressionEnabled() {
        return FIRETEAM_SUPPRESSION_ENABLED.get();
    }

    public static float getFireteamRiseRate() {
        return FIRETEAM_RISE_RATE.get().floatValue();
    }

    public static float getFireteamFallRate() {
        return FIRETEAM_FALL_RATE.get().floatValue();
    }

    public static float getFireteamSuppressionImpulse() {
        return FIRETEAM_SUPPRESSION_IMPULSE.get().floatValue();
    }

    public static float getFireteamCasualtyBump() {
        return FIRETEAM_CASUALTY_BUMP.get().floatValue();
    }

    public static float getFireteamIncomingPressureDecay() {
        return FIRETEAM_INCOMING_PRESSURE_DECAY.get().floatValue();
    }

    public static float getFireteamSuperiorityDampening() {
        return FIRETEAM_SUPERIORITY_DAMPENING.get().floatValue();
    }

    public static float getFireteamSuppressionThreshold() {
        return FIRETEAM_SUPPRESSION_THRESHOLD.get().floatValue();
    }

    public static float getFireteamHeavyThreshold() {
        return FIRETEAM_HEAVY_THRESHOLD.get().floatValue();
    }

    public static float getFireteamPeekBlockThreshold() {
        return FIRETEAM_PEEK_BLOCK_THRESHOLD.get().floatValue();
    }

    public static int getFireteamHoldNotifyTicks() {
        return FIRETEAM_HOLD_NOTIFY_TICKS.get();
    }

    public static int getFireteamNotifyCooldownTicks() {
        return FIRETEAM_NOTIFY_COOLDOWN_TICKS.get();
    }

    public static int getRecoverySafetyPeekMs() {
        return RECOVERY_SAFETY_PEEK_MS.get();
    }

    public static float getCoverSoftFactor() {
        return COVER_SOFT_FACTOR.get().floatValue();
    }

    public static int getCoverWaitTimeoutTicks() {
        return COVER_WAIT_TIMEOUT_TICKS.get();
    }

    public static boolean isFireteamHeavyHoldReposition() {
        return FIRETEAM_HEAVY_HOLD_REPOSITION.get();
    }

    public static float getGroupCohesionBandBlocks() {
        return GROUP_COHESION_BAND_BLOCKS.get().floatValue();
    }

    public static float getGroupAheadDwellMult() {
        return GROUP_AHEAD_DWELL_MULT.get().floatValue();
    }

    public static float getGroupBehindDwellMult() {
        return GROUP_BEHIND_DWELL_MULT.get().floatValue();
    }


    public static boolean isArmorAwarenessEnabled() {
        return ARMOR_AWARENESS_ENABLED.get();
    }

    public static double getArmorDetectionDistance() {
        return ARMOR_DETECTION_DISTANCE.get();
    }

    public static boolean isArmorPathDisplacementEnabled() {
        return ARMOR_PATH_DISPLACEMENT_ENABLED.get();
    }

    public static double getArmorPathCorridorWidth() {
        return ARMOR_PATH_CORRIDOR_WIDTH.get();
    }

    public static double getArmorEngagementMaxRange() {
        return ARMOR_ENGAGEMENT_MAX_RANGE.get();
    }

    public static java.util.List<? extends String> getArmorAtGunPatterns() {
        return ARMOR_AT_GUN_PATTERNS.get();
    }

    public static ArmorDoctrineOverride getArmorDoctrineOverride() {
        return ARMOR_DOCTRINE_OVERRIDE.get();
    }

    /** Legacy compatibility hook; nearby-target snapshots are disabled. */
    public static int getTargetCandidateCacheTicks() {
        return 0;
    }

    /** Legacy compatibility hook; positional visibility caching is disabled. */
    public static int getPositionVisibilityCacheTicks() {
        return 0;
    }

    /** Legacy compatibility hook; aim-point caching is disabled. */
    public static int getAimPointCacheTicks() {
        return 0;
    }

    /** Returns the number of ticks an exposure cache entry remains valid. */
    public static int getExposureCacheTicks() {
        return 1;
    }

    public static boolean useSharedTargetQueryCache() {
        return isPerceptionFrameEnabled();
    }
}

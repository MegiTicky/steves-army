package com.stevesarmy;

import net.minecraftforge.common.ForgeConfigSpec;

public class StevesArmyConfig {
    
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue SOLDIER_CHUNK_LOADING_ENABLED;
    public static final ForgeConfigSpec.IntValue SOLDIER_CHUNK_LOADING_DISTANCE;
    
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
    public static final ForgeConfigSpec.IntValue MG_DIRECT_BURST_SHOTS;
    public static final ForgeConfigSpec.IntValue MG_DIRECT_RECOVERY_TICKS;
    public static final ForgeConfigSpec.DoubleValue MG_AIM_QUALITY_THRESHOLD_SCALE;
    public static final ForgeConfigSpec.DoubleValue MG_TARGET_MOVE_PENALTY_SCALE;
    public static final ForgeConfigSpec.DoubleValue MG_AIM_BUILD_RATE_SCALE;
    public static final ForgeConfigSpec.DoubleValue MG_RECOIL_AIM_LOSS_SCALE;
    public static final ForgeConfigSpec.BooleanValue CRAWL_STANCE_RECOIL;
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
    public static final ForgeConfigSpec.BooleanValue MG_SUPPRESSION_ROUND_ROBIN;
    public static final ForgeConfigSpec.IntValue MG_SUPPRESSION_ROTATION_SIZE;

    /** How a suppressed soldier reacts when returning direct fire. */
    public enum SuppressedFireMode { SPRAY, HOLD }
    public static final ForgeConfigSpec.BooleanValue SQUAD_FRIENDLY_FIRE;
    public static final ForgeConfigSpec.DoubleValue THREAT_SMOOTH_BLEND_FACTOR;
    public static final ForgeConfigSpec.IntValue THREAT_SMOOTH_DECAY_TIME_MS;
    
    public static final ForgeConfigSpec.BooleanValue TARGET_MONSTERS;
    public static final ForgeConfigSpec.BooleanValue TARGET_TARGET_ENTITIES;
    public static final ForgeConfigSpec.BooleanValue MONSTERS_ATTACK_SOLDIERS;
    public static final ForgeConfigSpec.DoubleValue BASIC_DETECTION_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue MACHINE_GUNNER_DETECTION_DISTANCE;

    public static final ForgeConfigSpec.BooleanValue DANGER_MAP_ENABLED;
    public static final ForgeConfigSpec.DoubleValue DANGER_AMBIENT_RADIUS;
    public static final ForgeConfigSpec.IntValue DANGER_RAY_BUDGET_PER_TICK;
    public static final ForgeConfigSpec.IntValue DANGER_CELL_TTL_TICKS;
    public static final ForgeConfigSpec.IntValue DANGER_MAX_THREATS;
    public static final ForgeConfigSpec.DoubleValue DANGER_THREAT_RANGE;
    public static final ForgeConfigSpec.DoubleValue DANGER_PATH_ANTI_TANK_WEIGHT;
    public static final ForgeConfigSpec.DoubleValue DANGER_PATH_SUPPORT_WEIGHT;
    public static final ForgeConfigSpec.DoubleValue DANGER_PATH_RIFLEMAN_WEIGHT;
    public static final ForgeConfigSpec.DoubleValue DANGER_PATH_MACHINE_GUNNER_WEIGHT;
    public static final ForgeConfigSpec.DoubleValue DANGER_PATH_GARRISON_WEIGHT;
    public static final ForgeConfigSpec.BooleanValue DANGER_TRAVEL_RESPONSE;
    public static final ForgeConfigSpec.IntValue DANGER_TRAVEL_REROUTE_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.DoubleValue DANGER_TRAVEL_SUPPRESSION_GAIN;

    public static final ForgeConfigSpec.DoubleValue CLOSE_COMBAT_RANGE;
    public static final ForgeConfigSpec.DoubleValue CLOSE_COMBAT_EXIT_RANGE;
    public static final ForgeConfigSpec.DoubleValue CQB_EMERGENCY_RANGE;
    public static final ForgeConfigSpec.DoubleValue CQB_OVERRUN_RANGE;
    public static final ForgeConfigSpec.DoubleValue CQB_DOCK_FINISH_THRESHOLD;
    public static final ForgeConfigSpec.IntValue CQB_HOLD_NO_SHOT_TIMEOUT_TICKS;
    public static final ForgeConfigSpec.IntValue CQB_HOLD_RELEASE_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue CQB_HOLD_FREEZE_GRACE_TICKS;

    public static final ForgeConfigSpec.BooleanValue SQUAD_CONTACT_PLAN_ENABLED;
    public static final ForgeConfigSpec.DoubleValue CONTACT_COORDINATION_RANGE;
    public static final ForgeConfigSpec.DoubleValue CONTACT_DANGER_ARC_DEGREES;

    public static final ForgeConfigSpec.BooleanValue EXPOSED_COMBAT_ENABLED;
    public static final ForgeConfigSpec.IntValue EXPOSED_PLANT_MIN_TICKS;
    public static final ForgeConfigSpec.IntValue EXPOSED_ADJUST_WINDOW_TICKS;
    public static final ForgeConfigSpec.IntValue EXPOSED_ADJUST_MAX_ATTEMPTS;
    public static final ForgeConfigSpec.IntValue EXPOSED_ADJUST_TIMEOUT_TICKS;
    public static final ForgeConfigSpec.DoubleValue EXPOSED_SIDESTEP_BLOCKS;
    public static final ForgeConfigSpec.DoubleValue EXPOSED_WITHDRAW_DISTANCE;
    public static final ForgeConfigSpec.IntValue EXPOSED_WITHDRAW_TIMEOUT_TICKS;
    public static final ForgeConfigSpec.BooleanValue SQUAD_BOUNDING_ENABLED;
    public static final ForgeConfigSpec.IntValue SQUAD_BOUND_COVER_WINDOW_TICKS;
    public static final ForgeConfigSpec.IntValue SQUAD_BOUND_COVER_WAIT_TICKS;
    public static final ForgeConfigSpec.IntValue SQUAD_BOUND_MAX_TICKS;
    public static final ForgeConfigSpec.DoubleValue SQUAD_BOUND_THREAT_RANGE;
    public static final ForgeConfigSpec.IntValue HOLD_MARCH_DWELL_TICKS;
    public static final ForgeConfigSpec.BooleanValue CQB_DOORWAY_QUEUE_ENABLED;
    public static final ForgeConfigSpec.DoubleValue CQB_DOORWAY_SPACING;
    public static final ForgeConfigSpec.BooleanValue CQB_CORNER_WATCH_ENABLED;
    public static final ForgeConfigSpec.IntValue CQB_LOST_CONTACT_WATCH_TICKS;
    public static final ForgeConfigSpec.BooleanValue ATTACK_CLEAR_ENABLED;
    public static final ForgeConfigSpec.IntValue ATTACK_CLEAR_RADIUS;
    public static final ForgeConfigSpec.IntValue ATTACK_CLEAR_AWARENESS;
    public static final ForgeConfigSpec.IntValue ATTACK_CLEAR_RAY_BUDGET;
    public static final ForgeConfigSpec.IntValue ATTACK_CLEAR_SETTLE_TICKS;
    public static final ForgeConfigSpec.IntValue ATTACK_CLEAR_STALL_TICKS;
    public static final ForgeConfigSpec.BooleanValue ATTACK_CLEAR_DIRECT_EGRESS;
    public static final ForgeConfigSpec.BooleanValue ATTACK_PATH_SPINE_ENABLED;
    public static final ForgeConfigSpec.IntValue ATTACK_SPINE_FLOW_DWELL_MS;
    public static final ForgeConfigSpec.DoubleValue CLOSE_COMBAT_SEEK_RADIUS;
    public static final ForgeConfigSpec.IntValue CLOSE_COMBAT_REEVAL_INTERVAL_TICKS;

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
    public static final ForgeConfigSpec.BooleanValue VS2_SHIP_RAYCAST_GATE;
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
    public static final ForgeConfigSpec.DoubleValue ARMOR_ENGAGEMENT_MAX_RANGE;
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> ARMOR_AT_GUN_PATTERNS;
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> SIDEARM_GUN_PATTERNS;
    public static final ForgeConfigSpec.BooleanValue SOLDIER_DEATH_DROPS;
    public static final ForgeConfigSpec.BooleanValue SWAP_SYNCS_HEALTH;
    public static final ForgeConfigSpec.EnumValue<ArmorDoctrineOverride> ARMOR_DOCTRINE_OVERRIDE;
    public static final ForgeConfigSpec.BooleanValue OCCUPANCY_VEHICLE_DETECTION;
    public static final ForgeConfigSpec.BooleanValue SBW_COMPAT_ENABLED;

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
    public static final ForgeConfigSpec.IntValue GO_TO_SEEK_GRACE_TICKS;
    public static final ForgeConfigSpec.IntValue COVER_SCORING_MAX_CONTACTS;
    public static final ForgeConfigSpec.IntValue MOVE_GOAL_REPATH_TICKS;

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
    public static final ForgeConfigSpec.BooleanValue SUPPRESSED_RELOCATION_SAFE_ROUTE;
    public static final ForgeConfigSpec.DoubleValue GROUP_COHESION_BAND_BLOCKS;
    public static final ForgeConfigSpec.DoubleValue GROUP_AHEAD_DWELL_MULT;
    public static final ForgeConfigSpec.DoubleValue GROUP_BEHIND_DWELL_MULT;

    static {
        BUILDER.push("soldier_chunk_loading");
        SOLDIER_CHUNK_LOADING_ENABLED = BUILDER
            .comment("Keep a small entity-ticking area around loaded soldiers near a non-spectator player. May increase server load.",
                "Applies to friendly and enemy soldiers. Does not search unloaded chunks for soldiers.")
            .define("enabled", false);
        SOLDIER_CHUNK_LOADING_DISTANCE = BUILDER
            .comment("Maximum horizontal distance in blocks from any non-spectator player in the same dimension.",
                "This is a total distance, not an addition to simulation distance. Normal simulation still applies outside it.",
                "Each qualifying soldier keeps a 3x3 entity-ticking area plus Minecraft's supporting chunk border loaded.")
            .defineInRange("distance", 256, 16, 1024);
        BUILDER.pop();
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
            .comment("Tracking aim penalty when the target is moving, scaled by the effective aim build rate each tick.",
                     "Lowers settled tracking quality without overwhelming slow aim acquisition. Default: 0.05.")
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

        MG_DIRECT_BURST_SHOTS = BUILDER
            .comment("Burst length for suppressive machine guns in DIRECT fire (0 = legacy 5-shot profile).",
                     "MGs should hose at an active target the way they do in suppression: long",
                     "aimed bursts instead of the shared 5-shot profile. Default: 10")
            .defineInRange("mgDirectBurstShots", 10, 0, 30);

        MG_DIRECT_RECOVERY_TICKS = BUILDER
            .comment("Inter-burst recovery for suppressive machine guns in DIRECT fire (0 = legacy 10).",
                     "Default: 6")
            .defineInRange("mgDirectRecoveryTicks", 6, 0, 40);

        MG_AIM_QUALITY_THRESHOLD_SCALE = BUILDER
            .comment("Aim-quality gate cap for machine gunners (0 = standard path).",
                     "MG doctrine is to open up early and walk fire onto the target, so the",
                     "burst-start gate is capped like SUPPRESSIVE discipline: shotThreshold",
                     "uses at most this fraction of targetAimQuality. Default: 0.20")
            .defineInRange("mgAimQualityThresholdScale", 0.20, 0.0, 1.0);

        MG_TARGET_MOVE_PENALTY_SCALE = BUILDER
            .comment("Scales the target-moving aim decay for machine gunners (1.0 = standard).",
                     "Tracking fire is the MG's job: without relief the per-tick decay matches",
                     "the build rate at range and the gunner starves at the gate. Default: 0.4")
            .defineInRange("mgTargetMovePenaltyScale", 0.4, 0.0, 1.0);

        MG_AIM_BUILD_RATE_SCALE = BUILDER
            .comment("Aim-quality build-rate multiplier for machine gunners (1.0 = standard).",
                     "Belt-fed guns stay on target; raises the equilibrium quality at range",
                     "so sustained bursts survive. Default: 1.5")
            .defineInRange("mgAimBuildRateScale", 1.5, 1.0, 3.0);

        MG_RECOIL_AIM_LOSS_SCALE = BUILDER
            .comment("Scales the per-shot recoil aim loss for machine gunners (1.0 = standard).",
                     "A stationary gunner has the bipod down - the M249's TaCZ recoil (0.4/0.3)",
                     "charges the full shoulder-fire penalty today and collapses bursts after",
                     "1-2 shots. Stacks with the crawl multiplier while low-crouching.",
                     "Default: 0.5")
            .defineInRange("mgRecoilAimLossScale", 0.5, 0.0, 2.0);

        CRAWL_STANCE_RECOIL = BUILDER
            .comment("Mirror TaCZ's per-gun crawl_recoil_multiplier into the aim model: while",
                     "low-crouching (the duck/crawl pose), the per-shot recoil aim loss is",
                     "multiplied by the gun's crawl_recoil_multiplier (M249: 0.1). Prone keeps",
                     "its own 0.45 multiplier. Standing in the open pays the full penalty.",
                     "Default: true")
            .define("crawlStanceRecoil", true);

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

        MG_SUPPRESSION_ROUND_ROBIN = BUILDER
            .comment("Machine gunners rotate suppression bursts round-robin across known threats'",
                     " last-seen positions instead of stacking every burst on one contact.",
                     "Unsuppressed (unclaimed, not-yet-pinned) enemies come first in the",
                     "rotation; riflemen and heavy-ping fire are unchanged.",
                     "Default: true")
            .define("mgSuppressionRoundRobin", true);

        MG_SUPPRESSION_ROTATION_SIZE = BUILDER
            .comment("Maximum number of threats in a machine gunner's round-robin suppression",
                     "rotation (2 to 6). Single-contact fights are unaffected.",
                     "Default: 3")
            .defineInRange("mgSuppressionRotationSize", 3, 2, 6);

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

        MONSTERS_ATTACK_SOLDIERS = BUILDER
            .comment("Whether vanilla monsters proactively hunt soldiers (Recruits-style).",
                     "Without it monsters only retaliate after being attacked; with it zombies,",
                     "skeletons and other monsters actively hunt nearby soldiers.",
                     "Default: true")
            .define("monstersAttackSoldiers", true);

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

        BUILDER.push("danger_map");

        DANGER_MAP_ENABLED = BUILDER
            .comment("Per-squad spatial danger map: which nearby positions are exposed to",
                     "line-of-sight from the squad's known threats, probed at leg and eye heights.",
                     "Pure intelligence - it changes no behavior by itself; AI consumers read it",
                     "through later features.",
                     "Default: true")
            .define("dangerMapEnabled", true);

        DANGER_AMBIENT_RADIUS = BUILDER
            .comment("Radius (blocks) around the squad anchor the ambient baker keeps warm.",
                     "Default: 24.0 blocks")
            .defineInRange("dangerAmbientRadius", 24.0, 8.0, 64.0);

        DANGER_RAY_BUDGET_PER_TICK = BUILDER
            .comment("Global per-tick line-of-sight ray budget shared by every squad's danger map.",
                     "Baking defers when the budget runs out; queries never spend rays.",
                     "Sized for the 6-ray body-span fan: ~85 cells/tick for one threat,",
                     "still leaving head-room for two concurrent squads.",
                     "Default: 512 rays/tick")
            .defineInRange("dangerRayBudgetPerTick", 512, 16, 8192);

        DANGER_CELL_TTL_TICKS = BUILDER
            .comment("How long a baked danger cell stays fresh before re-baking.",
                     "Default: 60 ticks (3 s)")
            .defineInRange("dangerCellTtlTicks", 60, 20, 400);

        DANGER_MAX_THREATS = BUILDER
            .comment("Maximum number of tracked threats the map bakes shadows for (strongest first).",
                     "Default: 6")
            .defineInRange("dangerMaxThreats", 6, 1, 12);

        DANGER_THREAT_RANGE = BUILDER
            .comment("Threats farther than this from the squad anchor are ignored by the map.",
                     "Default: 96.0 blocks")
            .defineInRange("dangerThreatRange", 96.0, 16.0, 256.0);

        DANGER_PATH_ANTI_TANK_WEIGHT = BUILDER
            .comment("Pathfinding danger cost for ANTI_TANK soldiers: a fully-exposed corridor cell",
                     "adds this much malus (vanilla fire-type malus is ~8-16). Tank hunters approach",
                     "hard targets through covered ground; 0 disables.",
                     "Default: 4.0")
            .defineInRange("dangerPathAntiTankWeight", 4.0, 0.0, 16.0);

        DANGER_PATH_SUPPORT_WEIGHT = BUILDER
            .comment("Pathfinding danger cost for SUPPORT soldiers - strongest avoidance: logistics",
                     "routes through low-danger ground. 0 disables.",
                     "Default: 6.0")
            .defineInRange("dangerPathSupportWeight", 6.0, 0.0, 16.0);

        DANGER_PATH_RIFLEMAN_WEIGHT = BUILDER
            .comment("Pathfinding danger cost for RIFLEMAN soldiers - lightest avoidance: assault",
                     "roles cross exposed ground when the objective demands it, but prefer shadow",
                     "when the detour is cheap. 0 disables.",
                     "Default: 1.5")
            .defineInRange("dangerPathRiflemanWeight", 1.5, 0.0, 16.0);

        DANGER_PATH_MACHINE_GUNNER_WEIGHT = BUILDER
            .comment("Pathfinding danger cost for MACHINE_GUNNER soldiers: base-of-fire doctrine",
                     "is more cautious than riflemen but still closes to its lane. 0 disables.",
                     "Default: 3.0")
            .defineInRange("dangerPathMachineGunnerWeight", 3.0, 0.0, 16.0);

        DANGER_PATH_GARRISON_WEIGHT = BUILDER
            .comment("Pathfinding danger cost for GARRISON soldiers: mostly stationary, but their",
                     "occasional repositioning prefers low-danger ground. 0 disables.",
                     "Default: 2.0")
            .defineInRange("dangerPathGarrisonWeight", 2.0, 0.0, 16.0);

        DANGER_TRAVEL_RESPONSE = BUILDER
            .comment("Suppressed-traveler doctrine: a soldier moving under fire keeps moving -",
                     "the squad map bakes the shooter from the near-misses, the path re-routes",
                     "around the new shadow, and the soldier crawls through any stretch where",
                     "the map says crawling hides - hold marches, ping moves, and the approach",
                     "to cover alike. Pinned soldiers still dive for cover, but crawl there low.",
                     "Cover-seek waits until pinned or arrival. false restores stop-at-cover doctrine.",
                     "Default: true")
            .define("dangerTravelResponse", true);

        DANGER_TRAVEL_REROUTE_COOLDOWN_TICKS = BUILDER
            .comment("Minimum ticks between travel re-route requests for one soldier, so sustained",
                     "fire re-routes the march without recomputing a path on every near-miss.",
                     "Default: 60 ticks (3 s)")
            .defineInRange("dangerTravelRerouteCooldownTicks", 60, 20, 200);

        DANGER_TRAVEL_SUPPRESSION_GAIN = BUILDER
            .comment("Suppression gain multiplier for soldiers actively traveling (hold march or",
                     "GO_TO ping move). Real bullet speeds pin a stationary soldier in ~2-3",
                     "near-misses; a moving soldier is a harder target to suppress, so the",
                     "pressured band - where re-routing and no-cover-dive apply - lasts seconds",
                     "instead of a fraction of a second. 1.0 disables the resistance.",
                     "Default: 0.35")
            .defineInRange("dangerTravelSuppressionGain", 0.35, 0.05, 1.0);

        BUILDER.pop();

BUILDER.pop();

        BUILDER.push("close_combat");

        CLOSE_COMBAT_RANGE = BUILDER
            .comment("Radius (blocks) of the close-combat context: inside it soldiers reassess targets",
                     "faster, keep cover-seek reasoning alive, and bound their movements to small",
                     "adjustments. A wider hysteresis exit (closeCombatExitRange) stops behavior",
                     "flapping at the boundary. 0 disables the context.",
                     "Default: 16.0 blocks")
            .defineInRange("closeCombatRange", 16.0, 0.0, 48.0);

        CLOSE_COMBAT_EXIT_RANGE = BUILDER
            .comment("Hysteresis exit for the close-combat context: the context stays on until the",
                     "nearest known threat is farther than this. Must be >= closeCombatRange.",
                     "Default: 20.0 blocks")
            .defineInRange("closeCombatExitRange", 20.0, 4.0, 64.0);

        CQB_EMERGENCY_RANGE = BUILDER
            .comment("Emergency engagement range: a visible enemy inside this distance may latch the",
                     "emergency engagement hold (stop and fight) when the soldier can actually shoot.",
                     "Manual CQB formation no longer freeze-holds at any distance - it is a mobility",
                     "doctrine (caution steering, faster reassessment) that keeps cover use and spacing.",
                     "Default: 5.0 blocks")
            .defineInRange("cqbEmergencyRange", 5.0, 1.0, 16.0);

        CQB_OVERRUN_RANGE = BUILDER
            .comment("Overrun range: a visible enemy inside this distance freezes movement immediately,",
                     "even mid-approach - survival outranks finishing the dock.",
                     "Default: 3.0 blocks")
            .defineInRange("cqbOverrunRange", 3.0, 1.0, 16.0);

        CQB_DOCK_FINISH_THRESHOLD = BUILDER
            .comment("When the emergency hold latches while a cover approach is already this close to",
                     "its slot, the approach finishes first: the last step into protection beats",
                     "engaging from the open. Docking defers only while the threat is beyond",
                     "cqbOverrunRange.",
                     "Default: 2.5 blocks")
            .defineInRange("cqbDockFinishThreshold", 2.5, 0.5, 8.0);

        CQB_HOLD_NO_SHOT_TIMEOUT_TICKS = BUILDER
            .comment("An engagement hold that produces no burst for this many ticks releases itself so",
                     "the soldier can reposition (blocked lane, obstructed arc) instead of standing",
                     "frozen while unable to shoot. Any started burst resets the clock.",
                     "Default: 60 ticks (3 s)")
            .defineInRange("cqbHoldNoShotTimeoutTicks", 60, 20, 400);

        CQB_HOLD_RELEASE_COOLDOWN_TICKS = BUILDER
            .comment("After a no-shot release the soldier waits this long before the emergency hold may",
                     "latch again, so hold-release-relatch cannot oscillate in place.",
                     "Default: 40 ticks (2 s)")
            .defineInRange("cqbHoldReleaseCooldownTicks", 40, 0, 200);

        CQB_HOLD_FREEZE_GRACE_TICKS = BUILDER
            .comment("The engagement hold freezes movement only within this window of actual shot",
                     "activity (latch or last burst). Past the grace, movement resumes while the hold",
                     "flag stays latched, so a hold that cannot produce fire never blocks cover",
                     "seeking; a started burst re-arms the freeze immediately.",
                     "Default: 20 ticks (1 s)")
            .defineInRange("cqbHoldFreezeGraceTicks", 20, 0, 100);

        SQUAD_CONTACT_PLAN_ENABLED = BUILDER
            .comment("Contact plan (INC 2): one close contact per squad with an assigned base of fire",
                     "and checker; soldiers may not advance across the contact's danger arc without",
                     "support (LoS onto the threat or a recent squad shot) unless an emergency bypass",
                     "applies (point-blank overrun, suppressed/downed mate, STALLED latch).",
                     "Default: true")
            .define("squadContactPlanEnabled", true);

        CONTACT_COORDINATION_RANGE = BUILDER
            .comment("A threat within this band of the squad opens/keeps the close contact plan;",
                     "beyond it the legacy soft-wait street doctrine applies.",
                     "Default: 16.0 blocks")
            .defineInRange("contactCoordinationRange", 16.0, 6.0, 48.0);

        CONTACT_DANGER_ARC_DEGREES = BUILDER
            .comment("Half-angle of the threat's danger apex cone. An advance whose segment crosses",
                     "the cone is denied unless base-of-fire support or an emergency bypass covers it.",
                     "Default: 35.0 degrees")
            .defineInRange("contactDangerArcDegrees", 35.0, 10.0, 90.0);

        CLOSE_COMBAT_SEEK_RADIUS = BUILDER
            .comment("Cover searches inside the close-combat context (or an emergency-range enemy)",
                     "are bounded to this radius: close-range protection must be a short, useful",
                     "movement, not a long relocation under fire. Wide enough to still see real",
                     "protection near a squad position; small enough to exclude far-side pockets.",
                     "Default: 14.0 blocks")
            .defineInRange("closeCombatSeekRadius", 14.0, 4.0, 24.0);

        CLOSE_COMBAT_REEVAL_INTERVAL_TICKS = BUILDER
            .comment("Target re-evaluation interval inside the close-combat context (ordinary play",
                     "uses targeting.targetReevaluateInterval). Close quarters change faster than",
                     "open field fights.",
                     "Default: 10 ticks")
            .defineInRange("closeCombatReevalIntervalTicks", 10, 4, 40);

        BUILDER.pop();

        BUILDER.push("cqb");

        CQB_DOORWAY_QUEUE_ENABLED = BUILDER
            .comment("Doorway queueing: a soldier moving under CQB caution pauses before a",
                     "wall-narrow passage (door, breach) while a squadmate is still inside or",
                     "just past it, so the stack crosses single-file instead of clumping in",
                     "the fatal funnel.",
                     "Default: true")
            .define("cqbDoorwayQueueEnabled", true);

        CQB_DOORWAY_SPACING = BUILDER
            .comment("Distance from the doorway center at which a squadmate holds the passage",
                     "occupied for queueing purposes. Must exceed one body length so the",
                     "follower freezes his creep before the leader has cleared the frame.",
                     "Default: 3.5 blocks")
            .defineInRange("cqbDoorwaySpacing", 3.5, 1.0, 6.0);

        CQB_CORNER_WATCH_ENABLED = BUILDER
            .comment("Corner pie-slicing: while moving under CQB caution, a soldier whose path",
                     "bends within a few blocks pre-aims the arc the corner opens into (toward",
                     "the known threat side) instead of staring at the wall he is hugging.",
                     "Default: true")
            .define("cqbCornerWatchEnabled", true);

        CQB_LOST_CONTACT_WATCH_TICKS = BUILDER
            .comment("Lost-contact watch: after close-quarters contact breaks (target visible,",
                     "then gone), the soldier holds position and keeps his aim on the last-known",
                     "position for this long before resuming routine behavior.",
                     "Default: 80 ticks (4 s)")
            .defineInRange("cqbLostContactWatchTicks", 80, 20, 200);

        BUILDER.pop();

        BUILDER.push("attack_clear");

        ATTACK_CLEAR_ENABLED = BUILDER
            .comment("Attack clearing doctrine: an ATTACK ping defines a bounded area to advance into,",
                     "actively clear (observe blind spots, resolve contact), then secure - instead of",
                     "completing the moment soldiers get within 4 blocks of the marker.",
                     "false restores proximity completion.",
                     "Default: true")
            .define("attackClearEnabled", true);

        ATTACK_CLEAR_RADIUS = BUILDER
            .comment("Clearing boundary radius (blocks) around the ping. The area is flood-filled",
                     "through walkable space, so walls bound it even when the radius would not.",
                     "Default: 8 blocks")
            .defineInRange("attackClearRadius", 8, 4, 16);

        ATTACK_CLEAR_AWARENESS = BUILDER
            .comment("Close-combat awareness range (blocks): blind-spot observation, threat",
                     "resolution, and visibility checks use this range, separately from the",
                     "order's clearing boundary.",
                     "Default: 16 blocks")
            .defineInRange("attackClearAwareness", 16, 8, 32);

        ATTACK_CLEAR_RAY_BUDGET = BUILDER
            .comment("Visibility rays per server tick spent observing unchecked blind spots across",
                     "all active clearing orders. 0 disables ray observation (proximity only).",
                     "Default: 24")
            .defineInRange("attackClearRayBudget", 24, 0, 128);

        ATTACK_CLEAR_SETTLE_TICKS = BUILDER
            .comment("Deprecated compatibility setting; ignored. Attack orders complete immediately",
                     "once the area is reached, checked, and free of unresolved threats.",
                     "Default: 80 ticks (4 s)")
            .defineInRange("attackClearSettleTicks", 80, 20, 400);

        ATTACK_CLEAR_STALL_TICKS = BUILDER
            .comment("No blind spot checked, no threat resolved, and no soldier progress for this",
                     "long marks the attack STALLED (reported once; the order persists).",
                     "Default: 600 ticks (30 s)")
            .defineInRange("attackClearStallTicks", 600, 100, 2400);

        ATTACK_CLEAR_DIRECT_EGRESS = BUILDER
            .comment("Direct egress for approach-stage soldiers: a check job beyond the investigate",
                     "band (or no in-band job at all) is walked as one full route to the target -",
                     "partial paths taken - instead of being dock-hopped. Fixes soldiers parking at",
                     "a dock mid-route or drifting backwards when the exit faces away from the",
                     "objective. false restores dock-hop-only advances.",
                     "Default: true")
            .define("attackClearDirectEgress", true);

        ATTACK_PATH_SPINE_ENABLED = BUILDER
            .comment("Path-based attack progression (advance spine): route the soldier to the attack",
                     "ping once with the wide-lens pathfinder, then measure cover progression along",
                     "that route instead of straight-line distance. Covers that advance the route win;",
                     "interior covers that do not are rejected, so soldiers exit buildings by the door",
                     "and dock on street cover en route. false restores straight-line progression.",
                     "Default: true")
            .define("attackPathSpineEnabled", true);

        ATTACK_SPINE_FLOW_DWELL_MS = BUILDER
            .comment("Cover dwell (ms) during a spine advance with NO live contact: the soldier",
                     "docks, glances, and keeps hopping. Under contact the legacy dwell (4000 ms",
                     "baseline, suppression-scaled) applies unchanged.",
                     "Default: 600 ms")
            .defineInRange("attackSpineFlowDwellMs", 600, 200, 2000);

        BUILDER.pop();

        BUILDER.push("exposed_combat");

        EXPOSED_COMBAT_ENABLED = BUILDER
            .comment("Deliberate no-cover combat doctrine: soldiers without usable cover plant and",
                     "engage, make bounded sidesteps when their lane is blocked, withdraw from",
                     "overrunning threats, and hold deliberately instead of standing frozen.",
                     "false restores stand-in-place fallback.",
                     "Default: true")
            .define("exposedCombatEnabled", true);

        EXPOSED_PLANT_MIN_TICKS = BUILDER
            .comment("A chosen plant/hold action persists at least this long before re-deciding, so",
                     "exposed soldiers act with purpose instead of strafing constantly.",
                     "Default: 40 ticks (2 s)")
            .defineInRange("exposedPlantMinTicks", 40, 10, 200);

        EXPOSED_ADJUST_WINDOW_TICKS = BUILDER
            .comment("How long a friendly-fire-blocked shot stays remembered as a blocked lane.",
                     "Default: 40 ticks (2 s)")
            .defineInRange("exposedAdjustWindowTicks", 40, 10, 200);

        EXPOSED_ADJUST_MAX_ATTEMPTS = BUILDER
            .comment("Bounded sidesteps per engagement: a failed adjustment never loops - after the",
                     "cap the soldier plants, switches target, or holds.",
                     "Default: 1")
            .defineInRange("exposedAdjustMaxAttempts", 1, 0, 4);

        EXPOSED_ADJUST_TIMEOUT_TICKS = BUILDER
            .comment("A sidestep move that has not landed within this window is abandoned.",
                     "Default: 30 ticks")
            .defineInRange("exposedAdjustTimeoutTicks", 30, 10, 100);

        EXPOSED_SIDESTEP_BLOCKS = BUILDER
            .comment("Lateral reach of one bounded sidestep (blocks), perpendicular to the blocked",
                     "aim direction, away from friendly bodies.",
                     "Default: 2.5 blocks")
            .defineInRange("exposedSidestepBlocks", 2.5, 0.5, 6.0);

        EXPOSED_WITHDRAW_DISTANCE = BUILDER
            .comment("Bounded retreat length when a threat overruns an exposed soldier (blocks).",
                     "Default: 8.0 blocks")
            .defineInRange("exposedWithdrawDistance", 8.0, 2.0, 24.0);

        EXPOSED_WITHDRAW_TIMEOUT_TICKS = BUILDER
            .comment("A withdrawal that has not ended within this window is abandoned.",
                     "Default: 60 ticks (3 s)")
            .defineInRange("exposedWithdrawTimeoutTicks", 60, 20, 200);

        BUILDER.pop();

        BUILDER.push("squad_movement");

        SQUAD_BOUNDING_ENABLED = BUILDER
            .comment("Squad street-crossing doctrine: within a fire team advancing across a hot",
                     "lane, one soldier bounds at a time and the others hold overwatch; a bounder",
                     "advances under real covering fire (a teammate actually shooting), machine",
                     "gunners anchor the overwatch, and lone soldiers bypass the coordinator.",
                     "Default: true")
            .define("squadBoundingEnabled", true);

        SQUAD_BOUND_COVER_WINDOW_TICKS = BUILDER
            .comment("A teammate's shot counts as covering fire for this long after it fires.",
                     "Default: 60 ticks (3 s)")
            .defineInRange("squadBoundCoverWindowTicks", 60, 10, 200);

        SQUAD_BOUND_COVER_WAIT_TICKS = BUILDER
            .comment("A bounder without covering fire waits this long before advancing anyway.",
                     "Default: 60 ticks (3 s)")
            .defineInRange("squadBoundCoverWaitTicks", 60, 0, 200);

        SQUAD_BOUND_MAX_TICKS = BUILDER
            .comment("A bound that has not completed within this window is released so the next",
                     "soldier (rotation) gets the lane. No permanent stalls.",
                     "Default: 200 ticks (10 s)")
            .defineInRange("squadBoundMaxTicks", 200, 40, 600);

        HOLD_MARCH_DWELL_TICKS = BUILDER
            .comment("A docked soldier with the hold point still far away resumes the march after",
                     "this much quiet dwell time in cover: a dock is a pause under fire, not a",
                     "terminus (street-crossing doctrine). The dwell resets while suppressed or in",
                     "the close-combat context; live combat always owns the soldier first.",
                     "Default: 80 ticks (4 s)")
            .defineInRange("holdMarchDwellTicks", 80, 20, 400);

        SQUAD_BOUND_THREAT_RANGE = BUILDER
            .comment("Max distance for a squadmate to count as part of the same bound (blocks).",
                     "Default: 32 blocks")
            .defineInRange("squadBoundThreatRange", 32.0, 8.0, 64.0);

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

        VS2_SHIP_RAYCAST_GATE = BUILDER
            .comment("Gate ship-aware visibility rays: rays whose bounding box meets no ship skip",
                     "the VS2 clip entirely, and rays that do meet a ship are clipped per ship over",
                     "just the ship's AABB span instead of the whole ray. Hit results are unchanged;",
                     "this only removes redundant ray work on VS 2.3, whose clip re-walks the world",
                     "and every ship in the query box for each ray. Default: true")
            .define("shipRaycastGate", true);

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
            .comment("Soldiers detect enemy-crewed vehicles as shared hard-target contacts.",
                     "A launcher carrier may select the launcher and use the ordinary direct",
                     "combat pipeline against a visible hull surface; vehicle contacts never",
                     "change cover, peeking, posture, navigation, or rifleman behavior.",
                     "Vehicles are spotted via crewed tallyho turrets (requires tallyho) or",
                     "via crewed occupancy (see occupancyVehicleDetection, no tallyho needed).",
                     "Default: true.")
            .define("enabled", true);

        ARMOR_DETECTION_DISTANCE = BUILDER
            .comment("How far a soldier notices an actively crewed enemy vehicle turret.",
                     "Vehicles are large and loud; detection is instant inside this range.",
                     "Default: 48 blocks.")
            .defineInRange("detectionDistance", 48.0, 8.0, 256.0);

        ARMOR_ENGAGEMENT_MAX_RANGE = BUILDER
            .comment("Maximum range at which the designated armor hunter opens fire on a",
                     "vehicle. Keep this inside the AT gun's effective range so rockets do",
                     "not sail past the hull. Default: 96 blocks.")
            .defineInRange("engagementMaxRange", 96.0, 8.0, 256.0);

        ARMOR_AT_GUN_PATTERNS = BUILDER
            .comment("Gun-ID substrings that count as anti-armor weapons (TaCZ gun id,",
                     "e.g. tacz:rpg7). An ANTI-TANK-role soldier carrying any such gun is",
                     "designated the squad's armor hunter; other soldiers never hunt even",
                     "if they pick a launcher up. Default: rpg / rocket / launcher.")
            .defineList("atGunIdPatterns",
                java.util.List.of("rpg", "rocket", "launcher"),
                entry -> entry instanceof String);

        SIDEARM_GUN_PATTERNS = BUILDER
            .comment("Gun-ID substrings that count as sidearm pistols for gun slotting",
                     "(TaCZ gun id, e.g. tacz:glock_17). Gun slotting puts the main gun in",
                     "the main hand and a pistol in the sidearm slot. Default: pistol /",
                     "glock / m1911 / deagle / usp / makarov.")
            .defineList("sidearmGunIdPatterns",
                java.util.List.of("pistol", "glock", "m1911", "deagle", "usp", "makarov"),
                entry -> entry instanceof String);

        SOLDIER_DEATH_DROPS = BUILDER
            .comment("Soldiers drop guns and one carried ammo stack per gun ammo type loose",
                     "where they die, with remaining inventory in a supply pouch. Default: true.")
            .define("soldierDeathDrops", true);

        SWAP_SYNCS_HEALTH = BUILDER
            .comment("Player-soldier body swap carries health with the body: swapping into",
                     "a wounded soldier leaves you at his hearts, and the body you leave",
                     "behind keeps yours (so swapping back never heals you for free).",
                     "Default: true.")
            .define("swapSyncsHealth", true);

        ARMOR_DOCTRINE_OVERRIDE = BUILDER
            .comment("Force the reaction branch for testing: AUTO uses each squad's real",
                     "loadout; NO_AT makes every squad react as if it has no anti-armor",
                     "weapon; FORCE_AT makes every squad count as having anti-armor support",
                     "(riflemen will suppress the vehicle even with no hunter designated).",
                     "Default: AUTO.")
            .defineEnum("doctrineOverride", ArmorDoctrineOverride.AUTO);

        OCCUPANCY_VEHICLE_DETECTION = BUILDER
            .comment("Detect ships as enemy vehicles when enemy CREW are aboard: a soldier",
                     "with the vehicle-crew role, or anyone riding a seat on the hull.",
                     "Infantry merely standing on a ship or dock never triggers a contact.",
                     "Works without tallyho. Default: true.")
            .define("occupancyVehicleDetection", true);

        BUILDER.pop();

        BUILDER.push("superbwarfare");

        SBW_COMPAT_ENABLED = BUILDER
            .comment("Enable Superb Warfare compatibility when the superbwarfare mod is installed.",
                     "AT soldiers treat enemy-crewed SBW vehicles as hard targets, and vehicle",
                     "crew can board and man them via wheel/crew stick/egg (gunnery only -",
                     "SBW vehicles are client-input driven, so AI cannot drive them).",
                     "Default: true.")
            .define("enabled", true);

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

        GO_TO_SEEK_GRACE_TICKS = BUILDER
            .comment("Ticks a GO_TO ping move may hunt for a cover route before handing the",
                     "walk to direct navigation. The counter is keyed on the destination, not",
                     "the ping generation, so repeated re-pings to the same spot cannot keep",
                     "restarting the hunt (the mashed-ping strand). Default: 60")
            .defineInRange("goToSeekGraceTicks", 60, 10, 400);

        COVER_SCORING_MAX_CONTACTS = BUILDER
            .comment("Maximum squad firing contacts traced per cover candidate while scoring cover.",
                     "Contacts beyond the cap are skipped for scoring only; targeting and peek",
                     "decisions still use every contact. Each contact is one visibility ray per",
                     "candidate firing origin, so lower this if cover searches spike the server tick.",
                     "Default: 6")
            .defineInRange("coverScoringMaxContacts", 6, 0, 24);

        MOVE_GOAL_REPATH_TICKS = BUILDER
            .comment("Ticks between hold/defend movement re-paths while the current path is still",
                     "pending or being walked. Lower values react faster to blocked routes but",
                     "recompute pathfinding snapshots more often; unreachable targets always back",
                     "off to 100 ticks regardless. Default: 20")
            .defineInRange("moveGoalRepathTicks", 20, 4, 100);

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

        SUPPRESSED_RELOCATION_SAFE_ROUTE = BUILDER
            .comment("Suppressed relocation is fully safe or does not happen: every",
                     "autonomous suppressed reposition must route through the safe-route",
                     "classifier (PROTECTED / forced-crawl CRAWL_SAFE accepted, EXPOSED",
                     "withheld so the soldier stays at his current cover). Applies to",
                     "attack-target soldiers too, whose searches previously skipped the",
                     "classifier entirely. Disable to restore the legacy behavior where",
                     "only non-attack soldiers classified their relocation routes.",
                     "Default: true")
            .define("suppressedRelocationSafeRoute", true);

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

    public static int getMgDirectBurstShots() {
        return MG_DIRECT_BURST_SHOTS.get();
    }

    public static int getMgDirectRecoveryTicks() {
        return MG_DIRECT_RECOVERY_TICKS.get();
    }

    public static float getMgAimQualityThresholdScale() {
        return MG_AIM_QUALITY_THRESHOLD_SCALE.get().floatValue();
    }

    public static float getMgTargetMovePenaltyScale() {
        return MG_TARGET_MOVE_PENALTY_SCALE.get().floatValue();
    }

    public static float getMgAimBuildRateScale() {
        return MG_AIM_BUILD_RATE_SCALE.get().floatValue();
    }

    public static float getMgRecoilAimLossScale() {
        return MG_RECOIL_AIM_LOSS_SCALE.get().floatValue();
    }

    public static boolean isCrawlStanceRecoilEnabled() {
        return CRAWL_STANCE_RECOIL.get();
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

    public static boolean isMgSuppressionRoundRobinEnabled() {
        return MG_SUPPRESSION_ROUND_ROBIN.get();
    }

    public static int getMgSuppressionRotationSize() {
        return MG_SUPPRESSION_ROTATION_SIZE.get();
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

    public static boolean shouldMonstersAttackSoldiers() {
        return MONSTERS_ATTACK_SOLDIERS.get();
    }

    public static double getBasicDetectionDistance() {
        return BASIC_DETECTION_DISTANCE.get();
    }

    public static boolean isDangerMapEnabled() {
        return DANGER_MAP_ENABLED.get();
    }

    public static double getDangerAmbientRadius() {
        return DANGER_AMBIENT_RADIUS.get();
    }

    public static int getDangerRayBudgetPerTick() {
        return DANGER_RAY_BUDGET_PER_TICK.get();
    }

    public static long getDangerCellTtlTicks() {
        return DANGER_CELL_TTL_TICKS.get();
    }

    public static int getDangerMaxThreats() {
        return DANGER_MAX_THREATS.get();
    }

    public static double getDangerThreatRange() {
        return DANGER_THREAT_RANGE.get();
    }

    public static double getDangerPathAntiTankWeight() {
        return DANGER_PATH_ANTI_TANK_WEIGHT.get();
    }

    public static double getDangerPathSupportWeight() {
        return DANGER_PATH_SUPPORT_WEIGHT.get();
    }

    public static double getDangerPathRiflemanWeight() {
        return DANGER_PATH_RIFLEMAN_WEIGHT.get();
    }

    public static double getDangerPathMachineGunnerWeight() {
        return DANGER_PATH_MACHINE_GUNNER_WEIGHT.get();
    }

    public static double getDangerPathGarrisonWeight() {
        return DANGER_PATH_GARRISON_WEIGHT.get();
    }

    public static boolean isDangerTravelResponseEnabled() {
        return DANGER_TRAVEL_RESPONSE.get();
    }

    public static int getDangerTravelRerouteCooldownTicks() {
        return DANGER_TRAVEL_REROUTE_COOLDOWN_TICKS.get();
    }

    public static double getDangerTravelSuppressionGain() {
        return DANGER_TRAVEL_SUPPRESSION_GAIN.get();
    }

    public static double getCloseCombatRange() {
        return CLOSE_COMBAT_RANGE.get();
    }

    public static double getCloseCombatExitRange() {
        return CLOSE_COMBAT_EXIT_RANGE.get();
    }

    public static double getCqbEmergencyRange() {
        return CQB_EMERGENCY_RANGE.get();
    }

    public static double getCqbOverrunRange() {
        return CQB_OVERRUN_RANGE.get();
    }

    public static double getCqbDockFinishThreshold() {
        return CQB_DOCK_FINISH_THRESHOLD.get();
    }

    public static int getCqbHoldNoShotTimeoutTicks() {
        return CQB_HOLD_NO_SHOT_TIMEOUT_TICKS.get();
    }

    public static int getCqbHoldReleaseCooldownTicks() {
        return CQB_HOLD_RELEASE_COOLDOWN_TICKS.get();
    }

    public static int getCqbHoldFreezeGraceTicks() {
        return CQB_HOLD_FREEZE_GRACE_TICKS.get();
    }

    public static boolean isSquadContactPlanEnabled() {
        return SQUAD_CONTACT_PLAN_ENABLED.get();
    }

    public static double getContactCoordinationRange() {
        return CONTACT_COORDINATION_RANGE.get();
    }

    public static double getContactDangerArcDegrees() {
        return CONTACT_DANGER_ARC_DEGREES.get();
    }

    public static boolean isExposedCombatEnabled() {
        return EXPOSED_COMBAT_ENABLED.get();
    }

    public static int getExposedPlantMinTicks() {
        return EXPOSED_PLANT_MIN_TICKS.get();
    }

    public static int getExposedAdjustWindowTicks() {
        return EXPOSED_ADJUST_WINDOW_TICKS.get();
    }

    public static int getExposedAdjustMaxAttempts() {
        return EXPOSED_ADJUST_MAX_ATTEMPTS.get();
    }

    public static int getExposedAdjustTimeoutTicks() {
        return EXPOSED_ADJUST_TIMEOUT_TICKS.get();
    }

    public static double getExposedSidestepBlocks() {
        return EXPOSED_SIDESTEP_BLOCKS.get();
    }

    public static double getExposedWithdrawDistance() {
        return EXPOSED_WITHDRAW_DISTANCE.get();
    }

    public static int getExposedWithdrawTimeoutTicks() {
        return EXPOSED_WITHDRAW_TIMEOUT_TICKS.get();
    }

    public static boolean isSquadBoundingEnabled() {
        return SQUAD_BOUNDING_ENABLED.get();
    }

    public static int getSquadBoundCoverWindowTicks() {
        return SQUAD_BOUND_COVER_WINDOW_TICKS.get();
    }

    public static int getSquadBoundCoverWaitTicks() {
        return SQUAD_BOUND_COVER_WAIT_TICKS.get();
    }

    public static int getSquadBoundMaxTicks() {
        return SQUAD_BOUND_MAX_TICKS.get();
    }

    public static double getSquadBoundThreatRange() {
        return SQUAD_BOUND_THREAT_RANGE.get();
    }

    public static int getHoldMarchDwellTicks() {
        return HOLD_MARCH_DWELL_TICKS.get();
    }

    public static boolean isCqbDoorwayQueueEnabled() {
        return CQB_DOORWAY_QUEUE_ENABLED.get();
    }

    public static double getCqbDoorwaySpacing() {
        return CQB_DOORWAY_SPACING.get();
    }

    public static boolean isCqbCornerWatchEnabled() {
        return CQB_CORNER_WATCH_ENABLED.get();
    }

    public static int getCqbLostContactWatchTicks() {
        return CQB_LOST_CONTACT_WATCH_TICKS.get();
    }

    public static boolean isAttackClearEnabled() {
        return ATTACK_CLEAR_ENABLED.get();
    }

    public static int getAttackClearRadius() {
        return ATTACK_CLEAR_RADIUS.get();
    }

    public static int getAttackClearAwareness() {
        return ATTACK_CLEAR_AWARENESS.get();
    }

    public static int getAttackClearRayBudget() {
        return ATTACK_CLEAR_RAY_BUDGET.get();
    }

    public static int getAttackClearSettleTicks() {
        return ATTACK_CLEAR_SETTLE_TICKS.get();
    }

    public static int getAttackClearStallTicks() {
        return ATTACK_CLEAR_STALL_TICKS.get();
    }

    public static boolean isAttackClearDirectEgress() {
        return ATTACK_CLEAR_DIRECT_EGRESS.get();
    }

    public static boolean isAttackPathSpineEnabled() {
        return ATTACK_PATH_SPINE_ENABLED.get();
    }

    public static long getAttackSpineFlowDwellMs() {
        return ATTACK_SPINE_FLOW_DWELL_MS.get();
    }

    public static double getCloseCombatSeekRadius() {
        return CLOSE_COMBAT_SEEK_RADIUS.get();
    }

    public static int getCloseCombatReevalIntervalTicks() {
        return CLOSE_COMBAT_REEVAL_INTERVAL_TICKS.get();
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

    public static int getGoToSeekGraceTicks() {
        return GO_TO_SEEK_GRACE_TICKS.get();
    }

    public static int getMoveGoalRepathTicks() {
        return MOVE_GOAL_REPATH_TICKS.get();
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

    public static boolean isSuppressedRelocationSafeRoute() {
        return SUPPRESSED_RELOCATION_SAFE_ROUTE.get();
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

    public static double getArmorEngagementMaxRange() {
        return ARMOR_ENGAGEMENT_MAX_RANGE.get();
    }

    public static java.util.List<? extends String> getArmorAtGunPatterns() {
        return ARMOR_AT_GUN_PATTERNS.get();
    }

    public static java.util.List<? extends String> getSidearmGunPatterns() {
        return SIDEARM_GUN_PATTERNS.get();
    }

    public static boolean isSoldierDeathDropsEnabled() {
        return SOLDIER_DEATH_DROPS.get();
    }

    public static boolean isSwapHealthSyncEnabled() {
        return SWAP_SYNCS_HEALTH.get();
    }

    public static ArmorDoctrineOverride getArmorDoctrineOverride() {
        return ARMOR_DOCTRINE_OVERRIDE.get();
    }

    public static boolean isOccupancyVehicleDetectionEnabled() {
        return OCCUPANCY_VEHICLE_DETECTION.get();
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

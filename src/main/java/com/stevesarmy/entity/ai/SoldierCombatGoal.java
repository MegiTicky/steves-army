package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.AimAccuracyManager;
import com.stevesarmy.combat.ArmorRoleManager;
import com.stevesarmy.combat.ArmorThreatScanner;
import com.stevesarmy.combat.CombatTargetQueryCache;
import com.stevesarmy.combat.DetectionSystem;
import com.stevesarmy.combat.EngagementGateReason;
import com.stevesarmy.combat.EnemyContactTracker;
import com.stevesarmy.combat.ExposureCalculator;
import com.stevesarmy.combat.FireControl;
import com.stevesarmy.combat.FireControl.DirectFireWeaponProfile;
import com.stevesarmy.combat.FireControl.SuppressionWeaponProfile;
import com.stevesarmy.combat.FirePersonality;
import com.stevesarmy.combat.FriendlyFireChecker;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.SoldierWeaponSelector;
import com.stevesarmy.combat.SuppressPingDebugManager;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.ThreatTracker;
import com.stevesarmy.combat.VisibilityRay;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.CoverFinder;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverProtectionContext;
import com.stevesarmy.combat.cover.CoverType;
import com.stevesarmy.combat.cover.SuppressionTracker;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.debug.PerformanceMetrics;
import com.stevesarmy.debug.trace.SoldierFlightRecorder;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.entity.TargetEntity;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.PotentialTargetsDebugMessage;
import com.stevesarmy.network.SuppressPingDebugPacket;
import com.stevesarmy.squad.FireDiscipline;
import com.stevesarmy.squad.FireTeamSuppressionTracker;
import com.stevesarmy.squad.FofCategory;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import com.stevesarmy.squad.SuppressireAssignmentManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class SoldierCombatGoal extends Goal implements CombatGoalController {
    private final SoldierEntity soldier;
    private final ThreatTracker threatTracker;
    private final DetectionSystem detectionSystem;
    private final boolean machineGunnerPipeline;
    private LivingEntity target;
    private long lastDetectionTick = Long.MIN_VALUE;

    private boolean gunInitialized = false;
    private ItemStack lastGunStack = ItemStack.EMPTY;
    private boolean wasAiming = false;
    private boolean navigationTraversalLockWasActive = false;
    private boolean wasReloading = false;
    
    private float aimQuality = 0.0f;
    private int aimWaitTicks;
    private int aimWaitLastTick = Integer.MIN_VALUE;
    private UUID aimPatienceTargetUUID;
    private int aimNoLosTicks;
    private int aimContactLastTick = Integer.MIN_VALUE;
    private boolean directBurstImperfect;
    private boolean directRecoveryImperfect;
    private static final int IMPERFECT_FIRE_RECOVERY_TICKS = 10;
    private UUID trackedTargetUUID = null;
    private boolean lastShotNeededBolt = false;
    private ExposureCalculator.AimPointResult currentAimPoint = null;
    
    private static final float ADS_THRESHOLD = 0.8f;
    private static final float MOBILE_FIRE_ADS_THRESHOLD = 0.40f;
    private static final float MOBILE_FIRE_THRESHOLD_FLOOR = 0.08f;
    private static final float MOBILE_FIRE_MOVEMENT_DECAY = 0.005f;
    private static final float MOBILE_FIRE_TARGET_MOVEMENT_DECAY = 0.008f;
    private static final int MOBILE_FIRE_MAX_BURST_SHOTS = 2;
    private static final int PATH_BLOCKED_SWITCH_TICKS = 40;
    private static final int CQB_PATH_BLOCKED_SWITCH_TICKS = 10;
    private int pathBlockedCounter = 0;
    private int debugSyncTickCounter = 0;
    private static final int DEBUG_SYNC_INTERVAL = 20;
    private int targetReevaluateCounter = 0;
    private int lastCqbGoToPursuitSuppressedTick = Integer.MIN_VALUE;
    private int lastHiddenCoverFireDebugTick = Integer.MIN_VALUE;
    
    private int findNewTargetLogCounter = 0;

    private boolean isSuppressing = false;
    private boolean reloadPending = false;
    private boolean tacticalReloadPending = false;
    private boolean reloadStartRequested = false;
    private int reloadRetryTicks = 0;
    private BlockPos suppressionTargetPos = null;
    private UUID suppressionTargetUUID = null;
    private SquadThreatIntel.ThreatKnowledge pendingSuppressionThreat = null;
    private static final float SUPPRESSION_ADS_THRESHOLD = 0.5f;
    private static final double SUPPRESSION_MAX_RANGE = 128.0;
    private static final int SUPPRESSION_ACTIVE_FIRE_TICKS = 120;
    private static final int SUPPRESSION_PREPARATION_TICKS = 20;
    private static final double SUPPRESSION_LOS_TOLERANCE = 2.0; // blocks
    private static final float PRONE_FIRING_ARC_DEGREES = 30.0f;
    private static final float FIRING_ALIGNMENT_DEGREES = 7.0f;
    private static final float TURN_RATE_DEGREES = 30.0f;
    private static final double EMERGENCY_FLANK_DISTANCE = 6.0;
    private int suppressionPlanStartTick = -1;
    private int suppressionFirstShotTick = -1;
    private long suppressionLastSeenTick = -1;
    private Vec3 suppressionTargetAimPoint = null;
    private int suppressionLaneSlot = 0;
    private float suppressionLaneSide = 1.0F;
    // Round-robin MG suppression: prioritized threats this plan cycles bursts
    // across; the index advances once per completed burst.
    private List<UUID> suppressionRotation = List.of();
    private int suppressionRotationIndex = -1;
    private final Map<UUID, AttackSuppressionContact> attackSuppressionContacts = new LinkedHashMap<>();
    private final Map<UUID, Integer> attackSuppressionBlockedUntilTick = new java.util.HashMap<>();
    private int attackSuppressionGeneration = -1;
    private boolean attackSuppressionActive;
    private Vec3 attackSuppressionSelectedAim;

    private static final class AttackSuppressionContact {
        private BlockPos lastObservedBlock;
        private Vec3 lastVisibleAim;
        private List<Vec3> peekAimPoints = List.of();
        private int aimPointsRefreshTick;
        private int aimIndex;

        private AttackSuppressionContact(BlockPos block, Vec3 aim) {
            this.lastObservedBlock = block;
            this.lastVisibleAim = aim;
        }
    }

    // Sidearm policy changes equipment only. Vehicle contacts are adapted into
    // the ordinary direct-fire pipeline below; they never own cover or movement.

    // Vehicle engagements must look like one stable fight to the cover system.
    // The latch rides out contact flicker, the streak gate keeps a peeking crew
    // from flipping the branch (and the held weapon) every few ticks.
    private int vehicleEngagementTicks = 0;
    private int visibleTargetStreakTicks = 0;
    private static final int VEHICLE_ENGAGEMENT_LATCH_TICKS = 60;
    private static final int VEHICLE_EXIT_VISIBLE_TICKS = 10;

    private enum EngagementPostureState {
        READY,
        EXITING_LOW_CROUCH,
        ROTATING
    }

    private EngagementPostureState engagementPostureState = EngagementPostureState.READY;

    // Firing-prone is a temporary combat stance, separate from cover crawling.
    private static final int FIRING_PRONE_EVALUATION_INTERVAL = 10;
    private static final int FIRING_PRONE_DELAY_MIN_TICKS = 30;
    private static final int FIRING_PRONE_DELAY_MAX_TICKS = 70;
    private static final int FIRING_PRONE_MIN_COMMIT_TICKS = 60;
    private static final int FIRING_PRONE_COOLDOWN_MIN_TICKS = 200;
    private static final int FIRING_PRONE_COOLDOWN_MAX_TICKS = 400;
    private static final int FIRING_PRONE_INVALID_LOS_TICKS = 10;
    private static final int FIRING_PRONE_TRACE_INTERVAL = 40;
    private static final double FIRING_PRONE_MIN_RANGE = 16.0;
    private static final double FIRING_PRONE_CLOSE_THREAT_RANGE = 10.0;
    private static final double FIRING_PRONE_RECOIL_FLOOR = 0.50;
    private static final double FIRING_PRONE_RECOIL_CEILING = 1.25;
    private static final float FIRING_PRONE_RECOIL_WEIGHT = 0.55f;
    private static final float FIRING_PRONE_RANGE_WEIGHT = 0.20f;
    private static final float FIRING_PRONE_SUPPRESSION_WEIGHT = 0.15f;
    private static final float FIRING_PRONE_STATIONARY_WEIGHT = 0.10f;
    private static final float FIRING_PRONE_RECOIL_LOSS_MULTIPLIER = 0.45f;
    private static final float FIRING_PRONE_AIM_BUILD_MULTIPLIER = 1.15f;

    private int firingProneEligibleTicks;
    private int firingProneDelayTicks = FIRING_PRONE_DELAY_MIN_TICKS;
    private int firingProneCooldownTicks;
    private int firingProneInvalidLosTicks;
    private int firingProneCommitUntilTick = -1;
    private float firingPronePreference;
    private float firingProneEngagementJitter;
    // Retains the useful range contribution while a selected lane waits for its next target.
    private float firingProneLastRangeScore;
    private int lastFiringProneTick = -1;
    // CoverTacticalGoal owns selection and movement; combat only owns posture/aim.
    private boolean firingPronePositionAuthorized;
    
    private boolean isPingSuppressing = false;
    private int pingSuppressDurationTicks = 0;
    private int pingSuppressRemainingTicks = 0;
    private boolean pingSuppressHeavy = false;
    // One rocket per ping: once the heavy phase has fired its single shell the
    // order continues as ordinary sidearm suppression until the next ping.
    private boolean pingHeavyRocketSpent = false;
    private int lastHeavyFlipTick = -1000;
    private String lastHeavyLaneSource = null;
    private int pingNoTargetTicks = 0;
    private int pingPeekStuckTicks = 0;
    private int lastPingRepositionTick = -1000;
    private int lastRelocationHoldTick = -1000;
    private String lastRelocationHoldReason = null;
    private int aimPointsRefreshTick = 0;
    private int aimRediscoveryCooldownTick = 0;
    private Vec3 pingSuppressionTarget = null;
    private Vec3 pingSuppressionSweepEnd = null;
    private Vec3 pingSuppressionShotTarget = null;
    private static final int PING_SUPPRESS_MIN_DURATION_TICKS = 80; // 4 seconds
    private static final int PING_SUPPRESS_MAX_DURATION_TICKS = 200; // 10 seconds
    private static final int PING_NO_TARGET_REPOSITION_TICKS = 20;    // 1s without a valid lane
    private static final int PING_PEEK_STUCK_REPOSITION_TICKS = 100;  // 5s idle-in-cover without ever exposing
    private static final int PING_REPOSITION_COOLDOWN_TICKS = 40;     // at most one relocation per 2s
    private static final int PING_AIM_POINT_REFRESH_TICKS = 40;       // re-discover aim points while moving
    private static final int PING_AIM_REDISCOVERY_COOLDOWN_TICKS = 20;
    private static final int PING_HEAVY_ALTERNATE_ATTEMPTS = 6;
    private static final int PING_HEAVY_FLIP_HYSTERESIS_TICKS = 20;
    private static final int PING_DEBUG_SYNC_INTERVAL_TICKS = 5;
    
    private List<LivingEntity> cachedPotentialTargets = null;
    private long cachedPotentialTargetsTick = -1;

    private ExposureCalculator.AimPointResult cachedAimPoint = null;
    private int cachedAimPointTick = -1;
    private UUID cachedAimPointTargetUUID = null;
    private Vec3 cachedAimObserverEye = null;
    private Vec3 cachedAimTargetPosition = null;
    private Pose cachedAimTargetPose = null;
    private float cachedAimTargetWidth;
    private float cachedAimTargetHeight;
    private BlockPos cachedAimCoverBlock = null;

    private int getBurstTarget() {
        if (GunIntegration.isMachineGun(soldier)) {
            return soldier.getFireDiscipline() == FireDiscipline.SUPPRESSIVE
                ? MACHINE_GUN_SUPPRESSIVE_BURST_SHOTS : MACHINE_GUN_STANDARD_BURST_SHOTS;
        }
        return soldier.getFireDiscipline() == FireDiscipline.SUPPRESSIVE
            ? SUPPRESSIVE_BURST_SHOTS : BURST_SHOTS_TARGET;
    }

    private static final int BURST_SHOTS_TARGET = 3;
    private static final int SUPPRESSIVE_BURST_SHOTS = 6;
    private static final int MACHINE_GUN_STANDARD_BURST_SHOTS = 8;
    private static final int MACHINE_GUN_SUPPRESSIVE_BURST_SHOTS = 12;
    private static final float BURST_INTERVAL_RIFLE_SECONDS = 0.8f;
    private static final float BURST_INTERVAL_MG_SECONDS = 0.35f;
    private int burstShotsFired = 0;
    private int burstCooldownTicks = 0;
    private int ticksSinceLastBurstShot = 0;
    private boolean burstWaitingForBolt = false;

    // Direct fire must not share state with a last-known-position fire plan.
    private boolean directBurstActive = false;
    private int directBurstShotsFired = 0;
    private int directBurstCooldownTicks = 0;
    private DirectFireWeaponProfile directBurstProfile = null;
    private int directBurstShotLimit = 0;
    private float directBurstContinuationThreshold = 0.0f;
    private java.util.Random mobileHipRandom;
    private int mobileHipShotsInBurst;
    private int mobileHipBurstLimit;
    private int mobileHipNextBurstTick;

    // Dynamic firing: per-engagement personality plus ineffective-burst tracking.
    private FirePersonality firePersonality;
    private float burstStartTargetHealth = -1.0f;
    private int ineffectiveBurstStreak = 0;
    private int lastShotTick = Integer.MIN_VALUE;
    private static final int TACTICAL_RELOAD_QUIET_TICKS = 100;
    private static final int MG_TACTICAL_RELOAD_QUIET_TICKS = 300;
    // Independent of per-engagement accuracy state; target changes must not erase this.
    private long lastTacticalReloadShotTime = Long.MIN_VALUE;
    private int suppressionCadenceTicks = 1;
    private int suppressionBurstTarget = 0;

    public static void setDebugLoggingEnabled(boolean enabled) {
        DiagnosticLogManager.setAttackLoggingEnabled(enabled);
    }

    private static boolean isDebugLogging() {
        return DiagnosticLogManager.isAttackLoggingEnabled();
    }

    private static boolean isDamageDebugLogging() {
        return DiagnosticLogManager.isDamageLoggingEnabled();
    }

    private static boolean isSuppressionDebugLogging() {
        return DiagnosticLogManager.isSuppressionLoggingEnabled();
    }

    private boolean isAttackSuppressionOrderActive() {
        return machineGunnerPipeline && soldier.hasValidAttackTarget();
    }

    private void synchronizeAttackSuppressionOrder() {
        int generation = soldier.getAttackGeneration();
        if (generation != attackSuppressionGeneration) {
            if (!attackSuppressionContacts.isEmpty() && isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[Suppression] MG {} clears ATTACK memory on generation {} -> {}",
                    soldier.getId(), attackSuppressionGeneration, generation);
            }
            attackSuppressionContacts.clear();
            attackSuppressionBlockedUntilTick.clear();
            attackSuppressionGeneration = generation;
            attackSuppressionActive = false;
            if (isSuppressing) cancelAllSuppression();
        }
        if (!isAttackSuppressionOrderActive()) {
            if (!attackSuppressionContacts.isEmpty() && isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[Suppression] MG {} clears ATTACK memory: order ended gen={}",
                    soldier.getId(), generation);
            }
            attackSuppressionContacts.clear();
            attackSuppressionBlockedUntilTick.clear();
            if (attackSuppressionActive) cancelAllSuppression();
            attackSuppressionActive = false;
            return;
        }

        SquadThreatIntel intel = getSquadIntel();
        if (intel != null) {
            long now = soldier.level().getGameTime();
            attackSuppressionContacts.entrySet().removeIf(entry -> intel.getThreat(entry.getKey())
                .map(threat -> !threat.isAlive).orElse(false));
            attackSuppressionBlockedUntilTick.keySet().removeIf(id -> intel.getThreat(id)
                .map(threat -> !threat.isAlive).orElse(false));
            for (SquadThreatIntel.ThreatKnowledge threat : intel.getAllThreats()) {
                if (!threat.isAlive || threat.isHardTarget || threat.lastKnownPosition == null
                    || now - threat.lastSeenTime > FireControl.SUPPRESSION_CONTACT_MAX_TICKS) {
                    continue;
                }
                rememberAttackSuppressionContact(threat.threatEntityId, threat.lastKnownPosition,
                    threat.lastVisibleAimPoint);
            }
        }
    }

    private void rememberAttackSuppressionObservation(LivingEntity observed) {
        if (!isAttackSuppressionOrderActive() || !observed.isAlive()
            || !TargetAcquisition.isValidTarget(soldier, observed)
            || !TargetAcquisition.hasLineOfSight(soldier, observed)) {
            return;
        }
        ExposureCalculator.AimPointResult aim = ExposureCalculator.getBestAimPoint(soldier, observed);
        Vec3 visibleAim = aim != null && aim.canShoot() ? aim.position : observed.getEyePosition();
        rememberAttackSuppressionContact(observed.getUUID(), observed.blockPosition(), visibleAim);
    }

    private void rememberAttackSuppressionContact(UUID id, BlockPos block, Vec3 visibleAim) {
        if (!isAttackSuppressionOrderActive() || block == null) return;
        AttackSuppressionContact contact = attackSuppressionContacts.computeIfAbsent(id,
            ignored -> new AttackSuppressionContact(block, visibleAim));
        boolean locationChanged = !contact.lastObservedBlock.equals(block);
        boolean aimChanged = visibleAim != null && (contact.lastVisibleAim == null
            || contact.lastVisibleAim.distanceToSqr(visibleAim) > 0.25);
        contact.lastObservedBlock = block;
        if (visibleAim != null) contact.lastVisibleAim = visibleAim;
        if (locationChanged) {
            contact.aimPointsRefreshTick = 0;
            contact.aimIndex = 0;
        }
        if (locationChanged || aimChanged) attackSuppressionBlockedUntilTick.remove(id);
    }

    private boolean startAttackSuppression() {
        if (!isAttackSuppressionOrderActive() || attackSuppressionContacts.isEmpty()) return false;
        UUID selected = null;
        for (UUID candidateId : attackSuppressionContacts.keySet()) {
            Integer retryTick = attackSuppressionBlockedUntilTick.get(candidateId);
            if (retryTick != null && soldier.tickCount < retryTick) continue;
            attackSuppressionBlockedUntilTick.remove(candidateId);
            AttackSuppressionContact candidate = attackSuppressionContacts.get(candidateId);
            if (soldier.position().distanceTo(candidate.lastObservedBlock.getCenter()) > SUPPRESSION_MAX_RANGE) continue;
            refreshAttackSuppressionAimPoints(candidate);
            if (getAttackSuppressionAim(candidate) == null) {
                attackSuppressionBlockedUntilTick.put(candidateId,
                    soldier.tickCount + PING_AIM_POINT_REFRESH_TICKS);
                if (isSuppressionDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[Suppression] MG {} skips blocked ATTACK contact={} at {} openings={} gen={}",
                        soldier.getId(), candidateId.toString().substring(0, 8), candidate.lastObservedBlock,
                        candidate.peekAimPoints.size(), attackSuppressionGeneration);
                }
                continue;
            }
            selected = candidateId;
            break;
        }
        if (selected == null) return false;
        attackSuppressionActive = true;
        suppressionTargetUUID = selected;
        AttackSuppressionContact contact = attackSuppressionContacts.get(selected);
        suppressionTargetPos = contact.lastObservedBlock;
        suppressionTargetAimPoint = contact.lastVisibleAim;
        suppressionLastSeenTick = soldier.level().getGameTime();
        suppressionPlanStartTick = soldier.tickCount;
        suppressionFirstShotTick = -1;
        pendingSuppressionThreat = null;
        resetBurstState();
        refreshAttackSuppressionAimPoints(contact);
        if (isSuppressionDebugLogging()) {
            StevesArmyMod.LOGGER.info("[Suppression] MG {} starts ATTACK contact={} gen={} contacts={} openings={}",
                soldier.getId(), selected.toString().substring(0, 8), attackSuppressionGeneration,
                attackSuppressionContacts.size(), contact.peekAimPoints.size());
        }
        return true;
    }

    private void refreshAttackSuppressionAimPoints(AttackSuppressionContact contact) {
        if (soldier.tickCount < contact.aimPointsRefreshTick) return;
        CoverFinder finder = new CoverFinder(soldier.level());
        contact.peekAimPoints = finder.findSuppressionAimPoints(soldier, contact.lastObservedBlock,
            SoldierEntity.SUPPRESSION_ZONE_RADIUS);
        contact.aimPointsRefreshTick = soldier.tickCount + PING_AIM_POINT_REFRESH_TICKS;
        if (isSuppressionDebugLogging()) {
            StevesArmyMod.LOGGER.info("[Suppression] MG {} ATTACK contact={} refreshed openings={} gen={}",
                soldier.getId(), suppressionTargetUUID == null ? "-" : suppressionTargetUUID.toString().substring(0, 8),
                contact.peekAimPoints.size(), attackSuppressionGeneration);
        }
    }

    private Vec3 getAttackSuppressionAim(AttackSuppressionContact contact) {
        refreshAttackSuppressionAimPoints(contact);
        List<Vec3> points = attackSuppressionAimPool(contact);
        if (!points.isEmpty()) {
            for (int offset = 0; offset < points.size(); offset++) {
                int index = Math.floorMod(contact.aimIndex + offset, points.size());
                Vec3 point = points.get(index);
                if (TargetAcquisition.hasNearLineOfSightToPosition(soldier, point, SUPPRESSION_LOS_TOLERANCE)) {
                    contact.aimIndex = index;
                    return point;
                }
            }
        }
        if (contact.lastVisibleAim != null
            && TargetAcquisition.hasNearLineOfSightToPosition(soldier, contact.lastVisibleAim, SUPPRESSION_LOS_TOLERANCE)) {
            return contact.lastVisibleAim;
        }
        Vec3 rememberedBlockAim = Vec3.atCenterOf(contact.lastObservedBlock).add(0, 1.0, 0);
        return TargetAcquisition.hasNearLineOfSightToPosition(soldier, rememberedBlockAim, SUPPRESSION_LOS_TOLERANCE)
            ? rememberedBlockAim : null;
    }

    private List<Vec3> attackSuppressionAimPool(AttackSuppressionContact contact) {
        List<Vec3> points = new ArrayList<>(contact.peekAimPoints);
        if (contact.lastVisibleAim != null
            && points.stream().noneMatch(point -> point.distanceToSqr(contact.lastVisibleAim) < 0.25)) {
            points.add(contact.lastVisibleAim);
        }
        return points;
    }

    private void advanceAttackSuppressionAim() {
        if (suppressionTargetUUID == null) return;
        AttackSuppressionContact current = attackSuppressionContacts.get(suppressionTargetUUID);
        if (current != null) {
            List<Vec3> aimPool = attackSuppressionAimPool(current);
            if (!aimPool.isEmpty() && current.aimIndex + 1 < aimPool.size()) {
                current.aimIndex++;
                return;
            }
        }
        List<UUID> ids = new ArrayList<>(attackSuppressionContacts.keySet());
        int currentIndex = ids.indexOf(suppressionTargetUUID);
        for (int step = 1; step <= ids.size(); step++) {
            UUID candidateId = ids.get(Math.floorMod(currentIndex + step, ids.size()));
            AttackSuppressionContact candidate = attackSuppressionContacts.get(candidateId);
            if (soldier.position().distanceTo(candidate.lastObservedBlock.getCenter()) > SUPPRESSION_MAX_RANGE) continue;
            suppressionTargetUUID = candidateId;
            suppressionTargetPos = candidate.lastObservedBlock;
            suppressionTargetAimPoint = candidate.lastVisibleAim;
            candidate.aimIndex = 0;
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[Suppression] MG {} ATTACK rotates contact={} openings={} gen={}",
                    soldier.getId(), candidateId.toString().substring(0, 8), candidate.peekAimPoints.size(),
                    attackSuppressionGeneration);
            }
            return;
        }
    }

    public SoldierCombatGoal(SoldierEntity soldier) {
        this(soldier, false);
    }

    public SoldierCombatGoal(SoldierEntity soldier, boolean machineGunnerPipeline) {
        this.soldier = soldier;
        this.machineGunnerPipeline = machineGunnerPipeline;
        this.mobileHipRandom = new java.util.Random(soldier.getUUID().getMostSignificantBits()
            ^ soldier.getUUID().getLeastSignificantBits() ^ 0x5EEDB17L);
        this.threatTracker = new ThreatTracker();
        this.detectionSystem = new DetectionSystem(soldier.getUUID(), machineGunnerPipeline);
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!soldier.isAlive() || soldier.isHealing()) return false;
        // A crew soldier manning a hull MG fights through the mounted gun, not
        // its rifle; posted-but-idle crew holds position for the next station.
        if (soldier.isVehicleCrewActive() || soldier.getRole() == SoldierRole.VEHICLE_CREW) return false;

        if (soldier.hasValidPingThreatPos() || soldier.hasValidPingSuppressPos()) {
            return true;
        }
        if (soldier.getTarget() != null && soldier.getTarget().isAlive()) {
            return true;
        }
        if (hasPotentialTargets()) {
            return true;
        }
        if (isAttackSuppressionOrderActive() && !attackSuppressionContacts.isEmpty()) {
            return true;
        }
        if (soldier.getCoverBehaviorManager().isInCover()) {
            return true;
        }

        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (!soldier.isAlive() || soldier.isHealing()) return false;
        if (soldier.isVehicleCrewActive() || soldier.getRole() == SoldierRole.VEHICLE_CREW) return false;

        // A valid fire plan must keep this goal alive even after its original
        // entity has left the local target list.
        if (isSuppressing) {
            return true;
        }
        if (isAttackSuppressionOrderActive() && !attackSuppressionContacts.isEmpty()) {
            return true;
        }

        if (soldier.getGrenadeTacticalController().isActive()) {
            return true;
        }

        if (target != null && target.isAlive() && TargetAcquisition.isValidTarget(soldier, target)) {
            if (TargetAcquisition.hasLineOfSight(soldier, target)) {
                return true;
            }
        }
        if (hasPotentialTargets()) {
            return true;
        }
        if (soldier.getCoverBehaviorManager().isInCover()) {
            return true;
        }
        if (GunIntegration.isAnyGunLoaded() && GunIntegration.hasGun(soldier)) {
            if (reloadPending || GunIntegration.isReloading(soldier)) {
                return true;
            }
            if (GunIntegration.getCurrentAmmo(soldier) == 0) {
                return true;
            }
        }
        if (soldier.hasValidPingSuppressPos()) {
            return true;
        }

        return false;
    }

    @Override
    public void start() {
        if (target == null) {
            findNewTarget();
        }

        if (target != null) {
            soldier.setTarget(target);
            threatTracker.reportThreatDirect(target);
            resetAim(target);
        }
        wasAiming = false;
    }

    @Override
    public void stop() {
        cancelAllSuppression();
        soldier.endCqbEngagement();
        if (GunIntegration.isAnyGunLoaded() && GunIntegration.hasGun(soldier)) {
            GunIntegration.aim(soldier, false);
        }
        soldier.setTarget(null);
        this.target = null;
        this.wasAiming = false;
        this.engagementPostureState = EngagementPostureState.READY;
        soldier.clearEmergencyEngagementPosture();
        resetAim(null);
    }
    
    private void resetAim(LivingEntity newTarget) {
        if (newTarget == null) {
            resetAimPatience();
            aimQuality = 0.0f;
            trackedTargetUUID = null;
            resetDirectFireBurst();
            resetDynamicFiringState();
            return;
        }
        if (trackedTargetUUID == null || !trackedTargetUUID.equals(newTarget.getUUID())) {
            resetAimPatience();
            aimPatienceTargetUUID = newTarget.getUUID();
            trackedTargetUUID = newTarget.getUUID();
            float switchReset = StevesArmyConfig.getAimQualitySwitchReset();
            aimQuality *= switchReset;
            resetDirectFireBurst();
            resetDynamicFiringState();
        }
    }

    /** A new engagement rolls a fresh personality and clears the miss-streak ratchet. */
    private void resetDynamicFiringState() {
        firePersonality = StevesArmyConfig.isDynamicFiringEnabled()
            ? FirePersonality.roll(soldier.getRandom()) : null;
        ineffectiveBurstStreak = 0;
        burstStartTargetHealth = -1.0f;
        lastShotTick = Integer.MIN_VALUE;
    }
    
    private ExposureCalculator.AimPointResult getOrComputeAimPoint() {
        if (target == null) {
            currentAimPoint = null;
            return null;
        }
        
        int currentTick = soldier.tickCount;
        UUID targetUUID = target.getUUID();
        Vec3 observerEye = soldier.getEyePosition();
        Vec3 targetPosition = target.position();
        Pose targetPose = target.getPose();
        float targetWidth = target.getBbWidth();
        float targetHeight = target.getBbHeight();
        // Select the same physically clear lane that the corner stop validates.
        // Ignoring the dock can otherwise prefer a blocked torso over an open edge.
        BlockPos coverBlock = null;
        int cacheTicks = StevesArmyConfig.getAimPointCacheTicks();

        if (cachedAimPoint != null && cacheTicks > 0
            && currentTick - cachedAimPointTick < cacheTicks
            && cachedAimPointTargetUUID.equals(targetUUID)
            && observerEye.equals(cachedAimObserverEye)
            && targetPosition.equals(cachedAimTargetPosition)
            && targetPose == cachedAimTargetPose
            && targetWidth == cachedAimTargetWidth
            && targetHeight == cachedAimTargetHeight
            && java.util.Objects.equals(coverBlock, cachedAimCoverBlock)) {
            currentAimPoint = cachedAimPoint;
            return cachedAimPoint;
        }
        
        currentAimPoint = ExposureCalculator.getBestAimPoint(soldier, target, coverBlock);
        cachedAimPoint = currentAimPoint;
        cachedAimPointTick = currentTick;
        cachedAimPointTargetUUID = targetUUID;
        cachedAimObserverEye = observerEye;
        cachedAimTargetPosition = targetPosition;
        cachedAimTargetPose = targetPose;
        cachedAimTargetWidth = targetWidth;
        cachedAimTargetHeight = targetHeight;
        cachedAimCoverBlock = coverBlock;
        return currentAimPoint;
    }

    @Override
    public void tick() {
        PerformanceMetrics.recordCombatTick(machineGunnerPipeline);
        updateAimPatienceContact();
        if (soldier.isHealing()) return;

        threatTracker.update(soldier);
        ArmorThreatScanner.maybeScan(soldier);
        synchronizeAttackSuppressionOrder();

        if (soldier.hasValidPingThreatPos()) {
            BlockPos threatPos = soldier.getPingThreatPos();
            if (threatPos != null) {
                threatTracker.reportThreatAtPosition(threatPos);
            }
        }
        
        List<LivingEntity> potentialTargets = getPotentialTargets();
        boolean detectionRefreshed = shouldRefreshDetection();
        DetectionSystem.DetectionScanResult scanResult = null;
        if (detectionRefreshed) {
            scanResult = detectionSystem.tick(soldier, potentialTargets, getSquadIntel());
            lastDetectionTick = soldier.level().getGameTime();
        } else {
            detectionSystem.advanceWithoutScan(soldier.level().getGameTime());
        }
        
        // Feed detected entities into ThreatAwareness using the scan result
        ThreatAwareness threats = soldier.getThreatAwareness();
        for (LivingEntity potential : potentialTargets) {
            boolean hasLineOfSight = false;
            boolean isDetected = false;
            if (scanResult != null) {
                var obs = scanResult.find(potential);
                if (obs.isPresent()) {
                    hasLineOfSight = obs.get().visible();
                    isDetected = obs.get().detected();
                } else {
                    // Candidate was filtered by isValidTarget or was duplicate
                    hasLineOfSight = TargetAcquisition.isValidTarget(soldier, potential)
                        && detectionSystem.wasTargetInLOS(potential);
                    isDetected = detectionSystem.isTargetDetected(potential);
                }
            } else {
                hasLineOfSight = TargetAcquisition.isValidTarget(soldier, potential)
                    && detectionSystem.wasTargetInLOS(potential);
                isDetected = detectionSystem.isTargetDetected(potential);
            }

            if (isDetected) {
                threats.onEntityDetected(potential, soldier.position(), hasLineOfSight && detectionRefreshed);
                if (hasLineOfSight && detectionRefreshed) {
                    reportThreatToSquadIntel(potential, 1.0f);
                }
            }

            if (hasLineOfSight && detectionRefreshed) {
                EnemyContactTracker.reportContact(soldier, potential);
                if (isDetected) {
                    rememberAttackSuppressionObservation(potential);
                }
            }
        }
        if (target != null && target.isAlive() && TargetAcquisition.hasLineOfSight(soldier, target)) {
            rememberAttackSuppressionObservation(target);
        }
        // Feed ping threat positions into ThreatAwareness if no entity found
        if (soldier.hasValidPingThreatPos() && !threats.hasActiveThreat()) {
            BlockPos threatPos = soldier.getPingThreatPos();
        }
        
        boolean hasGun = GunIntegration.isAnyGunLoaded() && GunIntegration.hasGun(soldier);
        if (!hasGun && reloadPending) {
            clearReloadStatus();
        }
        
        if (hasGun) {
            handleGunInitialization();
        }

        maintainSuppressionAssignment();

        // Branch ownership is decided before weapon selection so the held gun
        // always matches the engagement this tick: the launcher is raised only
        // while the vehicle branch owns the loop, and entity combat never runs
        // with a rocket launcher waiting on a swap cooldown.
        boolean targetVisible = target != null && target.isAlive()
            && TargetAcquisition.hasLineOfSight(soldier, target);
        visibleTargetStreakTicks = targetVisible ? visibleTargetStreakTicks + 1 : 0;

        ArmorThreatScanner.ArmorContact activeVehicleTarget = hasGun ? getVehicleCombatTarget() : null;
        if (activeVehicleTarget != null) {
            vehicleEngagementTicks = VEHICLE_ENGAGEMENT_LATCH_TICKS;
        } else if (vehicleEngagementTicks > 0) {
            vehicleEngagementTicks--;
        }
        soldier.setVehicleEngagement(vehicleEngagementTicks > 0);

        tickWeaponSelection(activeVehicleTarget);

        // Last-seen suppression must yield to a real visible target immediately,
        // including while the soldier is exposed from cover. Use a fresh scan so
        // newly in-range enemies are not delayed by the normal target cache.
        if (isSuppressing) {
            preemptSuppressionForVisibleTarget();
        }
        boolean inCover = soldier.getCoverBehaviorManager().isInCover();
        
        if (target == null || !target.isAlive()) {
            if (inCover && target == null) {
                targetReevaluateCounter = 0;
                findNewTarget();
            } else {
                findNewTarget();
            }
        } else if (!TargetAcquisition.hasLineOfSight(soldier, target)) {
            if (!inCover || soldier.getPeekController().getState() != PeekController.State.EXPOSED) {
                if (inCover) {
                    if (++targetReevaluateCounter >= StevesArmyConfig.getTargetReevaluateInterval()) {
                        targetReevaluateCounter = 0;
                        findNewTarget();
                    }
                } else {
                    findNewTarget();
                }
            }
        }

        if (hasGun && tickReloadState()) {
            if (soldier.getSuppressionOrder().isActive()) {
                soldier.getSuppressionOrder().setPaused(SuppressionOrderController.BlockReason.RELOAD);
            }
            updateDebugSync();
            if (soldier.tickCount % PING_DEBUG_SYNC_INTERVAL_TICKS == 0) {
                sendSuppressPingDebugToOwner();
            }
            return;
        }

        if (holdCombatForNavigationTraversal(hasGun)) {
            if (soldier.getSuppressionOrder().isActive()) {
                soldier.getSuppressionOrder().setPaused(SuppressionOrderController.BlockReason.MOVING);
                if (soldier.tickCount % PING_DEBUG_SYNC_INTERVAL_TICKS == 0) {
                    sendSuppressPingDebugToOwner();
                }
            }
            updateDebugSync();
            return;
        }

        // A suppress-area ping is a player order, not a target hint. It owns
        // combat until completion; only an immediate close-range threat may
        // borrow the loop for self-defense.
        SuppressionOrderController suppressionOrder = soldier.getSuppressionOrder();
        if (suppressionOrder.isActive()) {
            boolean closeSelfDefense = target != null && target.isAlive()
                && TargetAcquisition.hasLineOfSight(soldier, target)
                && soldier.distanceToSqr(target) <= SoldierEntity.CQB_RANGE * SoldierEntity.CQB_RANGE;
            if (closeSelfDefense) {
                suppressionOrder.setPaused(SuppressionOrderController.BlockReason.SELF_DEFENSE);
                tickCombat(hasGun, null);
            } else {
                // Release autonomous last-seen suppression, never the explicit order.
                if (isSuppressing) {
                    cancelAllSuppression();
                }
                soldier.setVehicleEngagement(false);
                if (inCover) {
                    tickCoverPeekCycle(soldier.getCoverBehaviorManager());
                }
                if (hasGun && shouldSuppressPingTarget()) {
                    trySuppressPingFire();
                } else {
                    suppressionOrder.setPaused(SuppressionOrderController.BlockReason.WEAPON);
                }
            }
            updateDebugSync();
            if (soldier.tickCount % PING_DEBUG_SYNC_INTERVAL_TICKS == 0) {
                sendSuppressPingDebugToOwner();
            }
            return;
        }

        // A hard target supplies only a hull aim point. It enters the same
        // direct-fire path as an entity target and never changes cover, peek,
        // posture, navigation, burst, or readiness control.
        if (activeVehicleTarget != null) {
            // Keep a live cover-search direction at the hull: without this the
            // smooth threat direction decays mid-fight and cover searches
            // alternate between direction-led and direction-less results.
            soldier.getThreatAwareness().onEnemyPing(BlockPos.containing(activeVehicleTarget.hullCenter()));
            tickCombat(hasGun, activeVehicleTarget);
            updateDebugSync();
        } else if (hasGun && heavyPingPhaseActive()) {
            // The heavy suppress ping is an explicit player order: the hunter
            // shells the cover blocks even while enemies are visible instead
            // of dropping to the sidearm — one rocket, then the order
            // continues as sidearm suppression below.
            if (inCover) {
                tickCoverPeekCycle(soldier.getCoverBehaviorManager());
            }
            if (shouldSuppressPingTarget()) {
                trySuppressPingFire();
            } else {
                isPingSuppressing = false;
            }
            updateDebugSync();
        } else if (target != null && target.isAlive()) {
            LivingEntity combatTarget = target;
            tickCombat(hasGun, null);
            updateDebugSync();

            if (combatTarget.isAlive() && TargetAcquisition.hasLineOfSight(soldier, combatTarget)) {
                reportThreatToSquadIntel(combatTarget, 1.0f);
            }
        } else {
            CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
            if (DiagnosticLogManager.isAttackLoggingEnabled() && soldier.tickCount % 100 == 0) {
                StevesArmyMod.LOGGER.info("[CombatGoal] Soldier {} tick: target=null, inCover={}, peekState={}, coverState={}",
                    soldier.getId(), inCover, soldier.getPeekController().getState(), coverManager.getState());
            }
            if (inCover) {
                tickCoverPeekCycle(coverManager);
            }

            if (hasGun && isSuppressing) {
                trySuppressireFire(null);
            } else if (hasGun && isAttackSuppressionOrderActive() && shouldSuppressTarget()) {
                isSuppressing = true;
                trySuppressireFire(null);
            } else {
                tickScanning(potentialTargets);
            }

            if (!isSuppressing && hasGun && shouldSuppressPingTarget()) {
                trySuppressPingFire();
            } else if (!isSuppressing) {
                isPingSuppressing = false;
            }
            
            if (isPingSuppressing) {
                updateDebugSync();
            } else {
                updateDebugSync();
            }
        }
        
        debugSyncTickCounter++;
        if (debugSyncTickCounter >= DEBUG_SYNC_INTERVAL) {
            debugSyncTickCounter = 0;
            sendDebugPacketToOwner();
        }
    }


    private boolean holdCombatForNavigationTraversal(boolean hasGun) {
        if (!soldier.isNavigationTraversalLocked()) {
            if (navigationTraversalLockWasActive
                && (DiagnosticLogManager.isAttackLoggingEnabled() || DiagnosticLogManager.isCoverLoggingEnabled())) {
                StevesArmyMod.LOGGER.info("[TraversalLock] soldier={} released tick={}",
                    soldier.getId(), soldier.tickCount);
            }
            navigationTraversalLockWasActive = false;
            return false;
        }

        if (!navigationTraversalLockWasActive
            && (DiagnosticLogManager.isAttackLoggingEnabled() || DiagnosticLogManager.isCoverLoggingEnabled())) {
            StevesArmyMod.LOGGER.info("[TraversalLock] soldier={} admitted tick={} reason={} heightDelta={}",
                soldier.getId(), soldier.tickCount, soldier.getNavigationTraversalLockReason(),
                soldier.getNavigationTraversalHeightDelta());
        }
        navigationTraversalLockWasActive = true;

        soldier.faceNavigationTraversal();
        resetDirectFireBurst();
        if (hasGun && wasAiming) {
            GunIntegration.aim(soldier, false);
        }
        wasAiming = false;
        return true;
    }

    private void tickFiringProne(boolean hasGun, boolean canSee) {
        if (lastFiringProneTick == soldier.tickCount) {
            return;
        }
        lastFiringProneTick = soldier.tickCount;
        traceFiringProne(hasGun, canSee);

        if (!hasGun) {
            if (soldier.isFiringProne()) {
                logFiringProneExit("gun_lost");
            }
            clearFiringProne(true, "gun_lost");
            return;
        }
        boolean hasTarget = target != null && target.isAlive();

        if (soldier.isFiringProne()) {
            String blockReason = getFiringProneBlockReason(canSee);
            if (blockReason != null) {
                logFiringProneExit(blockReason);
                clearFiringProne(true, blockReason);
                return;
            }

            if (hasTarget && !isProneAimVisible()) {
                firingProneInvalidLosTicks++;
                if (firingProneInvalidLosTicks >= FIRING_PRONE_INVALID_LOS_TICKS
                    && soldier.tickCount >= firingProneCommitUntilTick) {
                    logFiringProneExit("prone_los_blocked");
                    clearFiringProne(true, "prone_los_blocked");
                }
            } else {
                firingProneInvalidLosTicks = 0;
            }
            return;
        }

        if (firingProneCooldownTicks > 0) {
            firingProneCooldownTicks--;
            return;
        }

        String blockReason = getFiringProneBlockReason(canSee);
        if (blockReason != null) {
            resetFiringProneOpportunity(blockReason);
            return;
        }

        if (firingProneEligibleTicks == 0) {
            startFiringProneOpportunity();
        }
        firingProneEligibleTicks++;
        if (firingProneEligibleTicks < firingProneDelayTicks
            || soldier.tickCount % FIRING_PRONE_EVALUATION_INTERVAL != 0) {
            return;
        }

        float score = hasTarget ? getFiringProneScore() : getPositionOnlyFiringProneScore();
        if (hasTarget && !isProneAimVisible()) {
            return;
        }

        soldier.setFiringProne(true);
        firingProneCommitUntilTick = soldier.tickCount + FIRING_PRONE_MIN_COMMIT_TICKS;
        firingProneInvalidLosTicks = 0;
        if (isDamageDebugLogging()) {
            StevesArmyMod.LOGGER.info("[FiringProne] Soldier {} entered firing-prone stance score={} target={} distance={}",
                soldier.getId(), String.format("%.2f", score), hasTarget ? target.getId() : -1,
                hasTarget ? String.format("%.1f", soldier.distanceTo(target)) : "none");
        }
    }

    private void traceFiringProne(boolean hasGun, boolean canSee) {
        if (!isDamageDebugLogging() || soldier.tickCount % FIRING_PRONE_TRACE_INTERVAL != 0) {
            return;
        }

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        String blockReason;
        if (!hasGun) {
            blockReason = "no_gun";
        } else if (target == null || !target.isAlive()) {
            blockReason = "no_target";
        } else if (soldier.isFiringProne()) {
            blockReason = "active";
        } else if (firingProneCooldownTicks > 0) {
            blockReason = "cooldown";
        } else {
            blockReason = getFiringProneBlockReason(canSee);
            if (blockReason == null && firingProneEligibleTicks < firingProneDelayTicks) {
                blockReason = "settling_delay";
            } else if (blockReason == null) {
                if (!isProneAimVisible()) {
                    blockReason = "prone_los_blocked";
                } else {
                    blockReason = "position_authorized";
                }
            }
        }

        double speed = soldier.getDeltaMovement().horizontalDistance();
        double distance = target != null ? soldier.distanceTo(target) : -1.0;
        double angle = target != null
            ? Math.abs(Mth.wrapDegrees(getYawTo(target.getEyePosition()) - soldier.getYRot())) : -1.0;
        double recoil = -1.0;
        float score = -1.0f;
        boolean proneLos = false;
        if (hasGun && target != null && target.isAlive()) {
            float[] recoilValues = GunIntegration.getGunRecoil(soldier);
            recoil = Math.abs(recoilValues[0]) + Math.abs(recoilValues[1]);
            score = getFiringProneScore();
            proneLos = isProneAimVisible();
        }

        StevesArmyMod.LOGGER.info(
            "[FiringProneTrace] soldier={} active={} reason={} gun={} target={} targetId={} canSee={} coverState={} currentCover={} targetCover={} pingMove={} speed={} distance={} angle={} cooldown={} eligible={}/{} recoil={} score={} threshold={} proneLos={}",
            soldier.getId(), soldier.isFiringProne(), blockReason, hasGun,
            target != null && target.isAlive(), target != null ? target.getId() : -1,
            canSee, coverManager.getState(),
            coverManager.getCurrentCover() != null, coverManager.getTargetCover() != null,
            soldier.hasValidPingMoveTarget(), String.format("%.3f", speed),
            String.format("%.1f", distance), String.format("%.1f", angle),
            firingProneCooldownTicks, firingProneEligibleTicks, firingProneDelayTicks,
            String.format("%.2f", recoil), String.format("%.2f", score),
            "position-owned", proneLos);
    }

    private void logFiringProneExit(String reason) {
        if (isDamageDebugLogging()) {
            StevesArmyMod.LOGGER.info("[FiringProneExit] soldier={} reason={} target={} tick={}",
                soldier.getId(), reason, target != null ? target.getId() : -1, soldier.tickCount);
        }
    }

    @javax.annotation.Nullable
    private String getFiringProneBlockReason(boolean canSee) {
        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        CoverBehaviorManager.CoverState coverState = coverManager.getState();
        boolean hasCurrentCover = coverManager.getCurrentCover() != null;
        boolean hasSelectedCover = coverManager.getTargetCover() != null;
        boolean coverMovementActive = hasCurrentCover || hasSelectedCover
            || coverState == CoverBehaviorManager.CoverState.REPOSITIONING;

        if (!firingPronePositionAuthorized) return "no_prone_position";
        if (target != null && target.isAlive() && !canSee) return "no_direct_los";
        if (coverMovementActive) return "cover_active";
        if (soldier.hasValidPingMoveTarget()) return "ping_move";
        if (soldier.isCrawlMoving()) return "crawl_moving";
        if (soldier.getDeltaMovement().horizontalDistanceSqr() > 0.0025D) return "moving";

        if (target == null || !target.isAlive()) return null;

        double distance = soldier.distanceTo(target);
        if (distance < FIRING_PRONE_MIN_RANGE) return "too_close";
        if (distance <= FIRING_PRONE_CLOSE_THREAT_RANGE) return "close_threat";

        for (LivingEntity potential : getPotentialTargets()) {
            if (potential == target || !potential.isAlive() || soldier.isFriendlyTo(potential)) {
                continue;
            }
            if (soldier.distanceTo(potential) <= FIRING_PRONE_CLOSE_THREAT_RANGE) {
                return "nearby_threat";
            }
        }

        float targetYaw = getYawTo(target.getEyePosition());
        float proneAngle = Math.abs(Mth.wrapDegrees(targetYaw - soldier.getYRot()));
        return proneAngle <= PRONE_FIRING_ARC_DEGREES ? null : "outside_prone_arc";
    }

    private boolean isProneAimVisible() {
        if (target == null || !target.isAlive()) {
            return false;
        }

        Vec3 aimPoint = target.getEyePosition();
        ExposureCalculator.AimPointResult computed = getOrComputeAimPoint();
        if (computed != null && computed.pointVisible) {
            aimPoint = computed.position;
        }

        Vec3 proneEye = new Vec3(
            soldier.getX(),
            soldier.getY() + soldier.getEyeHeight(Pose.SWIMMING),
            soldier.getZ()
        );
        return VisibilityRay.trace(soldier.level(), proneEye, aimPoint, soldier).hasContact();
    }

    public Vec3 getProneFiringAimPoint(LivingEntity requestedTarget) {
        if (requestedTarget == target) {
            ExposureCalculator.AimPointResult computed = getOrComputeAimPoint();
            if (computed != null && computed.pointVisible) {
                return computed.position;
            }
        }
        return requestedTarget.getEyePosition();
    }

    private float getFiringProneScore() {
        float[] recoil = GunIntegration.getGunRecoil(soldier);
        double recoilMagnitude = Math.abs(recoil[0]) + Math.abs(recoil[1]);
        float recoilScore = (float) Math.max(0.0, Math.min(1.0,
            (recoilMagnitude - FIRING_PRONE_RECOIL_FLOOR)
                / (FIRING_PRONE_RECOIL_CEILING - FIRING_PRONE_RECOIL_FLOOR)));

        double range = soldier.distanceTo(target);
        float rangeScore = (float) Math.max(0.0, Math.min(1.0,
            (range - FIRING_PRONE_MIN_RANGE) / 32.0));
        firingProneLastRangeScore = rangeScore * FIRING_PRONE_RANGE_WEIGHT;
        float suppressiveScore = soldier.getFireDiscipline() == FireDiscipline.SUPPRESSIVE
            ? FIRING_PRONE_SUPPRESSION_WEIGHT : 0.0f;
        float stationaryScore = firingProneEligibleTicks >= firingProneDelayTicks
            ? FIRING_PRONE_STATIONARY_WEIGHT : 0.0f;

        return recoilScore * FIRING_PRONE_RECOIL_WEIGHT
            + firingProneLastRangeScore
            + suppressiveScore
            + stationaryScore
            + firingPronePreference
            + firingProneEngagementJitter;
    }

    private float getPositionOnlyFiringProneScore() {
        float[] recoil = GunIntegration.getGunRecoil(soldier);
        double recoilMagnitude = Math.abs(recoil[0]) + Math.abs(recoil[1]);
        float recoilScore = (float) Math.max(0.0, Math.min(1.0,
            (recoilMagnitude - FIRING_PRONE_RECOIL_FLOOR)
                / (FIRING_PRONE_RECOIL_CEILING - FIRING_PRONE_RECOIL_FLOOR)));
        float suppressiveScore = soldier.getFireDiscipline() == FireDiscipline.SUPPRESSIVE
            ? FIRING_PRONE_SUPPRESSION_WEIGHT : 0.0f;
        float stationaryScore = firingProneEligibleTicks >= firingProneDelayTicks
            ? FIRING_PRONE_STATIONARY_WEIGHT : 0.0f;
        return recoilScore * FIRING_PRONE_RECOIL_WEIGHT + firingProneLastRangeScore
            + suppressiveScore + stationaryScore + firingPronePreference + firingProneEngagementJitter;
    }

    private float getFiringProneRecoilLossMultiplier() {
        return soldier.isFiringProne() ? FIRING_PRONE_RECOIL_LOSS_MULTIPLIER : 1.0f;
    }

    /**
     * Stance term for the per-shot recoil aim loss: prone-firing and crawl
     * (TaCZ {@code crawl_recoil_multiplier}, mirrored here because TaCZ
     * applies it to the real kick via lowCrouch), plus the MG bipod scale —
     * a stationary gunner has the bipod down and does not pay shoulder-fire
     * recoil per shot.
     */
    private float stanceRecoilLossMultiplier() {
        float multiplier = getFiringProneRecoilLossMultiplier();
        if (StevesArmyConfig.isCrawlStanceRecoilEnabled() && soldier.isLowCrouching()) {
            multiplier *= GunIntegration.getCrawlRecoilMultiplier(soldier);
        }
        if (machineGunnerPipeline) {
            multiplier *= StevesArmyConfig.getMgRecoilAimLossScale();
        }
        return multiplier;
    }

    private void startFiringProneOpportunity() {
        firingProneDelayTicks = FIRING_PRONE_DELAY_MIN_TICKS
            + soldier.level().random.nextInt(FIRING_PRONE_DELAY_MAX_TICKS - FIRING_PRONE_DELAY_MIN_TICKS + 1);
        firingProneEngagementJitter = (soldier.level().random.nextFloat() - 0.5f) * 0.16f;
        int hash = soldier.getUUID().hashCode();
        firingPronePreference = ((hash & 0xFFFF) / 65535.0f - 0.5f) * 0.16f;

        if (isDamageDebugLogging()) {
            StevesArmyMod.LOGGER.info(
                "[FiringProneOpportunity] soldier={} event=start target={} delay={} jitter={} preference={}",
                soldier.getId(), target != null ? target.getId() : -1, firingProneDelayTicks,
                String.format("%.2f", firingProneEngagementJitter),
                String.format("%.2f", firingPronePreference));
        }
    }

    private void resetFiringProneOpportunity(String reason) {
        if (firingProneEligibleTicks <= 0) {
            return;
        }

        if (isDamageDebugLogging()) {
            StevesArmyMod.LOGGER.info(
                "[FiringProneOpportunity] soldier={} event=reset reason={} target={} eligible={}/{}",
                soldier.getId(), reason, target != null ? target.getId() : -1,
                firingProneEligibleTicks, firingProneDelayTicks);
        }
        firingProneEligibleTicks = 0;
        firingProneEngagementJitter = 0.0f;
        firingProneLastRangeScore = 0.0f;
    }

    private void validateFiringProneTargetHandoff(LivingEntity newTarget) {
        if (!soldier.isFiringProne()) {
            return;
        }

        boolean canSee = TargetAcquisition.hasLineOfSight(soldier, newTarget);
        String blockReason = getFiringProneBlockReason(canSee);
        if (blockReason == null && isProneAimVisible()) {
            firingProneInvalidLosTicks = 0;
            if (isDamageDebugLogging()) {
                StevesArmyMod.LOGGER.info(
                    "[FiringProneHandoff] soldier={} target={} result=retained",
                    soldier.getId(), newTarget.getId());
            }
            return;
        }

        String exitReason = blockReason != null ? blockReason : "prone_los_blocked";
        logFiringProneExit("target_handoff_" + exitReason);
        clearFiringProne(true, "target_handoff_" + exitReason);
    }

    private void clearFiringProne(boolean startCooldown, String reason) {
        boolean wasFiringProne = soldier.isFiringProne();
        if (wasFiringProne) {
            soldier.setFiringProne(false);
            firingProneCommitUntilTick = -1;
            firingProneInvalidLosTicks = 0;
        }
        resetFiringProneOpportunity(reason);
        if (wasFiringProne && startCooldown) {
            firingProneCooldownTicks = FIRING_PRONE_COOLDOWN_MIN_TICKS
                + soldier.level().random.nextInt(FIRING_PRONE_COOLDOWN_MAX_TICKS - FIRING_PRONE_COOLDOWN_MIN_TICKS + 1);
        }
    }

    public void setFiringPronePositionAuthorized(boolean authorized) {
        firingPronePositionAuthorized = authorized;
        if (!authorized) {
            if (soldier.isFiringProne()) {
                clearFiringProne(true, "position_cancelled");
            } else {
                resetFiringProneOpportunity("position_cancelled");
            }
        }
    }

    public boolean isFiringPronePositionAuthorized() {
        return firingPronePositionAuthorized;
    }

    public void tickFiringPronePositionFromCover() {
        boolean hasGun = GunIntegration.isAnyGunLoaded() && GunIntegration.hasGun(soldier);
        boolean canSee = target != null && target.isAlive()
            && TargetAcquisition.hasLineOfSight(soldier, target);
        tickFiringProne(hasGun, canSee);
    }
    
    private void handleGunInitialization() {
        ItemStack currentGun = soldier.getMainHandItem();
        boolean gunChanged = !lastGunStack.isEmpty() && !ItemStack.isSameItem(lastGunStack, currentGun);
        
        if (gunChanged) {
            gunInitialized = false;
        }
        
        if (!gunInitialized) {
            if (!GunIntegration.isReloading(soldier) && !GunIntegration.isBolting(soldier)) {
                GunIntegration.initialData(soldier);
                GunIntegration.draw(soldier);
            }
            gunInitialized = true;
            lastGunStack = currentGun.copy();
        }
        
    }

    private boolean tickReloadState() {
        if (reloadRetryTicks > 0) {
            reloadRetryTicks--;
        }

        if (GunIntegration.isReloading(soldier)) {
            reloadPending = false;
            reloadStartRequested = false;
            soldier.setReloadStatus(false, tacticalReloadPending);
            wasReloading = true;
            return true;
        }

        if (wasReloading) {
            GunIntegration.initialData(soldier);
            GunIntegration.draw(soldier);
            clearReloadStatus();
            wasReloading = false;
            // A reload that consumed reserve ammo but left the magazine at zero
            // means the write never landed on the held stack; back off instead
            // of re-requesting every tick and draining the ammo supply.
            if (GunIntegration.getCurrentAmmo(soldier) == 0 && !usesInventoryAmmoWithoutReserve()) {
                reloadRetryTicks = 100;
            }
            return true;
        }

        if (reloadStartRequested) {
            clearReloadStatus();
            reloadRetryTicks = 20;
            return false;
        }

        if (reloadPending) {
            if (tacticalReloadPending && (isDirectlyEngaging() || !hasTacticalReloadQuietTime())) {
                clearReloadStatus();
                return false;
            }

            if (isReadyToReload()) {
                prepareEnemyReserveAmmo();
                GunIntegration.reload(soldier);
                reloadStartRequested = true;
            }
            return true;
        }

        if (reloadRetryTicks > 0 || usesInventoryAmmoWithoutReserve()) {
            return false;
        }

        if (GunIntegration.getCurrentAmmo(soldier) == 0) {
            requestReload(false);
            return reloadPending;
        }

        if (shouldTacticalReload()) {
            requestReload(true);
            return true;
        }

        return false;
    }

    private void requestReload(boolean tactical) {
        prepareEnemyReserveAmmo();
        if (reloadPending || GunIntegration.isReloading(soldier)
            || usesInventoryAmmoWithoutReserve()
            || !GunIntegration.canReload(soldier)) {
            return;
        }

        if (!isSuppressing) {
            cancelAllSuppression();
        }
        reloadPending = true;
        tacticalReloadPending = tactical;
        soldier.setReloadStatus(true, tactical);
    }

    private void clearReloadStatus() {
        reloadPending = false;
        tacticalReloadPending = false;
        reloadStartRequested = false;
        soldier.setReloadStatus(false, false);
    }

    private boolean isReadyToReload() {
        if (GunIntegration.isBolting(soldier) || GunIntegration.isDrawing(soldier)
            || GunIntegration.getShootCoolDown(soldier) != 0) {
            return false;
        }

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        return !coverManager.isInCover() || soldier.getPeekController().isIdleInCover();
    }

    private boolean shouldTacticalReload() {
        if (usesInventoryAmmoWithoutReserve()
            || !GunIntegration.canReload(soldier)
            || !soldier.getCoverBehaviorManager().isInCover() || isDirectlyEngaging()
            || !hasTacticalReloadQuietTime()) {
            return false;
        }

        int magazineSize = GunIntegration.getMagazineSize(soldier);
        int currentAmmo = GunIntegration.getCurrentAmmo(soldier);
        return magazineSize > 0
            && currentAmmo * 2 < magazineSize
            && getTotalAmmo() > currentAmmo;
    }

    private boolean hasTacticalReloadQuietTime() {
        int quietTicks = machineGunnerPipeline
            ? MG_TACTICAL_RELOAD_QUIET_TICKS : TACTICAL_RELOAD_QUIET_TICKS;
        return lastTacticalReloadShotTime == Long.MIN_VALUE
            || soldier.level().getGameTime() - lastTacticalReloadShotTime >= quietTicks;
    }

    private boolean usesInventoryAmmoWithoutReserve() {
        return GunIntegration.useInventoryAmmo(soldier) && !soldier.hasInfiniteReserveAmmo();
    }

    private void prepareEnemyReserveAmmo() {
        soldier.ensureInfiniteReserveAmmo();
    }

    private boolean isDirectlyEngaging() {
        return (target != null && target.isAlive() && TargetAcquisition.hasLineOfSight(soldier, target))
            || isSuppressing
            || suppressionTargetPos != null
            || isPingSuppressing
            || pingSuppressRemainingTicks > 0
            || soldier.hasValidPingSuppressPos();
    }
    
    /**
     * Returns a fresh hull contact only when no normal living target owns the
     * direct-fire loop. Point-blank self-defence remains ordinary combat.
     */
    @javax.annotation.Nullable
    private ArmorThreatScanner.ArmorContact getVehicleCombatTarget() {
        if (!ArmorRoleManager.isArmorHunter(soldier) || soldier.isCqbEngagementHold()) {
            return null;
        }
        // While a vehicle engagement is latched, a briefly peeking crew does
        // not steal the loop back; a sustained sighting hands it to ordinary
        // entity combat.
        if (target != null && target.isAlive()
            && TargetAcquisition.hasLineOfSight(soldier, target)
            && (vehicleEngagementTicks <= 0
                || visibleTargetStreakTicks >= VEHICLE_EXIT_VISIBLE_TICKS)) {
            return null;
        }
        ArmorThreatScanner.ArmorContact armor = ArmorThreatScanner.getPrimaryArmorThreat(soldier);
        if (armor == null) {
            return null;
        }
        // Never engage the ship we are ourselves riding or standing on, even
        // if stale squad intel still lists it as a contact.
        if (ArmorThreatScanner.isOwnMountContact(soldier, armor)) {
            return null;
        }
        double maxRange = StevesArmyConfig.getArmorEngagementMaxRange();
        return soldier.distanceToSqr(armor.aimPoint()) <= maxRange * maxRange ? armor : null;
    }

    /**
     * The ordinary combat flow with one optional non-entity target adapter.
     * A vehicle changes only the coordinate given to direct firing; all cover,
     * peek, posture, readiness, and burst logic stays shared.
     */
    private void tickCombat(boolean hasGun, @javax.annotation.Nullable ArmorThreatScanner.ArmorContact vehicleTarget) {
        boolean vehicleCombat = vehicleTarget != null;
        boolean canSee = !vehicleCombat && TargetAcquisition.hasLineOfSight(soldier, target);

        if (!vehicleCombat) {
            // Emergency engagement hold: stop and fight only when a visible
            // threat is inside the emergency range AND the soldier can actually
            // shoot (not mid reload) AND the hold was not just released for
            // producing nothing. Manual CQB is a mobility doctrine - caution
            // steering, faster reassessment, cover use and spacing - so it
            // keeps only the point-blank survival stop, not a freeze on sight.
            double holdRange = soldier.isCQB()
                ? StevesArmyConfig.getCqbOverrunRange()
                : StevesArmyConfig.getCqbEmergencyRange();
            SoldierMovementController movement = soldier.getMovementController();
            boolean canLatchCqbHold = !movement.canBypassCqbHold();
            boolean shouldHoldCqbEngagement = canLatchCqbHold && target != null && target.isAlive()
                && canSee
                && GunIntegration.isAnyGunLoaded() && GunIntegration.hasGun(soldier)
                && !soldier.isPreparingOrReloading()
                && !soldier.isCqbHoldReleaseCooldown()
                && !soldier.getCoverBehaviorManager().isSuppressed()
                && soldier.distanceToSqr(target) <= holdRange * holdRange;
            if (shouldHoldCqbEngagement) {
                soldier.beginCqbEngagement();
                if (soldier.isCqbEngagementHold()
                    && soldier.cqbHoldTicksWithoutShot() > StevesArmyConfig.getCqbHoldNoShotTimeoutTicks()) {
                    // A hold that never produces a burst is a stranded soldier,
                    // not a fighting position - release it so cover and movement
                    // reasoning act, and rate-limit the next latch.
                    soldier.armCqbHoldReleaseCooldown();
                    soldier.endCqbEngagement();
                }
            } else if (soldier.isCqbEngagementHold()) {
                soldier.endCqbEngagement();
            }
            if (directBurstActive) {
                soldier.markCqbHoldShot();
            }

            if (canSee) {
                threatTracker.reportThreatDirect(target);
                resetAim(target);
                cancelAllSuppression();
            }
        }

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        if (coverManager.isInCover()) {
            tickCoverPeekCycle(coverManager);
        }

        if (vehicleCombat) {
            if (target != null && !TargetAcquisition.hasLineOfSight(soldier, target)) {
                // Fighting the hull, not a hidden crew entity: drop the stale
                // target so aim and posture serve the vehicle engagement only.
                // Detection memory is untouched; the latch keeps findNewTarget
                // from re-acquiring the hidden entity while this lasts.
                this.target = null;
                soldier.setTarget(null);
            }
            soldier.getLookControl().setLookAt(
                vehicleTarget.aimPoint().x, vehicleTarget.aimPoint().y, vehicleTarget.aimPoint().z, 30.0F, 30.0F);
            if (hasGun) {
                tickGunCombat(vehicleTarget);
            }
            return;
        }

        // A valid direct shot from cover must track the target just like an
        // exposed peek. Cover ownership still controls the soldier's position.
        if (!soldier.getMovementController().hasMovingIntent()) {
            soldier.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }

        if (hasGun) {
            if (soldier.getPeekController().getQuickPeekAim() != null || canSee) {
                tickGunCombat(null);
            } else if (isSuppressing) {
                trySuppressireFire(null);
            } else if (shouldSuppressTarget()) {
                isSuppressing = true;
                trySuppressireFire(null);
            } else if (shouldSuppressPingTarget()) {
                trySuppressPingFire();
            } else {
                isSuppressing = false;
                isPingSuppressing = false;
            }
        }

        if (!soldier.isCqbEngagementHold() && soldier.isCQB() && target != null && target.isAlive()
            && soldier.hasGoToNavigationOwnership() && isDebugLogging()
            && soldier.tickCount - lastCqbGoToPursuitSuppressedTick >= DEBUG_SYNC_INTERVAL) {
            lastCqbGoToPursuitSuppressedTick = soldier.tickCount;
            StevesArmyMod.LOGGER.info(
                "[CqbNav] Soldier {} preserved GO_TO {} while not pursuing enemy {} hold={}",
                soldier.getId(), soldier.getPingMoveTarget(), target.getId(), soldier.isCqbEngagementHold());
        }
    }

    private void cancelAllSuppression() {
        SquadThreatIntel intel = getSquadIntel();
        if (intel != null && suppressionTargetUUID != null) {
            intel.releaseThreatSuppression(suppressionTargetUUID, soldier.getUUID());
        }

        suppressionTargetUUID = null;
        suppressionTargetPos = null;
        suppressionTargetAimPoint = null;
        suppressionPlanStartTick = -1;
        suppressionFirstShotTick = -1;
        suppressionLastSeenTick = -1;
        suppressionLaneSlot = 0;
        suppressionRotation = List.of();
        suppressionRotationIndex = -1;
        pendingSuppressionThreat = null;
        isSuppressing = false;
        attackSuppressionActive = false;
        attackSuppressionSelectedAim = null;
        
        isPingSuppressing = false;
        pingSuppressRemainingTicks = 0;
        
        resetBurstState();
    }

    /**
     * Records why the direct-fire pipeline did not produce a shot this tick.
     * Every silent exit in {@link #tickGunCombat} tags itself so the engagement
     * telemetry (state snapshot, debug card, sim detectors) can tell a
     * legitimate hold from a visible-target deadlock.
     */
    private void noteEngagementGate(EngagementGateReason reason) {
        com.stevesarmy.combat.EngagementTelemetry telemetry = soldier.getEngagementTelemetry();
        telemetry.noteGate(reason, soldier.tickCount);
        telemetry.setWeaponReady(!GunIntegration.isReloading(soldier)
            && !GunIntegration.isDrawing(soldier)
            && !GunIntegration.isBolting(soldier));
        telemetry.setTargetVisible(target != null && target.isAlive());
    }

    private void tickGunCombat(@javax.annotation.Nullable ArmorThreatScanner.ArmorContact vehicleTarget) {
        if (quickBurstWasActive && soldier.getPeekController().getQuickPeekAim() == null) {
            resetBurstState();
            quickBurstWasActive = false;
        }
        boolean vehicleCombat = vehicleTarget != null;
        updateAimPatienceContact();
        if (vehicleCombat) resetAimPatience();
        if (!vehicleCombat && soldier.getPeekController().getQuickPeekAim() != null) {
            if (!GunIntegration.isReloading(soldier) && !GunIntegration.isDrawing(soldier)
                && !GunIntegration.isBolting(soldier)) trySuppressireFire(null);
            return;
        }
        // Corner slicing owns ADS while approaching; it never bypasses the shot gates below.
        if (!vehicleCombat && soldier.getPeekController().isMovingToPeek()
            && soldier.getPeekController().isCornerPreAiming()) {
            noteEngagementGate(EngagementGateReason.PREPARE_FAILED);
            resetDirectFireBurst();
            return;
        }
        if (!vehicleCombat && (soldier.getMovementController().isStationaryFireConstrained()
            || (soldier.getMovementController().hasMovingIntent()
                && soldier.getMovementController().fireMode() != SoldierMovementController.FireMode.ADS))) {
            GunIntegration.aim(soldier, false);
            wasAiming = false;
        }
        // Doctrine: the launcher only ever fires at a vehicle. If entity combat
        // owns the loop while the launcher is still in hand (swap failed or the
        // launcher is the soldier's only gun), hold fire rather than rocket
        // infantry.
        if (!vehicleCombat && ArmorRoleManager.isAtGunStack(soldier.getMainHandItem())) {
            noteEngagementGate(EngagementGateReason.WEAPON_HOLD);
            resetDirectFireBurst();
            return;
        }
        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        Vec3 vehicleSolution = vehicleCombat
            ? ArmorThreatScanner.findFiringSolution(soldier, vehicleTarget) : null;
        if (vehicleCombat && vehicleSolution == null) {
            noteEngagementGate(EngagementGateReason.WEAPON_HOLD);
            resetDirectFireBurst();
            return;
        }

        if (soldier.isHalfCoverRising()) {
            if (soldier.getMovementController().hasMovingIntent()) {
                GunIntegration.aim(soldier, false);
                wasAiming = false;
                noteEngagementGate(EngagementGateReason.PREPARE_FAILED);
                resetDirectFireBurst();
                return;
            }
            Vec3 risingAim = vehicleCombat ? vehicleSolution
                : target != null && target.isAlive() ? target.getEyePosition() : null;
            if (risingAim != null && prepareToFire(risingAim, true)) {
                GunIntegration.aim(soldier, true);
                wasAiming = true;
            }
            noteEngagementGate(EngagementGateReason.PREPARE_FAILED);
            resetDirectFireBurst();
            return;
        }

        boolean isDrawing = GunIntegration.isDrawing(soldier);
        boolean isBolting = GunIntegration.isBolting(soldier);
        boolean isReloading = GunIntegration.isReloading(soldier);

        if (isDamageDebugLogging()) {
            StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] tickGunCombat: soldier={}({}) target={}({}) isDrawing={} isBolting={} isReloading={}",
                soldier.getName().getString(), soldier.getId(),
                target != null ? target.getName().getString() + "(" + target.getId() + ")" : "null",
                target != null ? target.getClass().getSimpleName() : "null",
                isDrawing, isBolting, isReloading);
        }

        if (isReloading) {
            wasReloading = true;
            noteEngagementGate(EngagementGateReason.RELOADING);
            resetDirectFireBurst();
            return;
        }

        if (isDrawing || isBolting) {
            noteEngagementGate(isDrawing ? EngagementGateReason.DRAWING : EngagementGateReason.BOLTING);
            resetDirectFireBurst();
            return;
        }

        ExposureCalculator.AimPointResult aimPoint = vehicleCombat
            ? new ExposureCalculator.AimPointResult(vehicleSolution,
                ExposureCalculator.AimPointType.FALLBACK, true, true, 0.0)
            : getOrComputeAimPoint();
        if (aimPoint == null) {
            if (isDamageDebugLogging()) {
                StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] tickGunCombat: aimPoint is null, can't shoot");
            }
            noteEngagementGate(EngagementGateReason.NO_AIM_POINT);
            resetDirectFireBurst();
            return;
        }

        if (!aimPoint.canShoot()) {
            if (isDamageDebugLogging()) {
                StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] tickGunCombat: canShoot=false (pointVisible={} bulletPathClear={}) aimType={}",
                    aimPoint.pointVisible, aimPoint.bulletPathClear, aimPoint.type.displayName);
            }
            noteEngagementGate(aimPoint.pointVisible
                ? EngagementGateReason.BULLET_PATH_BLOCKED : EngagementGateReason.NOT_VISIBLE);
            if (!vehicleCombat && isSuppressing) {
                resetDirectFireBurst();
                trySuppressireFire(null);
                return;
            }
            if (!vehicleCombat && shouldSuppressTarget()) {
                isSuppressing = true;
                resetDirectFireBurst();
                trySuppressireFire(null);
                return;
            }
            
            int maxBlockedTicks = (soldier.isCQB() || soldier.hasCloseRangeTarget())
                ? CQB_PATH_BLOCKED_SWITCH_TICKS : PATH_BLOCKED_SWITCH_TICKS;
            pathBlockedCounter++;
            if (pathBlockedCounter >= maxBlockedTicks) {
                if (isDamageDebugLogging()) {
                    StevesArmyMod.LOGGER.info("Path blocked for {} ticks (CQB={}), switching target", pathBlockedCounter, soldier.isCQB() || soldier.hasCloseRangeTarget());
                }
                pathBlockedCounter = 0;
                if (findNewTarget()) {
                    resetAim(target);
                }
            }
            return;
        }

        SoldierMovementController movement = soldier.getMovementController();
        boolean aimQualityUpdated = false;
        if (!vehicleCombat && movement.isStationaryFireConstrained()) {
            GunIntegration.aim(soldier, false);
            wasAiming = false;
            noteEngagementGate(EngagementGateReason.PREPARE_FAILED);
            return;
        }
        if (!vehicleCombat && movement.hasMovingIntent()) {
            updateAimQuality(null);
            aimQualityUpdated = true;
            if (movement.fireMode() == SoldierMovementController.FireMode.NONE
                || !movement.canFireAt(aimPoint.position)) {
                GunIntegration.aim(soldier, false);
                wasAiming = false;
                noteEngagementGate(EngagementGateReason.PREPARE_FAILED);
                resetDirectFireBurst();
                return;
            }
            if (movement.fireMode() == SoldierMovementController.FireMode.HIP) {
                if (target == null || !target.isAlive()
                    || !TargetAcquisition.hasLineOfSight(soldier, target)
                    || !TargetAcquisition.isValidTarget(soldier, target)) {
                    noteEngagementGate(EngagementGateReason.NOT_VISIBLE);
                    return;
                }
                tickTraverseHipFire(aimPoint);
                return;
            }
            if (movement.fireMode() != SoldierMovementController.FireMode.ADS
                || !prepareForMovingFire(aimPoint.position)) {
                noteEngagementGate(EngagementGateReason.PREPARE_FAILED);
                return;
            }
        } else if (!prepareToFire(aimPoint.position, true)) {
            noteEngagementGate(EngagementGateReason.PREPARE_FAILED);
            resetDirectFireBurst();
            return;
        }

        pathBlockedCounter = 0;

        GunIntegration.aim(soldier, true);
        wasAiming = true;

        float adsProgress = GunIntegration.getAimProgress(soldier);
        boolean mobileFire = !vehicleCombat && movement.hasMovingIntent();
        float adsThreshold = mobileFire || soldier.getFireDiscipline() == FireDiscipline.SUPPRESSIVE
            ? MOBILE_FIRE_ADS_THRESHOLD : ADS_THRESHOLD;
        if (adsProgress < adsThreshold) {
            noteEngagementGate(EngagementGateReason.ADS_LOW);
            return;
        }
        
        // Frustration cools one step at a time while the soldier holds fire.
        if (dynamicFiringEnabled() && ineffectiveBurstStreak > 0 && lastShotTick != Integer.MIN_VALUE
                && soldier.tickCount - lastShotTick > StevesArmyConfig.getFiringMissStreakDecayTicks()) {
            ineffectiveBurstStreak--;
            lastShotTick = soldier.tickCount - StevesArmyConfig.getFiringMissStreakDecayTicks();
        }

        if (!vehicleCombat) applyCornerPreAimQuality();
        if (!aimQualityUpdated) updateAimQuality(vehicleCombat ? vehicleSolution : null);

        float targetAimQ = vehicleCombat ? StevesArmyConfig.getAimQualityBaseAccuracy()
            : AimAccuracyManager.getTargetAimQuality(soldier, target);
        float thresholdScale = lastShotNeededBolt || GunIntegration.isBolting(soldier)
            ? StevesArmyConfig.getAimQualitySlowGunThresholdScale()
            : StevesArmyConfig.getAimQualityThresholdScale();
        FireDiscipline discipline = soldier.getFireDiscipline();
        if (discipline == FireDiscipline.CONSERVE) {
            thresholdScale = Math.max(thresholdScale, 0.55f);
        } else if (discipline == FireDiscipline.SUPPRESSIVE) {
            thresholdScale = Math.min(thresholdScale, 0.20f);
        }
        if (mobileFire) {
            thresholdScale = Math.min(thresholdScale, 0.20f);
        }
        thresholdScale = applyDynamicThresholdModifiers(thresholdScale, discipline);
        if (machineGunnerPipeline) {
            // MG doctrine: open up early and walk fire onto the target, so the
            // burst-start gate is hard-capped (same relief SUPPRESSIVE gets).
            float mgCap = StevesArmyConfig.getMgAimQualityThresholdScale();
            if (mgCap > 0.0f && thresholdScale > mgCap) {
                thresholdScale = mgCap;
            }
        }
        float shotThreshold = Math.max(mobileFire ? MOBILE_FIRE_THRESHOLD_FLOOR : 0.15f,
            targetAimQ * thresholdScale);
        cornerDebugShotThreshold = shotThreshold;

        DirectFireWeaponProfile directProfile = getDirectFireWeaponProfile();
        int requestedBurstShotLimit;
        if (mobileFire) {
            requestedBurstShotLimit = Math.min(directBurstShotsFor(directProfile), MOBILE_FIRE_MAX_BURST_SHOTS);
        } else if (dynamicFiringEnabled() && directBurstShotsFor(directProfile) > 1) {
            requestedBurstShotLimit = FireControl.rollBurstShots(directBurstShotsFor(directProfile),
                burstBias(), soldier.getRandom());
        } else {
            requestedBurstShotLimit = directBurstShotsFor(directProfile);
        }
        if (directBurstCooldownTicks > 0) {
            directBurstCooldownTicks--;
            if (directBurstCooldownTicks == 0) directRecoveryImperfect = false;
            if (isDebugLogging() && (directBurstCooldownTicks == 0 || directBurstCooldownTicks % 5 == 0)) {
                StevesArmyMod.LOGGER.info("[DirectBurst] soldier={} recovery_remaining={}",
                    soldier.getId(), directBurstCooldownTicks);
            }
            noteEngagementGate(EngagementGateReason.BURST_COOLDOWN);
            return;
        }

        boolean hiddenCoverFire = isHiddenCoverFire();
        if (hiddenCoverFire && isDebugLogging()
            && soldier.tickCount - lastHiddenCoverFireDebugTick >= DEBUG_SYNC_INTERVAL) {
            lastHiddenCoverFireDebugTick = soldier.tickCount;
            StevesArmyMod.LOGGER.info(
                "[HiddenCoverFire] soldier={} peek={} target={} aim={} canShoot={}",
                soldier.getId(), soldier.getPeekController().getState(),
                vehicleCombat || target == null ? "vehicle" : target.getId(),
                aimPoint.type.displayName, aimPoint.canShoot());
        }

        float continuationThreshold = directBurstActive
            ? directBurstContinuationThreshold
            : getDirectBurstContinuationThreshold(shotThreshold);
        boolean imperfectAllowed = !vehicleCombat && !isSuppressedFireHoldActive();
        if (directBurstActive && directBurstImperfect && !imperfectAllowed) {
            finishDirectFireBurst("imperfect_hold");
            return;
        }
        boolean imperfectAttempt = directBurstActive && directBurstImperfect;
        if (directBurstActive && !imperfectAttempt && aimQuality < continuationThreshold) {
            noteEngagementGate(EngagementGateReason.AIM_QUALITY);
            finishDirectFireBurst("aim_quality");
            return;
        }

        if (!directBurstActive && aimQuality < shotThreshold) {
            // Patience counts only otherwise-fireable ticks, not time spent
            // reloading, turning, behind geometry, or covering a friendly.
            if (imperfectAllowed) {
                if (!isAimWaitLaneReady(aimPoint, mobileFire)) return;
                if (aimWaitLastTick != soldier.tickCount) {
                    aimWaitLastTick = soldier.tickCount;
                    aimWaitTicks = Math.min(60, aimWaitTicks + 1);
                }
                imperfectAttempt = aimWaitTicks >= imperfectFireWaitTicks();
                if (isFiringDebugLogging() && (imperfectAttempt || aimWaitTicks % 10 == 0)) {
                    StevesArmyMod.LOGGER.info(
                        "[AimPatience] soldier={} mode={} wait={}/{} quality={} threshold={}",
                        soldier.getId(), imperfectAttempt ? "imperfect" : "tracking", aimWaitTicks,
                        imperfectFireWaitTicks(), aimQuality, shotThreshold);
                }
            }
            if (!imperfectAttempt) {
                noteEngagementGate(EngagementGateReason.AIM_QUALITY);
                targetReevaluateCounter++;
                int reevaluateInterval = StevesArmyConfig.getTargetReevaluateInterval();
                if (soldier.isInCloseCombatContext()) {
                    // Close quarters change faster than open-field fights.
                    reevaluateInterval = Math.min(reevaluateInterval,
                        StevesArmyConfig.getCloseCombatReevalIntervalTicks());
                }
                if (dynamicFiringEnabled() && ineffectiveBurstStreak >= 2) {
                    // Repeatedly ineffective against this target: look for a better
                    // answer twice as often instead of hammering the same shot.
                    reevaluateInterval = Math.max(5, reevaluateInterval / 2);
                }
                if (!vehicleCombat && targetReevaluateCounter >= reevaluateInterval) {
                    targetReevaluateCounter = 0;
                    Optional<LivingEntity> betterTarget = findBetterTarget(aimQuality);
                    if (betterTarget.isPresent()) {
                        this.target = betterTarget.get();
                        soldier.setTarget(target);
                        threatTracker.reportThreatDirect(target);
                        detectionSystem.forceDetect(target);
                        resetAim(target);
                        if (isDamageDebugLogging()) {
                            StevesArmyMod.LOGGER.info("Switched to better target: {} (higher hit probability)",
                                target.getName().getString());
                        }
                        return;
                    }
                }
                return;
            }
            requestedBurstShotLimit = Math.min(2, requestedBurstShotLimit);
        }

        // Cached perception chooses the aim point; the shot still validates the
        // exact current firing lane immediately before firing.
        if (vehicleCombat) {
            Vec3 refreshedSolution = ArmorThreatScanner.findFiringSolution(soldier, vehicleTarget);
            if (refreshedSolution == null) {
                noteEngagementGate(EngagementGateReason.PREPARE_FAILED);
                resetDirectFireBurst();
                return;
            }
            aimPoint = new ExposureCalculator.AimPointResult(refreshedSolution,
                ExposureCalculator.AimPointType.FALLBACK, true, true, 0.0);
        } else {
            if (!VisibilityRay.traceFresh(soldier.level(), soldier.getEyePosition(), aimPoint.position, soldier)
                .hasContact()) {
                noteEngagementGate(EngagementGateReason.BULLET_PATH_BLOCKED);
                resetDirectFireBurst();
                return;
            }
        }
        
        GunIntegration.ShootResult result;
        
        // Always fire through the aim point with angular deviation.
        // The accuracy model is angular dispersion, not a hit/miss roll.
        float[] spread = directFireSpread(aimPoint);
        float yawSigma = spread[0];
        float pitchSigma = spread[1];
        float[] deviation = AimAccuracyManager.sampleGaussianDeviation(aimQuality, yawSigma, pitchSigma, soldier.level());
        float pitchDev = deviation[0];
        float yawDev = deviation[1];

        boolean safeLane = mobileFire
            ? FriendlyFireChecker.isSafeToShoot(soldier, aimPoint.position, aimQuality,
                yawSigma, pitchSigma, pitchDev, yawDev, true)
            : FriendlyFireChecker.isSafeToShoot(soldier, aimPoint.position, aimQuality);
        if (!safeLane) {
            if (isDamageDebugLogging()) {
                StevesArmyMod.LOGGER.info("[FriendlyFire] Soldier {} blocked shot - spread envelope overlaps friendly",
                    soldier.getId());
            }
            if (StevesArmyConfig.isExposedCombatEnabled()) {
                ExposedCombatController.noteShotBlocked(soldier, aimPoint.position);
            }
            noteEngagementGate(EngagementGateReason.FF_BLOCK);
            resetDirectFireBurst();
            return;
        }
        
        if (isDamageDebugLogging()) {
            StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] tickGunCombat: SHOOT target={}({}) aimPoint=({},{},{}) aimType={} aimQuality={} yawSigma={} pitchSigma={} yawDev={} pitchDev={}",
                vehicleCombat ? "vehicle" : target.getName().getString(),
                vehicleCombat ? "hull" : target.getId(), String.format("%.2f", aimPoint.position.x),
                String.format("%.2f", aimPoint.position.y),
                String.format("%.2f", aimPoint.position.z),
                aimPoint.type.displayName,
                String.format("%.3f", aimQuality),
                String.format("%.3f", yawSigma),
                String.format("%.3f", pitchSigma),
                String.format("%.3f", yawDev),
                String.format("%.3f", pitchDev));
        }
        
        result = GunIntegration.shootWithDeviation(soldier, aimPoint, pitchDev, yawDev);
        
        if (result == GunIntegration.ShootResult.SUCCESS) {
            int waitedTicks = aimWaitTicks;
            aimWaitTicks = 0;
            lastTacticalReloadShotTime = soldier.level().getGameTime();
            com.stevesarmy.squad.SquadMovementCoordinator.noteShot(soldier);
            noteEngagementGate(EngagementGateReason.NONE);
            soldier.getEngagementTelemetry().noteShot(soldier.tickCount);
            if (mobileFire) {
                recordMobileShot(SoldierMovementController.FireMode.ADS, aimQuality,
                    yawSigma, pitchSigma, pitchDev, yawDev, adsProgress, "direct-ads");
            }
            if (!directBurstActive) {
                beginDirectFireBurst(directProfile, requestedBurstShotLimit, mobileFire, continuationThreshold);
                directBurstImperfect = imperfectAttempt;
            }
            if (isFiringDebugLogging()) {
                StevesArmyMod.LOGGER.info(
                    "[AimPatience] soldier={} shotMode={} waited={} quality={} threshold={} burstLimit={}",
                    soldier.getId(), directBurstImperfect ? "imperfect" : "normal", waitedTicks,
                    aimQuality, shotThreshold, directBurstShotLimit);
            }
            directBurstShotsFired++;
            lastShotTick = soldier.tickCount;
            if (isDebugLogging()) {
                StevesArmyMod.LOGGER.info("[DirectBurst] soldier={} shot={}/{} profile={} mobile={}",
                    soldier.getId(), directBurstShotsFired, directBurstShotLimit,
                    directBurstProfile, mobileFire);
            }
            if (coverManager.isInCover()) {
                coverManager.onPeekShot();
            }

            if (hiddenCoverFire && isDebugLogging()) {
                StevesArmyMod.LOGGER.info(
                    "[HiddenCoverFire] soldier={} shot target={} aim={}",
                    // The shot pipeline can clear this.target (kill events,
                    // stale drops) between firing and this log; a debug line
                    // must never NPE the server tick.
                    soldier.getId(), vehicleCombat || target == null ? "vehicle" : target.getId(),
                    aimPoint.type.displayName);
            }

            if (GunIntegration.isAnyGunLoaded() && GunIntegration.hasGun(soldier)) {
                float[] recoil = AimAccuracyManager.getGunRecoil(soldier);
                float recoilMagnitude = Math.abs(recoil[0]) + Math.abs(recoil[1]);
                float recoilLoss = recoilMagnitude * StevesArmyConfig.getAimQualityRecoilScale()
                    * stanceRecoilLossMultiplier();
                aimQuality = Math.max(0.0f, aimQuality - recoilLoss);
                if (isDamageDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[Recoil] pitch={}, yaw={}, magnitude={}, recoilLoss={}, aimQuality={}",
                        String.format("%.3f", recoil[0]), String.format("%.3f", recoil[1]),
                        String.format("%.3f", recoilMagnitude), String.format("%.3f", recoilLoss),
                        String.format("%.3f", aimQuality));
                }
            }

            if (directBurstShotsFired >= directBurstShotLimit) {
                finishDirectFireBurst("completed");
            }
        }
        
        if (isDamageDebugLogging()) {
            StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] tickGunCombat: ShootResult={}", result);
        }
        switch (result) {
            case SUCCESS -> lastShotNeededBolt = false;
            case NEED_BOLT -> {
                GunIntegration.bolt(soldier);
                noteEngagementGate(EngagementGateReason.BOLTING);
                lastShotNeededBolt = true;
                resetDirectFireBurst();
            }
            case NO_AMMO -> {
                noteEngagementGate(EngagementGateReason.RELOADING);
                resetDirectFireBurst();
                requestReload(false);
            }
            case COOLDOWN -> noteEngagementGate(EngagementGateReason.BURST_COOLDOWN);
            case IS_BOLTING -> noteEngagementGate(EngagementGateReason.BOLTING);
            case IS_RELOADING -> noteEngagementGate(EngagementGateReason.RELOADING);
            case IS_DRAWING -> noteEngagementGate(EngagementGateReason.DRAWING);
            case NOT_DRAWN -> {
                noteEngagementGate(EngagementGateReason.DRAWING);
                GunIntegration.draw(soldier);
            }
            case PATH_BLOCKED -> {
                noteEngagementGate(EngagementGateReason.BULLET_PATH_BLOCKED);
                resetDirectFireBurst();
                pathBlockedCounter++;
                if (pathBlockedCounter >= PATH_BLOCKED_SWITCH_TICKS) {
                    StevesArmyMod.LOGGER.info("PATH_BLOCKED result, switching target");
                    pathBlockedCounter = 0;
                    if (findNewTarget()) {
                        resetAim(target);
                    }
                }
            }
            default -> {}
        }
    }
    
    private void tickCoverPeekCycle(CoverBehaviorManager coverManager) {
        PeekController peekCtrl = soldier.getPeekController();
        PeekController.State peekState = peekCtrl.getState();
        
        if (target != null && target.isAlive()) {
            soldier.setTarget(target);
        }
        
        if (peekState == PeekController.State.EXPOSED 
            || peekState == PeekController.State.MOVING_TO_PEEK
            || peekState == PeekController.State.RETURNING_TO_COVER) {
            lookTowardThreat();
        }
    }

    private boolean isHiddenCoverFire() {
        return soldier.getCoverBehaviorManager().isInCover()
            && soldier.getPeekController().getState() == PeekController.State.HIDING;
    }
    
    @javax.annotation.Nullable
    private BlockPos getCoverBlockPos() {
        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        if (coverManager.isInCover()) {
            CoverPoint cover = coverManager.getCurrentCover();
            if (cover != null) {
                return cover.getPosition();
            }
        }
        return null;
    }

    private void lookTowardThreat() {
        if (target != null && target.isAlive()) {
            soldier.getLookControl().setLookAt(target, 30.0F, 30.0F);
        } else {
            BlockPos threatPos = soldier.getThreatAwareness().getPrimaryThreatPosition();
            if (threatPos != null) {
                soldier.getLookControl().setLookAt(
                    threatPos.getX() + 0.5,
                    soldier.getEyeY(),
                    threatPos.getZ() + 0.5,
                    30.0F, 30.0F
                );
            }
        }
    }
    
    private Optional<LivingEntity> findBetterTarget(float currentAimQuality) {
        List<LivingEntity> potentialTargets = getPotentialTargets();
        
        List<LivingEntity> detectedTargets = potentialTargets.stream()
            .filter(detectionSystem::isTargetDetected)
            .filter(e -> TargetAcquisition.hasLineOfSight(soldier, e))
            .filter(e -> !e.getUUID().equals(target.getUUID()))
            .collect(Collectors.toList());
        
        if (detectedTargets.isEmpty()) {
            return Optional.empty();
        }
        
        float improvementThreshold = StevesArmyConfig.getTargetSwitchImprovement();
        
        Optional<LivingEntity> betterTarget = detectedTargets.stream()
            .map(e -> new TargetScore(e, AimAccuracyManager.calculateHitProbability(soldier, e)))
            .filter(ts -> ts.hitProbability > currentAimQuality + improvementThreshold)
            .max(Comparator.comparingDouble(ts -> ts.hitProbability))
            .map(ts -> ts.target);
        
        return betterTarget;
    }
    
    private static class TargetScore {
        final LivingEntity target;
        final float hitProbability;
        
        TargetScore(LivingEntity target, float hitProbability) {
            this.target = target;
            this.hitProbability = hitProbability;
        }
    }
    
    private void resetAimPatience() {
        aimWaitTicks = 0;
        aimWaitLastTick = Integer.MIN_VALUE;
        aimPatienceTargetUUID = null;
        aimNoLosTicks = 0;
    }

    /** Runs even when hidden contact never reaches the direct-shot pipeline. */
    private void updateAimPatienceContact() {
        if (aimContactLastTick == soldier.tickCount) return;
        aimContactLastTick = soldier.tickCount;
        if (target == null || !target.isAlive()) {
            resetAimPatience();
            return;
        }
        if (!target.getUUID().equals(aimPatienceTargetUUID)) {
            resetAimPatience();
            aimPatienceTargetUUID = target.getUUID();
        }
        if (TargetAcquisition.hasLineOfSight(soldier, target)) {
            aimNoLosTicks = 0;
        } else if (++aimNoLosTicks > 5) {
            aimWaitTicks = 0;
            if (directBurstImperfect) finishDirectFireBurst("imperfect_lost_contact");
        }
    }

    private int imperfectFireWaitTicks() {
        if (soldier.getFireDiscipline() == FireDiscipline.CONSERVE || lastShotNeededBolt
            || GunIntegration.isManualBolt(soldier)) return 60;
        return soldier.getFireDiscipline() == FireDiscipline.SUPPRESSIVE ? 20 : 40;
    }

    private boolean isSuppressedFireHoldActive() {
        return dynamicFiringEnabled() && soldier.getFireDiscipline() == FireDiscipline.STANDARD
            && StevesArmyConfig.getSuppressedFireMode() == StevesArmyConfig.SuppressedFireMode.HOLD
            && StevesArmyConfig.getSuppressionThresholdTighten() > 0.0f
            && effectiveSuppressionLevel() > 0.0f;
    }

    private boolean isAimWaitLaneReady(ExposureCalculator.AimPointResult aimPoint, boolean mobileFire) {
        if (GunIntegration.getShootCoolDown(soldier) > 0) {
            noteEngagementGate(EngagementGateReason.BURST_COOLDOWN);
            return false;
        }
        if (!GunIntegration.hasAmmoInBarrel(soldier) && GunIntegration.getCurrentAmmo(soldier) <= 0) {
            noteEngagementGate(EngagementGateReason.RELOADING);
            requestReload(false);
            return false;
        }
        if (GunIntegration.isManualBolt(soldier) && !GunIntegration.hasAmmoInBarrel(soldier)) {
            GunIntegration.bolt(soldier);
            lastShotNeededBolt = true;
            noteEngagementGate(EngagementGateReason.BOLTING);
            return false;
        }
        BlockPos coverBlock = getCoverBlockPos();
        if (!VisibilityRay.traceFresh(soldier.level(), soldier.getEyePosition(), aimPoint.position, soldier,
            coverBlock, coverBlock == null ? null : coverBlock.above()).hasContact()) {
            noteEngagementGate(EngagementGateReason.BULLET_PATH_BLOCKED);
            return false;
        }
        float[] sigma = directFireSpread(aimPoint);
        boolean safe = mobileFire
            ? FriendlyFireChecker.isSafeToShoot(soldier, aimPoint.position, aimQuality,
                sigma[0], sigma[1], 0.0f, 0.0f, true)
            : FriendlyFireChecker.isSafeToShoot(soldier, aimPoint.position, aimQuality);
        if (!safe) {
            noteEngagementGate(EngagementGateReason.FF_BLOCK);
            return false;
        }
        return true;
    }

    /** Same spread for patience eligibility and the actual sampled shot. */
    private float[] directFireSpread(ExposureCalculator.AimPointResult aimPoint) {
        float yawSigma = AimAccuracyManager.getYawSigma(aimQuality);
        float pitchSigma = AimAccuracyManager.getPitchSigma(aimQuality);
        float suppression = effectiveSuppressionLevel();
        if (suppression > 0.0f) {
            float shakeShare = soldier.getFireDiscipline() == FireDiscipline.SUPPRESSIVE
                ? StevesArmyConfig.getFiringDutySuppressionFactor() : 1.0f;
            float shake = 1.0f + suppression * StevesArmyConfig.getSuppressionSigmaScale() * shakeShare;
            yawSigma *= shake;
            pitchSigma *= shake;
        }
        yawSigma += (float) aimPoint.concealment * 2.00f;
        pitchSigma += (float) aimPoint.concealment * 0.75f;
        if (aimPoint.type == ExposureCalculator.AimPointType.HEAD
            && target != null && com.stevesarmy.compat.sbw.SbwVehicles.isExposedRider(target)) {
            // A small exposed head needs a deliberate shot, not a torso-sized burst.
            yawSigma = Math.min(yawSigma, 0.8f);
            pitchSigma = Math.min(pitchSigma, 0.8f);
        }
        return new float[]{yawSigma, pitchSigma};
    }

    /** Shared readiness transfer; neither this nor pre-ADS authorizes a shot. */
    public void applyCornerPreAimQuality() {
        if (target == null || !target.isAlive() || GunIntegration.isReloading(soldier)
            || GunIntegration.isDrawing(soldier) || GunIntegration.isBolting(soldier)
            || GunIntegration.getAimProgress(soldier) < ADS_THRESHOLD
            || !ExposureCalculator.hasFreshFiringLaneFrom(soldier.getEyePosition(), target)) return;
        Vec3 delta = target.getEyePosition().subtract(soldier.getEyePosition());
        float yaw = (float)Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float)-Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance()));
        if (Math.abs(Mth.wrapDegrees(yaw - soldier.getYHeadRot())) > 8
            || Math.abs(pitch - soldier.getXRot()) > 8) return;
        float credit = soldier.getPeekController().consumeCornerPreAimCredit(target);
        // At most 30% of equilibrium, earned from sixteen aligned ADS ticks.
        if (credit > 0) aimQuality = Math.max(aimQuality,
            Math.min(0.30f, AimAccuracyManager.getTargetAimQuality(soldier, target) * 0.30f) * credit);
    }

    private float cornerDebugShotThreshold;
    public float getCornerShotThresholdForDebug() { return cornerDebugShotThreshold; }
    public boolean isCornerTargetAcquiredForDebug() { return target != null && detectionSystem.isTargetDetected(target); }
    public float getAimQualityForDebug() { return aimQuality; }

    private void updateAimQuality(@javax.annotation.Nullable Vec3 staticVisibleTarget) {
        if (target == null && staticVisibleTarget == null) return;

        boolean staticTarget = staticVisibleTarget != null;
        boolean inLOS = staticTarget || TargetAcquisition.hasLineOfSight(soldier, target);

        if (inLOS) {
            boolean mobileFire = !staticTarget && soldier.getMovementController().hasMovingIntent();
            float targetAimQuality = staticTarget ? StevesArmyConfig.getAimQualityBaseAccuracy()
                : AimAccuracyManager.getTargetAimQuality(soldier, target);
            float buildRate = staticTarget ? StevesArmyConfig.getAimQualityBuildRate()
                : AimAccuracyManager.getBuildRate(soldier, target);
            if (machineGunnerPipeline) {
                // Belt-fed tracking fire: MGs hold their aim equilibrium at
                // range instead of bleeding it to the standard decay terms.
                buildRate *= StevesArmyConfig.getMgAimBuildRateScale();
            }
            if (dynamicFiringEnabled()) {
                buildRate *= buildBias();
            }
            if (soldier.isFiringProne()) {
                buildRate *= FIRING_PRONE_AIM_BUILD_MULTIPLIER;
            }

            if (soldier.getDeltaMovement().horizontalDistanceSqr() > 0.01) {
                aimQuality -= mobileFire ? MOBILE_FIRE_MOVEMENT_DECAY
                    : StevesArmyConfig.getAimQualityMoveDecayRate();
            }

            double targetSpeed = staticTarget ? 0.0 : target.getDeltaMovement().horizontalDistanceSqr();
            if (targetSpeed > 0.01) {
                float targetMovePenalty = StevesArmyConfig.getAimQualityTargetMovePenalty();
                if (machineGunnerPipeline) {
                    targetMovePenalty *= StevesArmyConfig.getMgTargetMovePenaltyScale();
                }
                // Tracking difficulty must not overwhelm acquisition at low
                // build rates and create an unreachable firing threshold.
                aimQuality -= buildRate * (mobileFire ? MOBILE_FIRE_TARGET_MOVEMENT_DECAY : targetMovePenalty);
            }

            if (aimQuality < targetAimQuality) {
                aimQuality = Mth.lerp(buildRate, aimQuality, targetAimQuality);
            } else if (aimQuality > targetAimQuality) {
                aimQuality = Mth.lerp(buildRate * 0.5f, aimQuality, targetAimQuality);
            }
        } else {
            aimQuality -= StevesArmyConfig.getAimQualityLosDecayRate();
        }

        aimQuality = Mth.clamp(aimQuality, 0.0f, 1.0f);
    }

    private boolean prepareForMovingFire(Vec3 aimPoint) {
        SoldierMovementController movement = soldier.getMovementController();
        if (!movement.hasMovingIntent() || movement.fireMode() != SoldierMovementController.FireMode.ADS
            || !movement.canFireAt(aimPoint)) {
            return false;
        }
        return turnHeadToward(getYawTo(aimPoint), getPitchTo(aimPoint)) <= FIRING_ALIGNMENT_DEGREES;
    }

    private void tickTraverseHipFire(ExposureCalculator.AimPointResult aimPoint) {
        GunIntegration.aim(soldier, false);
        wasAiming = false;
        float hipQuality = Math.min(aimQuality, 0.20f);
        if (soldier.getFireDiscipline() == FireDiscipline.CONSERVE || hipQuality < 0.08f) {
            noteEngagementGate(EngagementGateReason.AIM_QUALITY);
            return;
        }
        if (GunIntegration.getAimProgress(soldier) > 0.10f) {
            noteEngagementGate(EngagementGateReason.ADS_LOW);
            return;
        }

        if (soldier.tickCount < mobileHipNextBurstTick) {
            noteEngagementGate(EngagementGateReason.BURST_COOLDOWN);
            return;
        }
        if (GunIntegration.isReloading(soldier) || GunIntegration.isDrawing(soldier)
            || GunIntegration.isBolting(soldier)) {
            noteEngagementGate(GunIntegration.isReloading(soldier) ? EngagementGateReason.RELOADING
                : GunIntegration.isDrawing(soldier) ? EngagementGateReason.DRAWING : EngagementGateReason.BOLTING);
            return;
        }

        float yawSigma = Math.max(6.0f, AimAccuracyManager.getYawSigma(hipQuality));
        float pitchSigma = Math.max(3.0f, AimAccuracyManager.getPitchSigma(hipQuality));
        float[] deviation = sampleMobileHipDeviation(yawSigma, pitchSigma);
        if (!FriendlyFireChecker.isSafeToShoot(soldier, aimPoint.position, hipQuality,
            yawSigma, pitchSigma, deviation[0], deviation[1], false)) {
            noteEngagementGate(EngagementGateReason.FF_BLOCK);
            return;
        }
        GunIntegration.ShootResult result = GunIntegration.shootWithDeviation(
            soldier, aimPoint, deviation[0], deviation[1]);
        if (result == GunIntegration.ShootResult.SUCCESS) {
            aimWaitTicks = 0;
            lastTacticalReloadShotTime = soldier.level().getGameTime();
            com.stevesarmy.squad.SquadMovementCoordinator.noteShot(soldier);
            soldier.getEngagementTelemetry().noteShot(soldier.tickCount);
            noteEngagementGate(EngagementGateReason.NONE);
            recordMobileShot(SoldierMovementController.FireMode.HIP, hipQuality,
                yawSigma, pitchSigma, deviation[0], deviation[1],
                GunIntegration.getAimProgress(soldier), "direct-hip");
            if (mobileHipShotsInBurst == 0) mobileHipBurstLimit = 1 + mobileHipRandom.nextInt(2);
            mobileHipShotsInBurst++;
            lastShotTick = soldier.tickCount;
            float[] recoil = AimAccuracyManager.getGunRecoil(soldier);
            aimQuality = Math.max(0.0f, aimQuality - (Math.abs(recoil[0]) + Math.abs(recoil[1]))
                * StevesArmyConfig.getAimQualityRecoilScale() * stanceRecoilLossMultiplier());
            if (mobileHipShotsInBurst >= mobileHipBurstLimit) {
                mobileHipShotsInBurst = 0;
                mobileHipNextBurstTick = soldier.tickCount + 20 + mobileHipRandom.nextInt(21);
            }
        } else if (result == GunIntegration.ShootResult.NO_AMMO) {
            requestReload(false);
        } else if (result == GunIntegration.ShootResult.NEED_BOLT) {
            GunIntegration.bolt(soldier);
            lastShotNeededBolt = true;
            noteEngagementGate(EngagementGateReason.BOLTING);
        } else if (result == GunIntegration.ShootResult.COOLDOWN) {
            noteEngagementGate(EngagementGateReason.BURST_COOLDOWN);
        } else if (result == GunIntegration.ShootResult.IS_RELOADING) {
            noteEngagementGate(EngagementGateReason.RELOADING);
        } else if (result == GunIntegration.ShootResult.IS_BOLTING) {
            noteEngagementGate(EngagementGateReason.BOLTING);
        } else if (result == GunIntegration.ShootResult.IS_DRAWING || result == GunIntegration.ShootResult.NOT_DRAWN) {
            if (result == GunIntegration.ShootResult.NOT_DRAWN) GunIntegration.draw(soldier);
            noteEngagementGate(EngagementGateReason.DRAWING);
        } else if (result != GunIntegration.ShootResult.SUCCESS) {
            noteEngagementGate(EngagementGateReason.PREPARE_FAILED);
        }
    }

    private float[] sampleMobileHipDeviation(float yawSigma, float pitchSigma) {
        double u1 = Math.max(1.0e-15, mobileHipRandom.nextDouble());
        double u2 = mobileHipRandom.nextDouble();
        double radius = Math.sqrt(-2.0 * Math.log(u1));
        double angle = 2.0 * Math.PI * u2;
        double yawNormal = Mth.clamp(radius * Math.cos(angle), -2.5, 2.5);
        double pitchNormal = Mth.clamp(radius * Math.sin(angle), -2.5, 2.5);
        return new float[]{(float) (pitchNormal * pitchSigma), (float) (yawNormal * yawSigma)};
    }

    private void recordMobileShot(SoldierMovementController.FireMode fireMode, float effectiveQuality,
                                  float yawSigma, float pitchSigma, float sampledPitchDeviation,
                                  float sampledYawDeviation, float adsProgress, String source) {
        if (!SoldierFlightRecorder.isRecording()) return;
        SoldierFlightRecorder.event(soldier, "mobile-fire", source, java.util.Map.of(
            "fireMode", fireMode.name(),
            "effectiveQuality", effectiveQuality,
            "yawSigma", yawSigma,
            "pitchSigma", pitchSigma,
            "sampledPitchDeviation", sampledPitchDeviation,
            "sampledYawDeviation", sampledYawDeviation,
            "adsProgress", adsProgress));
    }

    private void tickScanning(List<LivingEntity> potentialTargets) {
        if (wasAiming) {
            if (GunIntegration.isAnyGunLoaded() && GunIntegration.hasGun(soldier)) {
                GunIntegration.aim(soldier, false);
            }
            wasAiming = false;
        }
        
        Optional<LivingEntity> suspectedTarget = detectionSystem.getHighestProgressTarget(potentialTargets);
        
        if (suspectedTarget.isPresent()) {
            soldier.getLookControl().setLookAt(suspectedTarget.get(), 30.0F, 30.0F);
            return;
        }
        
        if (soldier.hasValidForcedTarget()) {
            BlockPos forcedPos = soldier.getForcedTargetPos();
            soldier.getLookControl().setLookAt(
                forcedPos.getX() + 0.5, forcedPos.getY() + 0.5, forcedPos.getZ() + 0.5, 
                30.0F, 30.0F
            );
            return;
        }
        
        if (soldier.hasValidPingThreatPos()) {
            BlockPos threatPos = soldier.getPingThreatPos();
            soldier.getLookControl().setLookAt(
                threatPos.getX() + 0.5, threatPos.getY() + 0.5, threatPos.getZ() + 0.5,
                30.0F, 30.0F
            );
            return;
        }
        
        // Corner pie-slicing: approaching a bend with the threat known to be
        // near the remaining path - pre-aim the arc the corner opens into.
        Vec3 cornerWatch = CqbSteeringAdvisor.getCornerWatchPos(soldier);
        if (cornerWatch != null) {
            soldier.getLookControl().setLookAt(cornerWatch.x, cornerWatch.y, cornerWatch.z, 30.0F, 30.0F);
            return;
        }

        // Lost-contact watch (phase-4 CQB doctrine): close-quarters contact
        // just broke - hold position and keep the aim on the last-known spot
        // for the watch window instead of drifting into routine scanning.
        Optional<BlockPos> lastKnownPos = threatTracker.getLastKnownPosition();
        if (lastKnownPos.isPresent()) {
            BlockPos pos = lastKnownPos.get();
            soldier.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 30.0F, 30.0F);
            if (shouldStandLostContactWatch()) {
                soldier.getNavigation().stop();
            }
            return;
        }
        
        Optional<LivingEntity> nearestTarget = potentialTargets.stream()
            .min(Comparator.comparingDouble(e -> e.distanceToSqr(soldier)));
        
        if (nearestTarget.isPresent()) {
            soldier.getLookControl().setLookAt(nearestTarget.get(), 30.0F, 30.0F);
        }
    }

    /**
     * True during the lost-contact watch window: close-quarters contact broke
     * recently (the freshest threat memory is younger than the watch window)
     * and the soldier is in the close-combat context. Stand and watch the
     * last-known position; movement decisions owned by other doctrines
     * (cover seek, repositioning, exposed-combat moves) are respected.
     */
    private boolean shouldStandLostContactWatch() {
        if (!StevesArmyConfig.isExposedCombatEnabled()
            || !StevesArmyConfig.isCqbCornerWatchEnabled()
            || !soldier.isInCloseCombatContext()) {
            return false;
        }
        ThreatTracker.ThreatInfo freshest = threatTracker.getFreshestThreatInfo();
        if (freshest == null) {
            return false;
        }
        long age = soldier.level().getGameTime() - freshest.lastSeenTime;
        if (age > StevesArmyConfig.getCqbLostContactWatchTicks()) {
            return false;
        }
        CoverBehaviorManager.CoverState coverState = soldier.getCoverBehaviorManager().getState();
        if (coverState == CoverBehaviorManager.CoverState.SEEKING_COVER
            || coverState == CoverBehaviorManager.CoverState.REPOSITIONING) {
            return false;
        }
        if (ExposedCombatController.hasActiveMove(soldier)
            || ExposedCombatController.plantHoldActive(soldier)) {
            return false;
        }
        // Docked at cover already - the aim keeps watching the last-known spot
        // (the scanning chain above), only the open-ground stand still applies.
        return soldier.getCoverBehaviorManager().getCurrentCover() == null;
    }

    public List<LivingEntity> getPotentialTargets() {
        long currentTick = soldier.tickCount;
        
        int cacheTicks = StevesArmyConfig.getTargetCandidateCacheTicks();
        if (cachedPotentialTargets != null && currentTick == cachedPotentialTargetsTick) {
            PerformanceMetrics.recordSameTickPotentialTargetCacheHit();
            return cachedPotentialTargets;
        }
        if (cachedPotentialTargets != null && cacheTicks > 0
            && (currentTick - cachedPotentialTargetsTick < cacheTicks)) {
            return cachedPotentialTargets;
        }
        
        cachedPotentialTargets = computePotentialTargets();
        cachedPotentialTargetsTick = currentTick;
        PerformanceMetrics.recordTargetRefresh(cachedPotentialTargets.size());
        return cachedPotentialTargets;
    }

    private boolean shouldRefreshDetection() {
        return true;
    }
    
    private List<LivingEntity> computePotentialTargets() {
        long queryStart = System.nanoTime();
        List<LivingEntity> potentialTargets = new ArrayList<>();

        double maxRange = Math.max(detectionSystem.getFocusedRange(), DetectionSystem.PERIPHERAL_RANGE);
        List<LivingEntity> nearby = StevesArmyConfig.useSharedTargetQueryCache()
            ? CombatTargetQueryCache.getNearbyLivingEntities(
                soldier.level(), soldier.position(), maxRange,
                StevesArmyConfig.getTargetCandidateCacheTicks())
            : soldier.level().getEntitiesOfClass(
                LivingEntity.class, soldier.getBoundingBox().inflate(maxRange));

        List<LivingEntity> monsters = new ArrayList<>();
        List<LivingEntity> targetEntities = new ArrayList<>();
        List<LivingEntity> players = new ArrayList<>();
        List<LivingEntity> soldiers = new ArrayList<>();

        for (LivingEntity entity : nearby) {
            if (entity == soldier) continue;
            if (entity.getBoundingBox().intersects(soldier.getBoundingBox().inflate(maxRange))
                && TargetAcquisition.isValidTarget(soldier, entity)
                && !soldier.isFriendlyTo(entity)
                && soldier.isProactiveTarget(entity)) {
                if (entity instanceof SoldierEntity otherSoldier && otherSoldier != soldier) {
                    soldiers.add(entity);
                } else if (entity instanceof TargetEntity) {
                    targetEntities.add(entity);
                } else if (entity instanceof Player) {
                    players.add(entity);
                } else if (entity instanceof Monster) {
                    monsters.add(entity);
                }
            }
        }

        // Preserve original category ordering. An explicit FoF category stance
        // replaces the config toggle; no stance keeps the config default.
        if (soldier.fofCategoryAllows(FofCategory.MONSTERS, StevesArmyConfig.shouldTargetMonsters())) {
            potentialTargets.addAll(monsters);
        }
        if (soldier.fofCategoryAllows(FofCategory.TARGET_DUMMIES, StevesArmyConfig.shouldTargetTargetEntities())) {
            potentialTargets.addAll(targetEntities);
        }
        potentialTargets.addAll(players);
        potentialTargets.addAll(soldiers);

        PerformanceMetrics.recordStageTime(PerformanceMetrics.Stage.ENTITY_QUERY,
            System.nanoTime() - queryStart);
        return potentialTargets;
    }

    private boolean hasPotentialTargets() {
        return !getPotentialTargets().isEmpty();
    }
    
    public boolean hasDetectedTargets() {
        List<LivingEntity> potentialTargets = getPotentialTargets();
        return potentialTargets.stream().anyMatch(detectionSystem::isTargetDetected);
    }

    private void onTargetAcquiredDuringPeek() {
        PeekController peekCtrl = soldier.getPeekController();
        if (peekCtrl.isMovingToPeek()) {
            if (peekCtrl.getState() == PeekController.State.HIDING) {
                if (isDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[CombatGoal] Soldier {} acquired target during peek", soldier.getId());
                }
            }
        }
    }

    private Optional<LivingEntity> findBestVisibleTarget(List<LivingEntity> potentialTargets) {
        return potentialTargets.stream()
            .filter(e -> TargetAcquisition.hasLineOfSight(soldier, e))
            .map(e -> new TargetScore(e, AimAccuracyManager.calculateHitProbability(soldier, e)))
            .max(Comparator.comparingDouble(ts -> ts.hitProbability))
            .map(ts -> ts.target);
    }

    private void preemptSuppressionForVisibleTarget() {
        Optional<LivingEntity> visibleTarget = findBestVisibleTarget(computePotentialTargets());
        if (visibleTarget.isEmpty()) {
            // The detection pool is bounded by focused/peripheral range while a
            // suppression claim can live out to SUPPRESSION_MAX_RANGE — in that
            // band the pool never contains the assigned threat, so resolve him
            // directly by UUID and check sight himself.
            visibleTarget = findAssignedThreatDirect();
        }
        if (visibleTarget.isEmpty()) {
            return;
        }

        UUID releasedThreatId = suppressionTargetUUID;
        LivingEntity acquiredTarget = visibleTarget.get();
        this.target = acquiredTarget;
        soldier.setTarget(acquiredTarget);

        ThreatAwareness threats = soldier.getThreatAwareness();
        threats.onEntityDetected(acquiredTarget, soldier.position());
        threatTracker.reportThreatDirect(acquiredTarget);
        detectionSystem.forceDetect(acquiredTarget);

        cancelAllSuppression();

        if (isSuppressionDebugLogging()) {
            String releasedId = releasedThreatId == null
                ? "none" : releasedThreatId.toString().substring(0, 8);
            StevesArmyMod.LOGGER.info("[Suppression] Soldier {} preempted last-seen threat {} for visible target {}",
                soldier.getId(), releasedId, acquiredTarget.getName().getString());
        }
    }

    /**
     * Tier-1 check for the assigned suppression threat regardless of the
     * detection pool radius: if the suppressor himself has clean sight of the
     * live enemy, last-seen suppression must yield to direct fire. Strict
     * foliage semantics apply — a lane the bullets could not take must not
     * pull the soldier out of suppression.
     */
    private Optional<LivingEntity> findAssignedThreatDirect() {
        if (!(soldier.level() instanceof ServerLevel serverLevel) || suppressionTargetUUID == null) {
            return Optional.empty();
        }
        net.minecraft.world.entity.Entity threatEntity = serverLevel.getEntity(suppressionTargetUUID);
        if (!(threatEntity instanceof LivingEntity living) || !living.isAlive()
            || !TargetAcquisition.isValidTarget(soldier, living)
            || soldier.isFriendlyTo(living)
            || soldier.distanceToSqr(living) > SUPPRESSION_MAX_RANGE * SUPPRESSION_MAX_RANGE) {
            return Optional.empty();
        }
        return TargetAcquisition.hasLineOfSight(soldier, living) ? Optional.of(living) : Optional.empty();
    }

    private boolean findNewTarget() {
        boolean found = findNewTargetInternal();
        if (found) {
            onTargetAcquiredDuringPeek();
        }
        return found;
    }

    private boolean findNewTargetInternal() {
        List<LivingEntity> potentialTargets = getPotentialTargets();
        ThreatAwareness threats = soldier.getThreatAwareness();

        if (isDebugLogging()) {
            findNewTargetLogCounter++;
            if (findNewTargetLogCounter >= 20) {
                findNewTargetLogCounter = 0;
                StevesArmyMod.LOGGER.info("[CombatGoal] findNewTarget: {} potential targets, inCover={}", 
                    potentialTargets.size(), soldier.getCoverBehaviorManager().isInCover());
            }
        }
        
        if (soldier.hasValidForcedTarget()) {
            BlockPos forcedPos = soldier.getForcedTargetPos();
            double radius = 20.0;
            
            Optional<LivingEntity> forcedEntity = potentialTargets.stream()
                .filter(e -> TargetAcquisition.hasLineOfSight(soldier, e))
                .filter(e -> e.distanceToSqr(forcedPos.getX(), forcedPos.getY(), forcedPos.getZ()) < radius * radius)
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(forcedPos.getX(), forcedPos.getY(), forcedPos.getZ())));
            
            if (forcedEntity.isPresent()) {
                this.target = forcedEntity.get();
                soldier.setTarget(target);
                threats.onEntityDetected(target, soldier.position());
                threatTracker.reportThreatDirect(target);
                detectionSystem.forceDetect(target);
                soldier.clearForcedTarget();
                soldier.clearPingThreatPos();
                if (isDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[CombatGoal] Acquired forced target: {}", target.getName().getString());
                }
                return true;
            }
        }
        
        if (soldier.hasValidPingThreatPos()) {
            BlockPos threatPos = soldier.getPingThreatPos();
            double threatRadius = 20.0;
            
            Optional<LivingEntity> pingTarget = potentialTargets.stream()
                .filter(e -> TargetAcquisition.hasLineOfSight(soldier, e))
                .filter(e -> e.distanceToSqr(threatPos.getX(), threatPos.getY(), threatPos.getZ()) < threatRadius * threatRadius)
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(threatPos.getX(), threatPos.getY(), threatPos.getZ())));
            
            if (pingTarget.isPresent()) {
                this.target = pingTarget.get();
                soldier.setTarget(target);
                threats.onEntityDetected(target, soldier.position());
                threatTracker.reportThreatDirect(target);
                detectionSystem.forceDetect(target);
                soldier.clearPingThreatPos();
                if (isDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[CombatGoal] Acquired ping target: {}", target.getName().getString());
                }
                return true;
            }
        }
        
        boolean inCover = soldier.getCoverBehaviorManager().isInCover();
        
        List<LivingEntity> losTargets = potentialTargets.stream()
            .filter(e -> TargetAcquisition.hasLineOfSight(soldier, e))
            .collect(Collectors.toList());
        
        if (!losTargets.isEmpty()) {
            Optional<LivingEntity> best = findBestVisibleTarget(losTargets);
            
            if (best.isPresent()) {
                this.target = best.get();
                soldier.setTarget(target);
                threats.onEntityDetected(target, soldier.position());
                threatTracker.reportThreatDirect(target);
                detectionSystem.forceDetect(target);
                if (isDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[CombatGoal] Acquired LOS target: {}", target.getName().getString());
                }
                return true;
            }
            
            this.target = losTargets.get(0);
            soldier.setTarget(target);
            threats.onEntityDetected(target, soldier.position());
            threatTracker.reportThreatDirect(target);
            if (isDebugLogging()) {
                StevesArmyMod.LOGGER.info("[CombatGoal] Acquired nearest LOS target: {}", target.getName().getString());
            }
            return true;
        }
        
        // Hidden-fallback acquisition only when no vehicle engagement is
        // latched: during one, invisible crew re-acquired here would fight the
        // hull engagement for the aim/posture state every few ticks.
        if (!potentialTargets.isEmpty() && vehicleEngagementTicks <= 0) {
            Vec3 primaryDir = threats.getPrimaryDirection(soldier.position());
            if (primaryDir != null && primaryDir.lengthSqr() > 0.001) {
                Optional<LivingEntity> threatDirTarget = potentialTargets.stream()
                    .min(Comparator.comparingDouble(e -> {
                        Vec3 toTarget = e.position().subtract(soldier.position()).normalize();
                        return toTarget.distanceToSqr(primaryDir);
                    }));
                
                if (threatDirTarget.isPresent()) {
                    this.target = threatDirTarget.get();
                    soldier.setTarget(target);
                    threats.onEntityDetected(target, soldier.position(), false);
                    threatTracker.reportThreatDirect(target);
                    if (isDebugLogging()) {
                        StevesArmyMod.LOGGER.info("[CombatGoal] Acquired threat-direction target (cover fallback): {}", 
                            target.getName().getString());
                    }
                    return true;
                }
            }
            
            Optional<BlockPos> lastKnown = threatTracker.getLastKnownPosition();
            if (lastKnown.isPresent()) {
                BlockPos lk = lastKnown.get();
                Optional<LivingEntity> nearLastKnown = potentialTargets.stream()
                    .filter(e -> e.distanceToSqr(lk.getX(), lk.getY(), lk.getZ()) < 400.0)
                    .min(Comparator.comparingDouble(e -> e.distanceToSqr(lk.getX(), lk.getY(), lk.getZ())));
                
                if (nearLastKnown.isPresent()) {
                    this.target = nearLastKnown.get();
                    soldier.setTarget(target);
                    threats.onEntityDetected(target, soldier.position(), false);
                    threatTracker.reportThreatDirect(target);
                    if (isDebugLogging()) {
                        StevesArmyMod.LOGGER.info("[CombatGoal] Acquired target near last known position (cover fallback): {}", 
                            target.getName().getString());
                    }
                    return true;
                }
            }
            
            Optional<LivingEntity> closest = potentialTargets.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(soldier)));
            
            if (closest.isPresent()) {
                this.target = closest.get();
                soldier.setTarget(target);
                threats.onEntityDetected(target, soldier.position(), false);
                threatTracker.reportThreatDirect(target);
                if (isDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[CombatGoal] Acquired closest target (cover fallback): {}", 
                        target.getName().getString());
                }
                return true;
            }
        }
        
        if (isDebugLogging()) {
            findNewTargetLogCounter++;
            if (findNewTargetLogCounter >= 20) {
                findNewTargetLogCounter = 0;
                StevesArmyMod.LOGGER.info("[CombatGoal] findNewTarget: no target found ({} potential, {} LOS, inCover={})",
                    potentialTargets.size(), losTargets.size(), inCover);
            }
        }
        
        this.target = null;
        soldier.setTarget(null);
        return false;
    }
    
    public LivingEntity getCurrentTarget() {
        return target;
    }
    
    public DetectionSystem getDetectionSystem() {
        return detectionSystem;
    }

    public void onEnemyGunshot(LivingEntity shooter, GunIntegration.GunshotSignature signature) {
        if (shooter == null || !shooter.isAlive() || !getPotentialTargets().contains(shooter)) {
            return;
        }

        double distance = soldier.distanceTo(shooter);
        double strengthMultiplier = signature.suppressed() ? 0.25 : 1.0;
        double points = 0.0;

        if (distance < 64.0
            && TargetAcquisition.isInPeripheralArc(soldier, shooter)
            && TargetAcquisition.hasLineOfSight(soldier, shooter)) {
            points += 35.0 * quadraticFalloff(distance, 64.0) * strengthMultiplier;
        }

        double soundRange = Math.max(8.0, 64.0 + signature.soundDistanceAdjustment());
        if (distance < soundRange) {
            boolean soundBlocked = !VisibilityRay.traceIgnoringSmoke(
                soldier.level(), soldier.getEyePosition(), shooter.getEyePosition(), soldier).clear();
            double obstructionMultiplier = soundBlocked ? 0.5 : 1.0;
            points += 25.0 * quadraticFalloff(distance, soundRange)
                * strengthMultiplier * obstructionMultiplier;
        }

        detectionSystem.addDetectionPoints(shooter, points);
    }

    private static double quadraticFalloff(double distance, double range) {
        return Math.max(0.0, 1.0 - (distance * distance) / (range * range));
    }
    
    public void setTarget(LivingEntity newTarget) {
        resetAim(newTarget);
        this.target = newTarget;
        soldier.setTarget(newTarget);
    }

    public CoverProtectionContext resolveCoverProtectionContext() {
        if (target != null && target.isAlive() && !soldier.isFriendlyTo(target)
            && TargetAcquisition.hasLineOfSight(soldier, target)) {
            return new CoverProtectionContext(CoverProtectionContext.Source.VISIBLE_TARGET,
                target.position(), null);
        }

        Optional<BlockPos> lastKnown = threatTracker.getLastKnownPosition();
        if (lastKnown.isPresent()) {
            return new CoverProtectionContext(CoverProtectionContext.Source.LAST_SEEN,
                Vec3.atCenterOf(lastKnown.get()), null);
        }

        Vec3 awarenessDirection = soldier.getThreatAwareness().getPrimaryDirection(soldier.position());
        if (awarenessDirection != null && awarenessDirection.lengthSqr() > 0.001D) {
            return new CoverProtectionContext(CoverProtectionContext.Source.THREAT_AWARENESS,
                null, awarenessDirection);
        }
        return CoverProtectionContext.NONE;
    }
    
    private void updateDebugSync() {
        if (target != null && target.isAlive()) {
            if (!soldier.level().isClientSide) {
                double distance = soldier.distanceTo(target);
                boolean hasLOS = TargetAcquisition.hasLineOfSight(soldier, target);
                boolean inFocused = TargetAcquisition.isInFocusedArc(soldier, target);
                double detectionProgress = detectionSystem.getDetectionProgress(target);
                boolean isDetected = detectionSystem.isTargetDetected(target);
                
                soldier.updateDebugData((float)detectionProgress, isDetected, (float)distance, hasLOS, inFocused);
                soldier.setDebugTargetUUID(target.getUUID());
            }
            return;
        }
        
        if (!soldier.level().isClientSide) {
            ThreatAwareness threats = soldier.getThreatAwareness();
            if (threats.hasActiveThreat()) {
                Vec3 threatPos = threats.getWeightedAveragePosition();
                if (threatPos.lengthSqr() > 0.001) {
                    soldier.updateDebugData(0, false, (float)soldier.position().distanceTo(threatPos), false, false);
                    soldier.setDebugTargetUUID(soldier.getUUID());
                }
            } else {
                soldier.setDebugTargetUUID(null);
            }
        }
    }
    
    private void sendDebugPacketToOwner() {
        if (soldier.level().isClientSide) return;
        
        LivingEntity owner = soldier.getOwner();
        if (owner instanceof ServerPlayer serverPlayer) {
            List<PotentialTargetInfo> potentialTargetsDebug = getPotentialTargetsForDebug(5);
            
            List<PotentialTargetsDebugMessage.PotentialTargetEntry> entries = new ArrayList<>();
            for (PotentialTargetInfo info : potentialTargetsDebug) {
                entries.add(new PotentialTargetsDebugMessage.PotentialTargetEntry(
                    info.uuid, info.position, info.detectionPoints, info.distance,
                    info.hasLOS, info.inFocused, info.inPeripheral,
                    info.distanceFactor, info.exposureFactor, info.movementFactor, info.brightnessFactor
                ));
            }
            
            UUID lockedTargetUUID = target != null ? target.getUUID() : null;
            Vec3 lockedTargetPos = target != null ? target.position() : Vec3.ZERO;
            
            if (target == null) {
                ThreatAwareness threats = soldier.getThreatAwareness();
                if (threats.hasActiveThreat()) {
                    Vec3 threatPos = threats.getWeightedAveragePosition();
                    if (threatPos.lengthSqr() > 0.001) {
                        lockedTargetUUID = soldier.getUUID();
                        lockedTargetPos = threatPos;
                    }
                }
            }
            
            double lockedDetectionPoints = target != null ? 
                (detectionSystem.getDetectionState(target.getUUID()) != null ? 
                    detectionSystem.getDetectionState(target.getUUID()).accumulatedPoints : 0) : 0;
            double lockedDistance = target != null ? soldier.distanceTo(target) : 0;
            boolean lockedHasLOS = target != null ? TargetAcquisition.hasLineOfSight(soldier, target) : false;
            boolean lockedInFocused = target != null ? TargetAcquisition.isInFocusedArc(soldier, target) : false;
            boolean lockedInPeripheral = target != null ? TargetAcquisition.isInPeripheralArc(soldier, target) : false;
            boolean lockedIsDetected = target != null ? detectionSystem.isTargetDetected(target) : false;
            double lockedDistanceFactor = target != null && detectionSystem.getDetectionState(target.getUUID()) != null ?
                detectionSystem.getDetectionState(target.getUUID()).lastDistanceFactor : 0;
            double lockedExposureFactor = target != null && detectionSystem.getDetectionState(target.getUUID()) != null ?
                detectionSystem.getDetectionState(target.getUUID()).lastExposureFactor : 0;
            double lockedMovementFactor = target != null && detectionSystem.getDetectionState(target.getUUID()) != null ?
                detectionSystem.getDetectionState(target.getUUID()).lastMovementFactor : 0;
            double lockedBrightnessFactor = target != null && detectionSystem.getDetectionState(target.getUUID()) != null ?
                detectionSystem.getDetectionState(target.getUUID()).lastBrightnessFactor : 0;
            float lockedAimQuality = target != null ? aimQuality : 0;
            float lockedTargetAimQuality = target != null ? AimAccuracyManager.getTargetAimQuality(soldier, target) : 0;
            float lockedSuppressiveMin = lastShotNeededBolt || GunIntegration.isBolting(soldier) 
                ? StevesArmyConfig.getAimQualitySlowGunThresholdScale() 
                : StevesArmyConfig.getAimQualityThresholdScale();
            FireDiscipline debugDisc = soldier.getFireDiscipline();
            if (debugDisc == FireDiscipline.CONSERVE) {
                lockedSuppressiveMin = Math.max(lockedSuppressiveMin, 0.55f);
            } else if (debugDisc == FireDiscipline.SUPPRESSIVE) {
                lockedSuppressiveMin = Math.min(lockedSuppressiveMin, 0.20f);
            }
            float lockedAdsProgress = target != null ? GunIntegration.getAimProgress(soldier) : 0;
            String lockedAimPointType = target != null && currentAimPoint != null ? 
                currentAimPoint.type.displayName : "";
            boolean lockedBulletPathClear = target != null && currentAimPoint != null ? 
                currentAimPoint.bulletPathClear : false;
            
            Vec3 suppressionPos = suppressionTargetPos != null ? 
                Vec3.atCenterOf(suppressionTargetPos) : null;
            
            PotentialTargetsDebugMessage msg = new PotentialTargetsDebugMessage(
                soldier.getUUID(), lockedTargetUUID, soldier.position(), lockedTargetPos,
                lockedDetectionPoints, lockedDistance, lockedHasLOS, lockedInFocused,
                lockedInPeripheral, lockedIsDetected,
                lockedDistanceFactor, lockedExposureFactor, lockedMovementFactor, lockedBrightnessFactor,
                lockedAimQuality, lockedTargetAimQuality, lockedSuppressiveMin, lockedAdsProgress,
                lockedAimPointType, lockedBulletPathClear,
                isSuppressing, suppressionPos,
                entries
            );
            
            NetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> serverPlayer), msg);
            sendSuppressPingDebug(serverPlayer);
        }
    }

    /** Snapshot of the ping-suppress pipeline for the client debug render. */
    private void sendSuppressPingDebug(ServerPlayer serverPlayer) {
        if (!SuppressPingDebugManager.isEnabled(serverPlayer.getUUID())) return;
        if (!soldier.hasValidPingSuppressPos() && pingSuppressRemainingTicks <= 0) return;

        BlockPos pingPos = soldier.getPingSuppressPos();
        Vec3 pingCentre = pingPos != null ? Vec3.atCenterOf(pingPos) : null;

        List<Vec3> aimPoints = soldier.getSuppressionAimPoints();
        List<Boolean> aimPointValid = new ArrayList<>(aimPoints.size());
        for (Vec3 point : aimPoints) {
            aimPointValid.add(TargetAcquisition.hasLineOfSightToPositionIgnoringSmoke(soldier, point));
        }

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        CoverBehaviorManager.CoverState coverState = coverManager.getState();
        Vec3 relocTarget = null;
        if (coverState == CoverBehaviorManager.CoverState.SEEKING_COVER
            || coverState == CoverBehaviorManager.CoverState.REPOSITIONING) {
            CoverPoint targetCover = coverManager.getTargetCover();
            if (targetCover != null) {
                relocTarget = Vec3.atCenterOf(targetCover.getPosition());
            }
        }

        // Relocation-process debug: the suppress-position search state, its
        // failure history, and the path the soldier is actually walking.
        CoverGoalController coverTacticalGoal = soldier.getCoverTacticalGoal();
        int relocationGeneration = soldier.getSuppressionOrder().getGeneration();
        CoverGoalController.SuppressionPositionStatus relocState =
            coverTacticalGoal.getSuppressionPositionStatus(relocationGeneration);
        String relocStatus = relocState == CoverGoalController.SuppressionPositionStatus.IDLE
            ? "" : relocState.name();
        int relocFailures = coverTacticalGoal.getSuppressionPositionFailures(relocationGeneration);
        String relocLastFailure = coverTacticalGoal.getSuppressionPositionLastFailure(relocationGeneration);
        if (relocLastFailure == null) relocLastFailure = "";
        Vec3 searchOrigin = relocState == CoverGoalController.SuppressionPositionStatus.IDLE
            ? null : coverTacticalGoal.getSuppressionPositionSearchOrigin(relocationGeneration);
        float searchRadius = coverTacticalGoal.getSuppressionPositionSearchRadius();

        List<Vec3> navPath = new ArrayList<>();
        Path path = soldier.getNavigation().getPath();
        if (path != null && !path.isDone()) {
            int nodeCount = path.getNodeCount();
            int stride = Math.max(1, (nodeCount + 31) / 32);
            for (int i = 0; i < nodeCount; i += stride) {
                navPath.add(Vec3.atBottomCenterOf(path.getNode(i).asBlockPos()).add(0, 0.1, 0));
            }
        }

        // Relocation held short by the current-cover lane guard — surface the
        // reason briefly so a held ping doesn't look like a dead one.
        String relocHoldNote = "";
        if (soldier.tickCount - lastRelocationHoldTick < PING_REPOSITION_COOLDOWN_TICKS
            && lastRelocationHoldReason != null) {
            relocHoldNote = "hold (" + lastRelocationHoldReason + " — lane ok)";
        }

        NetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
            new SuppressPingDebugPacket(true, soldier.getUUID(), soldier.position(), pingCentre,
                pingSuppressHeavy, pingSuppressRemainingTicks, pingSuppressDurationTicks,
                buildPingStatusLine(coverState, coverManager), pingSuppressionTarget, relocTarget,
                aimPoints, aimPointValid,
                relocStatus, relocFailures, relocLastFailure,
                searchOrigin, searchRadius, navPath,
                pingNoTargetTicks, PING_NO_TARGET_REPOSITION_TICKS,
                pingPeekStuckTicks, PING_PEEK_STUCK_REPOSITION_TICKS, relocHoldNote));
    }

    /** Active player orders return before the ordinary debug-sync cadence. */
    private void sendSuppressPingDebugToOwner() {
        if (soldier.level().isClientSide) return;
        LivingEntity owner = soldier.getOwner();
        if (owner instanceof ServerPlayer serverPlayer) {
            sendSuppressPingDebug(serverPlayer);
        }
    }

    private String buildPingStatusLine(CoverBehaviorManager.CoverState coverState,
                                       CoverBehaviorManager coverManager) {
        StringBuilder status = new StringBuilder();
        status.append(isPingSuppressing ? "firing" : "pending");
        if (pingHeavyRocketSpent) status.append(" rpgSpent");
        status.append(" peek=").append(soldier.getPeekController().getState());
        status.append(String.format(" ads=%.0f%%", GunIntegration.getAimProgress(soldier) * 100.0f));
        status.append(" burst=").append(burstShotsFired).append('/').append(suppressionBurstTarget);
        if (burstCooldownTicks > 0) status.append(" cd=").append(burstCooldownTicks);
        if (pingNoTargetTicks > 0) status.append(" noLane=").append(pingNoTargetTicks);
        if (pingPeekStuckTicks > 0) status.append(" peekStuck=").append(pingPeekStuckTicks);
        if (GunIntegration.isReloading(soldier)) status.append(" reload");
        if (GunIntegration.isDrawing(soldier)) status.append(" draw");
        if (GunIntegration.isBolting(soldier)) status.append(" bolt");
        if (!soldier.getSuppressionAimPoints().isEmpty() && pingSuppressionTarget == null) {
            status.append(" laneBlocked");
        } else if (pingSuppressHeavy && soldier.getSuppressionAimPoints().isEmpty()
            && pingNoTargetTicks > 0) {
            status.append(" noFacePts");
        }
        if (pingSuppressHeavy && pingSuppressionTarget != null && lastHeavyLaneSource != null) {
            status.append(" lane=").append(lastHeavyLaneSource);
        }
        SuppressionOrderController order = soldier.getSuppressionOrder();
        CoverGoalController.SuppressionPositionStatus relocation = soldier.getCoverTacticalGoal()
            .getSuppressionPositionStatus(order.getGeneration());
        if (relocation != CoverGoalController.SuppressionPositionStatus.IDLE) {
            status.append(" reloc=").append(relocation);
        }
        if (order.getBlockReason() != SuppressionOrderController.BlockReason.NONE) {
            status.append(" block=").append(order.getBlockReason());
        }
        status.append(" cv=").append(coverState);
        if (coverManager.isContinuousSuppressionRepositionRequested()) status.append(" scootReq");
        if (coverManager.isRepositionRequested()) status.append(" relocReq");
        if (coverManager.isNonPeekableCover()) status.append(" nonPeekable");
        return status.toString();
    }
    
    public List<PotentialTargetInfo> getPotentialTargetsForDebug(int maxCount) {
        List<PotentialTargetInfo> result = new ArrayList<>();
        
        List<LivingEntity> allTargets = getPotentialTargets();
        
        for (LivingEntity potentialTarget : allTargets) {
            if (potentialTarget == this.target) continue;
            
            DetectionSystem.DetectionState state = detectionSystem.getDetectionState(potentialTarget.getUUID());
            if (state == null || state.accumulatedPoints <= 0) continue;
            
            double distance = soldier.distanceTo(potentialTarget);
            boolean hasLOS = TargetAcquisition.hasLineOfSight(soldier, potentialTarget);
            boolean inFocused = TargetAcquisition.isInFocusedArc(soldier, potentialTarget);
            boolean inPeripheral = TargetAcquisition.isInPeripheralArc(soldier, potentialTarget);
            double detectionPoints = state.accumulatedPoints;
            double distanceFactor = state.lastDistanceFactor;
            double exposureFactor = state.lastExposureFactor;
            double movementFactor = state.lastMovementFactor;
            double brightnessFactor = state.lastBrightnessFactor;
            
            result.add(new PotentialTargetInfo(
                potentialTarget.getUUID(),
                potentialTarget.position(),
                detectionPoints,
                distance,
                hasLOS,
                inFocused,
                inPeripheral,
                distanceFactor,
                exposureFactor,
                movementFactor,
                brightnessFactor
            ));
        }
        
        result.sort(Comparator.comparingDouble(a -> -a.detectionPoints));
        
        if (result.size() > maxCount) {
            result = result.subList(0, maxCount);
        }
        
        return result;
    }
    
    public static class PotentialTargetInfo {
        public final UUID uuid;
        public final Vec3 position;
        public final double detectionPoints;
        public final double distance;
        public final boolean hasLOS;
        public final boolean inFocused;
        public final boolean inPeripheral;
        public final double distanceFactor;
        public final double exposureFactor;
        public final double movementFactor;
        public final double brightnessFactor;
        
        public PotentialTargetInfo(UUID uuid, Vec3 position, double detectionPoints, double distance, 
                                   boolean hasLOS, boolean inFocused, boolean inPeripheral,
                                   double distanceFactor, double exposureFactor, 
                                   double movementFactor, double brightnessFactor) {
            this.uuid = uuid;
            this.position = position;
            this.detectionPoints = detectionPoints;
            this.distance = distance;
            this.hasLOS = hasLOS;
            this.inFocused = inFocused;
            this.inPeripheral = inPeripheral;
            this.distanceFactor = distanceFactor;
            this.exposureFactor = exposureFactor;
            this.movementFactor = movementFactor;
            this.brightnessFactor = brightnessFactor;
        }
    }

    private SquadThreatIntel getSquadIntel() {
        UUID squadId = soldier.getSquadId();
        if (squadId == null) return null;
        
        if (!(soldier.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        
        SquadManager manager = SquadManager.get(serverLevel);
        Optional<SquadData> squad = manager.getSquadById(squadId);
        return squad.map(SquadData::getThreatIntel).orElse(null);
    }

    private void reportThreatToSquadIntel(LivingEntity threat, float accuracy) {
        if (!threat.isAlive() || !TargetAcquisition.isValidTarget(soldier, threat)) {
            return;
        }

        SquadThreatIntel intel = getSquadIntel();
        if (intel == null) {
            if (isDebugLogging()) {
                StevesArmyMod.LOGGER.info("[ThreatReport] Soldier {} cannot report - no squad intel (squadId={})", 
                    soldier.getId(), soldier.getSquadId());
            }
            return;
        }
        
        if (isDebugLogging()) {
            StevesArmyMod.LOGGER.info("[ThreatReport] Soldier {} reporting threat {} to squad intel", 
                soldier.getId(), threat.getName().getString());
        }
        ExposureCalculator.AimPointResult aimPoint = ExposureCalculator.getBestAimPoint(soldier, threat);
        intel.reportThreat(soldier.getUUID(), threat, threat.blockPosition(),
            aimPoint != null && aimPoint.canShoot() ? aimPoint.position : threat.getEyePosition(),
            threat.getEyePosition(), accuracy);
    }

    private boolean shouldSuppressTarget() {
        pendingSuppressionThreat = null;

        if (soldier.getFireDiscipline() == FireDiscipline.CONSERVE) {
            return false;
        }
        
        if (GunIntegration.isReloading(soldier) ||
            GunIntegration.isBolting(soldier) ||
            GunIntegration.isDrawing(soldier)) {
            if (machineGunnerPipeline && isSuppressionDebugLogging() && soldier.tickCount % 100 == 0) {
                StevesArmyMod.LOGGER.info("[Suppression] MG {} gate: weapon cycling (reload/bolt/draw)", soldier.getId());
            }
            return false;
        }

        if (target != null && TargetAcquisition.hasLineOfSight(soldier, target)) {
            if (machineGunnerPipeline && isSuppressionDebugLogging() && soldier.tickCount % 100 == 0) {
                StevesArmyMod.LOGGER.info("[Suppression] MG {} gate: held target has LOS — direct fire owns", soldier.getId());
            }
            return false;
        }

        if (isAttackSuppressionOrderActive()) {
            if (GunIntegration.isReloading(soldier) || GunIntegration.isBolting(soldier)
                || GunIntegration.isDrawing(soldier) || !GunIntegration.isAnyGunLoaded()
                || !GunIntegration.hasGun(soldier) || getTotalAmmo() <= 0) {
                return false;
            }
            return startAttackSuppression();
        }

        SquadThreatIntel intel = getSquadIntel();
        if (intel == null) {
            return false;
        }

        if (soldier.level() instanceof ServerLevel serverLevel) {
            UUID squadId = soldier.getSquadId();
            if (squadId != null) {
                SquadManager manager = SquadManager.get(serverLevel);
                Optional<SquadData> squadOpt = manager.getSquadById(squadId);
                if (squadOpt.isPresent()) {
                    SuppressireAssignmentManager.assignSuppressionTargets(squadOpt.get(), intel, serverLevel, soldier.getUUID());
                }
            }
        }

        Optional<SquadThreatIntel.ThreatKnowledge> existingAssignment =
            intel.getAssignedThreatForSoldier(soldier.getUUID());
        if (existingAssignment.isPresent()) {
            pendingSuppressionThreat = existingAssignment.get();
            if (pendingSuppressionThreat.isAlive
                && !pendingSuppressionThreat.isHardTarget
                && !intel.isThreatStale(pendingSuppressionThreat.threatEntityId, soldier.level().getGameTime())) {
                return true;
            }
            intel.releaseThreatSuppression(pendingSuppressionThreat.threatEntityId, soldier.getUUID());
            pendingSuppressionThreat = null;
        }

        List<SquadThreatIntel.ThreatKnowledge> suppressibleThreats = intel.getAllThreats().stream()
            .filter(threat -> threat.isAlive)
            .sorted(suppressionCandidateOrder(intel))
            .collect(Collectors.toList());
        if (suppressibleThreats.isEmpty()) {
            return false;
        }

        for (SquadThreatIntel.ThreatKnowledge threat : suppressibleThreats) {
            if (!threat.isAlive) continue;
            if (intel.isThreatStale(threat.threatEntityId, soldier.level().getGameTime())) continue;
            if (threat.lastKnownPosition == null) continue;
            if (threat.isHardTarget) continue;
            
            double dist = soldier.position().distanceTo(threat.lastKnownPosition.getCenter());
            if (dist > SUPPRESSION_MAX_RANGE) continue;
            
            if (GunIntegration.isAnyGunLoaded() && GunIntegration.hasGun(soldier)) {
                int totalAmmo = getTotalAmmo();
                if (totalAmmo == 0) continue;
            }

            int maxSuppressors = getMaxSuppressorsForThreat(threat);
            if (intel.getSuppressionCount(threat.threatEntityId) >= maxSuppressors) continue;
            if (machineGunnerPipeline && hasAssignedMachineGunner(intel, threat)) continue;
            
            pendingSuppressionThreat = threat;
            return true;
        }

        if (machineGunnerPipeline && isSuppressionDebugLogging() && soldier.tickCount % 100 == 0) {
            StringBuilder reasons = new StringBuilder();
            for (SquadThreatIntel.ThreatKnowledge threat : suppressibleThreats) {
                String reason;
                if (!threat.isAlive) reason = "dead";
                else if (intel.isThreatStale(threat.threatEntityId, soldier.level().getGameTime())) reason = "stale";
                else if (threat.lastKnownPosition == null) reason = "no-pos";
                else if (threat.isHardTarget) reason = "hard";
                else if (soldier.position().distanceTo(threat.lastKnownPosition.getCenter()) > SUPPRESSION_MAX_RANGE) reason = "range";
                else if (GunIntegration.isAnyGunLoaded() && GunIntegration.hasGun(soldier) && getTotalAmmo() == 0) reason = "no-ammo";
                else if (intel.getSuppressionCount(threat.threatEntityId) >= getMaxSuppressorsForThreat(threat)) reason = "slots";
                else if (hasAssignedMachineGunner(intel, threat)) reason = "mg-assigned";
                else reason = "ELIGIBLE";
                reasons.append(' ').append(threat.threatEntityId.toString(), 0, 6).append('=').append(reason);
            }
            StevesArmyMod.LOGGER.info("[Suppression] MG {} scan: threats:{}", soldier.getId(), reasons);
        }
        return false;
    }

    private int getMaxSuppressorsForThreat(SquadThreatIntel.ThreatKnowledge threat) {
        long age = soldier.level().getGameTime() - threat.lastSeenTime;
        if (age > FireControl.SUPPRESSION_CONTACT_FOCUSED_TICKS) {
            return 1;
        }
        return machineGunnerPipeline && hasReadySquadMachineGunner(threat) ? 1 : 2;
    }

    /**
     * Sidearm doctrine: AT carriers hold a normal gun (sidearm slot when
     * loaded) and raise the launcher while the vehicle branch owns the
     * engagement or while a heavy-fire suppression ping is active. Soldiers
     * without an AT gun are untouched. The emergency sidearm draw
     * ({@link #tickSidearmFallback}) outranks this policy while active.
     */
    private void tickWeaponSelection(@javax.annotation.Nullable ArmorThreatScanner.ArmorContact vehicleTarget) {
        if (!GunIntegration.isAnyGunLoaded() || !GunIntegration.hasGun(soldier)) {
            abandonSidearmFallback();
            return;
        }
        // The emergency sidearm draw owns weapon choice while it is active —
        // the launcher policy below must not fight it, or a raised dry
        // launcher and the drawn sidearm would swap-flicker every tick.
        if (tickSidearmFallback(vehicleTarget)) {
            return;
        }
        // Soldiers without a launcher have no policy to apply; skip the scan.
        if (!isAtCarrier()) {
            return;
        }
        // The held gun must match the engagement, immediately. Branch flips
        // are already debounced (engagement latch + visible-sighting gate), so
        // a mismatch only means the fight genuinely changed — fighting infantry
        // with a rocket launcher must not wait a cooldown out, and vice versa.
        boolean launcherWanted = vehicleTarget != null || heavyPingPhaseActive();
        if (SoldierWeaponSelector.update(soldier, launcherWanted)) {
            resetAim(null);
        }
    }

    /**
     * Emergency sidearm doctrine: a soldier caught in the open with a dry gun
     * draws the sidearm-slot gun instead of reloading standing up, because
     * switching is faster than reloading. The in-cover peek-hide-reload loop
     * is untouched — this only rescues exposed soldiers. Returns true while
     * the fallback owns weapon choice; when it releases, the launcher/default
     * policy below re-asserts the correct gun that same tick.
     */
    private boolean tickSidearmFallback(@javax.annotation.Nullable ArmorThreatScanner.ArmorContact vehicleTarget) {
        if (sidearmRestoreCooldownTicks > 0) {
            sidearmRestoreCooldownTicks--;
            // Keep the restored primary in hand — and the launcher/default
            // policy out — until its reload has had time to run.
            return true;
        }
        if (!sidearmFallbackActive) {
            if (!shouldDrawSidearmFallback(vehicleTarget)) {
                return false;
            }
            ItemStack originalMain = soldier.getMainHandItem().copy();
            if (!SoldierWeaponSelector.swapWithSidearm(soldier)) {
                return false;
            }
            // The swap must not leave a pending reload request behind — it
            // would fire into the freshly drawn gun (cancelReload does not
            // clear reloadPending).
            if (reloadPending) {
                clearReloadStatus();
            }
            sidearmFallbackOriginalMain = originalMain;
            sidearmFallbackActive = true;
            sidearmFallbackTicks = 0;
            resetAim(null);
            if (isDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SidearmFallback] {} drew the sidearm", soldier.getId());
            }
            return true;
        }

        sidearmFallbackTicks++;
        if (sidearmFallbackTicks < SIDEARM_FALLBACK_MIN_HOLD_TICKS) {
            return true;
        }
        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        // Cover alone is enough to restore: the primary's own reload then
        // waits for a safe peek-idle window through the normal reload flow.
        boolean backInCover = coverManager.isInCover();
        boolean fightOver = !hasActiveEngagement() && !coverManager.isSuppressed();
        if (backInCover || fightOver) {
            if (!SoldierWeaponSelector.restoreGun(soldier, sidearmFallbackOriginalMain)) {
                return true;
            }
            sidearmFallbackActive = false;
            sidearmFallbackOriginalMain = ItemStack.EMPTY;
            // The restored primary is dry; a one-tick cover-state flicker
            // before its reload starts must not re-arm the sidearm.
            sidearmRestoreCooldownTicks = SIDEARM_RESTORE_REARM_TICKS;
            resetAim(null);
            if (isDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SidearmFallback] {} restored the primary (cover={}, fightOver={})",
                    soldier.getId(), backInCover, fightOver);
            }
            return true;
        }
        return true;
    }

    private void abandonSidearmFallback() {
        sidearmFallbackActive = false;
        sidearmFallbackOriginalMain = ItemStack.EMPTY;
        sidearmRestoreCooldownTicks = 0;
    }

    private boolean shouldDrawSidearmFallback(@javax.annotation.Nullable ArmorThreatScanner.ArmorContact vehicleTarget) {
        // An actually running reload always finishes; a merely pending one is
        // dropped by the swap instead of blocking the emergency draw.
        if (GunIntegration.isReloading(soldier)) {
            return false;
        }
        if (GunIntegration.getCurrentAmmo(soldier) > 0) {
            return false;
        }
        // In cover — even mid-peek — the duck-and-reload loop owns a dry mag.
        // The sidearm is for soldiers with no cover to reload behind.
        if (soldier.getCoverBehaviorManager().isInCover()) {
            return false;
        }
        // Emergency only: actively fighting (live target, suppression, or ping).
        if (!hasActiveEngagement()) {
            return false;
        }
        boolean launcherDesired = vehicleTarget != null || heavyPingPhaseActive();
        return SoldierWeaponSelector.isUsableSidearm(soldier, launcherDesired);
    }

    /**
     * Heavy-weapon suppression: an armor hunter with launcher rounds answers a
     * suppress ping by raising the AT gun and shelling the cover blocks instead
     * of the peek openings. The ping is an explicit player order, so it outranks
     * autonomous entity combat and squad suppression assignments; only live
     * vehicle contact (rocket reserved for the tank) outranks it.
     */
    private boolean wantsHeavySuppressPing() {
        if (!soldier.hasValidPingSuppressPos()) return false;
        if (!ArmorRoleManager.isArmorHunter(soldier)) return false;
        if (SoldierWeaponSelector.countLauncherAmmo(soldier) <= 0) return false;
        BlockPos pingPos = soldier.getPingSuppressPos();
        return soldier.position().distanceToSqr(pingPos.getCenter())
            <= SUPPRESSION_MAX_RANGE * SUPPRESSION_MAX_RANGE;
    }

    /**
     * The heavy phase of the current ping: an armor hunter with launcher
     * rounds that has not yet spent its one rocket. After the shell goes out
     * the launcher lowers to the sidearm slot and the order keeps running as
     * ordinary bullet suppression, so one ping never burns a burst of rockets.
     */
    private boolean heavyPingPhaseActive() {
        return !pingHeavyRocketSpent && wantsHeavySuppressPing();
    }

    /** True when the soldier carries an anti-armor gun anywhere in inventory (cached briefly). */
    private boolean isAtCarrier() {
        if (soldier.tickCount - atCarrierCheckTick >= 40) {
            atCarrierCached = SoldierWeaponSelector.hasLauncher(soldier);
            atCarrierCheckTick = soldier.tickCount;
        }
        return atCarrierCached;
    }

    private boolean atCarrierCached;
    private int atCarrierCheckTick = -1000;

    // Emergency sidearm draw: transient on purpose. After a save/load the
    // launcher/default policy self-normalizes whatever gun is in hand.
    private boolean sidearmFallbackActive;
    private int sidearmFallbackTicks;
    private int sidearmRestoreCooldownTicks;
    private ItemStack sidearmFallbackOriginalMain = ItemStack.EMPTY;
    private static final int SIDEARM_FALLBACK_MIN_HOLD_TICKS = 30;
    private static final int SIDEARM_RESTORE_REARM_TICKS = 100;

    @Override
    public boolean hasActiveEngagement() {
        return isDirectlyEngaging();
    }

    @Override
    public boolean isSidearmFallbackActive() {
        return sidearmFallbackActive;
    }

    @Override
    public int getSidearmRestoreCooldownTicks() {
        return sidearmRestoreCooldownTicks;
    }

    @Override
    public String getSidearmFallbackOriginalGunId() {
        return sidearmFallbackOriginalMain.isEmpty() ? "" : GunIntegration.getGunId(sidearmFallbackOriginalMain);
    }

    /** Why the sidearm is (or is not) in hand right now, for the debug overlay. */
    @Override
    public String getSidearmFallbackDebugStatus() {
        if (sidearmRestoreCooldownTicks > 0) {
            return "COOLDOWN " + sidearmRestoreCooldownTicks + "t";
        }
        if (!sidearmFallbackActive) {
            return "idle";
        }
        if (sidearmFallbackTicks < SIDEARM_FALLBACK_MIN_HOLD_TICKS) {
            return "WAIT min-hold " + (SIDEARM_FALLBACK_MIN_HOLD_TICKS - sidearmFallbackTicks) + "t";
        }
        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        if (coverManager.isInCover()) {
            return "RESTORING";
        }
        if (coverManager.isSuppressed()) {
            return "HOLD suppressed+exposed";
        }
        if (hasActiveEngagement()) {
            return "HOLD engaged+exposed";
        }
        return "RESTORING";
    }

    private boolean hasReadySquadMachineGunner(SquadThreatIntel.ThreatKnowledge threat) {
        SquadThreatIntel intel = getSquadIntel();
        if (intel == null || !(soldier.level() instanceof ServerLevel serverLevel)) return false;
        UUID squadId = soldier.getSquadId();
        if (squadId == null) return false;

        Vec3 aimPoint = threat.lastVisibleAimPoint != null ? threat.lastVisibleAimPoint
            : threat.lastKnownPosition != null ? Vec3.atCenterOf(threat.lastKnownPosition).add(0, 1.0, 0) : null;
        if (aimPoint == null) return false;

        Optional<SquadData> squad = SquadManager.get(serverLevel).getSquadById(squadId);
        return squad.isPresent() && squad.get().getMemberIds().stream()
            .map(serverLevel::getEntity)
            .filter(SoldierEntity.class::isInstance)
            .map(SoldierEntity.class::cast)
            .anyMatch(member -> member.isAlive() && GunIntegration.hasGun(member)
                && GunIntegration.isSuppressiveMachineGun(member)
                && !GunIntegration.isReloading(member)
                && !GunIntegration.isBolting(member)
                && !GunIntegration.isDrawing(member)
                && GunIntegration.getCurrentAmmo(member) > 0
                && TargetAcquisition.hasNearLineOfSightToPosition(member, aimPoint, SUPPRESSION_LOS_TOLERANCE));
    }

    private boolean hasAssignedMachineGunner(SquadThreatIntel intel, SquadThreatIntel.ThreatKnowledge threat) {
        if (!(soldier.level() instanceof ServerLevel serverLevel)) return false;
        return threat.suppressors.stream()
            .map(serverLevel::getEntity)
            .filter(SoldierEntity.class::isInstance)
            .map(SoldierEntity.class::cast)
            .anyMatch(member -> member.isAlive() && GunIntegration.isSuppressiveMachineGun(member));
    }

    private void maintainSuppressionAssignment() {
        if (!isSuppressing || suppressionTargetUUID == null) return;

        if (attackSuppressionActive) {
            if (!isAttackSuppressionOrderActive()) {
                cancelAllSuppression();
                return;
            }
            AttackSuppressionContact contact = attackSuppressionContacts.get(suppressionTargetUUID);
            if (contact == null || soldier.position().distanceTo(contact.lastObservedBlock.getCenter()) > SUPPRESSION_MAX_RANGE) {
                cancelAllSuppression();
                return;
            }
            suppressionTargetPos = contact.lastObservedBlock;
            suppressionTargetAimPoint = contact.lastVisibleAim;
            suppressionLastSeenTick = soldier.level().getGameTime();
            return;
        }

        SquadThreatIntel intel = getSquadIntel();
        if (intel == null || !isLiveSuppressionThreat(intel, suppressionTargetUUID)
            || !intel.hasSuppressionAssignment(suppressionTargetUUID, soldier.getUUID())) {
            cancelAllSuppression();
            return;
        }

        long contactAge = soldier.level().getGameTime() - suppressionLastSeenTick;
        boolean expired = soldier.tickCount - suppressionPlanStartTick > FireControl.SUPPRESSION_PLAN_MAX_TICKS
            || contactAge > FireControl.SUPPRESSION_CONTACT_MAX_TICKS
            || (suppressionFirstShotTick < 0
                && soldier.tickCount - suppressionPlanStartTick > SUPPRESSION_PREPARATION_TICKS)
            || (suppressionFirstShotTick >= 0
                && soldier.tickCount - suppressionFirstShotTick > SUPPRESSION_ACTIVE_FIRE_TICKS);
        if (expired) {
            cancelAllSuppression();
            return;
        }
        intel.updateSuppressionHeartbeat(suppressionTargetUUID, soldier.getUUID(), soldier.level().getGameTime());
    }

    private Vec3 calculateSuppressionSpread(Vec3 targetPos, float aimInaccuracy) {
        double distance = soldier.position().distanceTo(targetPos);
        double gunSpread = distance * Math.tan(Math.toRadians(aimInaccuracy));
        return FireControl.calculateSuppressionSpread(soldier.position(), soldier.getEyePosition(),
            soldier.level().random, targetPos, gunSpread);
    }

    private boolean prepareToFire(Vec3 targetPos, boolean isDirectTarget) {
        float targetYaw = getYawTo(targetPos);
        float targetPitch = getPitchTo(targetPos);

        if (engagementPostureState != EngagementPostureState.READY
            && !canUseEmergencyEngagementPosture(isDirectTarget)) {
            engagementPostureState = EngagementPostureState.READY;
            soldier.clearEmergencyEngagementPosture();
            return false;
        }

        if (soldier.isLowCrouching()) {
            float proneYaw = soldier.isCrawlMoving() ? soldier.getCrawlFacingYaw() : soldier.getYRot();
            float proneAngle = Math.abs(Mth.wrapDegrees(targetYaw - proneYaw));
            if (proneAngle <= PRONE_FIRING_ARC_DEGREES) {
                return turnHeadToward(targetYaw, targetPitch) <= FIRING_ALIGNMENT_DEGREES;
            }

            if (soldier.isCrawlMoving()) {
                return false;
            }

            if (!canUseEmergencyEngagementPosture(isDirectTarget)) {
                return false;
            }

            soldier.requestEmergencyEngagementPosture();
            soldier.setLowCrouching(false);
            CoverPoint cover = soldier.getCoverBehaviorManager().getCurrentCover();
            if (cover != null && cover.getType() == CoverType.HALF) {
                soldier.beginHalfCoverRise("emergency-engagement");
            }
            engagementPostureState = EngagementPostureState.EXITING_LOW_CROUCH;
            return false;
        }

        if (engagementPostureState == EngagementPostureState.EXITING_LOW_CROUCH) {
            engagementPostureState = EngagementPostureState.ROTATING;
            return false;
        }

        float remainingYaw = turnToward(targetYaw, targetPitch);
        if (remainingYaw > FIRING_ALIGNMENT_DEGREES) {
            return false;
        }

        if (engagementPostureState == EngagementPostureState.ROTATING) {
            CoverPoint cover = soldier.getCoverBehaviorManager().getCurrentCover();
            if (cover != null && cover.getType() == CoverType.HALF
                && soldier.getPeekController().exposeForEmergencyEngagement(soldier, cover)) {
                engagementPostureState = EngagementPostureState.READY;
                return false;
            }
        }

        engagementPostureState = EngagementPostureState.READY;
        return true;
    }

    private boolean canUseEmergencyEngagementPosture(boolean isDirectTarget) {
        if (!isDirectTarget || target == null || !target.isAlive()
            || soldier.distanceToSqr(target) > EMERGENCY_FLANK_DISTANCE * EMERGENCY_FLANK_DISTANCE) {
            return false;
        }

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        CoverPoint cover = coverManager.getCurrentCover();
        return coverManager.isSuppressed() && cover != null && cover.getType() == CoverType.HALF;
    }

    private float turnToward(float targetYaw, float targetPitch) {
        if (soldier.getPeekController().isCornerPreAiming()) return turnHeadToward(targetYaw, targetPitch);
        float previousYaw = soldier.getYRot();
        float previousBodyYaw = soldier.yBodyRot;
        float previousHeadYaw = soldier.getYHeadRot();
        float yaw = approachAngle(soldier.getYRot(), targetYaw, TURN_RATE_DEGREES);
        float pitch = approachAngle(soldier.getXRot(), targetPitch, TURN_RATE_DEGREES);
        soldier.setYRot(yaw);
        soldier.setYBodyRot(yaw);
        soldier.setYHeadRot(yaw);
        soldier.setXRot(pitch);
        soldier.traceRotationWrite("combat-turn", previousYaw, previousBodyYaw, previousHeadYaw,
            "targetYaw=" + String.format("%.1f", targetYaw) + ", targetPitch=" + String.format("%.1f", targetPitch)
                + ", appliedPitch=" + String.format("%.1f", pitch));
        return Math.abs(Mth.wrapDegrees(targetYaw - yaw));
    }

    private float turnHeadToward(float targetYaw, float targetPitch) {
        float previousYaw = soldier.getYRot();
        float previousBodyYaw = soldier.yBodyRot;
        float previousHeadYaw = soldier.getYHeadRot();
        float headYaw = approachAngle(soldier.getYHeadRot(), targetYaw, TURN_RATE_DEGREES);
        float pitch = approachAngle(soldier.getXRot(), targetPitch, TURN_RATE_DEGREES);
        soldier.setYHeadRot(headYaw);
        soldier.setXRot(pitch);
        soldier.traceRotationWrite("combat-head-turn", previousYaw, previousBodyYaw, previousHeadYaw,
            "targetYaw=" + String.format("%.1f", targetYaw) + ", targetPitch=" + String.format("%.1f", targetPitch)
                + ", appliedPitch=" + String.format("%.1f", pitch));
        return Math.abs(Mth.wrapDegrees(targetYaw - headYaw));
    }

    private static float approachAngle(float current, float target, float maxChange) {
        return current + Mth.clamp(Mth.wrapDegrees(target - current), -maxChange, maxChange);
    }

    private float getYawTo(Vec3 targetPos) {
        Vec3 toTarget = targetPos.subtract(soldier.getEyePosition());
        return (float) Math.toDegrees(Math.atan2(-toTarget.x, toTarget.z));
    }

    private float getPitchTo(Vec3 targetPos) {
        return GunIntegration.getAimPitch(soldier, targetPos);
    }

    private int getTicksBetweenBurstShots() {
        if (GunIntegration.isMachineGun(soldier)) {
            return 1;
        }

        int rpm = GunIntegration.getRPM(soldier);
        double shotsPerSecond = rpm / 60.0;
        int baseTicks = (int) Math.ceil(20.0 / shotsPerSecond);
        
        if (GunIntegration.isManualBolt(soldier)) {
            baseTicks += 10;
        }
        
        return Math.max(1, baseTicks);
    }
    
    private float getBurstIntervalSeconds() {
        return GunIntegration.getBurstMinInterval(soldier);
    }

    private DirectFireWeaponProfile getDirectFireWeaponProfile() {
        if (GunIntegration.isManualBolt(soldier)) return DirectFireWeaponProfile.SINGLE_SHOT;
        if (GunIntegration.isSuppressiveMachineGun(soldier)) return DirectFireWeaponProfile.MACHINE_GUN;

        String tabType = GunIntegration.getGunTabType(soldier);
        if ("smg".equals(tabType)) return DirectFireWeaponProfile.SMG;
        if (GunIntegration.getRPM(soldier) >= 600 && GunIntegration.getMagazineSize(soldier) >= 25) {
            return DirectFireWeaponProfile.AUTO_RIFLE;
        }
        return DirectFireWeaponProfile.SINGLE_SHOT;
    }

    /**
     * MG direct fire runs the machine-gun cadence from config instead of the
     * shared 5-shot enum constant — an M249 hosing an active target should
     * look like its suppression bursts, just aimed. 0/disabled falls back.
     */
    private int directBurstShotsFor(DirectFireWeaponProfile profile) {
        if (profile != DirectFireWeaponProfile.MACHINE_GUN) return profile.burstShots;
        int configured = StevesArmyConfig.getMgDirectBurstShots();
        return configured > 0 ? configured : profile.burstShots;
    }

    private int directRecoveryTicksFor(DirectFireWeaponProfile profile) {
        if (profile != DirectFireWeaponProfile.MACHINE_GUN) return profile.recoveryTicks;
        int configured = StevesArmyConfig.getMgDirectRecoveryTicks();
        return configured > 0 ? configured : profile.recoveryTicks;
    }

    private float getDirectBurstContinuationThreshold(float startThreshold) {
        return FireControl.directBurstContinuationThreshold(soldier.getFireDiscipline(), startThreshold);
    }

    // --- Dynamic firing gate ---------------------------------------------------

    private static boolean dynamicFiringEnabled() {
        return StevesArmyConfig.isDynamicFiringEnabled();
    }

    private static boolean isFiringDebugLogging() {
        return DiagnosticLogManager.isFiringLoggingEnabled();
    }

    /** Pull a raw personality bias toward 1.0 by the configured strength. */
    private static float scaledBias(float rawBias) {
        return Mth.lerp(StevesArmyConfig.getFiringPersonalityStrength(), 1.0f, rawBias);
    }

    private float burstBias() {
        return firePersonality != null ? scaledBias(firePersonality.burstBias()) : 1.0f;
    }

    private float gapBias() {
        return firePersonality != null ? scaledBias(firePersonality.gapBias()) : 1.0f;
    }

    private float thresholdBias() {
        return firePersonality != null ? scaledBias(firePersonality.thresholdBias()) : 1.0f;
    }

    private float cadenceBias() {
        return firePersonality != null ? scaledBias(firePersonality.cadenceBias()) : 1.0f;
    }

    private float buildBias() {
        return firePersonality != null ? scaledBias(firePersonality.buildBias()) : 1.0f;
    }

    /**
     * Blended suppression driving the firing gate: the soldier's own pressure,
     * floored by a configured share of the fireteam's aggregated level.
     */
    private float effectiveSuppressionLevel() {
        if (!dynamicFiringEnabled()) return 0.0f;
        SuppressionTracker tracker = soldier.getCoverBehaviorManager().getSuppressionTracker();
        float individual = tracker != null ? tracker.getSuppressionLevel() : 0.0f;
        float team = FireTeamSuppressionTracker.getLevel(soldier);
        return Math.max(individual, StevesArmyConfig.getFiringFireteamBlend() * team);
    }

    /**
     * Reshapes how picky the current firing solution must be: personality sets
     * the baseline, suppression shifts urgency for soldiers under their own
     * orders (STANDARD discipline only — CONSERVE and SUPPRESSIVE are doctrine),
     * and an ineffective-burst streak demands a better shot before trying again.
     */
    private float applyDynamicThresholdModifiers(float scale, FireDiscipline discipline) {
        if (!dynamicFiringEnabled()) return scale;

        scale *= thresholdBias();

        float suppression = effectiveSuppressionLevel();
        if (suppression > 0.0f && discipline == FireDiscipline.STANDARD) {
            if (StevesArmyConfig.getSuppressedFireMode() == StevesArmyConfig.SuppressedFireMode.SPRAY) {
                scale *= 1.0f - suppression * StevesArmyConfig.getSuppressionThresholdRelief();
            } else {
                scale *= 1.0f + suppression * StevesArmyConfig.getSuppressionThresholdTighten();
            }
        }

        if (ineffectiveBurstStreak > 0) {
            scale *= 1.0f + ineffectiveBurstStreak * StevesArmyConfig.getFiringMissStreakStep();
        }

        return scale;
    }

    private float getDisciplineGapMultiplier() {
        return switch (soldier.getFireDiscipline()) {
            case CONSERVE -> 1.4f;
            case SUPPRESSIVE -> 0.7f;
            default -> 1.0f;
        };
    }

    private int rollDirectRecoveryTicks(int baseTicks) {
        if (!dynamicFiringEnabled() || baseTicks <= 0) return baseTicks;
        float bias = gapBias() * getDisciplineGapMultiplier();
        if (ineffectiveBurstStreak > 0) {
            bias *= 1.0f + ineffectiveBurstStreak * StevesArmyConfig.getFiringMissStreakRecoveryScale();
        }
        return FireControl.rollRecoveryTicks(baseTicks, bias, soldier.getRandom());
    }

    /** Suppressed gunners hose: team pressure shortens the pause after duty bursts. */
    private int rollSuppressionPauseTicks(int baseTicks, float hoseFactor) {
        if (!dynamicFiringEnabled() || baseTicks <= 0) return baseTicks;
        float bias = gapBias() * getDisciplineGapMultiplier() / Math.max(hoseFactor, 1.0f);
        return FireControl.rollRecoveryTicks(baseTicks, bias, soldier.getRandom());
    }

    /**
     * Judge the previous direct burst: if the target is untouched the streak
     * ratchets (harder gate, longer next recovery); damage or death resets it.
     * Called when the next burst begins — the inter-burst gap doubles as the
     * impact window for the previous burst's rounds.
     */
    private void evaluatePreviousBurstEffect() {
        if (target == null) {
            burstStartTargetHealth = -1.0f;
            return;
        }
        if (dynamicFiringEnabled() && burstStartTargetHealth >= 0.0f) {
            boolean ineffective = !target.isDeadOrDying()
                && target.getHealth() >= burstStartTargetHealth - 0.1f;
            int cap = StevesArmyConfig.getFiringMissStreakCap();
            if (ineffective && ineffectiveBurstStreak < cap) {
                ineffectiveBurstStreak++;
                if (isFiringDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[FiringGate] soldier={} burst ineffective, streak={}/{}",
                        soldier.getId(), ineffectiveBurstStreak, cap);
                }
            } else if (!ineffective && ineffectiveBurstStreak > 0) {
                if (isFiringDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[FiringGate] soldier={} burst landed, streak reset",
                        soldier.getId());
                }
                ineffectiveBurstStreak = 0;
            }
        }
        burstStartTargetHealth = target.isDeadOrDying() ? -1.0f : target.getHealth();
    }

    private void beginDirectFireBurst(DirectFireWeaponProfile profile, int shotLimit, boolean mobile,
                                      float continuationThreshold) {
        evaluatePreviousBurstEffect();
        directBurstProfile = profile;
        directBurstShotLimit = shotLimit;
        directBurstContinuationThreshold = continuationThreshold;
        directBurstShotsFired = 0;
        directBurstActive = shotLimit > 1;
        if (isDebugLogging()) {
            StevesArmyMod.LOGGER.info("[DirectBurst] soldier={} start profile={} shots={} mobile={} continuation={}",
                soldier.getId(), profile, shotLimit, mobile, String.format("%.3f", continuationThreshold));
        }
        if (isFiringDebugLogging()) {
            StevesArmyMod.LOGGER.info(
                "[FiringGate] soldier={} burst start profile={} rolledShots={} effSup={} streak={} aggression={} steadiness={}",
                soldier.getId(), profile, shotLimit,
                String.format("%.2f", effectiveSuppressionLevel()), ineffectiveBurstStreak,
                firePersonality != null ? String.format("%.2f", firePersonality.aggression()) : "-",
                firePersonality != null ? String.format("%.2f", firePersonality.steadiness()) : "-");
        }
    }

    private void finishDirectFireBurst(String reason) {
        DirectFireWeaponProfile profile = directBurstProfile != null
            ? directBurstProfile : getDirectFireWeaponProfile();
        int shotsFired = directBurstShotsFired;
        directBurstActive = false;
        directBurstShotsFired = 0;
        directBurstCooldownTicks = rollDirectRecoveryTicks(directRecoveryTicksFor(profile));
        if (directBurstImperfect) {
            directBurstCooldownTicks = Math.max(IMPERFECT_FIRE_RECOVERY_TICKS, directBurstCooldownTicks);
        }
        directRecoveryImperfect = directBurstImperfect;
        directBurstImperfect = false;
        directBurstProfile = null;
        directBurstShotLimit = 0;
        directBurstContinuationThreshold = 0.0f;
        if (isDebugLogging()) {
            StevesArmyMod.LOGGER.info("[DirectBurst] soldier={} end reason={} shots={} profile={} recovery={}",
                soldier.getId(), reason, shotsFired, profile, directBurstCooldownTicks);
        }
    }

    private void resetDirectFireBurst() {
        if (isDebugLogging() && (directBurstActive || directBurstCooldownTicks > 0)) {
            StevesArmyMod.LOGGER.info("[DirectBurst] soldier={} cancelled reason=state_change shots={} cooldown={}",
                soldier.getId(), directBurstShotsFired, directBurstCooldownTicks);
        }
        // Interrupted imperfect fire still owes its recovery pause. Preserve
        // an existing pause across repeated lane/readiness failures.
        int imperfectRecovery = directBurstImperfect
            ? Math.max(IMPERFECT_FIRE_RECOVERY_TICKS,
                rollDirectRecoveryTicks(directRecoveryTicksFor(directBurstProfile != null
                    ? directBurstProfile : getDirectFireWeaponProfile())))
            : directRecoveryImperfect ? directBurstCooldownTicks : 0;
        directRecoveryImperfect = imperfectRecovery > 0;
        directBurstImperfect = false;
        directBurstActive = false;
        directBurstShotsFired = 0;
        directBurstCooldownTicks = imperfectRecovery;
        directBurstProfile = null;
        directBurstShotLimit = 0;
        directBurstContinuationThreshold = 0.0f;
    }

    private void resetBurstState() {
        burstShotsFired = 0;
        burstCooldownTicks = 0;
        ticksSinceLastBurstShot = 0;
        burstWaitingForBolt = false;
        suppressionBurstTarget = 0;
        suppressionCadenceTicks = 1;
    }

    private boolean quickBurstWasActive;

    private void trySuppressireFire(@javax.annotation.Nullable Vec3 visibleContactAimPoint) {
        Vec3 quickAim = soldier.getPeekController().getQuickPeekAim();
        boolean quickFire = quickAim != null;
        if (quickFire && soldier.getPeekController().getQuickPeekShots() >= 3) return;
        if (quickFire != quickBurstWasActive) {
            resetBurstState();
            quickBurstWasActive = quickFire;
        }
        SoldierMovementController movement = soldier.getMovementController();
        if (movement.isStationaryFireConstrained()) {
            GunIntegration.aim(soldier, false);
            wasAiming = false;
            return;
        }
        boolean movingFire = movement.hasMovingIntent();
        if (movingFire) updateAimQuality(null);
        if (movingFire) {
            if (movement.fireMode() != SoldierMovementController.FireMode.ADS
                || target == null || !target.isAlive()
                || (!quickFire && (visibleContactAimPoint == null
                    || !TargetAcquisition.hasLineOfSight(soldier, target)))
                || !movement.canFireAt(quickFire ? quickAim : visibleContactAimPoint)) {
                GunIntegration.aim(soldier, false);
                wasAiming = false;
                return;
            }
        }
        if (!quickFire && soldier.getCoverBehaviorManager().isInCover()
            && soldier.getPeekController().getState() != PeekController.State.EXPOSED) {
            return;
        }

        if (!quickFire) {
            if (!attackSuppressionActive && suppressionTargetPos == null && pendingSuppressionThreat != null) {
                SquadThreatIntel intel = getSquadIntel();
                if (intel == null || !isLiveSuppressionThreat(intel, pendingSuppressionThreat.threatEntityId)
                    || pendingSuppressionThreat.lastKnownPosition == null) {
                    pendingSuppressionThreat = null;
                    return;
                }

                int maxSuppressors = getMaxSuppressorsForThreat(pendingSuppressionThreat);
                if (!intel.tryClaimThreatSuppression(pendingSuppressionThreat.threatEntityId, soldier.getUUID(),
                    soldier.level().getGameTime(), maxSuppressors)) {
                    if (isSuppressionDebugLogging()) {
                        StevesArmyMod.LOGGER.info("[Suppression] Soldier {} failed to claim threat {} (all suppression slots filled)",
                            soldier.getId(), pendingSuppressionThreat.threatEntityId.toString().substring(0, 8));
                    }
                    pendingSuppressionThreat = null;
                    return;
                }

                if (isSuppressionDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[Suppression] Soldier {} claimed threat {} at {}, starting suppression",
                        soldier.getId(), pendingSuppressionThreat.threatEntityId.toString().substring(0, 8),
                        pendingSuppressionThreat.lastKnownPosition);
                }

                suppressionTargetUUID = pendingSuppressionThreat.threatEntityId;
                suppressionTargetPos = pendingSuppressionThreat.lastKnownPosition;
                suppressionTargetAimPoint = pendingSuppressionThreat.lastVisibleAimPoint;
                suppressionLastSeenTick = pendingSuppressionThreat.lastSeenTime;
                // Dispersion slot: the first claimant holds the reported point,
                // the second brackets it off-axis so paired guns cover a
                // corridor instead of stacking the same spot.
                suppressionLaneSlot = Math.max(0, intel.getSuppressionCount(suppressionTargetUUID) - 1);
                suppressionLaneSide = ((soldier.getUUID().hashCode() ^ suppressionTargetUUID.hashCode()) & 1) == 0
                    ? 1.0F : -1.0F;
                suppressionPlanStartTick = soldier.tickCount;
                suppressionFirstShotTick = -1;
                pendingSuppressionThreat = null;
                burstShotsFired = 0;
                burstCooldownTicks = 0;
                ticksSinceLastBurstShot = 0;
                burstWaitingForBolt = false;
                suppressionRotation = machineGunnerPipeline && isMgSuppressionRoundRobinEnabled()
                    ? buildSuppressionRotation(intel, suppressionTargetUUID)
                    : List.of();
                suppressionRotationIndex = Math.max(0, suppressionRotation.indexOf(suppressionTargetUUID));
            }

            if (suppressionTargetPos == null) {
                return;
            }
        }

        SquadThreatIntel intel = getSquadIntel();
        AttackSuppressionContact attackContact = attackSuppressionActive
            ? attackSuppressionContacts.get(suppressionTargetUUID) : null;
        if (quickFire) {
            // A peek owns a fixed observation, independent of squad assignments.
        } else if (attackSuppressionActive) {
            if (!isAttackSuppressionOrderActive() || attackContact == null
                || soldier.position().distanceTo(attackContact.lastObservedBlock.getCenter()) > SUPPRESSION_MAX_RANGE) {
                cancelAllSuppression();
                return;
            }
            suppressionTargetPos = attackContact.lastObservedBlock;
            suppressionTargetAimPoint = attackContact.lastVisibleAim;
        } else {
            if (intel == null || suppressionTargetUUID == null
                || !isLiveSuppressionThreat(intel, suppressionTargetUUID)
                || !intel.hasSuppressionAssignment(suppressionTargetUUID, soldier.getUUID())) {
                cancelAllSuppression();
                return;
            }
            intel.updateSuppressionHeartbeat(suppressionTargetUUID, soldier.getUUID(), soldier.level().getGameTime());
        }

        // Live squad knowledge outranks the claim-time snapshot: aim point,
        // contact age, and expiry all follow the threat while anyone still
        // reports it. The claim-time anchor only bridges ticks where the live
        // point is blind from this eye, so a momentary angle change neither
        // yanks the aim off a workable lane nor cancels the plan.
        SquadThreatIntel.ThreatKnowledge suppressionKnowledge = !quickFire && !attackSuppressionActive && intel != null
            ? intel.getThreat(suppressionTargetUUID).orElse(null) : null;
        if (suppressionKnowledge != null) {
            if (suppressionKnowledge.lastKnownPosition != null) {
                suppressionTargetPos = suppressionKnowledge.lastKnownPosition;
            }
            suppressionLastSeenTick = suppressionKnowledge.lastSeenTime;
            Vec3 liveAim = suppressionKnowledge.lastVisibleAimPoint;
            if (liveAim != null && TargetAcquisition.hasNearLineOfSightToPosition(soldier, liveAim, SUPPRESSION_LOS_TOLERANCE)) {
                if (isSuppressionDebugLogging() && (suppressionTargetAimPoint == null
                    || suppressionTargetAimPoint.distanceToSqr(liveAim) > 0.25)) {
                    StevesArmyMod.LOGGER.info("[Suppression] Soldier {} aim follows live intel to {}",
                        soldier.getId(), liveAim);
                }
                suppressionTargetAimPoint = liveAim;
            }
        }

        long contactAge = quickFire || attackSuppressionActive ? 0
            : soldier.level().getGameTime() - suppressionLastSeenTick;
        boolean planExpired = (!attackSuppressionActive
            && (soldier.tickCount - suppressionPlanStartTick > FireControl.SUPPRESSION_PLAN_MAX_TICKS
                || contactAge > FireControl.SUPPRESSION_CONTACT_MAX_TICKS
                || (suppressionFirstShotTick >= 0
                    && soldier.tickCount - suppressionFirstShotTick > SUPPRESSION_ACTIVE_FIRE_TICKS)))
            || (suppressionFirstShotTick < 0
                && soldier.tickCount - suppressionPlanStartTick > SUPPRESSION_PREPARATION_TICKS);
        if (!quickFire && planExpired) {
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[Suppression] Soldier {} finished fire plan (contactAge={}ticks, active={}ticks)",
                    soldier.getId(), contactAge,
                    suppressionFirstShotTick >= 0 ? soldier.tickCount - suppressionFirstShotTick : 0);
            }
            cancelAllSuppression();
            return;
        }

        Vec3 targetPos = quickFire ? quickAim : attackSuppressionActive && attackContact != null ? getAttackSuppressionAim(attackContact)
            : visibleContactAimPoint != null ? visibleContactAimPoint
            : suppressionTargetAimPoint != null ? suppressionTargetAimPoint
            : Vec3.atCenterOf(suppressionTargetPos).add(0, 1.0, 0);
        if (attackSuppressionActive) attackSuppressionSelectedAim = targetPos;
        if (targetPos == null) {
            if (attackSuppressionActive && suppressionTargetUUID != null) {
                attackSuppressionBlockedUntilTick.put(suppressionTargetUUID,
                    soldier.tickCount + PING_AIM_POINT_REFRESH_TICKS);
            }
            cancelAllSuppression();
            return;
        }

        // Buttoning a vehicle: LOS to any block of the hull counts, and the
        // spray aims at a visible hull point rather than the masked gun optic.
        if (suppressionKnowledge != null && suppressionKnowledge.isHardTarget) {
            Vec3 hullSolution = ArmorThreatScanner.findFiringSolution(soldier, suppressionKnowledge);
            if (hullSolution == null) {
                if (isSuppressionDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[Suppression] Soldier {} has no hull LOS to vehicle {}, clearing assignment",
                        soldier.getId(), suppressionTargetPos);
                }
                cancelAllSuppression();
                return;
            }
            targetPos = hullSolution;
        } else if (!TargetAcquisition.hasNearLineOfSightToPosition(soldier, targetPos, SUPPRESSION_LOS_TOLERANCE)) {
            if (quickFire) return;
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[Suppression] Soldier {} has no LOS to suppression target at {}, clearing assignment",
                    soldier.getId(), suppressionTargetPos);
            }
            if (attackSuppressionActive && suppressionTargetUUID != null) {
                attackSuppressionBlockedUntilTick.put(suppressionTargetUUID,
                    soldier.tickCount + PING_AIM_POINT_REFRESH_TICKS);
            }
            cancelAllSuppression();
            return;
        }

        if (!(movingFire ? prepareForMovingFire(targetPos) : prepareToFire(targetPos, false))) {
            return;
        }
        
        if (!movingFire) {
            soldier.getLookControl().setLookAt(
                targetPos.x, targetPos.y, targetPos.z,
                30.0F, 30.0F
            );
        }
        
        GunIntegration.aim(soldier, true);
        wasAiming = true;
        
        float adsProgress = GunIntegration.getAimProgress(soldier);
        if (adsProgress < (movingFire ? MOBILE_FIRE_ADS_THRESHOLD : SUPPRESSION_ADS_THRESHOLD)) {
            if (isSuppressionDebugLogging() && soldier.tickCount % 20 == 0) {
                StevesArmyMod.LOGGER.info("[Suppression] Soldier {} waiting for ADS ({}%)", 
                    soldier.getId(), (int)(adsProgress * 100));
            }
            return;
        }
        
        if (burstCooldownTicks > 0) {
            burstCooldownTicks--;
            if (isSuppressionDebugLogging() && burstCooldownTicks % 20 == 0) {
                StevesArmyMod.LOGGER.info("[Suppression] Soldier {} burst cooldown: {} ticks remaining", 
                    soldier.getId(), burstCooldownTicks);
            }
            return;
        }
        
        if (burstWaitingForBolt && GunIntegration.isBolting(soldier)) {
            if (isSuppressionDebugLogging() && soldier.tickCount % 20 == 0) {
                StevesArmyMod.LOGGER.info("[Suppression] Soldier {} waiting for bolt", soldier.getId());
            }
            return;
        }
        burstWaitingForBolt = false;
        
        SuppressionWeaponProfile profile = getSuppressionWeaponProfile();
        // Pressure lengthens duty bursts and shortens their gaps: a suppressed
        // gunner hoses. The burst's shape rolls once, on its first shot.
        float hoseFactor = 1.0f + effectiveSuppressionLevel() * 0.5f;
        if (burstShotsFired == 0) {
            suppressionBurstTarget = dynamicFiringEnabled()
                ? FireControl.rollBurstShots(profile.burstShots, burstBias() * hoseFactor, soldier.getRandom())
                : profile.burstShots;
            suppressionCadenceTicks = dynamicFiringEnabled()
                ? Math.max(1, Math.round(getTicksBetweenBurstShots() * cadenceBias()))
                : getTicksBetweenBurstShots();
        }
        int burstTarget = quickFire ? 3 : suppressionBurstTarget;
        int ticksBetweenShots = suppressionCadenceTicks;
        if (burstShotsFired > 0 && burstShotsFired < burstTarget) {
            ticksSinceLastBurstShot++;
            if (ticksSinceLastBurstShot < ticksBetweenShots) {
                return;
            }
        }
        
        float aimInaccuracy = GunIntegration.getAimInaccuracy(soldier);
        Vec3 spreadPos;
        if (quickFire) {
            var aimPoint = new ExposureCalculator.AimPointResult(targetPos,
                ExposureCalculator.AimPointType.FALLBACK, true, true, 0.0);
            float[] sigma = directFireSpread(aimPoint);
            float[] deviation = AimAccuracyManager.sampleGaussianDeviation(aimQuality, sigma[0], sigma[1], soldier.level());
            Vec3 direction = targetPos.subtract(soldier.getEyePosition());
            float yaw = (float)Math.toDegrees(Math.atan2(-direction.x, direction.z));
            float pitch = (float)-Math.toDegrees(Math.atan2(direction.y, direction.horizontalDistance()));
            spreadPos = soldier.getEyePosition().add(Vec3.directionFromRotation(pitch + deviation[0], yaw + deviation[1])
                .scale(direction.length()));
        } else {
            spreadPos = calculateLastSeenSuppressionSpread(offsetSuppressorAnchor(targetPos), aimInaccuracy, contactAge);
        }
        boolean safeLane = movingFire
            ? FriendlyFireChecker.isSafeToShoot(soldier, spreadPos, aimQuality,
                AimAccuracyManager.getYawSigma(aimQuality), AimAccuracyManager.getPitchSigma(aimQuality),
                0.0f, 0.0f, true)
            : FriendlyFireChecker.isSafeToShoot(soldier, spreadPos, aimQuality);
        if (!safeLane) {
            return;
        }
        // Recheck the exact spread-adjusted lane immediately before firing. This
        // protects moving-fire prefire and remembered contacts from a stale
        // perception result after the body clears the opening.
        if (!ExposureCalculator.hasFreshBulletLaneTo(soldier.getEyePosition(), spreadPos, soldier)) {
            noteEngagementGate(EngagementGateReason.BULLET_PATH_BLOCKED);
            return;
        }
        GunIntegration.ShootResult result = GunIntegration.shootAtPosition(soldier, spreadPos);
        
        if (isSuppressionDebugLogging()) {
            if (attackSuppressionActive) {
                StevesArmyMod.LOGGER.info("[Suppression] MG {} ATTACK shot={} burst={}/{} contact={} aim={} contacts={} gen={} result={}",
                    soldier.getId(), result == GunIntegration.ShootResult.SUCCESS, burstShotsFired + 1,
                    burstTarget, suppressionTargetUUID.toString().substring(0, 8), targetPos,
                    attackSuppressionContacts.size(), attackSuppressionGeneration, result);
            } else {
                StevesArmyMod.LOGGER.info("[Suppression] Soldier {} SHOT burst {}/{} result={}",
                    soldier.getId(), burstShotsFired + 1, burstTarget, result);
            }
        }
        
        switch (result) {
            case SUCCESS -> {
                aimWaitTicks = 0;
                if (movingFire) {
                    // Moving and stationary suppression both count as firing.
                    recordMobileShot(SoldierMovementController.FireMode.ADS, aimQuality,
                        AimAccuracyManager.getYawSigma(aimQuality), AimAccuracyManager.getPitchSigma(aimQuality),
                        0.0f, 0.0f, adsProgress, "visible-suppression");
                }
                lastTacticalReloadShotTime = soldier.level().getGameTime();
                if (suppressionFirstShotTick < 0) {
                    suppressionFirstShotTick = soldier.tickCount;
                }
                burstShotsFired++;
                if (quickFire) {
                    soldier.getPeekController().onQuickPeekShot();
                    soldier.getEngagementTelemetry().noteShot(soldier.tickCount);
                    soldier.getEngagementTelemetry().noteGate(EngagementGateReason.NONE, soldier.tickCount);
                }
                ticksSinceLastBurstShot = 0;
                
                if (soldier.getCoverBehaviorManager().isInCover()) {
                    soldier.getCoverBehaviorManager().onPeekShot();
                }
                float[] recoil = AimAccuracyManager.getGunRecoil(soldier);
                float recoilMagnitude = Math.abs(recoil[0]) + Math.abs(recoil[1]);
                aimQuality = Math.max(0.0f, aimQuality - recoilMagnitude
                    * StevesArmyConfig.getAimQualityRecoilScale()
                    * stanceRecoilLossMultiplier());
                
                if (burstShotsFired >= burstTarget) {
                    int pauseTicks = rollSuppressionPauseTicks(profile.pauseTicks, hoseFactor);
                    if (isSuppressionDebugLogging()) {
                        StevesArmyMod.LOGGER.info("[Suppression] Soldier {} burst complete, starting cooldown ({} ticks)",
                            soldier.getId(), pauseTicks);
                    }
                    burstCooldownTicks = pauseTicks;
                    burstShotsFired = 0;
                    // Round-robin MGs point the next burst at a different
                    // last-seen position; direct-fire preemption outranks it.
                    if (attackSuppressionActive) {
                        advanceAttackSuppressionAim();
                    } else if (visibleContactAimPoint == null) {
                        advanceMgSuppressionRotation();
                    }
                }
            }
            case NEED_BOLT -> {
                GunIntegration.bolt(soldier);
                burstWaitingForBolt = true;
                if (isSuppressionDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[Suppression] Soldier {} needs bolt", soldier.getId());
                }
            }
            case NO_AMMO -> {
                if (isSuppressionDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[Suppression] Soldier {} out of ammo, reloading", soldier.getId());
                }
                requestReload(false);
                return;
            }
            case IS_RELOADING, IS_BOLTING, IS_DRAWING -> {}
            case NOT_DRAWN -> GunIntegration.draw(soldier);
            default -> {}
        }
        
    }

    /**
     * Paired suppressors must not stack on one spot: the first claimant holds
     * the reported point, later slots shift their anchor off the lateral axis
     * so together the guns cover a movement corridor. The side is stable per
     * (soldier, threat) pair.
     */
    private Vec3 offsetSuppressorAnchor(Vec3 anchor) {
        if (suppressionLaneSlot <= 0) return anchor;
        Vec3 toTarget = anchor.subtract(soldier.getEyePosition());
        Vec3 lateral = new Vec3(-toTarget.z, 0.0, toTarget.x);
        if (lateral.lengthSqr() < 1.0e-4) return anchor;
        return anchor.add(lateral.normalize()
            .scale(suppressionLaneSide * (0.5 + 0.75 * suppressionLaneSlot)));
    }

    /**
     * Candidate order for the next suppression target. Default: highest
     * reported accuracy first. Round-robin machine gunners instead prefer
     * threats nobody holds a suppression claim on, then threats whose own
     * tracker does not (or cannot) read as pinned, then accuracy — spreading
     * fire beats stacking it on one contact.
     */
    private Comparator<SquadThreatIntel.ThreatKnowledge> suppressionCandidateOrder(SquadThreatIntel intel) {
        Comparator<SquadThreatIntel.ThreatKnowledge> order =
            Comparator.comparingDouble(threat -> -threat.accuracy);
        if (machineGunnerPipeline && isMgSuppressionRoundRobinEnabled()) {
            order = Comparator
                .comparingInt((SquadThreatIntel.ThreatKnowledge threat) -> threat.isSuppressed ? 1 : 0)
                .thenComparingInt(threat -> isThreatPinned(threat) ? 1 : 0)
                .thenComparingDouble(threat -> -threat.accuracy);
        }
        return order;
    }

    /** True when the threat entity is one of our soldiers and its tracker reads pinned. */
    private boolean isThreatPinned(SquadThreatIntel.ThreatKnowledge threat) {
        if (soldier.level() instanceof ServerLevel serverLevel
            && serverLevel.getEntity(threat.threatEntityId) instanceof SoldierEntity enemySoldier) {
            return enemySoldier.getCoverBehaviorManager().getSuppressionLevel()
                >= com.stevesarmy.combat.cover.SuppressionTracker.PINNED_THRESHOLD;
        }
        return false;
    }

    private boolean isMgSuppressionRoundRobinEnabled() {
        return StevesArmyConfig.isMgSuppressionRoundRobinEnabled();
    }

    /**
     * The round-robin rotation for a fresh suppression plan: prioritized
     * known threats capped at the configured size, with the claimed threat
     * always a member so the index walk stays total.
     */
    private List<UUID> buildSuppressionRotation(SquadThreatIntel intel, UUID currentTarget) {
        List<UUID> rotation = new ArrayList<>();
        intel.getAllThreats().stream()
            .filter(threat -> threat.isAlive)
            .filter(threat -> !threat.isHardTarget)
            .filter(threat -> threat.lastKnownPosition != null)
            .filter(threat -> !intel.isThreatStale(threat.threatEntityId, soldier.level().getGameTime()))
            .sorted(suppressionCandidateOrder(intel))
            .limit(StevesArmyConfig.getMgSuppressionRotationSize())
            .map(threat -> threat.threatEntityId)
            .forEach(rotation::add);
        if (!rotation.contains(currentTarget)) {
            rotation.add(0, currentTarget);
            while (rotation.size() > StevesArmyConfig.getMgSuppressionRotationSize()) {
                rotation.remove(rotation.size() - 1);
            }
        }
        return List.copyOf(rotation);
    }

    /**
     * Round-robin advance: once a burst completes, the machine gunner walks
     * the rotation for the next threat that is still fresh, LOS-reachable
     * from this eye, and claimable — the next burst lands on a different
     * last-seen position instead of stacking on one contact. The claim moves
     * with the aim so squad bookkeeping mirrors who is firing at whom; a
     * claim lost to a paired gunner simply skips to the next entry. Finding
     * no candidate keeps the current target — rotation never cancels a
     * working plan.
     */
    private void advanceMgSuppressionRotation() {
        if (suppressionRotation.size() < 2 || suppressionTargetUUID == null) {
            return;
        }
        SquadThreatIntel intel = getSquadIntel();
        if (intel == null) {
            return;
        }
        UUID current = suppressionTargetUUID;
        long now = soldier.level().getGameTime();
        long maxContactAge = FireControl.SUPPRESSION_CONTACT_MAX_TICKS / 2;
        for (int step = 1; step <= suppressionRotation.size(); step++) {
            int candidateIndex = Math.floorMod(suppressionRotationIndex + step, suppressionRotation.size());
            UUID candidate = suppressionRotation.get(candidateIndex);
            if (candidate.equals(current)) {
                continue;
            }
            SquadThreatIntel.ThreatKnowledge knowledge = intel.getThreat(candidate).orElse(null);
            if (knowledge == null || !knowledge.isAlive || knowledge.isHardTarget
                || knowledge.lastKnownPosition == null
                || intel.isThreatStale(candidate, now)
                || now - knowledge.lastSeenTime > maxContactAge
                || soldier.position().distanceTo(knowledge.lastKnownPosition.getCenter()) > SUPPRESSION_MAX_RANGE) {
                continue;
            }
            Vec3 candidateAim = knowledge.lastVisibleAimPoint != null
                ? knowledge.lastVisibleAimPoint
                : Vec3.atCenterOf(knowledge.lastKnownPosition).add(0, 1.0, 0);
            if (!TargetAcquisition.hasNearLineOfSightToPosition(soldier, candidateAim, SUPPRESSION_LOS_TOLERANCE)) {
                continue;
            }
            if (!intel.tryClaimThreatSuppression(candidate, soldier.getUUID(), now,
                getMaxSuppressorsForThreat(knowledge))) {
                continue;
            }
            intel.releaseThreatSuppression(current, soldier.getUUID());
            suppressionTargetUUID = candidate;
            suppressionTargetPos = knowledge.lastKnownPosition;
            suppressionTargetAimPoint = knowledge.lastVisibleAimPoint;
            suppressionLastSeenTick = knowledge.lastSeenTime;
            suppressionLaneSlot = Math.max(0, intel.getSuppressionCount(candidate) - 1);
            suppressionLaneSide = ((soldier.getUUID().hashCode() ^ candidate.hashCode()) & 1) == 0
                ? 1.0F : -1.0F;
            suppressionRotationIndex = candidateIndex;
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[Suppression] Soldier {} round-robin rotates to threat {} (slot {}/{})",
                    soldier.getId(), candidate.toString().substring(0, 8),
                    candidateIndex + 1, suppressionRotation.size());
            }
            return;
        }
    }

    private SuppressionWeaponProfile getSuppressionWeaponProfile() {
        if (GunIntegration.isManualBolt(soldier)) return SuppressionWeaponProfile.BOLT;
        if (GunIntegration.isSuppressiveMachineGun(soldier)) return SuppressionWeaponProfile.MACHINE_GUN;

        String tabType = GunIntegration.getGunTabType(soldier);
        if ("smg".equals(tabType)) return SuppressionWeaponProfile.SMG;
        if (GunIntegration.getRPM(soldier) >= 600 && GunIntegration.getMagazineSize(soldier) >= 25) {
            return SuppressionWeaponProfile.AUTO_RIFLE;
        }
        return SuppressionWeaponProfile.RIFLE;
    }

    private Vec3 calculateLastSeenSuppressionSpread(Vec3 targetPos, float aimInaccuracy, long contactAge) {
        double distance = soldier.position().distanceTo(targetPos);
        double gunSpread = distance * Math.tan(Math.toRadians(aimInaccuracy));
        return FireControl.calculateLastSeenSuppressionSpread(soldier.position(), soldier.getEyePosition(),
            soldier.level().random, targetPos, gunSpread, contactAge);
    }

    public void onTargetKilledByTeammate(UUID killedThreatId) {
        attackSuppressionContacts.remove(killedThreatId);
        attackSuppressionBlockedUntilTick.remove(killedThreatId);
        boolean suppressionMatches = (suppressionTargetUUID != null && suppressionTargetUUID.equals(killedThreatId))
            || (pendingSuppressionThreat != null && pendingSuppressionThreat.threatEntityId.equals(killedThreatId));
        if (suppressionMatches) {
            cancelAllSuppression();
        }
        
        SquadThreatIntel intel = getSquadIntel();
        if (intel != null) {
            intel.markThreatDead(killedThreatId);
        }
        
        if (target != null && target.getUUID().equals(killedThreatId)) {
            target = null;
            soldier.setTarget(null);
        }
    }

    private boolean isLiveSuppressionThreat(SquadThreatIntel intel, UUID threatId) {
        return intel.getThreat(threatId).map(threat -> threat.isAlive).orElse(false);
    }

    public boolean isSuppressing() {
        return isSuppressing;
    }

    public BlockPos getSuppressireTargetPos() {
        return suppressionTargetPos;
    }

    public int getAttackSuppressionDebugContactCount() {
        return attackSuppressionContacts.size();
    }

    public Vec3 getAttackSuppressionDebugAimPoint() {
        return attackSuppressionSelectedAim;
    }

    public boolean canShootPrimaryTarget() {
        if (target == null || !target.isAlive()) return false;
        if (!TargetAcquisition.hasLineOfSight(soldier, target)) return false;
        
        ExposureCalculator.AimPointResult aimPoint = getOrComputeAimPoint();
        return aimPoint != null && aimPoint.canShoot();
    }
    
    private boolean shouldSuppressPingTarget() {
        if (!soldier.hasValidPingSuppressPos()) {
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} shouldSuppressPingTarget: no valid ping suppress pos", soldier.getId());
            }
            return false;
        }

        if (GunIntegration.isReloading(soldier) ||
            GunIntegration.isBolting(soldier) ||
            GunIntegration.isDrawing(soldier)) {
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} shouldSuppressPingTarget: busy (reloading/bolting/drawing)", soldier.getId());
            }
            return false;
        }
        
        if (!GunIntegration.isAnyGunLoaded() || !GunIntegration.hasGun(soldier)) {
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} shouldSuppressPingTarget: no gun", soldier.getId());
            }
            return false;
        }
        
        boolean heavy = heavyPingPhaseActive();
        // One-rocket doctrine fail-safe: the heavy phase is over but the
        // soldier is still holding the launcher (no sidearm or other normal
        // gun to fall back to). Hold fire rather than burning rockets through
        // the light-suppression path.
        if (!heavy && ArmorRoleManager.isAtGunStack(soldier.getMainHandItem())) {
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} shouldSuppressPingTarget: rocket spent, launcher still held, no fallback gun",
                    soldier.getId());
            }
            return false;
        }
        // Heavy fire burns launcher rounds, so its availability is measured in
        // rockets rather than the soldier's total ammo pool.
        int totalAmmo = heavy ? SoldierWeaponSelector.countLauncherAmmo(soldier) : getTotalAmmo();
        if (totalAmmo == 0) {
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} shouldSuppressPingTarget: no {}",
                    soldier.getId(), heavy ? "launcher ammo" : "ammo");
            }
            return false;
        }

        BlockPos suppressPos = soldier.getPingSuppressPos();
        double dist = soldier.position().distanceTo(suppressPos.getCenter());
        if (dist > SUPPRESSION_MAX_RANGE) {
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} shouldSuppressPingTarget: too far (dist={}, max={})",
                    soldier.getId(), String.format("%.1f", dist), SUPPRESSION_MAX_RANGE);
            }
            return false;
        }

        if (soldier.getSuppressionAimPoints().isEmpty()
            || soldier.tickCount >= aimPointsRefreshTick) {
            CoverFinder finder = new CoverFinder(soldier.level());
            List<Vec3> aimPoints = heavy
                ? finder.findHeavySuppressionAimPoints(soldier, suppressPos, SoldierEntity.SUPPRESSION_ZONE_RADIUS)
                : finder.findSuppressionAimPoints(soldier, suppressPos, SoldierEntity.SUPPRESSION_ZONE_RADIUS);
            soldier.setSuppressionAimPoints(aimPoints);
            aimPointsRefreshTick = soldier.tickCount + PING_AIM_POINT_REFRESH_TICKS;
            
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} found {} aim points in zone",
                    soldier.getId(), aimPoints.size());
            }
            
            if (aimPoints.isEmpty()) {
                if (isSuppressionDebugLogging()) {
                    StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} no aim points found, will use horizontal spread fallback",
                        soldier.getId());
                }
                return true;
            }
        }
        
        return true;
    }
    
    public int getTotalAmmo() {
        if (soldier.hasInfiniteReserveAmmo()) {
            return 1_000_000;
        }

        com.stevesarmy.inventory.SoldierInventory inv = soldier.getSoldierInventory();
        if (inv == null) {
            return GunIntegration.getCurrentAmmo(soldier);
        }

        List<ItemStack> guns = new ArrayList<>();
        ItemStack mainHand = inv.getItem(com.stevesarmy.inventory.SoldierInventory.SLOT_MAIN_HAND);
        if (GunIntegration.isGun(mainHand)) {
            guns.add(mainHand);
        }
        for (int i = com.stevesarmy.inventory.SoldierInventory.SLOT_GENERAL_START;
             i < com.stevesarmy.inventory.SoldierInventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && GunIntegration.isGun(stack)) {
                guns.add(stack);
            }
        }
        if (guns.isEmpty()) {
            return GunIntegration.isSimGunsArmed() ? GunIntegration.getCurrentAmmo(soldier) : 0;
        }

        int total = 0;
        for (ItemStack gun : guns) {
            total += GunIntegration.getCurrentAmmo(gun);
        }
        for (int i = com.stevesarmy.inventory.SoldierInventory.SLOT_GENERAL_START;
             i < com.stevesarmy.inventory.SoldierInventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            for (ItemStack gun : guns) {
                if (GunIntegration.getAmmoCountForGun(gun, stack) > 0) {
                    total += stack.getCount();
                    break;
                }
            }
        }

        return total;
    }
    
    private void trySuppressPingFire() {
        if (soldier.getMovementController().hasMovingIntent()
            || soldier.getMovementController().isStationaryFireConstrained()) {
            GunIntegration.aim(soldier, false);
            wasAiming = false;
            return;
        }
        SuppressionOrderController order = soldier.getSuppressionOrder();
        if (!order.isActive()) {
            isPingSuppressing = false;
            pingSuppressRemainingTicks = 0;
            return;
        }
        CoverGoalController.SuppressionPositionStatus repositionStatus = soldier.getCoverTacticalGoal()
            .getSuppressionPositionStatus(order.getGeneration());
        if (repositionStatus == CoverGoalController.SuppressionPositionStatus.SEARCHING
            || repositionStatus == CoverGoalController.SuppressionPositionStatus.MOVING) {
            order.setPaused(SuppressionOrderController.BlockReason.MOVING);
            return;
        }
        if (!isPingSuppressing) {
            isPingSuppressing = true;
            pingSuppressDurationTicks = SuppressionOrderController.FIRING_BUDGET_TICKS;
            pingSuppressRemainingTicks = SuppressionOrderController.FIRING_BUDGET_TICKS;
            pingSuppressHeavy = heavyPingPhaseActive();
            lastHeavyFlipTick = soldier.tickCount;
            pingSuppressionTarget = null;
            pingSuppressionSweepEnd = null;
            pingSuppressionShotTarget = null;
            pingNoTargetTicks = 0;
            pingPeekStuckTicks = 0;
            resetBurstState();

            boolean isMG = GunIntegration.isMachineGun(soldier);
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} starting suppression at {} (duration={}s, isMG={}, isHeavy={})",
                    soldier.getId(), soldier.getPingSuppressPos(), pingSuppressDurationTicks / 20.0, isMG, pingSuppressHeavy);
            }
        }

        // If launcher availability flips mid-burst (rockets ran dry, hunter
        // died), re-discover aim points under the new mode: block faces for
        // heavy fire, peek openings for the held normal gun. The flip is
        // hysteresis-gated so hovering on the ammo or range boundary cannot
        // wipe the cache every other tick.
        boolean heavyNow = heavyPingPhaseActive();
        if (heavyNow != pingSuppressHeavy
            && soldier.tickCount - lastHeavyFlipTick >= PING_HEAVY_FLIP_HYSTERESIS_TICKS) {
            pingSuppressHeavy = heavyNow;
            lastHeavyFlipTick = soldier.tickCount;
            lastHeavyLaneSource = null;
            soldier.setSuppressionAimPoints(java.util.List.of());
        }

        CoverBehaviorManager coverManager = soldier.getCoverBehaviorManager();
        if (coverManager.isInCover()) {
            PeekController.State peekState = soldier.getPeekController().getState();
            if (peekState != PeekController.State.EXPOSED) {
                if (isSuppressionDebugLogging() && pingSuppressRemainingTicks % 20 == 0) {
                    StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} waiting for peek (state={}, remaining={}ticks)",
                        soldier.getId(), peekState, pingSuppressRemainingTicks);
                }
                // Peek transit (sliding out, sliding back) is the cycle working,
                // not blindness — a full-cover slide alone outlasts the 20t
                // no-lane threshold and would relocate on every routine peek.
                // Only sustained idle-in-cover (never exposing) counts, on its
                // own long budget; PeekController's non-peekable watchdog sits
                // underneath it as the faster escape for dead covers.
                if (peekState == PeekController.State.MOVING_TO_PEEK
                    || peekState == PeekController.State.RETURNING_TO_COVER) {
                    pingPeekStuckTicks = 0;
                } else if (++pingPeekStuckTicks >= PING_PEEK_STUCK_REPOSITION_TICKS) {
                    pingPeekStuckTicks = 0;
                    requestPingEmergencyRelocation("peek stuck in " + peekState);
                }
                order.setPreparing(SuppressionOrderController.BlockReason.POSTURE);
                return;
            }
        }
        pingPeekStuckTicks = 0;
        
        if (pingSuppressionTarget == null || !hasLaneForCurrentMode(pingSuppressionTarget)) {
            // A null discovery result covers two very different states: a fresh
            // probe that just failed against live geometry, and the coasting
            // ticks while the cleared aim cache waits out its rediscovery
            // cooldown (which equals the no-lane threshold). Only the fresh
            // failure proves the position is blind; coasting must not count,
            // or every stale-cache refresh after arriving would relocate.
            boolean rediscoveryReady = soldier.tickCount >= aimRediscoveryCooldownTick;
            pingSuppressionTarget = getClearPingSuppressionTarget();
            pingSuppressionSweepEnd = null;
            pingSuppressionShotTarget = null;
            if (pingSuppressionTarget == null) {
                order.setPreparing(SuppressionOrderController.BlockReason.GEOMETRY);
                if (rediscoveryReady) {
                    tickPingSuppressReposition(PING_AIM_REDISCOVERY_COOLDOWN_TICKS);
                }
                return;
            }
        }
        pingNoTargetTicks = 0;
        // The ping burst's shape rolls once, on its first shot.
        if (burstShotsFired == 0) {
            suppressionBurstTarget = dynamicFiringEnabled()
                ? FireControl.rollBurstShots(getBurstTarget(), burstBias(), soldier.getRandom())
                : getBurstTarget();
            suppressionCadenceTicks = dynamicFiringEnabled()
                ? Math.max(1, Math.round(getTicksBetweenBurstShots() * cadenceBias()))
                : getTicksBetweenBurstShots();
        }
        int burstTarget = suppressionBurstTarget;
        Vec3 finalTarget = getPingSuppressionBurstTarget(burstTarget);
        
        soldier.getLookControl().setLookAt(finalTarget.x, finalTarget.y, finalTarget.z, 30.0F, 30.0F);
        GunIntegration.aim(soldier, true);
        wasAiming = true;
        
        float adsProgress = GunIntegration.getAimProgress(soldier);
        if (adsProgress < SUPPRESSION_ADS_THRESHOLD) {
            order.setPreparing(SuppressionOrderController.BlockReason.POSTURE);
            return;
        }

        order.consumeFiringTick();
        if (!order.isActive()) return;
        
        if (burstCooldownTicks > 0) {
            burstCooldownTicks--;
            if (burstCooldownTicks == 0) {
                pingSuppressionTarget = null;
                pingSuppressionSweepEnd = null;
                pingSuppressionShotTarget = null;
            }
            return;
        }
        
        if (burstWaitingForBolt && GunIntegration.isBolting(soldier)) {
            return;
        }
        burstWaitingForBolt = false;
        
        int ticksBetweenShots = getTicksBetweenBurstShots();
        if (burstShotsFired > 0 && burstShotsFired < burstTarget) {
            ticksSinceLastBurstShot++;
            if (ticksSinceLastBurstShot < ticksBetweenShots) {
                return;
            }
        }

        if (!prepareToFire(finalTarget, false)) {
            order.setPreparing(SuppressionOrderController.BlockReason.POSTURE);
            return;
        }

        VisibilityRay.Result lane = VisibilityRay.traceFreshIgnoringSmoke(
            soldier.level(), soldier.getEyePosition(), finalTarget, soldier);
        if (pingSuppressHeavy ? !lane.clear() : !lane.hasContact()) {
            order.setPreparing(SuppressionOrderController.BlockReason.GEOMETRY);
            return;
        }

        if (!FriendlyFireChecker.isSafeToShoot(soldier, finalTarget, aimQuality)) {
            order.setPreparing(SuppressionOrderController.BlockReason.ALLY);
            return;
        }

        GunIntegration.ShootResult result = GunIntegration.shootAtPosition(soldier, finalTarget);
        
        if (isSuppressionDebugLogging()) {
            StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} SHOT burst {}/{} result={}",
                soldier.getId(), burstShotsFired + 1, burstTarget, result);
        }
        
        switch (result) {
            case SUCCESS -> {
                aimWaitTicks = 0;
                lastTacticalReloadShotTime = soldier.level().getGameTime();
                order.recordShot();
                burstShotsFired++;
                ticksSinceLastBurstShot = 0;
                pingSuppressionShotTarget = null;

                if (soldier.getCoverBehaviorManager().isInCover()) {
                    soldier.getCoverBehaviorManager().onPeekShot();
                }
                float[] recoil = AimAccuracyManager.getGunRecoil(soldier);
                float recoilMagnitude = Math.abs(recoil[0]) + Math.abs(recoil[1]);
                aimQuality = Math.max(0.0f, aimQuality - recoilMagnitude
                    * StevesArmyConfig.getAimQualityRecoilScale()
                    * stanceRecoilLossMultiplier());

                if (pingSuppressHeavy) {
                    // One rocket per ping: spend the heavy phase now and hand
                    // the order to the sidearm. The flip bypasses the 20t
                    // hysteresis on purpose — waiting it out would let this
                    // burst's cadence fire a second rocket. Wiping the aim
                    // cache and burst state rolls a fresh sidearm burst
                    // against the light-mode (peek opening) lanes.
                    pingHeavyRocketSpent = true;
                    pingSuppressHeavy = false;
                    lastHeavyFlipTick = soldier.tickCount;
                    lastHeavyLaneSource = null;
                    soldier.setSuppressionAimPoints(java.util.List.of());
                    resetBurstState();
                    if (isSuppressionDebugLogging()) {
                        StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} rocket spent, switching to sidearm suppression",
                            soldier.getId());
                    }
                } else if (burstShotsFired >= burstTarget) {
                    int cooldownTicks = (int) (getBurstIntervalSeconds() * 20);
                    if (dynamicFiringEnabled()) {
                        cooldownTicks = FireControl.rollRecoveryTicks(cooldownTicks,
                            gapBias() * getDisciplineGapMultiplier(), soldier.getRandom());
                    }
                    if (isSuppressionDebugLogging()) {
                        StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} burst complete, starting cooldown ({} ticks)",
                            soldier.getId(), cooldownTicks);
                    }
                    burstCooldownTicks = cooldownTicks;
                    burstShotsFired = 0;
                }
            }
            case NEED_BOLT -> {
                GunIntegration.bolt(soldier);
                burstWaitingForBolt = true;
            }
            case NO_AMMO -> {
                requestReload(false);
            }
            case IS_RELOADING, IS_BOLTING, IS_DRAWING -> {}
            case NOT_DRAWN -> GunIntegration.draw(soldier);
            default -> {}
        }
    }

    /**
     * Accumulates no-lane blindness toward the relocation threshold. Callers
     * pass an increment sized to their failure semantics: the discovery gate
     * adds a full rediscovery window per genuine probe failure (its coasting
     * ticks never call here), so one real failure saturates the threshold.
     */
    private void tickPingSuppressReposition(int increment) {
        pingNoTargetTicks += increment;
        if (pingNoTargetTicks < PING_NO_TARGET_REPOSITION_TICKS) return;
        pingNoTargetTicks = 0;
        requestPingEmergencyRelocation("no valid lane into zone");
    }

    /**
     * Shared relocation request for every ping blindness path (throttled).
     * Before moving, checks whether the current cover already validates a
     * lane into the zone by the relocation search's own criteria — if it
     * does, relocating can only swap one valid spot for another, so hold
     * position instead.
     */
    private void requestPingEmergencyRelocation(String reason) {
        if (soldier.tickCount - lastPingRepositionTick < PING_REPOSITION_COOLDOWN_TICKS) return;
        SuppressionOrderController order = soldier.getSuppressionOrder();
        if (soldier.getCoverTacticalGoal()
                .currentCoverHasSuppressionLane(order.getGeneration())) {
            lastRelocationHoldTick = soldier.tickCount;
            lastRelocationHoldReason = reason;
            if (isSuppressionDebugLogging()) {
                StevesArmyMod.LOGGER.info(
                    "[SuppressPing] Soldier {} relocation held ({}): current cover already has a firing lane",
                    soldier.getId(), reason);
            }
            return;
        }
        lastPingRepositionTick = soldier.tickCount;
        if (isSuppressionDebugLogging()) {
            StevesArmyMod.LOGGER.info("[SuppressPing] Soldier {} no valid lane into zone ({}), requesting emergency reposition",
                soldier.getId(), reason);
        }
        order.beginRelocation();
        soldier.getCoverTacticalGoal().requestSuppressionPosition(
            soldier.getPingSuppressPos(), order.getGeneration());
    }

    /** Every cached aim point failed LOS: clear them (cooldown-throttled) so
     *  the next tick re-discovers from the current eye position. */
    private void clearPingAimPointsAfterFailure() {
        if (soldier.tickCount < aimRediscoveryCooldownTick) return;
        aimRediscoveryCooldownTick = soldier.tickCount + PING_AIM_REDISCOVERY_COOLDOWN_TICKS;
        soldier.setSuppressionAimPoints(java.util.List.of());
    }

    /**
     * All cached heavy face points are blocked from the current eye — the
     * soldier repositioned after discovery, or discovery found nothing at
     * all. Clip a fresh wall face inside the zone; its proud face point has a
     * clear solid path from this eye by construction, making this the lane of
     * last resort before the emergency reposition. The clips are uncached, so
     * probing is throttled to the rediscovery cadence.
     */
    private Vec3 clipFreshHeavyLane() {
        if (soldier.tickCount < aimRediscoveryCooldownTick) return null;
        aimRediscoveryCooldownTick = soldier.tickCount + PING_AIM_REDISCOVERY_COOLDOWN_TICKS;
        Vec3 fresh = new CoverFinder(soldier.level()).findFreshHeavyLaneIntoZone(soldier,
            soldier.getPingSuppressPos(), SoldierEntity.SUPPRESSION_ZONE_RADIUS);
        if (fresh == null) {
            // Every ray missed too: drop the stale points so re-discovery can
            // run from the current eye before the next probe window.
            soldier.setSuppressionAimPoints(java.util.List.of());
            return null;
        }
        soldier.setSuppressionAimPoints(java.util.List.of(fresh));
        lastHeavyLaneSource = "raycast";
        return fresh;
    }

    /** Rocket lanes ignore concealment (leaves do not stop a round); bullet
     *  suppression keeps the strict contact check. */
    private boolean hasLaneForCurrentMode(Vec3 target) {
        if (pingSuppressHeavy) {
            return TargetAcquisition.hasHeavyLaunchLane(soldier, target);
        }
        return TargetAcquisition.hasLineOfSightToPositionIgnoringSmoke(soldier, target);
    }

    /**
     * Selects a suppression target whose actual spread-adjusted shot has clear LOS.
     * The old code validated the unspread point but fired at a different point,
     * allowing horizontal or vertical spread to send bullets into cover.
     */
    private Vec3 getClearPingSuppressionTarget() {
        float aimInaccuracy = GunIntegration.getAimInaccuracy(soldier);
        // The spread fallback aims inside the zone, behind the very cover the
        // enemies hide behind — bullets can sweep with it, but no lane into it
        // can ever validate. Heavy fire clips fresh wall faces instead, so it
        // never consumes the fallback.
        Vec3 selected = soldier.getNextSuppressionAimPoint();
        if (selected == null && !pingSuppressHeavy) {
            selected = soldier.getHorizontalSpreadFallbackTarget(soldier.getPingSuppressPos());
        }

        if (pingSuppressHeavy) {
            // Heavy fire is pinned to the cover-block face points: no spread
            // roll (a scattered rocket overflies the wall instead of striking
            // it) and no opening-height clamp. Rotate through alternate face
            // points so one blocked wall doesn't silence the launcher.
            if (!aimPointsAreEmpty(soldier)) {
                java.util.List<Vec3> facePoints = soldier.getSuppressionAimPoints();
                int base = Math.max(0, facePoints.indexOf(selected));
                int attempts = Math.min(facePoints.size(), PING_HEAVY_ALTERNATE_ATTEMPTS);
                for (int i = 0; i < attempts; i++) {
                    Vec3 candidate = facePoints.get((base + i) % facePoints.size());
                    if (TargetAcquisition.hasHeavyLaunchLane(soldier, candidate)) {
                        lastHeavyLaneSource = "cached";
                        return candidate;
                    }
                }
            }
            return clipFreshHeavyLane();
        }

        Vec3 finalTarget = calculateSuppressionSpread(selected, aimInaccuracy);
        if (!aimPointsAreEmpty(soldier)) {
            finalTarget = clampSuppressionTargetHeight(finalTarget, selected);
        }
        if (TargetAcquisition.hasLineOfSightToPositionIgnoringSmoke(soldier, finalTarget)) {
            return finalTarget;
        }

        java.util.List<Vec3> aimPoints = soldier.getSuppressionAimPoints();
        int attempts = Math.min(aimPoints.size(), 12);
        for (int i = 0; i < attempts; i++) {
            Vec3 candidate = aimPoints.get(i);
            if (candidate.equals(selected)) {
                continue;
            }
            finalTarget = calculateSuppressionSpread(candidate, aimInaccuracy);
            finalTarget = clampSuppressionTargetHeight(finalTarget, candidate);
            if (TargetAcquisition.hasLineOfSightToPositionIgnoringSmoke(soldier, finalTarget)) {
                return finalTarget;
            }
        }

        // Every cached point is blocked from the current position — likely we
        // moved since discovery. Force a re-discovery (throttled).
        clearPingAimPointsAfterFailure();
        return null;
    }

    private Vec3 getPingSuppressionBurstTarget(int burstTarget) {
        if (pingSuppressHeavy) {
            // Every rocket of the burst stays on the same wall face — the
            // rifle sweep/vertical-variation logic would drag the aim off the
            // block the explosion is meant to break.
            pingSuppressionShotTarget = pingSuppressionTarget;
            return pingSuppressionShotTarget;
        }
        if (pingSuppressionShotTarget != null) return pingSuppressionShotTarget;

        if (pingSuppressionSweepEnd == null) {
            pingSuppressionSweepEnd = findPingSuppressionSweepEnd(pingSuppressionTarget);
        }

        double progress = burstTarget <= 1 ? 0.0 : burstShotsFired / (double) (burstTarget - 1);
        Vec3 target = pingSuppressionSweepEnd == null
            ? pingSuppressionTarget
            : pingSuppressionTarget.lerp(pingSuppressionSweepEnd, progress);

        double verticalVariation = getPingSuppressionVerticalVariation();
        Vec3 variedTarget = target.add(0.0, verticalVariation, 0.0);
        pingSuppressionShotTarget = TargetAcquisition.hasLineOfSightToPositionIgnoringSmoke(soldier, variedTarget)
            ? variedTarget
            : target;
        return pingSuppressionShotTarget;
    }

    private Vec3 findPingSuppressionSweepEnd(Vec3 start) {
        double maxSweepDistance = getPingSuppressionSweepDistance();
        if (maxSweepDistance <= 0.0) return null;

        List<Vec3> candidates = soldier.getSuppressionAimPoints().stream()
            .filter(point -> point.distanceToSqr(start) > 0.16)
            .filter(point -> {
                double dx = point.x - start.x;
                double dz = point.z - start.z;
                return dx * dx + dz * dz <= maxSweepDistance * maxSweepDistance;
            })
            .filter(point -> Math.abs(point.y - start.y) <= 0.75)
            .filter(point -> hasClearPingSuppressionSweep(start, point))
            .collect(Collectors.toList());
        if (candidates.isEmpty()) return null;

        return candidates.get(soldier.level().random.nextInt(candidates.size()));
    }

    private boolean hasClearPingSuppressionSweep(Vec3 start, Vec3 end) {
        for (int sample = 1; sample <= 4; sample++) {
            Vec3 point = start.lerp(end, sample / 4.0);
            if (!TargetAcquisition.hasLineOfSightToPositionIgnoringSmoke(soldier, point)) {
                return false;
            }
        }
        return true;
    }

    private double getPingSuppressionSweepDistance() {
        if (GunIntegration.isSuppressiveMachineGun(soldier)) return 3.5;
        if (GunIntegration.isMachineGun(soldier)) return 2.5;
        if (GunIntegration.getRPM(soldier) >= 600 && GunIntegration.getMagazineSize(soldier) >= 25) return 1.5;
        if ("smg".equals(GunIntegration.getGunTabType(soldier))) return 1.2;
        return 0.0;
    }

    private double getPingSuppressionVerticalVariation() {
        double amplitude = GunIntegration.isSuppressiveMachineGun(soldier) ? 0.14
            : GunIntegration.getRPM(soldier) >= 600 ? 0.09 : 0.05;
        return (soldier.level().random.nextDouble() - 0.5) * 2.0 * amplitude;
    }

    private boolean aimPointsAreEmpty(SoldierEntity soldier) {
        return soldier.getSuppressionAimPoints().isEmpty();
    }

    private Vec3 clampSuppressionTargetHeight(Vec3 target, Vec3 aimPoint) {
        return target.y < aimPoint.y
            ? new Vec3(target.x, aimPoint.y, target.z)
            : target;
    }
    
    public void forceRestartPingSuppression() {
        this.isPingSuppressing = false;
        this.pingSuppressRemainingTicks = 0;
        // A fresh ping re-arms the heavy phase's single rocket.
        this.pingHeavyRocketSpent = false;
        this.pingSuppressionTarget = null;
        this.pingSuppressionSweepEnd = null;
        this.pingSuppressionShotTarget = null;
    }
}

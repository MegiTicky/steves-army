package com.stevesarmy.entity;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.squad.OwnedSoldierRegistry;
import com.stevesarmy.combat.CombatDebugData;
import com.stevesarmy.combat.DetectionSystem;
import com.stevesarmy.combat.GrenadeIntegration;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverType;
import com.stevesarmy.combat.cover.IncomingFireHandler;
import com.stevesarmy.combat.cover.SuppressionTracker;
import com.stevesarmy.debug.DiagnosticLogManager;

import com.stevesarmy.entity.ai.SoldierCombatGoal;
import com.stevesarmy.entity.ai.SoldierFollowOwnerGoal;
import com.stevesarmy.entity.ai.SoldierHoleRescueGoal;
import com.stevesarmy.entity.ai.SoldierHoldPositionGoal;
import com.stevesarmy.entity.ai.SoldierHealController;
import com.stevesarmy.entity.ai.SoldierMoveToPingGoal;
import com.stevesarmy.entity.ai.SoldierStrollGoal;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import com.stevesarmy.entity.ai.CombatGoalController;
import com.stevesarmy.entity.ai.CoverGoalController;
import com.stevesarmy.entity.ai.PeekController;
import com.stevesarmy.entity.ai.GrenadeTacticalController;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.inventory.SoldierInventoryHandler;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.OpenSoldierInventoryMessage;
import com.stevesarmy.squad.FireDiscipline;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.FireTeamAssignment;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import com.stevesarmy.squad.SquadMode;
import com.stevesarmy.squad.SquadFormation;
import com.stevesarmy.squad.TeamManager;
import com.stevesarmy.registry.ModItems;
import com.stevesarmy.util.SpacingHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class SoldierEntity extends PathfinderMob implements Container {
    private static final EntityDataAccessor<Optional<UUID>> OWNER_UUID = 
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Integer> FOLLOW_STATE = 
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SQUAD_MODE = 
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<BlockPos> HOLD_POSITION = 
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BLOCK_POS);
    
    private static final EntityDataAccessor<Float> DEBUG_DETECTION_POINTS =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DEBUG_IS_DETECTED =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> DEBUG_DISTANCE =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DEBUG_HAS_LOS =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DEBUG_IN_FOCUSED =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Optional<UUID>> DEBUG_TARGET_UUID =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    
    private static final EntityDataAccessor<Integer> COVER_STATE =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<BlockPos> COVER_CURRENT_POS =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Integer> COVER_CURRENT_TYPE =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> COVER_CURRENT_QUALITY =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> COVER_CURRENT_HEIGHT =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<BlockPos> COVER_TARGET_POS =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Integer> COVER_TARGET_TYPE =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> COVER_TARGET_QUALITY =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<BlockPos> COVER_LAST_POS =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Float> SUPPRESSION_LEVEL =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> SUPPRESSION_EVENT_SEQUENCE =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> PEEK_STATE =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<BlockPos> PEEK_POSITION =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Boolean> LOW_CROUCHING =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> HALF_COVER_RISE_PROGRESS =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> HALF_COVER_RISING =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> RELOAD_PENDING =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> TACTICAL_RELOADING =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BOOLEAN);
    
    private static final EntityDataAccessor<Float> THREAT_DIR_X =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> THREAT_DIR_Y =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> THREAT_DIR_Z =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<String> DEBUG_CQB_PATH =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<Integer> FIRE_DISCIPLINE =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> FIRE_TEAM =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<String> YSM_MODEL_ID =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> YSM_TEXTURE_ID =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<Integer> RECALL_TICKS =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<BlockPos> MG_DEBUG_POSITION =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<BlockPos> MG_DEBUG_CENTER =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Float> MG_DEBUG_ACCESS =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> MG_DEBUG_POSTURE =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> MG_DEBUG_ACTIVE =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> MG_DEBUG_SUPPRESSED =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<BlockPos> MG_DEBUG_MOVEMENT_POSITION =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Boolean> MG_DEBUG_FALLBACK =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BOOLEAN);

    private static final int HALF_COVER_RISE_TICKS = 8;
    private static final int NAVIGATION_LANDING_LOCK_TICKS = 4;
    private static final int NAVIGATION_COLLISION_LOCK_TICKS = 2;

    @Nullable
    private UUID squadId;
    @Nullable
    protected BlockPos defendPosition;
    private long grenadeCooldownUntilTick;
    private SquadFormation squadFormation = SquadFormation.NONE;
    @Nullable
    private BlockPos formationOffset;
    @Nullable
    private LivingEntity cachedOwner;
    private final SoldierInventory inventory;
    private final SoldierInventoryHandler inventoryHandler;
    private final LazyOptional<IItemHandler> itemHandlerCap;
    
    protected CombatGoalController combatGoal;
    protected Goal combatGoalTask;
    private CoverBehaviorManager coverBehaviorManager;
    private PeekController peekController;
    protected CoverGoalController coverTacticalGoal;
    protected Goal coverTacticalGoalTask;
    private final ThreatAwareness threatAwareness;
    private final GrenadeTacticalController grenadeTacticalController;
    private final SoldierHealController healController;
    
    private boolean healing = false;
    private int navigationTraversalLockUntilTick = -1;
    private int navigationTraversalHeightDelta;
    private String navigationTraversalLockReason = "none";
    /** True when combat, rather than cover movement, owns the low-prone posture. */
    private boolean firingProne = false;
    /** Armed only by a low-crouch exit; prevents unrelated peek transitions from rising. */
    private boolean halfCoverRisePending;
    private int lastRotationTraceWriterTick = -1;
    private boolean hasRotationTraceSnapshot;
    private float lastRotationTraceYaw;
    private float lastRotationTraceBodyYaw;
    private float lastRotationTraceHeadYaw;
    private int lastPeekTraceSnapshotTick = -20;
    private SuppressionTracker.SuppressionState lastPeekTraceSuppressionState;
    private boolean lastPeekTraceEmergencyPosture;

    private BlockPos pingMoveTarget = null;
    private long pingMoveTimestamp = 0;
    private static final long PING_MOVE_MEMORY_MS = 15000;
    private boolean persistentGoTo = false;
    private boolean goToHolding = false;

    private int pingMoveGeneration = 0;

    private BlockPos attackTargetPos = null;
    private long attackTargetTimestamp = 0;
    private static final long ATTACK_MEMORY_MS = 60000;
    private int attackGeneration = 0;
    
    private BlockPos pingThreatPos = null;
    private long pingThreatTimestamp = 0;
    private static final long PING_THREAT_MEMORY_MS = 20000;
    
    private BlockPos forcedTargetPos = null;
    private long forcedTargetTimestamp = 0;
    private static final long FORCED_TARGET_MEMORY_MS = 10000;
    
    private BlockPos pingSuppressPos = null;
    private long pingSuppressTimestamp = 0;
    private static final long PING_SUPPRESS_MEMORY_MS = 10000;
    private java.util.List<Vec3> suppressionAimPoints = new java.util.ArrayList<>();
    private Vec3 lastSuppressionAimPoint = null;
    public static final double SUPPRESSION_ZONE_RADIUS = 15.0;
    
    private boolean dispatchedBySend = false;
    private boolean inventorySyncingFromEntity = false;
    private boolean cqbMode = false;
    private boolean cqbEngagementHold = false;
    private String lastSyncedCqbPath = null;

    public static final double CQB_RANGE = 5.0;
    /** Radius around the remaining path within which a known enemy triggers caution steering. */
    public static final double CQB_CAUTION_RADIUS = 10.0;

    public boolean isDispatchedBySend() {
        return dispatchedBySend;
    }

    public boolean isCQB() {
        return cqbMode;
    }

    public void setCQB(boolean cqbMode) {
        this.cqbMode = cqbMode;
    }

    public boolean hasCloseRangeTarget() {
        if (this.getTarget() == null || !this.getTarget().isAlive()) return false;
        return this.distanceToSqr(this.getTarget()) < CQB_RANGE * CQB_RANGE;
    }

    public boolean isCqbEngagementHold() {
        return cqbEngagementHold;
    }

    public void beginCqbEngagement() {
        if (cqbEngagementHold) return;
        cqbEngagementHold = true;
        getNavigation().stop();
        cancelCoverMovement();
        setZza(0.0F);
        setXxa(0.0F);
        setDeltaMovement(0.0D, getDeltaMovement().y, 0.0D);
    }

    public void endCqbEngagement() {
        cqbEngagementHold = false;
    }

    public String getSyncedCqbPath() {
        return entityData.get(DEBUG_CQB_PATH);
    }

    private void syncCqbDebugPath() {
        Path path = getNavigation().getPath();
        if (path == null || path.isDone() || path.getNodeCount() == 0) {
            if (!"".equals(lastSyncedCqbPath)) {
                entityData.set(DEBUG_CQB_PATH, "");
                lastSyncedCqbPath = "";
            }
            return;
        }
        StringBuilder encoded = new StringBuilder();
        int start = Math.max(0, path.getNextNodeIndex());
        int end = Math.min(path.getNodeCount(), start + 12);
        for (int i = start; i < end; i++) {
            if (encoded.length() > 0) encoded.append('|');
            BlockPos p = path.getNode(i).asBlockPos();
            encoded.append(p.getX()).append(':').append(p.getY()).append(':').append(p.getZ());
        }
        String value = encoded.toString();
        if (!value.equals(lastSyncedCqbPath)) {
            entityData.set(DEBUG_CQB_PATH, value);
            lastSyncedCqbPath = value;
        }
    }

    public boolean isHealing() {
        return healing;
    }

    public SoldierHealController getHealController() {
        return healController;
    }

    public void setHealing(boolean healing) {
        this.healing = healing;
    }

    public SoldierEntity(EntityType<? extends SoldierEntity> type, Level level) {
        super(type, level);
        this.moveControl = new com.stevesarmy.entity.ai.CoverPositionController(this);
        this.setCanPickUpLoot(true);
        this.inventory = new SoldierInventory();
        this.inventoryHandler = new SoldierInventoryHandler(inventory);
        this.itemHandlerCap = LazyOptional.of(() -> inventoryHandler);
        this.coverBehaviorManager = new CoverBehaviorManager(this);
        this.peekController = new PeekController();
        this.threatAwareness = new ThreatAwareness();
        this.grenadeTacticalController = new GrenadeTacticalController(this);
        this.healController = new SoldierHealController(this);
        this.inventory.setMainHandChangedCallback(stack -> {
            if (!this.level().isClientSide) {
                if (inventorySyncingFromEntity) return;
                boolean reloading = GunIntegration.isAnyGunLoaded() && GunIntegration.isReloading(this);
                if (reloading && !ItemStack.isSameItem(stack, getMainHandItem())) {
                    StevesArmyMod.LOGGER.info("[Soldier] Blocked gun swap during reload (callback)");
                    return;
                }
                setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
                if (GunIntegration.isAnyGunLoaded() && !stack.isEmpty() && !reloading) {
                    GunIntegration.initialData(this);
                    GunIntegration.draw(this);
                }
            }
        });
    }

    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        return new com.stevesarmy.entity.ai.SoldierGroundNavigation(this, level);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(OWNER_UUID, Optional.empty());
        this.entityData.define(FOLLOW_STATE, 1);
        this.entityData.define(SQUAD_MODE, SquadMode.FOLLOW.ordinal());
        this.entityData.define(HOLD_POSITION, BlockPos.ZERO);
        this.entityData.define(DEBUG_DETECTION_POINTS, 0f);
        this.entityData.define(DEBUG_IS_DETECTED, false);
        this.entityData.define(DEBUG_DISTANCE, 0f);
        this.entityData.define(DEBUG_HAS_LOS, false);
        this.entityData.define(DEBUG_IN_FOCUSED, false);
        this.entityData.define(DEBUG_TARGET_UUID, Optional.empty());
        
        this.entityData.define(COVER_STATE, 0);
        this.entityData.define(COVER_CURRENT_POS, BlockPos.ZERO);
        this.entityData.define(COVER_CURRENT_TYPE, 0);
        this.entityData.define(COVER_CURRENT_QUALITY, 0f);
        this.entityData.define(COVER_CURRENT_HEIGHT, 0f);
        this.entityData.define(COVER_TARGET_POS, BlockPos.ZERO);
        this.entityData.define(COVER_TARGET_TYPE, 0);
        this.entityData.define(COVER_TARGET_QUALITY, 0f);
        this.entityData.define(COVER_LAST_POS, BlockPos.ZERO);
        this.entityData.define(SUPPRESSION_LEVEL, 0f);
        this.entityData.define(SUPPRESSION_EVENT_SEQUENCE, 0);
        this.entityData.define(PEEK_STATE, 0);
        this.entityData.define(PEEK_POSITION, BlockPos.ZERO);
        this.entityData.define(LOW_CROUCHING, false);
        this.entityData.define(HALF_COVER_RISE_PROGRESS, 1.0f);
        this.entityData.define(HALF_COVER_RISING, false);
        this.entityData.define(RELOAD_PENDING, false);
        this.entityData.define(TACTICAL_RELOADING, false);
        this.entityData.define(THREAT_DIR_X, 0f);
        this.entityData.define(THREAT_DIR_Y, 0f);
        this.entityData.define(THREAT_DIR_Z, 0f);
        this.entityData.define(DEBUG_CQB_PATH, "");
        this.entityData.define(FIRE_DISCIPLINE, FireDiscipline.STANDARD.ordinal());
        this.entityData.define(FIRE_TEAM, FireTeam.ALPHA.ordinal());
        this.entityData.define(YSM_MODEL_ID, "");
        this.entityData.define(YSM_TEXTURE_ID, "");
        this.entityData.define(RECALL_TICKS, 0);
        this.entityData.define(MG_DEBUG_POSITION, BlockPos.ZERO);
        this.entityData.define(MG_DEBUG_CENTER, BlockPos.ZERO);
        this.entityData.define(MG_DEBUG_ACCESS, 0.0f);
        this.entityData.define(MG_DEBUG_POSTURE, 0);
        this.entityData.define(MG_DEBUG_ACTIVE, false);
        this.entityData.define(MG_DEBUG_SUPPRESSED, false);
        this.entityData.define(MG_DEBUG_MOVEMENT_POSITION, BlockPos.ZERO);
        this.entityData.define(MG_DEBUG_FALLBACK, false);
    }

    @Override
    protected void registerGoals() {
        initializeCombatGoal();
        
        this.goalSelector.addGoal(0, new SoldierHoleRescueGoal(this));
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(1, new SoldierMoveToPingGoal(this));
        initializeCoverTacticalGoal();
        this.goalSelector.addGoal(2, coverTacticalGoalTask);
        this.goalSelector.addGoal(3, new SoldierFollowOwnerGoal(this));
        this.goalSelector.addGoal(3, new SoldierHoldPositionGoal(this));
        this.goalSelector.addGoal(4, combatGoalTask);
        this.goalSelector.addGoal(5, new SoldierStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    /**
     * Stores the combat goal so cover and combat coordination use the same instance.
     * Subclasses with custom goal layouts must use this instead of constructing one directly.
     */
    protected Goal initializeCombatGoal() {
        SoldierCombatGoal goal = new SoldierCombatGoal(this);
        this.combatGoal = goal;
        this.combatGoalTask = goal;
        return goal;
    }

    /** Creates the shared cover goal instance used by squad cover coordination. */
    protected Goal initializeCoverTacticalGoal() {
        CoverTacticalGoal goal = new CoverTacticalGoal(this);
        this.coverTacticalGoal = goal;
        this.coverTacticalGoalTask = goal;
        return goal;
    }

    /** The default entity role is the rifleman pipeline. */
    public SoldierRole getRole() {
        return SoldierRole.RIFLEMAN;
    }

    /** True when this role should never peek from cover. Override for roles like support. */
    public boolean isPeekDisabled() {
        return false;
    }

    /**
     * Optional direction that a role prefers for cover evaluation. Riflemen keep
     * the default threat-based evaluation direction.
     */
    @Nullable
    public Vec3 getPreferredCoverEvaluationDirection() {
        return null;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        
        getOwnerUUID().ifPresent(uuid -> tag.putUUID("Owner", uuid));
        tag.putInt("FollowState", getFollowState());
        tag.putInt("SquadMode", getSquadMode().ordinal());
        tag.putBoolean("HasSquad", squadId != null);
        if (squadId != null) {
            tag.putUUID("SquadId", squadId);
        }
        tag.putLong("HoldPos", getHoldPosition().asLong());
        tag.put("Inventory", inventory.save());
        tag.putInt("FireDiscipline", getFireDiscipline().ordinal());
        tag.putInt("FireTeam", getFireTeam().ordinal());
        tag.putLong("GrenadeCooldownUntil", grenadeCooldownUntilTick);
        tag.putString("YsmModelId", getYsmModelId());
        tag.putString("YsmTextureId", getYsmTextureId());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) {
            setOwnerUUID(tag.getUUID("Owner"));
        }
        setFollowState(tag.getInt("FollowState"));
        setSquadMode(SquadMode.values()[tag.getInt("SquadMode") % SquadMode.values().length]);
        if (tag.getBoolean("HasSquad")) {
            squadId = tag.getUUID("SquadId");
        }
        setHoldPosition(BlockPos.of(tag.getLong("HoldPos")));
        if (tag.contains("Inventory")) {
            inventory.load(tag.getCompound("Inventory"));
        }
        inventory.syncArmorToEntity(this);
        if (tag.contains("FireDiscipline")) {
            setFireDiscipline(FireDiscipline.values()[tag.getInt("FireDiscipline") % FireDiscipline.values().length]);
        }
        if (tag.contains("FireTeam")) {
            setFireTeam(FireTeam.values()[tag.getInt("FireTeam") % FireTeam.values().length]);
        }
        grenadeCooldownUntilTick = tag.getLong("GrenadeCooldownUntil");
        if (tag.contains("YsmModelId")) {
            setYsmModelId(tag.getString("YsmModelId"));
        }
        if (tag.contains("YsmTextureId")) {
            setYsmTextureId(tag.getString("YsmTextureId"));
        }
    }

    @Override
    public ItemStack getPickedResult(HitResult target) {
        inventory.syncFromEntity(this);

        ItemStack stack = new ItemStack(getPickBlockEggItem());
        CompoundTag tag = new CompoundTag();
        tag.put("Inventory", inventory.save());
        tag.putInt("Role", getRole().ordinal());
        getOwnerUUID().ifPresent(owner -> tag.putUUID("Owner", owner));
        tag.putInt("FollowState", getFollowState());
        tag.putInt("SquadMode", getSquadMode().ordinal());
        UUID squadId = getSquadId();
        if (squadId != null) {
            tag.putBoolean("HasSquad", true);
            tag.putUUID("SquadId", squadId);
        }
        BlockPos holdPos = getHoldPosition();
        if (holdPos != null) {
            tag.putLong("HoldPos", holdPos.asLong());
        }
        if (defendPosition != null) {
            tag.putLong("DefendPosition", defendPosition.asLong());
        }
        tag.putInt("FireTeam", getFireTeam().ordinal());
        tag.putInt("FireDiscipline", getFireDiscipline().ordinal());
        tag.putString("YsmModelId", getYsmModelId());
        tag.putString("YsmTextureId", getYsmTextureId());

        stack.getOrCreateTag().put("EntityTag", tag);
        return stack;
    }

    /**
     * The spawn egg that pick-blocking this soldier yields. Subclasses may
     * override to surface a different egg (e.g. the team garrison egg).
     */
    protected Item getPickBlockEggItem() {
        return switch (getRole()) {
            case RIFLEMAN -> ModItems.SOLDIER_SPAWN_EGG.get();
            case MACHINE_GUNNER -> ModItems.MACHINE_GUNNER_SPAWN_EGG.get();
            case GARRISON -> ModItems.GARRISON_SPAWN_EGG.get();
            case SUPPORT -> ModItems.SUPPORT_SPAWN_EGG.get();
        };
    }

    /**
     * Restores the persistent soldier state carried by a pick-blocked spawn egg.
     * Position is not touched; the caller places the entity.
     */
    public void fillFromPickBlockData(CompoundTag entityTag) {
        if (entityTag.hasUUID("Owner")) {
            setOwnerUUID(entityTag.getUUID("Owner"));
        }
        if (entityTag.contains("FollowState")) {
            setFollowState(entityTag.getInt("FollowState"));
        }
        if (entityTag.contains("SquadMode")) {
            int modeOrdinal = entityTag.getInt("SquadMode");
            SquadMode[] modes = SquadMode.values();
            if (modeOrdinal >= 0 && modeOrdinal < modes.length) {
                setSquadMode(modes[modeOrdinal]);
            }
        }
        if (entityTag.contains("HasSquad") && entityTag.getBoolean("HasSquad")
            && entityTag.hasUUID("SquadId")) {
            setSquadId(entityTag.getUUID("SquadId"));
        }
        if (entityTag.contains("HoldPos")) {
            setHoldPosition(BlockPos.of(entityTag.getLong("HoldPos")));
        }
        if (entityTag.contains("DefendPosition")) {
            setDefendPosition(BlockPos.of(entityTag.getLong("DefendPosition")));
        }
        if (entityTag.contains("FireTeam")) {
            int ordinal = entityTag.getInt("FireTeam");
            FireTeam[] teams = FireTeam.values();
            if (ordinal >= 0 && ordinal < teams.length) {
                setFireTeam(teams[ordinal]);
            }
        }
        if (entityTag.contains("FireDiscipline")) {
            int ordinal = entityTag.getInt("FireDiscipline");
            FireDiscipline[] disciplines = FireDiscipline.values();
            if (ordinal >= 0 && ordinal < disciplines.length) {
                setFireDiscipline(disciplines[ordinal]);
            }
        }
        if (entityTag.contains("YsmModelId")) {
            setYsmModelId(entityTag.getString("YsmModelId"));
        }
        if (entityTag.contains("YsmTextureId")) {
            setYsmTextureId(entityTag.getString("YsmTextureId"));
        }
        if (entityTag.contains("Inventory")) {
            inventory.load(entityTag.getCompound("Inventory"));
            inventory.syncArmorToEntity(this);
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // Let the surgical knife's item interaction open the YSM editor before the
        // generic owned-soldier interaction consumes the click.
        if (player.getItemInHand(hand).is(ModItems.SURGICAL_KNIFE.get())) {
            return InteractionResult.PASS;
        }
        // Creative players may inspect the inventory of any soldier, regardless of ownership.
        boolean canOpenInventory = isOwnedBy(player) || player.getAbilities().instabuild;
        if (canOpenInventory && player.isShiftKeyDown()) {
            if (!this.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
                com.stevesarmy.inventory.SoldierInventoryMenuProvider provider =
                    new com.stevesarmy.inventory.SoldierInventoryMenuProvider(this);
                net.minecraftforge.network.NetworkHooks.openScreen(serverPlayer,
                    provider,
                    provider::writeExtraData);
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        if (isOwnedBy(player)) {
            if (!this.level().isClientSide) {
                // Right-click behavior reserved for future use
                // HOLD/FOLLOW modes are now set via ping wheel exclusively
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        return super.mobInteract(player, hand);
    }

    @Nullable
    public LivingEntity getOwner() {
        Optional<UUID> ownerUUID = getOwnerUUID();
        if (ownerUUID.isEmpty()) {
            return null;
        }
        
        UUID uuid = ownerUUID.get();
        if (cachedOwner != null
            && cachedOwner.getUUID().equals(uuid)
            && cachedOwner.isAlive()
            && !cachedOwner.isRemoved()
            && cachedOwner.level() == this.level()) {
            return cachedOwner;
        }

        cachedOwner = null;
        
        if (this.level() instanceof ServerLevel serverLevel) {
            Entity entity = serverLevel.getEntity(uuid);
            if (entity instanceof LivingEntity livingEntity
                && livingEntity.isAlive()
                && !livingEntity.isRemoved()) {
                cachedOwner = livingEntity;
                return cachedOwner;
            }
        }
        return null;
    }

    public Optional<UUID> getOwnerUUID() {
        return this.entityData.get(OWNER_UUID);
    }

    public void setOwnerUUID(UUID uuid) {
        this.entityData.set(OWNER_UUID, Optional.of(uuid));
        this.cachedOwner = null;
    }

    public boolean isOwnedBy(Player player) {
        return getOwnerUUID().map(uuid -> uuid.equals(player.getUUID())).orElse(false);
    }

    public int getFollowState() {
        return this.entityData.get(FOLLOW_STATE);
    }

    public void setFollowState(int state) {
        this.entityData.set(FOLLOW_STATE, state);
    }

    public SquadMode getSquadMode() {
        return SquadMode.values()[this.entityData.get(SQUAD_MODE) % SquadMode.values().length];
    }

    public void setSquadMode(SquadMode mode) {
        this.entityData.set(SQUAD_MODE, mode.ordinal());
        
        if (mode == SquadMode.HOLD && getOwner() != null) {
            setHoldPosition(blockPosition());
        }
    }

    public FireDiscipline getFireDiscipline() {
        return FireDiscipline.values()[this.entityData.get(FIRE_DISCIPLINE) % FireDiscipline.values().length];
    }

    public void setFireDiscipline(FireDiscipline discipline) {
        this.entityData.set(FIRE_DISCIPLINE, discipline.ordinal());
    }

    public FireTeam getFireTeam() {
        return FireTeam.values()[this.entityData.get(FIRE_TEAM) % FireTeam.values().length];
    }

    public void setFireTeam(FireTeam team) {
        this.entityData.set(FIRE_TEAM, team.ordinal());
    }

    public String getYsmModelId() {
        return this.entityData.get(YSM_MODEL_ID);
    }

    public void setYsmModelId(String modelId) {
        this.entityData.set(YSM_MODEL_ID, modelId);
    }

    public String getYsmTextureId() {
        return this.entityData.get(YSM_TEXTURE_ID);
    }

    public void setYsmTextureId(String textureId) {
        this.entityData.set(YSM_TEXTURE_ID, textureId);
    }

    public boolean hasYsmModel() {
        return !getYsmModelId().isEmpty();
    }

    public int getRecallTicks() {
        return this.entityData.get(RECALL_TICKS);
    }

    public boolean isRecalling() {
        return getRecallTicks() > 0;
    }

    public void setRecallTicks(int ticks) {
        this.entityData.set(RECALL_TICKS, ticks);
    }

    public void startRecall() {
        setRecallTicks(80);
        this.getNavigation().stop();
    }

    public void cancelRecall() {
        setRecallTicks(0);
    }

    @Override
    public Component getDisplayName() {
        FireTeam team = getFireTeam();
        if (team == FireTeam.ALL) {
            return super.getDisplayName();
        }
        return Component.literal("[" + team.getShortName() + "] ").append(super.getDisplayName());
    }

    public BlockPos getHoldPosition() {
        return this.entityData.get(HOLD_POSITION);
    }

    public void setHoldPosition(BlockPos pos) {
        this.entityData.set(HOLD_POSITION, pos);
    }

    @Nullable
    public BlockPos getDefendPosition() {
        return defendPosition;
    }

    public void setDefendPosition(@Nullable BlockPos pos) {
        this.defendPosition = pos;
    }

    public double getDefendRadius() {
        return 20.0;
    }

    @Nullable
    public UUID getSquadId() {
        return squadId;
    }

    public void setSquadId(UUID squadId) {
        this.squadId = squadId;
    }

    public SquadFormation getSquadFormation() {
        return squadFormation;
    }

    public void setSquadFormation(SquadFormation formation) {
        this.squadFormation = formation;
    }

    @Nullable
    public BlockPos getFormationOffset() {
        return formationOffset;
    }

    public void setFormationOffset(@Nullable BlockPos offset) {
        this.formationOffset = offset;
    }

    public void clearFormationOffset() {
        this.formationOffset = null;
    }

    @Override
    public boolean canBeLeashed(Player player) {
        return false;
    }

    @Override
    protected boolean canRide(Entity vehicle) {
        return VS2Compat.isAuthorizedMount(this, vehicle);
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean isAlliedTo(Entity other) {
        if (other instanceof LivingEntity living && isAlliedByTeam(living)) return true;
        LivingEntity owner = getOwner();
        if (owner != null && owner.isAlliedTo(other)) return true;
        return super.isAlliedTo(other);
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (target instanceof SoldierEntity soldier && soldier.getOwnerUUID().equals(this.getOwnerUUID())) {
            return false;
        }
        if (isAlliedByTeam(target)) {
            return false;
        }
        LivingEntity owner = getOwner();
        if (owner != null && target == owner) {
            return false;
        }
        if (owner != null && target instanceof Player targetPlayer && owner.isAlliedTo(targetPlayer)) {
            return false;
        }
        if (target instanceof SoldierEntity targetSoldier) {
            LivingEntity targetOwner = targetSoldier.getOwner();
            if (owner != null && targetOwner != null && owner.isAlliedTo(targetOwner)) {
                return false;
            }
        }
        return super.canAttack(target);
    }

    /** True when the other entity shares the soldier's (or owner's) scoreboard team. */
    private boolean isAlliedByTeam(LivingEntity other) {
        Team otherTeam = other.getTeam();
        if (otherTeam == null) return false;
        Team soldierTeam = getTeam();
        if (soldierTeam != null && soldierTeam.isAlliedTo(otherTeam)) return true;
        LivingEntity owner = getOwner();
        Team ownerTeam = owner != null ? owner.getTeam() : null;
        return ownerTeam != null && ownerTeam.isAlliedTo(otherTeam);
    }
    
    public boolean isFriendlyTo(LivingEntity other) {
        if (other == this) return false;
        
        if (isAlliedByTeam(other)) return true;
        
        LivingEntity owner = getOwner();
        if (other == owner) return true;
        
        Optional<UUID> myOwner = getOwnerUUID();
        if (myOwner.isPresent()) {
            if (other instanceof Player otherPlayer) {
                if (otherPlayer.getUUID().equals(myOwner.get())) return true;
                if (owner != null && owner.isAlliedTo(otherPlayer)) return true;
            }
        }
        
        if (other instanceof SoldierEntity otherSoldier) {
            Optional<UUID> theirOwner = otherSoldier.getOwnerUUID();
            if (myOwner.isPresent() && theirOwner.isPresent() 
                && myOwner.get().equals(theirOwner.get())) return true;
            if (owner != null) {
                LivingEntity theirOwnerEntity = otherSoldier.getOwner();
                if (theirOwnerEntity != null) {
                    if (owner.isAlliedTo(theirOwnerEntity)) return true;
                    Team ownerTeam = owner.getTeam();
                    Team theirTeam = theirOwnerEntity.getTeam();
                    if (ownerTeam != null && theirTeam != null
                        && ownerTeam.getName().startsWith("steves_army_friendly_")
                        && theirTeam.getName().startsWith("steves_army_friendly_")) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }

    @Override
    protected void dropEquipment() {
    }

    @Override
    public boolean canPickUpLoot() {
        return true;
    }

    @Override
    public boolean canHoldItem(ItemStack stack) {
        return true;
    }

    @Override
    protected void pickUpItem(ItemEntity itemEntity) {
        ItemStack stack = itemEntity.getItem();
        int count = stack.getCount();
        
        // Try to put in an existing partial stack in bag slots first
        for (int i = SoldierInventory.SLOT_GENERAL_START; i < inventory.getContainerSize(); i++) {
            ItemStack existing = inventory.getItem(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, stack)) {
                int space = Math.min(existing.getMaxStackSize(), 64) - existing.getCount();
                if (space > 0) {
                    int toAdd = Math.min(count, space);
                    existing.grow(toAdd);
                    inventory.setChanged();
                    count -= toAdd;
                    if (count <= 0) {
                        itemEntity.discard();
                        return;
                    }
                }
            }
        }
        
        // Try bag slots for empty slots
        for (int i = SoldierInventory.SLOT_GENERAL_START; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                ItemStack toInsert = stack.split(count);
                inventory.setItem(i, toInsert);
                inventory.setChanged();
                itemEntity.discard();
                return;
            }
        }
        
        // The main hand is reserved for firearms. Do not let vanilla pickup
        // behavior replace a soldier's gun with a knife or other item.
        ItemStack mainHand = inventory.getItem(SoldierInventory.SLOT_MAIN_HAND);
        if (mainHand.isEmpty() && GunIntegration.isGun(stack)) {
            ItemStack toInsert = stack.split(count);
            inventory.setItem(SoldierInventory.SLOT_MAIN_HAND, toInsert);
            inventory.setChanged();
            itemEntity.discard();
            return;
        }
    }

    public SoldierInventory getSoldierInventory() {
        return inventory;
    }

    public GrenadeTacticalController getGrenadeTacticalController() {
        return grenadeTacticalController;
    }

    @Nullable
    public SquadThreatIntel getGrenadeSquadIntel() {
        if (squadId == null || !(level() instanceof ServerLevel serverLevel)) return null;
        return SquadManager.get(serverLevel).getSquadById(squadId)
            .map(com.stevesarmy.squad.SquadData::getThreatIntel).orElse(null);
    }

    private boolean shouldTickGrenadeController(long gameTime) {
        if (grenadeTacticalController.isActive()
            || DiagnosticLogManager.isAttackLoggingEnabled()
            || DiagnosticLogManager.isGrenadeLoggingEnabled()) {
            return true;
        }
        // The shared arc scheduler limits expensive world simulations to one
        // complete request per server tick. Let controllers submit requests on
        // every tick so the queue is responsive instead of adding another
        // ten-tick delay before a soldier can enter it.
        return GrenadeIntegration.findSupportedSlot(inventory) >= 0;
    }

    public boolean canUseGrenade(long gameTime) {
        return gameTime >= grenadeCooldownUntilTick;
    }

    public long getGrenadeCooldownUntilTick() {
        return grenadeCooldownUntilTick;
    }

    public void markGrenadeUsed(long gameTime) {
        grenadeCooldownUntilTick = gameTime + com.stevesarmy.StevesArmyConfig.getGrenadePersonalCooldownTicks();
    }

    @Override
    public int getContainerSize() {
        return inventory.getContainerSize();
    }

    @Override
    public boolean isEmpty() {
        return inventory.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return inventory.getItem(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return inventory.removeItem(slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return inventory.removeItemNoUpdate(slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        boolean reloading = GunIntegration.isAnyGunLoaded() && GunIntegration.isReloading(this);
        if (slot == SoldierInventory.SLOT_MAIN_HAND && reloading && !ItemStack.isSameItem(stack, getMainHandItem())) {
            StevesArmyMod.LOGGER.info("[Soldier] Blocked gun swap during reload (setItem)");
            return;
        }
        inventory.setItem(slot, stack);
        if (slot == SoldierInventory.SLOT_MAIN_HAND) {
            setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
            if (GunIntegration.isAnyGunLoaded() && !stack.isEmpty() && !reloading) {
                GunIntegration.initialData(this);
                GunIntegration.draw(this);
            }
        }
    }

    @Override
    public void setChanged() {
        inventory.setChanged();
    }

    @Override
    public void clearContent() {
        inventory.clearContent();
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        super.setItemSlot(slot, stack);
        inventorySyncingFromEntity = true;
        try {
            if (slot == EquipmentSlot.MAINHAND) {
                inventory.setItem(SoldierInventory.SLOT_MAIN_HAND, stack.copy());
            } else if (slot.getType() == EquipmentSlot.Type.ARMOR) {
                int invSlot;
                switch (slot) {
                    case HEAD -> invSlot = SoldierInventory.ARMOR_HEAD;
                    case CHEST -> invSlot = SoldierInventory.ARMOR_CHEST;
                    case LEGS -> invSlot = SoldierInventory.ARMOR_LEGS;
                    default -> invSlot = SoldierInventory.ARMOR_FEET;
                }
                inventory.setItem(invSlot, stack.copy());
            }
        } finally {
            inventorySyncingFromEntity = false;
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return this.isAlive() && this.distanceTo(player) <= 64.0F;
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide) {
            com.stevesarmy.network.SyncSoldierInventoryPacket.applyPendingInventory(this);
        }
        
        if (!this.level().isClientSide) {
            syncCqbDebugPath();
            threatAwareness.tick();
            holdMovementForReload();
            updateCrawlFacing();
            // Reload and cover helpers may hold navigation and update their own
            // crouch state. A live firing-prone plan remains the authoritative
            // posture until that plan explicitly cancels it.
            if (firingProne && !entityData.get(LOW_CROUCHING)) {
                setLowCrouching(true);
            }
            tickHalfCoverRiseProgress();
            traceRotationSnapshot();
            tracePeekSnapshot();
        }

        // Enforce the server-synced posture every tick to fight vanilla pose overrides.
        // Suppression recovery is owned by CoverTacticalGoal, not this entity tick.
        if (entityData.get(LOW_CROUCHING)) {
            if (this.getPose() != Pose.SWIMMING) {
                this.setPose(Pose.SWIMMING);
                this.refreshDimensions();
            }
        } else {
            // Read synced data so the client knows what the server is doing
            int coverState = getSyncedCoverState();
            boolean inCover = coverState == CoverBehaviorManager.CoverState.IN_COVER.ordinal() 
                           || coverState == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER.ordinal();
            boolean isHalfCover = getSyncedCoverCurrentType() == CoverType.HALF.ordinal();

            if (inCover && isHalfCover) {
                Pose coverPose = isHalfCoverRising() ? Pose.CROUCHING
                    : getSyncedCoverCurrentHeight() >= 1.3f
                    ? Pose.STANDING : Pose.CROUCHING;
                if (this.getPose() != coverPose) {
                    this.setPose(coverPose);
                    this.refreshDimensions();
                }
            } else {
                // Ensure they stand back up when leaving cover
                if (this.getPose() == Pose.CROUCHING || this.getPose() == Pose.SWIMMING) {
                    this.setPose(Pose.STANDING);
                    this.refreshDimensions();
                }
            }
        }

    }

    @Override
    protected void customServerAiStep() {
        long gameTime = level().getGameTime();
        if (VS2Compat.prepareSoldierAi(this)) {
            if (shouldTickGrenadeController(gameTime)) {
                grenadeTacticalController.tick(getTarget(), getGrenadeSquadIntel());
            }
            return;
        }
        updateNavigationTraversalLock();
        if (shouldTickGrenadeController(gameTime)
            && grenadeTacticalController.tick(getTarget(), getGrenadeSquadIntel())) {
            return;
        }
        super.customServerAiStep();
    }
    
    @Override
    public void remove(RemovalReason reason) {
        if (DiagnosticLogManager.isRotationTraceEnabledFor(getUUID())) {
            DiagnosticLogManager.clearRotationTrace();
            StevesArmyMod.LOGGER.info("[RotationTrace] soldier={} removed, trace stopped", getId());
        }
        if (!this.level().isClientSide) {
            VS2Compat.onSoldierRemoved(this);
            TeamManager.removeFromTeam(this);
            com.stevesarmy.combat.cover.CoverReservationManager.releaseAll(this);
            com.stevesarmy.combat.VpbEntityState.remove(this.getUUID());
            if (this.level() instanceof ServerLevel serverLevel) {
                com.stevesarmy.squad.SquadManager.get(serverLevel).removeMemberFromSquad(this.getUUID());
                OwnedSoldierRegistry.get(serverLevel.getServer()).remove(this.getUUID());
                com.stevesarmy.squad.SquadActivityManager.removeSoldier(this.getUUID(), serverLevel.getServer());
            }
        }
        super.remove(reason);
    }
    
    @Override
    public EntityDimensions getDimensions(Pose pose) {
        if (entityData.get(LOW_CROUCHING)) {
            return EntityDimensions.scalable(0.6F, 0.6F);
        }
        if (isHalfCoverRising()) {
            EntityDimensions standing = super.getDimensions(Pose.STANDING);
            float height = Mth.lerp(getHalfCoverRiseProgress(), 1.5f, standing.height);
            return EntityDimensions.scalable(0.6F, height);
        }
        if (pose == Pose.CROUCHING) {
            return EntityDimensions.scalable(0.6F, 1.5F);
        }
        return super.getDimensions(pose);
    }

    @Override
    protected float getStandingEyeHeight(Pose pose, EntityDimensions dimensions) {
        if (entityData.get(LOW_CROUCHING)) {
            return 0.4F;
        }
        if (isHalfCoverRising()) {
            EntityDimensions standing = super.getDimensions(Pose.STANDING);
            float standingEyeHeight = super.getStandingEyeHeight(Pose.STANDING, standing);
            return Mth.lerp(getHalfCoverRiseProgress(), 1.27F, standingEyeHeight);
        }
        if (pose == Pose.CROUCHING) {
            return 1.27F;
        }
        return super.getStandingEyeHeight(pose, dimensions);
    }

    @Override
    public void die(DamageSource source) {
        if (!this.level().isClientSide && !this.isRemoved() && this.level() instanceof ServerLevel serverLevel) {
            getOwnerUUID().ifPresent(ownerUUID -> {
                ServerPlayer owner = serverLevel.getServer().getPlayerList().getPlayer(ownerUUID);
                if (owner != null) {
                    owner.sendSystemMessage(Component.literal("[Squad] ").append(this.getCombatTracker().getDeathMessage().copy()));
                }
            });
        }
        super.die(source);
    }
    
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (DiagnosticLogManager.isDamageLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] SoldierEntity.hurt() called: entity={} id={} source={} amount={} sourceEntity={}",
                this.getName().getString(), this.getId(), source.getMsgId(), amount,
                source.getEntity() != null ? source.getEntity().getName().getString() + "(" + source.getEntity().getClass().getSimpleName() + ")" : "null");
        }
        boolean result = super.hurt(source, amount);
        if (DiagnosticLogManager.isDamageLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[DAMAGE_DEBUG] SoldierEntity.hurt() result: {} (super.hurt returned {})", result ? "damage applied" : "damage blocked", result);
        }

        if (result && !this.level().isClientSide) {
            if (isHealing()) {
                stopUsingItem();
            }
            if (isRecalling()) {
                cancelRecall();
            }
            if (coverBehaviorManager != null) {
                CoverBehaviorManager.CoverState preState = coverBehaviorManager.getState();
                PeekController.State prePeekState = peekController.getState();
                
                LivingEntity attacker = source.getEntity() instanceof LivingEntity a ? a : null;
                coverBehaviorManager.onTakeDamage(attacker);
                
                if (attacker != null && attacker != this && !isFriendlyTo(attacker)) {
                    coverBehaviorManager.onIncomingFire(attacker);

                    // A hostile hit while fully hidden proves this position no longer protects us.
                    if ((preState == CoverBehaviorManager.CoverState.IN_COVER ||
                         preState == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER) &&
                        prePeekState == PeekController.State.HIDING) {
                        coverBehaviorManager.requestShotInCoverReposition();
                    }
                }
                
                if (attacker != null && attacker != this) {
                    Vec3 toAttacker = attacker.position().subtract(this.position()).normalize();
                    threatAwareness.setSmoothDirection(toAttacker);
                }
            }
        }
        
        return result;
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return itemHandlerCap.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        itemHandlerCap.invalidate();
    }
    
    public void receivePing(com.stevesarmy.ping.PingType type, net.minecraft.world.phys.Vec3 position) {
        com.stevesarmy.StevesArmyMod.LOGGER.info("Soldier received ping: type={} pos={}", type, position);
        
        switch (type) {
            case SEND -> {
                BlockPos pos = BlockPos.containing(position);
                clearGoToState();
                pingMoveTarget = pos;
                pingMoveTimestamp = System.currentTimeMillis();
                setSquadMode(com.stevesarmy.squad.SquadMode.HOLD);
                setHoldPosition(pos);
                coverBehaviorManager.clearCover();
                dispatchedBySend = true;
                StevesArmyMod.LOGGER.info("SEND: set hold position at {} (dispatched)", pos);
            }
            case GO_TO -> {
                pingMoveGeneration++;
                pingMoveTarget = BlockPos.containing(position);
                pingMoveTimestamp = System.currentTimeMillis();
                persistentGoTo = true;
                goToHolding = false;
                setSquadMode(com.stevesarmy.squad.SquadMode.HOLD);
                setHoldPosition(BlockPos.containing(position));
                coverBehaviorManager.clearCover();
                cancelCoverMovement();
                StevesArmyMod.LOGGER.info("Set move target: {}", pingMoveTarget);
            }
            case THREAT_DIRECTION -> {
                BlockPos pos = BlockPos.containing(position);
                threatAwareness.onPingDirection(pos);
                pingThreatPos = pos;
                pingThreatTimestamp = System.currentTimeMillis();
                forcedTargetPos = pos;
                forcedTargetTimestamp = System.currentTimeMillis();
                com.stevesarmy.StevesArmyMod.LOGGER.info("Set threat direction position: {} (forced target inherited from ENEMY)", pingThreatPos);
            }
            case LOCATION -> {
            }
            case FOLLOW -> {
                setSquadMode(com.stevesarmy.squad.SquadMode.FOLLOW);
                dispatchedBySend = false;
                clearPingMoveTarget();
                clearPingThreatPos();
                clearForcedTarget();
                clearPingSuppressPos();
                threatAwareness.clear();
                if (squadId != null) com.stevesarmy.util.SpacingHelper.clearAssignment(squadId);
                com.stevesarmy.StevesArmyMod.LOGGER.info("Switched to FOLLOW mode, cleared all threat data");
            }
            case HOLD -> {
                setSquadMode(com.stevesarmy.squad.SquadMode.HOLD);
                setHoldPosition(blockPosition());
                coverBehaviorManager.clearCover();
                dispatchedBySend = false;
                clearPingMoveTarget();
                clearPingThreatPos();
                clearForcedTarget();
                clearPingSuppressPos();
                threatAwareness.clear();
                if (squadId != null) com.stevesarmy.util.SpacingHelper.clearAssignment(squadId);
                StevesArmyMod.LOGGER.info("Switched to HOLD mode, cleared all threat data");
            }
            case SUPPRESS_AREA -> {
                pingSuppressPos = BlockPos.containing(position);
                pingSuppressTimestamp = System.currentTimeMillis();
                suppressionAimPoints.clear();
                lastSuppressionAimPoint = null;
                
                if (this.combatGoal != null) {
                    this.combatGoal.forceRestartPingSuppression();
                }
                
                StevesArmyMod.LOGGER.info("Set suppress area: {}", pingSuppressPos);
            }
            case ATTACK -> {
                setAttackTarget(BlockPos.containing(position));
                coverBehaviorManager.clearCover();
                cancelCoverMovement();
                setSquadMode(com.stevesarmy.squad.SquadMode.HOLD);
                setHoldPosition(attackTargetPos);
                StevesArmyMod.LOGGER.info("ATTACK: set attack position at {} (gen {})", attackTargetPos, attackGeneration);
            }
        }
    }
    
public BlockPos getPingMoveTarget() {
        return pingMoveTarget;
    }

    public int getPingMoveGeneration() {
        return pingMoveGeneration;
    }

    public boolean hasValidPingMoveTarget() {
        return pingMoveTarget != null &&
               (persistentGoTo || System.currentTimeMillis() - pingMoveTimestamp < PING_MOVE_MEMORY_MS);
    }

    public boolean hasPersistentGoTo() {
        return persistentGoTo && pingMoveTarget != null;
    }

    public boolean isGoToHolding() {
        return persistentGoTo && goToHolding;
    }

    /** True while an active or completed GO_TO command owns CQB movement decisions. */
    public boolean hasGoToNavigationOwnership() {
        return persistentGoTo;
    }

    public void completeGoToIfGeneration(int expectedGeneration) {
        if (pingMoveGeneration != expectedGeneration || !persistentGoTo) return;

        pingMoveTarget = null;
        pingMoveTimestamp = 0;
        goToHolding = true;
        if (squadId != null) {
            com.stevesarmy.util.SpacingHelper.clearAssignment(squadId);
        }
    }

    public void clearPingMoveTarget() {
        pingMoveTarget = null;
        pingMoveTimestamp = 0;
        clearGoToState();
        if (squadId != null) {
            com.stevesarmy.util.SpacingHelper.clearAssignment(squadId);
        }
    }

    public void clearPingMoveTargetIfGeneration(int expectedGeneration) {
        if (pingMoveGeneration == expectedGeneration) {
            pingMoveTarget = null;
            pingMoveTimestamp = 0;
            clearGoToState();
            if (squadId != null) {
                com.stevesarmy.util.SpacingHelper.clearAssignment(squadId);
            }
        }
    }

    private void clearGoToState() {
        persistentGoTo = false;
        goToHolding = false;
    }

    public BlockPos getAttackTargetPos() {
        return attackTargetPos;
    }

    public boolean hasValidAttackTarget() {
        return attackTargetPos != null &&
               System.currentTimeMillis() - attackTargetTimestamp < ATTACK_MEMORY_MS;
    }

    public int getAttackGeneration() {
        return attackGeneration;
    }

    public void setAttackTarget(BlockPos pos) {
        attackGeneration++;
        this.attackTargetPos = pos;
        this.attackTargetTimestamp = System.currentTimeMillis();
        // Clear incompatible ping move target
        clearPingMoveTarget();
        // ATTACK is objective-only: it defines where the soldier should
        // reposition (lateral/forward) but does NOT change the active threat
        // direction or combat target. Use THREAT_DIRECTION pings for that.
        // The existing threat (from entity detection, damage, or enemy pings)
        // continues to drive cover-facing and peek decisions.
    }

    public void clearAttackTarget() {
        this.attackTargetPos = null;
        this.attackTargetTimestamp = 0;
        if (squadId != null) {
            com.stevesarmy.util.SpacingHelper.clearAssignment(squadId);
        }
    }

    public void clearAttackTargetIfGeneration(int expectedGeneration) {
        if (attackGeneration == expectedGeneration) {
            this.attackTargetPos = null;
            this.attackTargetTimestamp = 0;
            if (squadId != null) {
                com.stevesarmy.util.SpacingHelper.clearAssignment(squadId);
            }
        }
    }

    public BlockPos getPingThreatPos() {
        return pingThreatPos;
    }
    
    public boolean hasValidPingThreatPos() {
        return pingThreatPos != null && 
               System.currentTimeMillis() - pingThreatTimestamp < PING_THREAT_MEMORY_MS;
    }
    
    public void clearPingThreatPos() {
        pingThreatPos = null;
        pingThreatTimestamp = 0;
    }
    
    public BlockPos getForcedTargetPos() {
        return forcedTargetPos;
    }
    
    public boolean hasValidForcedTarget() {
        return forcedTargetPos != null && 
               System.currentTimeMillis() - forcedTargetTimestamp < FORCED_TARGET_MEMORY_MS;
    }
    
    public void setForcedTargetPos(BlockPos pos) {
        this.forcedTargetPos = pos;
        this.forcedTargetTimestamp = System.currentTimeMillis();
    }
    
    public void clearForcedTarget() {
        this.forcedTargetPos = null;
        this.forcedTargetTimestamp = 0;
    }
    
    public BlockPos getPingSuppressPos() {
        return pingSuppressPos;
    }
    
    public boolean hasValidPingSuppressPos() {
        return pingSuppressPos != null &&
               System.currentTimeMillis() - pingSuppressTimestamp < PING_SUPPRESS_MEMORY_MS;
    }

    public void setPingSuppressPos(BlockPos pos) {
        if (pingSuppressPos == null || !pingSuppressPos.equals(pos)) {
            suppressionAimPoints.clear();
            lastSuppressionAimPoint = null;
        }
        this.pingSuppressPos = pos;
        this.pingSuppressTimestamp = System.currentTimeMillis();
    }
    
    public void clearPingSuppressPos() {
        pingSuppressPos = null;
        pingSuppressTimestamp = 0;
        suppressionAimPoints.clear();
        lastSuppressionAimPoint = null;
    }
    
    public java.util.List<Vec3> getSuppressionAimPoints() {
        return suppressionAimPoints;
    }
    
    public void setSuppressionAimPoints(java.util.List<Vec3> points) {
        this.suppressionAimPoints = points;
        this.lastSuppressionAimPoint = null;
    }
    
    public Vec3 getNextSuppressionAimPoint() {
        if (suppressionAimPoints.isEmpty()) return null;
        if (suppressionAimPoints.size() == 1) return suppressionAimPoints.get(0);
        
        java.util.Random random = new java.util.Random();
        Vec3 selected;
        int attempts = 0;
        
        do {
            int index = random.nextInt(suppressionAimPoints.size());
            selected = suppressionAimPoints.get(index);
            attempts++;
        } while (selected.equals(lastSuppressionAimPoint) && attempts < 10);
        
        lastSuppressionAimPoint = selected;
        return selected;
    }
    
    public Vec3 getHorizontalSpreadFallbackTarget(BlockPos pingCenter) {
        java.util.Random random = new java.util.Random();
        double angle = random.nextDouble() * 2 * Math.PI;
        double radius = random.nextDouble() * SUPPRESSION_ZONE_RADIUS;
        
        Vec3 center = pingCenter.getCenter();
        return new Vec3(
            center.x + Math.cos(angle) * radius,
            center.y + 1.0,
            center.z + Math.sin(angle) * radius
        );
    }
    
    public CombatGoalController getCombatGoal() {
        return combatGoal;
    }

    public void syncMachineGunnerDebug(BlockPos position, BlockPos movementPosition, BlockPos center,
                                       float access, int posture, boolean active, boolean fallback,
                                       boolean suppressed) {
        if (level().isClientSide) return;
        entityData.set(MG_DEBUG_POSITION, position != null ? position : BlockPos.ZERO);
        entityData.set(MG_DEBUG_MOVEMENT_POSITION,
            movementPosition != null ? movementPosition : BlockPos.ZERO);
        entityData.set(MG_DEBUG_CENTER, center != null ? center : BlockPos.ZERO);
        entityData.set(MG_DEBUG_ACCESS, access);
        entityData.set(MG_DEBUG_POSTURE, posture);
        entityData.set(MG_DEBUG_ACTIVE, active);
        entityData.set(MG_DEBUG_FALLBACK, fallback);
        entityData.set(MG_DEBUG_SUPPRESSED, suppressed);
    }

    public BlockPos getMachineGunnerDebugPosition() { return entityData.get(MG_DEBUG_POSITION); }
    public BlockPos getMachineGunnerDebugMovementPosition() { return entityData.get(MG_DEBUG_MOVEMENT_POSITION); }
    public BlockPos getMachineGunnerDebugCenter() { return entityData.get(MG_DEBUG_CENTER); }
    public float getMachineGunnerDebugAccess() { return entityData.get(MG_DEBUG_ACCESS); }
    public int getMachineGunnerDebugPosture() { return entityData.get(MG_DEBUG_POSTURE); }
    public boolean isMachineGunnerDebugActive() { return entityData.get(MG_DEBUG_ACTIVE); }
    public boolean isMachineGunnerDebugFallback() { return entityData.get(MG_DEBUG_FALLBACK); }
    public boolean isMachineGunnerDebugSuppressed() { return entityData.get(MG_DEBUG_SUPPRESSED); }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (LOW_CROUCHING.equals(accessor) || HALF_COVER_RISE_PROGRESS.equals(accessor)
            || HALF_COVER_RISING.equals(accessor)) {
            // DATA_POSE and LOW_CROUCHING can arrive in either order. Refresh
            // here so the client bounding box always uses the current posture.
            this.refreshDimensions();
        }
    }

    public boolean isPreparingOrReloading() {
        return entityData.get(RELOAD_PENDING)
            || (GunIntegration.isAnyGunLoaded() && GunIntegration.isReloading(this));
    }

    public boolean isTacticalReloading() {
        return entityData.get(TACTICAL_RELOADING);
    }

    public void setReloadStatus(boolean reloadPending, boolean tacticalReloading) {
        entityData.set(RELOAD_PENDING, reloadPending);
        entityData.set(TACTICAL_RELOADING, tacticalReloading);
    }

    /** True when the soldier's gun never runs out of reserve ammo. */
    public boolean hasInfiniteReserveAmmo() {
        return false;
    }

    /** Restores the virtual reserve before a reload when infinite reserve is enabled. */
    public boolean ensureInfiniteReserveAmmo() {
        return false;
    }

    public void configureInfiniteReserveAmmo() {
    }

    public CoverGoalController getCoverTacticalGoal() {
        return coverTacticalGoal;
    }
    
    public CoverBehaviorManager getCoverBehaviorManager() {
        return coverBehaviorManager;
    }
    
    public PeekController getPeekController() {
        return peekController;
    }
    
    public void updateDebugData(float detectionPoints, boolean isDetected, float distance, boolean hasLOS, boolean inFocused) {
        this.entityData.set(DEBUG_DETECTION_POINTS, detectionPoints);
        this.entityData.set(DEBUG_IS_DETECTED, isDetected);
        this.entityData.set(DEBUG_DISTANCE, distance);
        this.entityData.set(DEBUG_HAS_LOS, hasLOS);
        this.entityData.set(DEBUG_IN_FOCUSED, inFocused);
    }
    
    public float getDebugDetectionPoints() {
        return this.entityData.get(DEBUG_DETECTION_POINTS);
    }
    
    public boolean getDebugIsDetected() {
        return this.entityData.get(DEBUG_IS_DETECTED);
    }
    
    public float getDebugDistance() {
        return this.entityData.get(DEBUG_DISTANCE);
    }
    
    public boolean getDebugHasLOS() {
        return this.entityData.get(DEBUG_HAS_LOS);
    }
    
    public boolean getDebugInFocused() {
        return this.entityData.get(DEBUG_IN_FOCUSED);
    }
    
    public void setDebugTargetUUID(UUID targetUUID) {
        this.entityData.set(DEBUG_TARGET_UUID, Optional.ofNullable(targetUUID));
    }
    
    public Optional<UUID> getDebugTargetUUID() {
        return this.entityData.get(DEBUG_TARGET_UUID);
    }
    
    public void syncCoverState(int stateOrdinal) {
        this.entityData.set(COVER_STATE, stateOrdinal);
    }
    
    public int getSyncedCoverState() {
        return this.entityData.get(COVER_STATE);
    }
    
    public void syncCoverCurrent(BlockPos pos, int typeOrdinal, float quality, float height) {
        boolean changedCover = !pos.equals(this.entityData.get(COVER_CURRENT_POS))
            || typeOrdinal != this.entityData.get(COVER_CURRENT_TYPE);
        this.entityData.set(COVER_CURRENT_POS, pos);
        this.entityData.set(COVER_CURRENT_TYPE, typeOrdinal);
        this.entityData.set(COVER_CURRENT_QUALITY, quality);
        this.entityData.set(COVER_CURRENT_HEIGHT, height);
        if (changedCover) {
            cancelHalfCoverRise("cover-changed");
        }
    }

    public void beginHalfCoverRise() {
        beginHalfCoverRise("unspecified");
    }

    public void beginHalfCoverRise(String source) {
        if (this.level().isClientSide || entityData.get(LOW_CROUCHING) || !halfCoverRisePending) {
            return;
        }
        halfCoverRisePending = false;
        this.entityData.set(HALF_COVER_RISE_PROGRESS, 0.0f);
        this.entityData.set(HALF_COVER_RISING, true);
        this.refreshDimensions();
        tracePeek("half-cover-rise", "action=started, source=" + source);
    }

    public void cancelHalfCoverRise() {
        cancelHalfCoverRise("unspecified");
    }

    public void cancelHalfCoverRise(String source) {
        if (this.level().isClientSide) {
            return;
        }
        halfCoverRisePending = false;
        this.entityData.set(HALF_COVER_RISE_PROGRESS, 1.0f);
        this.entityData.set(HALF_COVER_RISING, false);
        this.refreshDimensions();
        tracePeek("half-cover-rise", "action=cancelled, source=" + source);
    }

    public float getHalfCoverRiseProgress() {
        return this.entityData.get(HALF_COVER_RISE_PROGRESS);
    }

    public boolean isHalfCoverRising() {
        if (!entityData.get(HALF_COVER_RISING) || entityData.get(LOW_CROUCHING)
            || getHalfCoverRiseProgress() >= 1.0f) {
            return false;
        }
        int state = getSyncedCoverState();
        boolean inCover = state == CoverBehaviorManager.CoverState.IN_COVER.ordinal()
            || state == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER.ordinal();
        return inCover && getSyncedCoverCurrentType() == CoverType.HALF.ordinal();
    }

    private void tickHalfCoverRiseProgress() {
        if (!isHalfCoverRising()) return;
        float next = Math.min(1.0f, getHalfCoverRiseProgress() + 1.0f / HALF_COVER_RISE_TICKS);
        this.entityData.set(HALF_COVER_RISE_PROGRESS, next);
        if (next >= 1.0f) {
            this.entityData.set(HALF_COVER_RISING, false);
        }
        this.refreshDimensions();
    }
    
    public BlockPos getSyncedCoverCurrentPos() {
        return this.entityData.get(COVER_CURRENT_POS);
    }
    
    public int getSyncedCoverCurrentType() {
        return this.entityData.get(COVER_CURRENT_TYPE);
    }
    
    public float getSyncedCoverCurrentQuality() {
        return this.entityData.get(COVER_CURRENT_QUALITY);
    }

    public float getSyncedCoverCurrentHeight() {
        return this.entityData.get(COVER_CURRENT_HEIGHT);
    }
    
    public void syncCoverTarget(BlockPos pos, int typeOrdinal, float quality) {
        this.entityData.set(COVER_TARGET_POS, pos);
        this.entityData.set(COVER_TARGET_TYPE, typeOrdinal);
        this.entityData.set(COVER_TARGET_QUALITY, quality);
    }
    
    public BlockPos getSyncedCoverTargetPos() {
        return this.entityData.get(COVER_TARGET_POS);
    }
    
    public int getSyncedCoverTargetType() {
        return this.entityData.get(COVER_TARGET_TYPE);
    }
    
    public float getSyncedCoverTargetQuality() {
        return this.entityData.get(COVER_TARGET_QUALITY);
    }
    
    public void syncCoverLast(BlockPos pos) {
        this.entityData.set(COVER_LAST_POS, pos);
    }
    
    public BlockPos getSyncedCoverLastPos() {
        return this.entityData.get(COVER_LAST_POS);
    }
    
    public void syncSuppressionLevel(float level) {
        this.entityData.set(SUPPRESSION_LEVEL, level);
    }
    
    public float getSyncedSuppressionLevel() {
        return this.entityData.get(SUPPRESSION_LEVEL);
    }

    public void syncSuppressionEventSequence(int sequence) {
        this.entityData.set(SUPPRESSION_EVENT_SEQUENCE, sequence);
    }

    public int getSyncedSuppressionEventSequence() {
        return this.entityData.get(SUPPRESSION_EVENT_SEQUENCE);
    }
    
    public void syncPeekState(int peekStateOrdinal) {
        this.entityData.set(PEEK_STATE, peekStateOrdinal);
    }
    
    public int getSyncedPeekState() {
        return this.entityData.get(PEEK_STATE);
    }
    
    public void syncPeekPosition(BlockPos pos) {
        this.entityData.set(PEEK_POSITION, pos);
    }
    
    public BlockPos getSyncedPeekPosition() {
        return this.entityData.get(PEEK_POSITION);
    }
    
    private int emergencyEngagementPostureUntilTick = -1;
    private static final double CRAWL_MOVEMENT_SPEED_SQR = 0.0004D;
    private static final float CRAWL_TURN_RATE_DEGREES = 25.0F;

    public void setLowCrouching(boolean lowCrouch) {
        if (this.level().isClientSide) {
            return;
        }

        if (!lowCrouch) {
            // Any explicit stand request (cover exit, movement, or combat) ends
            // the firing-prone ownership before the shared pose is cleared.
            this.firingProne = false;
        }

        boolean wasLowCrouching = entityData.get(LOW_CROUCHING);
        if (wasLowCrouching == lowCrouch) {
            return;
        }
        
        this.entityData.set(LOW_CROUCHING, lowCrouch);
        if (lowCrouch) {
            cancelHalfCoverRise("low-crouch");
        } else {
            halfCoverRisePending = true;
        }
        this.setPose(lowCrouch ? Pose.SWIMMING : Pose.STANDING);
        this.refreshDimensions();
        tracePeek("low-crouch", "changed=" + wasLowCrouching + "->" + lowCrouch);
    }

    /** Requests the shared low-prone pose for a direct-fire stabilization stance. */
    public void setFiringProne(boolean active) {
        if (this.level().isClientSide) {
            return;
        }

        this.firingProne = active;
        setLowCrouching(active);
    }

    public boolean isFiringProne() {
        return firingProne;
    }
    
    public boolean isLowCrouching() {
        return entityData.get(LOW_CROUCHING);
    }

    /** True while low-crouching movement has a stable horizontal travel direction. */
    public boolean isCrawlMoving() {
        return isLowCrouching()
            && getDeltaMovement().horizontalDistanceSqr() > CRAWL_MOVEMENT_SPEED_SQR;
    }

    /** Returns the yaw that points along the current crawl movement vector. */
    public float getCrawlMovementYaw() {
        Vec3 movement = getDeltaMovement();
        return (float) Math.toDegrees(Math.atan2(-movement.x, movement.z));
    }

    /** Returns the stable body direction used while crawling. */
    public float getCrawlFacingYaw() {
        return this.yBodyRot;
    }

    private void updateCrawlFacing() {
        if (!isCrawlMoving()) {
            return;
        }

        float previousYaw = getYRot();
        float previousBodyYaw = yBodyRot;
        float previousHeadYaw = getYHeadRot();
        float movementYaw = getCrawlMovementYaw();
        float bodyYaw = approachAngle(this.yBodyRot, movementYaw, CRAWL_TURN_RATE_DEGREES);
        this.setYRot(bodyYaw);
        this.setYBodyRot(bodyYaw);
        traceRotationWrite("crawl-facing", previousYaw, previousBodyYaw, previousHeadYaw,
            "movement=" + formatVec(getDeltaMovement()) + ", targetYaw=" + formatAngle(movementYaw));
    }

    /** Records a mod-owned yaw write when this soldier is the active rotation trace target. */
    public void traceRotationWrite(String source, float previousYaw, float previousBodyYaw,
                                   float previousHeadYaw, String detail) {
        if (!isRotationTraceActive()) return;
        lastRotationTraceWriterTick = tickCount;
        StevesArmyMod.LOGGER.info("[RotationTrace] tick={} soldier={} source={} {} -> {} detail={} context={}",
            tickCount, getId(), source, formatYawTriplet(previousYaw, previousBodyYaw, previousHeadYaw),
            formatYawTriplet(getYRot(), yBodyRot, getYHeadRot()), detail, rotationTraceContext());
    }

    private void traceRotationSnapshot() {
        if (!isRotationTraceActive()) {
            hasRotationTraceSnapshot = false;
            return;
        }
        float yaw = getYRot();
        float bodyYaw = yBodyRot;
        float headYaw = getYHeadRot();
        boolean changed = hasRotationTraceSnapshot
            && (Math.abs(Mth.wrapDegrees(yaw - lastRotationTraceYaw)) > 0.01F
                || Math.abs(Mth.wrapDegrees(bodyYaw - lastRotationTraceBodyYaw)) > 0.01F
                || Math.abs(Mth.wrapDegrees(headYaw - lastRotationTraceHeadYaw)) > 0.01F);
        String source = changed && lastRotationTraceWriterTick != tickCount ? "external-or-vanilla" : "final";
        StevesArmyMod.LOGGER.info("[RotationTrace] tick={} soldier={} source={} yaw={} changed={} context={}",
            tickCount, getId(), source, formatYawTriplet(yaw, bodyYaw, headYaw), changed, rotationTraceContext());
        hasRotationTraceSnapshot = true;
        lastRotationTraceYaw = yaw;
        lastRotationTraceBodyYaw = bodyYaw;
        lastRotationTraceHeadYaw = headYaw;
    }

    private boolean isRotationTraceActive() {
        return !level().isClientSide && DiagnosticLogManager.isRotationTraceEnabledFor(getUUID());
    }

    /** Records a selected soldier's posture/peek decision with one common state context. */
    public void tracePeek(String event, String detail) {
        if (!isPeekTraceActive()) return;
        StevesArmyMod.LOGGER.info("[PeekTrace] tick={} soldier={} event={} detail={} context={}",
            tickCount, getId(), event, detail, peekTraceContext());
    }

    private void tracePeekSnapshot() {
        if (!isPeekTraceActive()) {
            lastPeekTraceSuppressionState = null;
            return;
        }

        SuppressionTracker.SuppressionState suppressionState = getCoverBehaviorManager()
            .getSuppressionTracker().getState();
        boolean emergency = hasEmergencyEngagementPosture();
        if (suppressionState != lastPeekTraceSuppressionState) {
            tracePeek("suppression-state", "previous=" + lastPeekTraceSuppressionState + ", current=" + suppressionState);
            lastPeekTraceSuppressionState = suppressionState;
        }
        if (emergency != lastPeekTraceEmergencyPosture) {
            tracePeek("emergency-posture", "active=" + emergency + ", remainingTicks=" + getEmergencyEngagementPostureRemainingTicks());
            lastPeekTraceEmergencyPosture = emergency;
        }
        if (tickCount - lastPeekTraceSnapshotTick < 20) return;

        lastPeekTraceSnapshotTick = tickCount;
        PeekController peekController = getPeekController();
        boolean pinnedAndExposed = suppressionState == SuppressionTracker.SuppressionState.PINNED
            && (peekController.isExposed() || peekController.isMovingToPeek());
        tracePeek(pinnedAndExposed ? "ANOMALY-pinned-exposed" : "snapshot",
            "peekElapsedMs=" + peekController.getTimeInCurrentState()
                + ", emergencyOverride=" + emergency
                + ", riseActive=" + isHalfCoverRising()
                + ", riseProgress=" + String.format("%.3f", getHalfCoverRiseProgress())
                + ", pinnedAndExposed=" + pinnedAndExposed);
    }

    private boolean isPeekTraceActive() {
        return !level().isClientSide && DiagnosticLogManager.isPeekTraceEnabledFor(getUUID());
    }

    private String peekTraceContext() {
        CoverBehaviorManager manager = getCoverBehaviorManager();
        SuppressionTracker tracker = manager.getSuppressionTracker();
        CoverPoint cover = manager.getCurrentCover();
        LivingEntity target = getTarget();
        String targetDescription = target == null ? "none"
            : target.getId() + "@" + String.format("%.2f", distanceTo(target));
        return "suppression=" + String.format("%.5f", tracker.getSuppressionLevel())
            + ", suppressionState=" + tracker.getState()
            + ", coverState=" + manager.getState()
            + ", coverType=" + (cover == null ? "NONE" : cover.getType())
            + ", peek=" + getPeekController().getState()
            + ", lowCrouch=" + isLowCrouching()
            + ", riseActive=" + isHalfCoverRising()
            + ", riseProgress=" + String.format("%.3f", getHalfCoverRiseProgress())
            + ", emergency=" + hasEmergencyEngagementPosture()
            + ", emergencyRemaining=" + getEmergencyEngagementPostureRemainingTicks()
            + ", target=" + targetDescription;
    }

    private String rotationTraceContext() {
        Vec3 threat = threatAwareness.getPrimaryDirection(position());
        LivingEntity target = getTarget();
        String coverState = enumName(CoverBehaviorManager.CoverState.values(), getSyncedCoverState());
        String coverType = enumName(CoverType.values(), getSyncedCoverCurrentType());
        String peekState = enumName(PeekController.State.values(), getSyncedPeekState());
        return "coverState=" + coverState + ", coverType=" + coverType + ", peek=" + peekState
            + ", suppression=" + String.format("%.2f", getSyncedSuppressionLevel())
            + ", lowCrouch=" + isLowCrouching() + ", target=" + (target == null ? "none" : target.getId())
            + ", threat=" + formatVec(threat) + ", navDone=" + getNavigation().isDone()
            + ", velocity=" + formatVec(getDeltaMovement());
    }

    private static String enumName(Enum<?>[] values, int ordinal) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal].name() : "INVALID(" + ordinal + ")";
    }

    private static String formatYawTriplet(float yaw, float bodyYaw, float headYaw) {
        return "entity=" + formatAngle(yaw) + ",body=" + formatAngle(bodyYaw) + ",head=" + formatAngle(headYaw);
    }

    private static String formatAngle(float angle) { return String.format("%.1f", Mth.wrapDegrees(angle)); }

    private static String formatVec(Vec3 value) {
        return value == null ? "none" : String.format("(%.2f,%.2f,%.2f)", value.x, value.y, value.z);
    }

    private static float approachAngle(float current, float target, float maxChange) {
        return current + Mth.clamp(Mth.wrapDegrees(target - current), -maxChange, maxChange);
    }

    /** Lets a close flanker briefly interrupt defensive low-crouch posture. */
    public void requestEmergencyEngagementPosture() {
        emergencyEngagementPostureUntilTick = tickCount + 20;
        tracePeek("emergency-posture", "requested remainingTicks=20");
    }

    public boolean hasEmergencyEngagementPosture() {
        return tickCount <= emergencyEngagementPostureUntilTick;
    }

    public void clearEmergencyEngagementPosture() {
        emergencyEngagementPostureUntilTick = -1;
        tracePeek("emergency-posture", "cleared");
    }

    public int getEmergencyEngagementPostureRemainingTicks() {
        return Math.max(0, emergencyEngagementPostureUntilTick - tickCount + 1);
    }

    /**
     * Returns the forward direction for formation positioning.
     * Uses the goal/movement direction first (squad-consistent via owner's position),
     * then owner's look direction, then threat direction, then fallback.
     */
    public ThreatAwareness getThreatAwareness() {
        return threatAwareness;
    }
    
    public void syncThreatDirection(Vec3 direction) {
        if (direction == null || direction.lengthSqr() < 0.001) {
            this.entityData.set(THREAT_DIR_X, 0f);
            this.entityData.set(THREAT_DIR_Y, 0f);
            this.entityData.set(THREAT_DIR_Z, 0f);
        } else {
            Vec3 normalized = direction.normalize();
            this.entityData.set(THREAT_DIR_X, (float) normalized.x);
            this.entityData.set(THREAT_DIR_Y, (float) normalized.y);
            this.entityData.set(THREAT_DIR_Z, (float) normalized.z);
        }
    }
    
    public Vec3 getSyncedThreatDirection() {
        float x = this.entityData.get(THREAT_DIR_X);
        float y = this.entityData.get(THREAT_DIR_Y);
        float z = this.entityData.get(THREAT_DIR_Z);
        if (x == 0f && y == 0f && z == 0f) {
            return null;
        }
        return new Vec3(x, y, z);
    }

    public void cancelCoverMovement() {
        if (moveControl instanceof com.stevesarmy.entity.ai.CoverPositionController ctrl) {
            ctrl.clear();
        }
    }

    /**
     * Temporarily gives vanilla navigation exclusive control of facing while
     * traversing a vertical route segment. Combat uses this to avoid pulling a
     * soldier back toward a threat in the middle of a jump or landing.
     */
    private void updateNavigationTraversalLock() {
        Path path = getNavigation().getPath();
        if (path == null || path.isDone()) {
            return;
        }

        int nextIndex = path.getNextNodeIndex();
        BlockPos nextNode = path.getNode(Math.min(nextIndex, path.getNodeCount() - 1)).asBlockPos();
        int currentY = blockPosition().getY();
        int heightDelta = nextNode.getY() - currentY;
        boolean verticalNextNode = heightDelta != 0;
        boolean upcomingClimb = heightDelta > 0;

        if (!verticalNextNode && nextIndex + 1 < path.getNodeCount()) {
            BlockPos followingNode = path.getNode(nextIndex + 1).asBlockPos();
            upcomingClimb = followingNode.getY() > nextNode.getY();
        }

        if (verticalNextNode) {
            setNavigationTraversalLock("vertical_node", heightDelta, NAVIGATION_LANDING_LOCK_TICKS);
        } else if (!onGround()) {
            setNavigationTraversalLock("airborne", 0, NAVIGATION_LANDING_LOCK_TICKS);
        } else if ((horizontalCollision || minorHorizontalCollision) && upcomingClimb) {
            setNavigationTraversalLock("climb_collision", 1, NAVIGATION_COLLISION_LOCK_TICKS);
        }
    }

    private void setNavigationTraversalLock(String reason, int heightDelta, int durationTicks) {
        navigationTraversalLockUntilTick = Math.max(navigationTraversalLockUntilTick, tickCount + durationTicks);
        navigationTraversalHeightDelta = heightDelta;
        navigationTraversalLockReason = reason;
    }

    public boolean isNavigationTraversalLocked() {
        return tickCount <= navigationTraversalLockUntilTick;
    }

    public String getNavigationTraversalLockReason() {
        return isNavigationTraversalLocked() ? navigationTraversalLockReason : "none";
    }

    public int getNavigationTraversalHeightDelta() {
        return isNavigationTraversalLocked() ? navigationTraversalHeightDelta : 0;
    }

    /** Directs LookControl along the active path without directly changing body yaw. */
    public void faceNavigationTraversal() {
        if (!isNavigationTraversalLocked()) {
            return;
        }

        Path path = getNavigation().getPath();
        if (path != null && !path.isDone() && path.getNodeCount() > 0) {
            int nextIndex = Math.min(path.getNextNodeIndex(), path.getNodeCount() - 1);
            BlockPos nextNode = path.getNode(nextIndex).asBlockPos();
            getLookControl().setLookAt(nextNode.getX() + 0.5D, getEyeY(), nextNode.getZ() + 0.5D, 30.0F, 30.0F);
            return;
        }

        Vec3 movement = getDeltaMovement();
        if (movement.horizontalDistanceSqr() > 0.0001D) {
            getLookControl().setLookAt(getX() + movement.x, getEyeY(), getZ() + movement.z, 30.0F, 30.0F);
        }
    }

    /** Stops ordinary movement while a reload is pending or active. */
    public void holdMovementForReload() {
        if (!isPreparingOrReloading()) {
            return;
        }

        // A soldier without occupied cover must be allowed to finish an
        // already-selected cover approach. The cover goal and position
        // controller apply the bounded movement exception for this case.
        if (isMovingToUnoccupiedCoverDuringReload()) {
            return;
        }

        this.getNavigation().stop();
        if (moveControl instanceof com.stevesarmy.entity.ai.CoverPositionController ctrl) {
            // A tactical goal may deliberately preserve a full-cover duck-back.
            ctrl.stopForReload();
        }
    }

    public boolean isMovingToUnoccupiedCoverDuringReload() {
        CoverBehaviorManager.CoverState state = coverBehaviorManager.getState();
        return coverBehaviorManager.getCurrentCover() == null
            && coverBehaviorManager.getTargetCover() != null
            && (state == CoverBehaviorManager.CoverState.SEEKING_COVER
                || state == CoverBehaviorManager.CoverState.REPOSITIONING);
    }
}

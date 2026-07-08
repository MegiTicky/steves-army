package com.stevesarmy.entity;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.CombatDebugData;
import com.stevesarmy.combat.DetectionSystem;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.IncomingFireHandler;
import com.stevesarmy.entity.ai.SoldierCombatGoal;
import com.stevesarmy.entity.ai.SoldierFollowOwnerGoal;
import com.stevesarmy.entity.ai.SoldierHoleRescueGoal;
import com.stevesarmy.entity.ai.SoldierHoldPositionGoal;
import com.stevesarmy.entity.ai.SoldierMoveToPingGoal;
import com.stevesarmy.entity.ai.SoldierStrollGoal;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import com.stevesarmy.entity.ai.PeekController;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.inventory.SoldierInventoryHandler;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.OpenSoldierInventoryMessage;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadMode;
import com.stevesarmy.squad.SquadFormation;
import com.stevesarmy.util.FormationPositionCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
    private static final EntityDataAccessor<Integer> PEEK_STATE =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<BlockPos> PEEK_POSITION =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Boolean> CRAWLING =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.BOOLEAN);
    
    private static final EntityDataAccessor<Float> THREAT_DIR_X =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> THREAT_DIR_Y =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> THREAT_DIR_Z =
        SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.FLOAT);

    @Nullable
    private UUID squadId;
    private SquadFormation squadFormation = SquadFormation.NONE;
    @Nullable
    private LivingEntity cachedOwner;
    private final SoldierInventory inventory;
    private final SoldierInventoryHandler inventoryHandler;
    private final LazyOptional<IItemHandler> itemHandlerCap;
    
    private SoldierCombatGoal combatGoal;
    private CoverBehaviorManager coverBehaviorManager;
    private PeekController peekController;
    private final ThreatAwareness threatAwareness;
    
    private BlockPos pingMoveTarget = null;
    private long pingMoveTimestamp = 0;
    private static final long PING_MOVE_MEMORY_MS = 15000;
    
    private BlockPos pingThreatPos = null;
    private long pingThreatTimestamp = 0;
    private static final long PING_THREAT_MEMORY_MS = 20000;
    
    private BlockPos forcedTargetPos = null;
    private long forcedTargetTimestamp = 0;
    private static final long FORCED_TARGET_MEMORY_MS = 10000;
    
    private boolean dispatchedBySend = false;
    private boolean inventorySyncingFromEntity = false;
    private boolean cqbMode = false;
    
    public static final double CQB_RANGE = 5.0;

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
        this.inventory.setMainHandChangedCallback(stack -> {
            if (!this.level().isClientSide) {
                if (inventorySyncingFromEntity) return;
                if (GunIntegration.isTaczLoaded() && GunIntegration.isReloading(this)) {
                    StevesArmyMod.LOGGER.info("[Soldier] Blocked gun swap during reload (callback)");
                    return;
                }
                setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
                if (GunIntegration.isTaczLoaded() && !stack.isEmpty()) {
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
        this.entityData.define(COVER_TARGET_POS, BlockPos.ZERO);
        this.entityData.define(COVER_TARGET_TYPE, 0);
        this.entityData.define(COVER_TARGET_QUALITY, 0f);
        this.entityData.define(COVER_LAST_POS, BlockPos.ZERO);
        this.entityData.define(SUPPRESSION_LEVEL, 0f);
        this.entityData.define(PEEK_STATE, 0);
        this.entityData.define(PEEK_POSITION, BlockPos.ZERO);
        this.entityData.define(CRAWLING, false);
        this.entityData.define(THREAT_DIR_X, 0f);
        this.entityData.define(THREAT_DIR_Y, 0f);
        this.entityData.define(THREAT_DIR_Z, 0f);
    }

    @Override
    protected void registerGoals() {
        this.combatGoal = new SoldierCombatGoal(this);
        
        this.goalSelector.addGoal(0, new SoldierHoleRescueGoal(this));
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new SoldierMoveToPingGoal(this));
        this.goalSelector.addGoal(2, new CoverTacticalGoal(this));
        this.goalSelector.addGoal(3, new SoldierFollowOwnerGoal(this));
        this.goalSelector.addGoal(3, new SoldierHoldPositionGoal(this));
        this.goalSelector.addGoal(4, combatGoal);
        this.goalSelector.addGoal(5, new SoldierStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
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
    }

    @Override
    public ItemStack getPickedResult(HitResult target) {
        inventory.syncFromEntity(this);
        
        com.stevesarmy.item.SoldierSpawnEggItem egg = 
            (com.stevesarmy.item.SoldierSpawnEggItem) com.stevesarmy.registry.ModItems.SOLDIER_SPAWN_EGG.get();
        ItemStack stack = new ItemStack(egg);
        CompoundTag tag = new CompoundTag();
        this.addAdditionalSaveData(tag);
        
        StevesArmyMod.LOGGER.info("[SoldierEntity] === getPickedResult ===");
        StevesArmyMod.LOGGER.info("[SoldierEntity] Saving soldier UUID: {}", this.getUUID());
        StevesArmyMod.LOGGER.info("[SoldierEntity] Saved tag: {}", tag.toString());
        
        if (tag.contains("Inventory")) {
            CompoundTag invTag = tag.getCompound("Inventory");
            StevesArmyMod.LOGGER.info("[SoldierEntity] Inventory tag: {}", invTag.toString());
            if (invTag.contains("Items")) {
                ListTag items = invTag.getList("Items", 10);
                StevesArmyMod.LOGGER.info("[SoldierEntity] Inventory items count: {}", items.size());
                for (int i = 0; i < items.size(); i++) {
                    StevesArmyMod.LOGGER.info("[SoldierEntity]   Item {}: {}", i, items.getCompound(i).toString());
                }
            }
        }
        
        StevesArmyMod.LOGGER.info("[SoldierEntity] Current soldier inventory:");
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (!item.isEmpty()) {
                StevesArmyMod.LOGGER.info("[SoldierEntity]   Slot {}: {} x{}", i, item.getItem().toString(), item.getCount());
            }
        }
        
        stack.getOrCreateTag().put("EntityTag", tag);
        StevesArmyMod.LOGGER.info("[SoldierEntity] Final stack tag: {}", stack.getTag().toString());
        
        return stack;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (isOwnedBy(player)) {
            if (player.isShiftKeyDown()) {
                if (!this.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
                    com.stevesarmy.inventory.SoldierInventoryMenuProvider provider = 
                        new com.stevesarmy.inventory.SoldierInventoryMenuProvider(this);
                    net.minecraftforge.network.NetworkHooks.openScreen(serverPlayer, 
                        provider,
                        provider::writeExtraData);
                }
                return InteractionResult.sidedSuccess(this.level().isClientSide);
            }
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
        if (cachedOwner != null && cachedOwner.getUUID().equals(uuid)) {
            return cachedOwner;
        }
        
        if (this.level() instanceof ServerLevel serverLevel) {
            Entity entity = serverLevel.getEntity(uuid);
            if (entity instanceof LivingEntity) {
                cachedOwner = (LivingEntity) entity;
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

    

    public BlockPos getHoldPosition() {
        return this.entityData.get(HOLD_POSITION);
    }

    public void setHoldPosition(BlockPos pos) {
        this.entityData.set(HOLD_POSITION, pos);
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

    @Override
    public boolean canBeLeashed(Player player) {
        return false;
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
    public boolean canAttack(LivingEntity target) {
        if (target instanceof SoldierEntity soldier && soldier.getOwnerUUID().equals(this.getOwnerUUID())) {
            return false;
        }
        LivingEntity owner = getOwner();
        if (owner != null && target == owner) {
            return false;
        }
        return super.canAttack(target);
    }
    
    public boolean isFriendlyTo(LivingEntity other) {
        if (other == this) return false;
        
        LivingEntity owner = getOwner();
        if (other == owner) return true;
        
        Optional<UUID> myOwner = getOwnerUUID();
        if (myOwner.isPresent()) {
            if (other instanceof Player otherPlayer) {
                return otherPlayer.getUUID().equals(myOwner.get());
            }
        }
        
        if (other instanceof SoldierEntity otherSoldier) {
            Optional<UUID> theirOwner = otherSoldier.getOwnerUUID();
            return myOwner.isPresent() && theirOwner.isPresent() 
                && myOwner.get().equals(theirOwner.get());
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
        
        // Try main hand
        ItemStack mainHand = inventory.getItem(SoldierInventory.SLOT_MAIN_HAND);
        if (mainHand.isEmpty()) {
            ItemStack toInsert = stack.split(count);
            inventory.setItem(SoldierInventory.SLOT_MAIN_HAND, toInsert);
            inventory.setChanged();
            itemEntity.discard();
            return;
        }
        
        // Fall back to vanilla behavior (let super handle it)
        // This will call setItemSlot which our override syncs to inventory
        super.pickUpItem(itemEntity);
    }

    public SoldierInventory getSoldierInventory() {
        return inventory;
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
        if (slot == SoldierInventory.SLOT_MAIN_HAND && GunIntegration.isTaczLoaded() && GunIntegration.isReloading(this)) {
            StevesArmyMod.LOGGER.info("[Soldier] Blocked gun swap during reload (setItem)");
            return;
        }
        inventory.setItem(slot, stack);
        if (slot == SoldierInventory.SLOT_MAIN_HAND) {
            setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
            if (GunIntegration.isTaczLoaded() && !stack.isEmpty()) {
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
        
        if (!this.level().isClientSide) {
            IncomingFireHandler.tick();
            threatAwareness.tick();
            // Re-apply pose every tick when crawling to fight vanilla pose resets, but NOT refreshDimensions
            if (entityData.get(CRAWLING) && this.getPose() != Pose.SWIMMING) {
                this.setPose(Pose.SWIMMING);
            }
        }
    }
    
    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        
        if (!this.level().isClientSide) {
            com.stevesarmy.combat.cover.CoverReservationManager.releaseAll(this);
            if (this.level() instanceof ServerLevel serverLevel) {
                com.stevesarmy.squad.SquadManager.get(serverLevel).removeMemberFromSquad(this.getUUID());
            }
        }
    }
    
    @Override
    public EntityDimensions getDimensions(Pose pose) {
        if (entityData.get(CRAWLING)) {
            return EntityDimensions.scalable(0.6F, 0.8F);
        }
        return super.getDimensions(pose);
    }
    
    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean result = super.hurt(source, amount);
        
        if (result && !this.level().isClientSide && coverBehaviorManager != null) {
            CoverBehaviorManager.CoverState preState = coverBehaviorManager.getState();
            long timeInCover = coverBehaviorManager.getTimeInCover();
            PeekController.State prePeekState = peekController.getState();
            
            coverBehaviorManager.onTakeDamage();
            
            if ((preState == CoverBehaviorManager.CoverState.IN_COVER ||
                 preState == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER) &&
                timeInCover >= 2000 &&
                prePeekState == PeekController.State.HIDING) {
                coverBehaviorManager.requestShotInCoverReposition();
            }
            
            if (source.getEntity() instanceof LivingEntity attacker && attacker != this) {
                coverBehaviorManager.onIncomingFire(attacker);
                
                Vec3 toAttacker = attacker.position().subtract(this.position()).normalize();
                threatAwareness.setSmoothDirection(toAttacker);
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
                pingMoveTarget = pos;
                pingMoveTimestamp = System.currentTimeMillis();
                setSquadMode(com.stevesarmy.squad.SquadMode.HOLD);
                setHoldPosition(pos);
                coverBehaviorManager.clearCover();
                dispatchedBySend = true;
                StevesArmyMod.LOGGER.info("SEND: set hold position at {} (dispatched)", pos);
            }
            case GO_TO -> {
                pingMoveTarget = BlockPos.containing(position);
                pingMoveTimestamp = System.currentTimeMillis();
                setSquadMode(com.stevesarmy.squad.SquadMode.HOLD);
                setHoldPosition(BlockPos.containing(position));
                coverBehaviorManager.clearCover();
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
                threatAwareness.clear();
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
                threatAwareness.clear();
                StevesArmyMod.LOGGER.info("Switched to HOLD mode, cleared all threat data");
            }
        }
    }
    
    public BlockPos getPingMoveTarget() {
        return pingMoveTarget;
    }
    
    public boolean hasValidPingMoveTarget() {
        return pingMoveTarget != null && 
               System.currentTimeMillis() - pingMoveTimestamp < PING_MOVE_MEMORY_MS;
    }
    
    public void clearPingMoveTarget() {
        pingMoveTarget = null;
        pingMoveTimestamp = 0;
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
    
    public SoldierCombatGoal getCombatGoal() {
        return combatGoal;
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
    
    public void syncCoverCurrent(BlockPos pos, int typeOrdinal, float quality) {
        this.entityData.set(COVER_CURRENT_POS, pos);
        this.entityData.set(COVER_CURRENT_TYPE, typeOrdinal);
        this.entityData.set(COVER_CURRENT_QUALITY, quality);
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
    
    public void setCrawling(boolean crawling) {
        boolean wasCrawling = entityData.get(CRAWLING);
        if (wasCrawling == crawling) {
            return; // No change
        }
        
        this.entityData.set(CRAWLING, crawling);
        this.setPose(crawling ? Pose.SWIMMING : Pose.STANDING);
        this.refreshDimensions(); // Only refresh dimensions on state change
    }
    
    public boolean isCrawling() {
        return entityData.get(CRAWLING);
    }

    /**
     * Returns the forward direction for formation positioning.
     * Uses the goal/movement direction first (squad-consistent via owner's position),
     * then owner's look direction, then threat direction, then fallback.
     */
    public Vec3 getFormationForwardDirection(@Nullable BlockPos goalPos) {
        if (goalPos != null) {
            LivingEntity owner = getOwner();
            if (owner != null) {
                if (!goalPos.equals(owner.blockPosition())) {
                    Vec3 fromOwner = Vec3.atCenterOf(goalPos).subtract(owner.position()).normalize();
                    if (fromOwner.lengthSqr() > 0.001) {
                        StevesArmyMod.LOGGER.info("[Formation] Soldier {} forward=OWNER_TO_GOAL owner=({},{},{}) goal=({},{},{}) dir=({}, {}, {})",
                            getId(),
                            owner.blockPosition().getX(), owner.blockPosition().getY(), owner.blockPosition().getZ(),
                            goalPos.getX(), goalPos.getY(), goalPos.getZ(),
                            String.format("%.2f", fromOwner.x), String.format("%.2f", fromOwner.y), String.format("%.2f", fromOwner.z));
                        return fromOwner;
                    }
                }
                Vec3 look = owner.getLookAngle();
                if (look != null && look.lengthSqr() > 0.001) {
                    StevesArmyMod.LOGGER.info("[Formation] Soldier {} forward=OWNER_LOOK dir=({}, {}, {})",
                        getId(), String.format("%.2f", look.x), String.format("%.2f", look.y), String.format("%.2f", look.z));
                    return look;
                }
            }
            Vec3 toGoal = Vec3.atCenterOf(goalPos).subtract(position()).normalize();
            if (toGoal.lengthSqr() > 0.001) {
                StevesArmyMod.LOGGER.info("[Formation] Soldier {} forward=SELF_TO_GOAL goal=({},{},{}) self=({},{},{}) dir=({}, {}, {})",
                    getId(),
                    goalPos.getX(), goalPos.getY(), goalPos.getZ(),
                    blockPosition().getX(), blockPosition().getY(), blockPosition().getZ(),
                    String.format("%.2f", toGoal.x), String.format("%.2f", toGoal.y), String.format("%.2f", toGoal.z));
                return toGoal;
            }
        }
        LivingEntity owner = getOwner();
        Vec3 threatDir = owner != null
            ? threatAwareness.getPrimaryDirection(owner.position())
            : threatAwareness.getPrimaryDirection(position());
        if (threatDir != null && threatDir.lengthSqr() > 0.001) {
            StevesArmyMod.LOGGER.info("[Formation] Soldier {} forward=THREAT dir=({}, {}, {})",
                getId(), String.format("%.2f", threatDir.x), String.format("%.2f", threatDir.y), String.format("%.2f", threatDir.z));
            return threatDir;
        }
        if (owner != null) {
            Vec3 look = owner.getLookAngle();
            if (look != null && look.lengthSqr() > 0.001) {
                StevesArmyMod.LOGGER.info("[Formation] Soldier {} forward=OWNER_LOOK dir=({}, {}, {})",
                    getId(), String.format("%.2f", look.x), String.format("%.2f", look.y), String.format("%.2f", look.z));
                return look;
            }
        }
        StevesArmyMod.LOGGER.info("[Formation] Soldier {} forward=FALLBACK_NORTH", getId());
        return new Vec3(0, 0, -1);
    }

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
}

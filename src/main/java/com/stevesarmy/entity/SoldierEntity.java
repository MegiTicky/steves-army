package com.stevesarmy.entity;

import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.entity.ai.SoldierCombatGoal;
import com.stevesarmy.entity.ai.SoldierFollowOwnerGoal;
import com.stevesarmy.entity.ai.SoldierHoldPositionGoal;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.inventory.SoldierInventoryHandler;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.OpenSoldierInventoryMessage;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
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

    @Nullable
    private UUID squadId;
    @Nullable
    private LivingEntity cachedOwner;
    private final SoldierInventory inventory;
    private final SoldierInventoryHandler inventoryHandler;
    private final LazyOptional<IItemHandler> itemHandlerCap;

    public SoldierEntity(EntityType<? extends SoldierEntity> type, Level level) {
        super(type, level);
        this.setCanPickUpLoot(true);
        this.inventory = new SoldierInventory();
        this.inventoryHandler = new SoldierInventoryHandler(inventory);
        this.itemHandlerCap = LazyOptional.of(() -> inventoryHandler);
        this.inventory.setSlot0ChangedCallback(stack -> {
            if (!this.level().isClientSide) {
                setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
                if (GunIntegration.isTaczLoaded() && !stack.isEmpty()) {
                    GunIntegration.initialData(this);
                    GunIntegration.draw(this);
                }
            }
        });
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(OWNER_UUID, Optional.empty());
        this.entityData.define(FOLLOW_STATE, 1);
        this.entityData.define(SQUAD_MODE, SquadMode.FOLLOW.ordinal());
        this.entityData.define(HOLD_POSITION, BlockPos.ZERO);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new SoldierCombatGoal(this));
        this.goalSelector.addGoal(2, new SoldierFollowOwnerGoal(this));
        this.goalSelector.addGoal(2, new SoldierHoldPositionGoal(this));
        this.goalSelector.addGoal(3, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));
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
                cycleSquadMode();
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

    public void cycleSquadMode() {
        SquadMode current = getSquadMode();
        setSquadMode(current == SquadMode.FOLLOW ? SquadMode.HOLD : SquadMode.FOLLOW);
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

    @Override
    public boolean canBeLeashed(Player player) {
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

    @Override
    protected void dropEquipment() {
    }

    @Override
    public boolean canPickUpLoot() {
        return true;
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
        inventory.setItem(slot, stack);
        if (slot == 0) {
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
    public boolean stillValid(Player player) {
        return this.isAlive() && this.distanceTo(player) <= 64.0F;
    }

    @Override
    public void tick() {
        super.tick();
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
}

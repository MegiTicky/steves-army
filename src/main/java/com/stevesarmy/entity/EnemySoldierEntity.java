package com.stevesarmy.entity;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import com.stevesarmy.entity.ai.EnemyDefendGoal;
import com.stevesarmy.entity.ai.SoldierCombatGoal;
import com.stevesarmy.entity.ai.SoldierHealGoal;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

public class EnemySoldierEntity extends SoldierEntity {

    private static final double DEFEND_RADIUS = 20.0;
    private static final UUID ENEMY_SQUAD_LEADER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final int INFINITE_RESERVE_AMMO = 1_000_000;
    private BlockPos defendPosition = null;

    public EnemySoldierEntity(EntityType<? extends SoldierEntity> type, Level level) {
        super(type, level);
        this.setSquadMode(SquadMode.HOLD);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(1, new SoldierHealGoal(this));
        this.goalSelector.addGoal(2, initializeCoverTacticalGoal());
        this.goalSelector.addGoal(2, initializeCombatGoal());
        this.goalSelector.addGoal(3, new EnemyDefendGoal(this));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide) {
            if (defendPosition == null) {
                defendPosition = this.blockPosition();
            }

            ensureEnemySquadMembership();
        }
    }
    
    private void ensureEnemySquadMembership() {
        if (this.getSquadId() != null) return;
        
        if (!(this.level() instanceof ServerLevel serverLevel)) return;
        
        SquadManager manager = SquadManager.get(serverLevel);
        Optional<SquadData> existingSquad = manager.getSquadByLeader(ENEMY_SQUAD_LEADER_ID);
        
        SquadData enemySquad;
        if (existingSquad.isPresent()) {
            enemySquad = existingSquad.get();
        } else {
            enemySquad = manager.createSquad(ENEMY_SQUAD_LEADER_ID);
        }
        
        enemySquad.addMember(this.getUUID());
        manager.addMemberToSquad(enemySquad.getSquadId(), this.getUUID());
        this.setSquadId(enemySquad.getSquadId());
        
        StevesArmyMod.LOGGER.info("[EnemySquad] Enemy soldier {} joined enemy squad {}", this.getId(), enemySquad.getSquadId().toString().substring(0, 8));
    }

    public BlockPos getDefendPosition() {
        return defendPosition;
    }
    
    public void setDefendPosition(BlockPos pos) {
        this.defendPosition = pos;
    }

    public double getDefendRadius() {
        return DEFEND_RADIUS;
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
        if (target instanceof EnemySoldierEntity) return false;
        return super.canAttack(target);
    }

    public boolean isFriendlyTo(LivingEntity other) {
        if (other == this) return false;
        if (other instanceof EnemySoldierEntity) return true;
        return false;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack mainHandStack = player.getMainHandItem();
        ItemStack equipmentStack = player.getOffhandItem();
        boolean hasEmptyMainHand = mainHandStack.isEmpty();
        boolean isSneaking = player.isShiftKeyDown();
        boolean equippingGun = GunIntegration.isGun(equipmentStack);
        EquipmentSlot equipmentSlot = equippingGun ? EquipmentSlot.MAINHAND : getArmorSlot(equipmentStack);

        StevesArmyMod.LOGGER.info("[EnemyEquipment] Interaction: side={}, enemy={}, player={}, hand={}, sneaking={}, mainHand={}, offHand={}, offHandGun={}, armorSlot={}",
            this.level().isClientSide ? "client" : "server", this.getId(), player.getGameProfile().getName(), hand,
            isSneaking, mainHandStack, equipmentStack, equippingGun, equipmentSlot);

        // The equipped item is always read from the offhand. An empty main hand prevents
        // TaCZ from treating the configuration gesture as an attempt to aim or fire.
        if (!isSneaking || !hasEmptyMainHand || equipmentSlot == null) {
            StevesArmyMod.LOGGER.info("[EnemyEquipment] Ignored: sneaking={}, emptyMainHand={}, validEquipment={}",
                isSneaking, hasEmptyMainHand, equipmentSlot != null);
            return super.mobInteract(player, hand);
        }

        if (!this.level().isClientSide) {
            ItemStack replacedStack = getItemBySlot(equipmentSlot).copy();
            if (equippingGun) {
                // TaCZ keeps reload state on the shooter, so stop it before the old gun leaves the hand.
                GunIntegration.cancelReload(this);
                GunIntegration.aim(this, false);
            }
            setItemSlot(equipmentSlot, equipmentStack.copyWithCount(1));
            if (equippingGun) {
                configureInfiniteReserveAmmo();
                GunIntegration.initialData(this);
                GunIntegration.draw(this);
            }

            if (!player.getAbilities().instabuild) {
                equipmentStack.shrink(1);
            }

            if (!replacedStack.isEmpty() && !player.getInventory().add(replacedStack)) {
                player.drop(replacedStack, false);
            }

            StevesArmyMod.LOGGER.info("[EnemyEquipment] Equipped: enemy={}, slot={}, newItem={}, replacedItem={}, creative={}",
                this.getId(), equipmentSlot, getItemBySlot(equipmentSlot), replacedStack, player.getAbilities().instabuild);
        }

        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    private EquipmentSlot getArmorSlot(ItemStack stack) {
        if (!(stack.getItem() instanceof ArmorItem armorItem)) {
            return null;
        }

        EquipmentSlot slot = armorItem.getEquipmentSlot();
        return slot.getType() == EquipmentSlot.Type.ARMOR ? slot : null;
    }

    @Override
    public ItemStack getPickedResult(HitResult target) {
        getSoldierInventory().syncFromEntity(this);

        var egg = (com.stevesarmy.item.EnemySoldierSpawnEggItem) com.stevesarmy.registry.ModItems.ENEMY_SOLDIER_SPAWN_EGG.get();
        ItemStack stack = new ItemStack(egg);
        CompoundTag tag = new CompoundTag();
        tag.put("Inventory", getSoldierInventory().save());
        stack.getOrCreateTag().put("EntityTag", tag);
        return stack;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (defendPosition != null) {
            tag.putLong("DefendPosition", defendPosition.asLong());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("DefendPosition")) {
            defendPosition = BlockPos.of(tag.getLong("DefendPosition"));
        }
    }

    public boolean hasInfiniteReserveAmmo() {
        if (!GunIntegration.isTaczLoaded()) return false;
        try {
            ItemStack gunStack = this.getMainHandItem();
            if (!GunIntegration.isGun(gunStack)) return false;

            Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
            Method getIGunOrNull = iGunClass.getMethod("getIGunOrNull", ItemStack.class);
            Object iGun = getIGunOrNull.invoke(null, gunStack);
            if (iGun == null) return false;

            boolean usesDummyAmmo = (boolean) iGunClass.getMethod("useDummyAmmo", ItemStack.class).invoke(iGun, gunStack);
            int reserveAmmo = (int) iGunClass.getMethod("getDummyAmmoAmount", ItemStack.class).invoke(iGun, gunStack);
            int maxReserveAmmo = (int) iGunClass.getMethod("getMaxDummyAmmoAmount", ItemStack.class).invoke(iGun, gunStack);
            return usesDummyAmmo && reserveAmmo > 0 && maxReserveAmmo > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Gives the selected gun an internal TaCZ reserve without changing its magazine. */
    public void configureInfiniteReserveAmmo() {
        ensureInfiniteReserveAmmo();
    }

    /**
     * Restores virtual reserve before TaCZ validates a reload. This intentionally does
     * not touch the current magazine or chamber, so each reload remains a normal TaCZ reload.
     */
    public boolean ensureInfiniteReserveAmmo() {
        if (!GunIntegration.isTaczLoaded()) return false;
        try {
            ItemStack gunStack = getMainHandItem();
            if (!GunIntegration.isGun(gunStack)) return false;

            Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
            Method getIGunOrNull = iGunClass.getMethod("getIGunOrNull", ItemStack.class);
            Object iGun = getIGunOrNull.invoke(null, gunStack);
            if (iGun == null) return false;

            iGunClass.getMethod("setMaxDummyAmmoAmount", ItemStack.class, int.class)
                .invoke(iGun, gunStack, INFINITE_RESERVE_AMMO);
            iGunClass.getMethod("setDummyAmmoAmount", ItemStack.class, int.class)
                .invoke(iGun, gunStack, INFINITE_RESERVE_AMMO);

            return (boolean) iGunClass.getMethod("useDummyAmmo", ItemStack.class).invoke(iGun, gunStack);
        } catch (Exception e) {
            StevesArmyMod.LOGGER.warn("[EnemyAmmo] Failed to configure infinite reserve for enemy {} gun {}: {}",
                this.getId(), getMainHandItem(), e.toString());
            return false;
        }
    }
}

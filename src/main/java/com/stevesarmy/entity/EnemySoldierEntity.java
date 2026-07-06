package com.stevesarmy.entity;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import com.stevesarmy.entity.ai.EnemyDefendGoal;
import com.stevesarmy.entity.ai.SoldierCombatGoal;
import com.stevesarmy.entity.ai.TargetPlayerSoldierGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

public class EnemySoldierEntity extends SoldierEntity {

    private static final double DEFEND_RADIUS = 20.0;
    private BlockPos defendPosition = null;

    public EnemySoldierEntity(EntityType<? extends SoldierEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new CoverTacticalGoal(this));
        this.goalSelector.addGoal(2, new SoldierCombatGoal(this));
        this.goalSelector.addGoal(3, new EnemyDefendGoal(this));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new TargetPlayerSoldierGoal(this));
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide) {
            if (defendPosition == null) {
                defendPosition = this.blockPosition();
            }

            refillAmmo();
        }
    }

    public BlockPos getDefendPosition() {
        return defendPosition;
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

    private void refillAmmo() {
        if (!GunIntegration.isTaczLoaded()) return;
        try {
            ItemStack gunStack = this.getMainHandItem();
            if (gunStack.isEmpty()) return;

            Class<?> iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
            Method getIGunOrNull = iGunClass.getMethod("getIGunOrNull", ItemStack.class);
            Object iGun = getIGunOrNull.invoke(null, gunStack);
            if (iGun == null) return;

            Method getCurrentAmmoCount = iGunClass.getMethod("getCurrentAmmoCount", ItemStack.class);
            int currentAmmo = (int) getCurrentAmmoCount.invoke(iGun, gunStack);

            Method getGunId = iGunClass.getMethod("getGunId", ItemStack.class);
            Object gunId = getGunId.invoke(iGun, gunStack);

            Class<?> timelessApiClass = Class.forName("com.tacz.guns.api.TimelessAPI");
            Method getCommonGunIndex = timelessApiClass.getMethod("getCommonGunIndex", ResourceLocation.class);
            Object indexOpt = getCommonGunIndex.invoke(null, gunId);

            if (indexOpt instanceof java.util.Optional<?> opt && opt.isPresent()) {
                Object gunIndex = opt.get();
                Method getGunData = gunIndex.getClass().getMethod("getGunData");
                Object gunData = getGunData.invoke(gunIndex);
                Method getMagazineSize = gunData.getClass().getMethod("getMagazineSize");
                int magSize = (int) getMagazineSize.invoke(gunData);

                if (currentAmmo < magSize) {
                    Method setAmmoCount = iGunClass.getMethod("setAmmoCount", ItemStack.class, int.class);
                    setAmmoCount.invoke(iGun, gunStack, magSize);
                }
            }
        } catch (Exception e) {
        }
    }
}
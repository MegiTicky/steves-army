package com.stevesarmy.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class TargetEntity extends PathfinderMob {
    private static final EntityDataAccessor<Boolean> MARKER = 
        SynchedEntityData.defineId(TargetEntity.class, EntityDataSerializers.BOOLEAN);
    
    public TargetEntity(EntityType<? extends TargetEntity> type, Level level) {
        super(type, level);
        this.noPhysics = false;
    }
    
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.0D)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }
    
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
    }
    
    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(MARKER, false);
    }
    
    @Override
    public void tick() {
        super.tick();
        
        if (this.level().isClientSide && this.hurtTime > 0) {
            this.level().addParticle(ParticleTypes.DAMAGE_INDICATOR, 
                this.getX(), this.getY() + this.getBbHeight() * 0.5, this.getZ(), 
                0.0, 0.0, 0.0);
        }
    }
    
    @Override
    public boolean isPushable() {
        return false;
    }
    
    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity) {
    }
    
    @Override
    protected void pushEntities() {
    }
    
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return false;
    }
    
    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ARMOR_STAND_HIT;
    }
    
    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ARMOR_STAND_BREAK;
    }
    
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Marker", this.entityData.get(MARKER));
    }
    
    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(MARKER, tag.getBoolean("Marker"));
    }
    
    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }
    
    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
    }
    
    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        return ItemStack.EMPTY;
    }
}

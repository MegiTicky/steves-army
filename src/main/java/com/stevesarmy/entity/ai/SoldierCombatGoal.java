package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.combat.ThreatTracker;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

public class SoldierCombatGoal extends Goal {
    private final SoldierEntity soldier;
    private final ThreatTracker threatTracker;
    private LivingEntity target;
    private final double speedModifier = 1.2;
    private final double meleeRange = 3.0;
    private final double chaseRange = 16.0;
    private long lastTargetScan = 0;
    private static final long SCAN_INTERVAL = 5;
    
    private boolean gunInitialized = false;
    private ItemStack lastGunStack = ItemStack.EMPTY;
    private boolean wasAiming = false;
    private boolean wasReloading = false;
    
    private static final float AIM_PROGRESS_THRESHOLD = 0.8f;
    private static final int AIM_DELAY_TICKS = 10;
    private int aimDelayCounter = 0;
    
    private int reloadCheckCooldown = 0;
    private static final int RELOAD_CHECK_INTERVAL = 20;

    private int debugLogCounter = 0;
    private static final int DEBUG_LOG_INTERVAL = 20;

    public SoldierCombatGoal(SoldierEntity soldier) {
        this.soldier = soldier;
        this.threatTracker = new ThreatTracker();
        this.setFlags(EnumSet.of(Flag.TARGET, Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!soldier.isAlive()) return false;
        if (soldier.getTarget() != null && soldier.getTarget().isAlive()) {
            return true;
        }
        
        long currentTime = soldier.level().getGameTime();
        if (currentTime - lastTargetScan < SCAN_INTERVAL) return false;
        lastTargetScan = currentTime;
        
        return findNewTarget();
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null) return false;
        if (!target.isAlive()) return false;
        if (!TargetAcquisition.isValidTarget(soldier, target)) {
            target = null;
            return false;
        }
        
        if (!TargetAcquisition.canSeeTarget(soldier, target)) {
            long timeSinceSeen = soldier.level().getGameTime() - lastTargetScan;
            if (timeSinceSeen > 100) {
                return false;
            }
        }
        
        return true;
    }

    @Override
    public void start() {
        soldier.setTarget(target);
        if (target != null) {
            threatTracker.reportThreatDirect(target, soldier);
        }
        wasAiming = false;
        aimDelayCounter = 0;
        reloadCheckCooldown = 0;
    }

    @Override
    public void stop() {
        if (GunIntegration.isTaczLoaded() && GunIntegration.hasGun(soldier)) {
            GunIntegration.aim(soldier, false);
        }
        soldier.setTarget(null);
        this.target = null;
        this.wasAiming = false;
        this.aimDelayCounter = 0;
    }

    @Override
    public void tick() {
        threatTracker.update(soldier);
        
        if (reloadCheckCooldown > 0) {
            reloadCheckCooldown--;
        }
        
        boolean hasGun = GunIntegration.isTaczLoaded() && GunIntegration.hasGun(soldier);
        
        if (hasGun) {
            ItemStack currentGun = soldier.getMainHandItem();
            boolean gunChanged = !lastGunStack.isEmpty() && !ItemStack.isSameItem(lastGunStack, currentGun);
            
            if (gunChanged) {
                StevesArmyMod.LOGGER.info("[Combat Debug] Gun changed from {} to {} for soldier {}", lastGunStack.getItem(), currentGun.getItem(), soldier.getId());
            }
            
            if (!gunInitialized) {
                StevesArmyMod.LOGGER.info("[Combat Debug] Initializing gun for soldier {} (gunInitialized={})", soldier.getId(), gunInitialized);
                GunIntegration.initialData(soldier);
                GunIntegration.draw(soldier);
                gunInitialized = true;
                lastGunStack = currentGun.copy();
                StevesArmyMod.LOGGER.info("[Combat Debug] Gun initialized: {} for soldier {}", currentGun.getItem(), soldier.getId());
            }
        }
        
        if (target == null || !target.isAlive()) {
            findNewTarget();
            if (target == null) {
                if (hasGun && wasAiming) {
                    GunIntegration.aim(soldier, false);
                    wasAiming = false;
                }
                return;
            }
        }
        
        double distance = soldier.distanceTo(target);
        boolean canSee = TargetAcquisition.canSeeTarget(soldier, target);
        
        if (canSee) {
            threatTracker.reportThreatDirect(target, soldier);
            lastTargetScan = soldier.level().getGameTime();
        }
        
        soldier.getLookControl().setLookAt(target, 30.0F, 30.0F);
        
        double effectiveRange = hasGun ? GunIntegration.getEffectiveRange(soldier) : meleeRange;
        
        if (hasGun) {
            tickGunCombat(distance, canSee, effectiveRange);
        } else {
            tickMeleeCombat(distance);
        }
    }

    private void tickGunCombat(double distance, boolean canSee, double effectiveRange) {
        debugLogCounter++;
        boolean shouldLog = debugLogCounter >= DEBUG_LOG_INTERVAL;
        if (shouldLog) debugLogCounter = 0;
        
        boolean isDrawing = GunIntegration.isDrawing(soldier);
        boolean isBolting = GunIntegration.isBolting(soldier);
        boolean isReloading = GunIntegration.isReloading(soldier);
        int ammo = GunIntegration.getCurrentAmmo(soldier);
        
        if (wasReloading && !isReloading) {
            StevesArmyMod.LOGGER.info("[Combat Debug] Soldier {} reload completed, re-initializing gun", soldier.getId());
            GunIntegration.initialData(soldier);
            GunIntegration.draw(soldier);
            wasReloading = false;
            return;
        }
        
        if (isReloading) {
            wasReloading = true;
            if (shouldLog) StevesArmyMod.LOGGER.info("[Combat Debug] Soldier {} Reloading (ammo={})", soldier.getId(), ammo);
            return;
        }
        
        if (isDrawing) {
            if (shouldLog) StevesArmyMod.LOGGER.info("[Combat Debug] Soldier {} Drawing weapon", soldier.getId());
            return;
        }
        
        if (isBolting) {
            if (shouldLog) StevesArmyMod.LOGGER.info("[Combat Debug] Soldier {} Bolting", soldier.getId());
            return;
        }
        
        if (!canSee) {
            if (wasAiming) {
                GunIntegration.aim(soldier, false);
                wasAiming = false;
                aimDelayCounter = 0;
            }
            if (shouldLog) StevesArmyMod.LOGGER.info("[Combat Debug] Cannot see target, moving");
            moveToTarget(distance);
            return;
        }
        
        if (distance > effectiveRange * 1.1) {
            if (wasAiming) {
                GunIntegration.aim(soldier, false);
                wasAiming = false;
                aimDelayCounter = 0;
            }
            if (shouldLog) StevesArmyMod.LOGGER.info("[Combat Debug] Target too far ({}/{})", String.format("%.1f", distance), String.format("%.1f", effectiveRange));
            moveToTarget(distance);
            return;
        }
        
        GunIntegration.aim(soldier, true);
        wasAiming = true;
        
        float aimProgress = GunIntegration.getAimProgress(soldier);
        if (aimProgress < AIM_PROGRESS_THRESHOLD) {
            aimDelayCounter++;
            if (shouldLog) StevesArmyMod.LOGGER.info("[Combat Debug] Aiming... progress={}", String.format("%.2f", aimProgress));
            return;
        }
        
        if (shouldLog) {
            StevesArmyMod.LOGGER.info("[Combat Debug] State: drawing={}, bolting={}, reloading={}, aimProgress={}", 
                GunIntegration.isDrawing(soldier),
                GunIntegration.isBolting(soldier),
                GunIntegration.isReloading(soldier),
                String.format("%.2f", aimProgress));
        }
        
        GunIntegration.ShootResult result = GunIntegration.shoot(soldier, target);
        
        switch (result) {
            case SUCCESS -> {
                aimDelayCounter = 0;
            }
            case NEED_BOLT -> {
                GunIntegration.bolt(soldier);
            }
            case NO_AMMO -> {
                StevesArmyMod.LOGGER.info("[Combat Debug] NO_AMMO - Current ammo: {}, barrel: {}, magSize: {}", 
                    GunIntegration.getCurrentAmmo(soldier),
                    GunIntegration.hasAmmoInBarrel(soldier),
                    GunIntegration.getMagazineSize(soldier));
                
                soldier.getCapability(ForgeCapabilities.ITEM_HANDLER, null).ifPresent(handler -> {
                    StringBuilder inv = new StringBuilder("[Combat Debug] Inventory contents: ");
                    for (int i = 0; i < handler.getSlots(); i++) {
                        ItemStack slot = handler.getStackInSlot(i);
                        if (!slot.isEmpty()) {
                            inv.append(String.format("[%d]=%s x%d, ", i, slot.getItem().toString(), slot.getCount()));
                        }
                    }
                    StevesArmyMod.LOGGER.info(inv.toString());
                });
                
                GunIntegration.reload(soldier);
                StevesArmyMod.LOGGER.info("[Combat Debug] Reload called, isReloading: {}", GunIntegration.isReloading(soldier));
            }
            case COOLDOWN -> {
            }
            case IS_BOLTING, IS_RELOADING, IS_DRAWING -> {
            }
            case NOT_DRAWN -> {
                GunIntegration.draw(soldier);
            }
            default -> {
            }
        }
    }

    private void tickMeleeCombat(double distance) {
        if (distance <= meleeRange) {
            soldier.doHurtTarget(target);
        } else {
            moveToTarget(distance);
        }
    }

    private void moveToTarget(double distance) {
        if (distance > chaseRange || !TargetAcquisition.canSeeTarget(soldier, target)) {
            PathNavigation nav = soldier.getNavigation();
            var path = nav.createPath(target, 0);
            if (path != null) {
                nav.moveTo(path, speedModifier);
            }
        }
    }

    private boolean findNewTarget() {
        List<LivingEntity> visibleEnemies = new ArrayList<>();
        
        double detectionRange = TargetAcquisition.getEffectiveDetectionRange(soldier);
        List<Monster> nearbyMonsters = soldier.level().getEntitiesOfClass(
            Monster.class,
            soldier.getBoundingBox().inflate(detectionRange)
        );
        
        for (Monster monster : nearbyMonsters) {
            if (TargetAcquisition.isValidTarget(soldier, monster) &&
                TargetAcquisition.canSeeTarget(soldier, monster)) {
                visibleEnemies.add(monster);
            }
        }
        
        if (visibleEnemies.isEmpty()) {
            this.target = null;
            return false;
        }
        
        visibleEnemies.sort(Comparator.comparingDouble(e -> e.distanceToSqr(soldier)));
        
        this.target = visibleEnemies.get(0);
        return true;
    }
}
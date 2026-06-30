package com.stevesarmy.combat.cover;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class ThreatDirectionCalculator {
    
    private static final double FLANKING_THRESHOLD_DEGREES = 90.0;
    private static final double ACTIVE_SHOOTER_WEIGHT_MULTIPLIER = 2.0;
    private static final double AIMING_AT_WEIGHT_MULTIPLIER = 1.5;
    private static final double MAX_THREAT_DISTANCE = 100.0;
    
    public Vec3 calculatePrimaryThreatDirection(LivingEntity soldier, List<LivingEntity> threats) {
        if (threats == null || threats.isEmpty()) {
            return Vec3.ZERO;
        }
        
        Vec3 totalThreat = Vec3.ZERO;
        Vec3 soldierPos = soldier.position();
        
        for (LivingEntity threat : threats) {
            if (!threat.isAlive()) continue;
            
            double weight = calculateThreatWeight(soldier, threat, soldierPos);
            if (weight <= 0) continue;
            
            Vec3 threatPos = threat.position();
            Vec3 direction = soldierPos.subtract(threatPos).normalize();
            totalThreat = totalThreat.add(direction.scale(weight));
        }
        
        if (totalThreat.lengthSqr() < 0.001) {
            return Vec3.ZERO;
        }
        
        return totalThreat.normalize();
    }
    
    public double calculateThreatWeight(LivingEntity soldier, LivingEntity threat) {
        return calculateThreatWeight(soldier, threat, soldier.position());
    }
    
    private double calculateThreatWeight(LivingEntity soldier, LivingEntity threat, Vec3 soldierPos) {
        double distance = soldierPos.distanceTo(threat.position());
        if (distance > MAX_THREAT_DISTANCE) {
            return 0.0;
        }
        
        double baseWeight = 100.0 / (distance + 1.0);
        
        if (isActiveShooter(threat)) {
            baseWeight *= ACTIVE_SHOOTER_WEIGHT_MULTIPLIER;
        }
        
        if (isAimingAt(threat, soldier)) {
            baseWeight *= AIMING_AT_WEIGHT_MULTIPLIER;
        }
        
        return baseWeight;
    }
    
    private boolean isActiveShooter(LivingEntity entity) {
        if (entity.getMainHandItem() == null) {
            return false;
        }
        return entity.isUsingItem() || entity.getTicksUsingItem() > 0;
    }
    
    private boolean isAimingAt(LivingEntity shooter, LivingEntity target) {
        Vec3 lookVec = shooter.getLookAngle();
        Vec3 toTarget = target.position().subtract(shooter.position()).normalize();
        double dot = lookVec.dot(toTarget);
        return dot > 0.95;
    }
    
    public boolean isBeingFlanked(LivingEntity soldier, List<LivingEntity> threats) {
        if (threats == null || threats.size() < 2) {
            return false;
        }
        
        Vec3 soldierPos = soldier.position();
        double minAngle = 360.0;
        double maxAngle = 0.0;
        int validThreats = 0;
        
        for (LivingEntity threat : threats) {
            if (!threat.isAlive()) continue;
            
            Vec3 toThreat = threat.position().subtract(soldierPos);
            double angle = Math.toDegrees(Math.atan2(toThreat.x, toThreat.z));
            angle = normalizeAngle(angle);
            
            minAngle = Math.min(minAngle, angle);
            maxAngle = Math.max(maxAngle, angle);
            validThreats++;
        }
        
        if (validThreats < 2) {
            return false;
        }
        
        double span = calculateAngularSpan(minAngle, maxAngle);
        return span > FLANKING_THRESHOLD_DEGREES;
    }
    
    private double normalizeAngle(double angle) {
        while (angle < 0) angle += 360.0;
        while (angle >= 360.0) angle -= 360.0;
        return angle;
    }
    
    private double calculateAngularSpan(double minAngle, double maxAngle) {
        double directSpan = maxAngle - minAngle;
        if (directSpan <= 180.0) {
            return directSpan;
        }
        return 360.0 - directSpan;
    }
    
    public float getThreatCoverage(BlockPos coverPos, List<LivingEntity> threats, 
                                    CoverQualityEvaluator evaluator) {
        if (threats == null || threats.isEmpty()) {
            return 1.0f;
        }
        
        int protectedCount = 0;
        Vec3 coverCenter = coverPos.getCenter();
        
        for (LivingEntity threat : threats) {
            if (!threat.isAlive()) continue;
            
            Vec3 threatDir = coverCenter.subtract(threat.position()).normalize();
            if (isDirectionProtected(coverPos, threatDir)) {
                protectedCount++;
            }
        }
        
        return (float) protectedCount / threats.size();
    }
    
    public boolean isDirectionProtected(BlockPos coverPos, Vec3 threatDirection) {
        double dx = threatDirection.x;
        double dz = threatDirection.z;
        
        double absX = Math.abs(dx);
        double absZ = Math.abs(dz);
        
        if (absX > absZ) {
            return dx > 0;
        } else {
            return dz > 0;
        }
    }
    
    public ThreatAnalysis analyzeThreats(LivingEntity soldier, List<LivingEntity> threats) {
        ThreatAnalysis analysis = new ThreatAnalysis();
        
        analysis.primaryDirection = calculatePrimaryThreatDirection(soldier, threats);
        analysis.isFlanked = isBeingFlanked(soldier, threats);
        analysis.threatCount = threats != null ? (int) threats.stream().filter(LivingEntity::isAlive).count() : 0;
        
        if (threats != null && !threats.isEmpty()) {
            analysis.closestThreatDistance = threats.stream()
                .filter(LivingEntity::isAlive)
                .mapToDouble(t -> t.distanceTo(soldier))
                .min()
                .orElse(Double.MAX_VALUE);
        }
        
        return analysis;
    }
    
    public static class ThreatAnalysis {
        public Vec3 primaryDirection = Vec3.ZERO;
        public boolean isFlanked = false;
        public int threatCount = 0;
        public double closestThreatDistance = Double.MAX_VALUE;
    }
}

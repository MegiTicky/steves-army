package com.stevesarmy.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public class CombatDebugData {
    public final UUID soldierId;
    public final UUID targetId;
    public final Vec3 soldierPos;
    public final Vec3 targetPos;
    
    public final double detectionPoints;
    public final double detectionThreshold;
    public final boolean hasLOS;
    public final boolean inFocusedArc;
    public final boolean inPeripheralArc;
    
    public final double distanceFactor;
    public final double exposureFactor;
    public final double movementFactor;
    public final double brightnessFactor;
    public final double baseRate;
    
    public final boolean isDetected;
    public final double distance;
    public final boolean isLockedTarget;
    
    public final float aimQuality;
    public final float targetAimQuality;
    public final float shotThreshold;
    public final float suppressiveMin;
    public final float adsProgress;
    public final String aimPointType;
    public final boolean bulletPathClear;
    
    public final boolean isSuppressing;
    public final Vec3 suppressionTargetPos;
    
    public CombatDebugData(UUID soldierId, UUID targetId, Vec3 soldierPos, Vec3 targetPos,
                          double detectionPoints, double detectionThreshold,
                          boolean hasLOS, boolean inFocusedArc, boolean inPeripheralArc,
                          double distanceFactor, double exposureFactor, double movementFactor,
                          double brightnessFactor, double baseRate, boolean isDetected, 
                          double distance, boolean isLockedTarget,
                          float aimQuality, float targetAimQuality, float shotThreshold, float suppressiveMin,
                          float adsProgress, String aimPointType, boolean bulletPathClear,
                          boolean isSuppressing, Vec3 suppressionTargetPos) {
        this.soldierId = soldierId;
        this.targetId = targetId;
        this.soldierPos = soldierPos;
        this.targetPos = targetPos;
        this.detectionPoints = detectionPoints;
        this.detectionThreshold = detectionThreshold;
        this.hasLOS = hasLOS;
        this.inFocusedArc = inFocusedArc;
        this.inPeripheralArc = inPeripheralArc;
        this.distanceFactor = distanceFactor;
        this.exposureFactor = exposureFactor;
        this.movementFactor = movementFactor;
        this.brightnessFactor = brightnessFactor;
        this.baseRate = baseRate;
        this.isDetected = isDetected;
        this.distance = distance;
        this.isLockedTarget = isLockedTarget;
        this.aimQuality = aimQuality;
        this.targetAimQuality = targetAimQuality;
        this.shotThreshold = shotThreshold;
        this.suppressiveMin = suppressiveMin;
        this.adsProgress = adsProgress;
        this.aimPointType = aimPointType;
        this.bulletPathClear = bulletPathClear;
        this.isSuppressing = isSuppressing;
        this.suppressionTargetPos = suppressionTargetPos;
    }
    
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(soldierId);
        buf.writeUUID(targetId);
        buf.writeDouble(soldierPos.x);
        buf.writeDouble(soldierPos.y);
        buf.writeDouble(soldierPos.z);
        buf.writeDouble(targetPos.x);
        buf.writeDouble(targetPos.y);
        buf.writeDouble(targetPos.z);
        buf.writeDouble(detectionPoints);
        buf.writeDouble(detectionThreshold);
        buf.writeBoolean(hasLOS);
        buf.writeBoolean(inFocusedArc);
        buf.writeBoolean(inPeripheralArc);
        buf.writeDouble(distanceFactor);
        buf.writeDouble(exposureFactor);
        buf.writeDouble(movementFactor);
        buf.writeDouble(brightnessFactor);
        buf.writeDouble(baseRate);
        buf.writeBoolean(isDetected);
        buf.writeDouble(distance);
        buf.writeBoolean(isLockedTarget);
        buf.writeFloat(aimQuality);
        buf.writeFloat(targetAimQuality);
        buf.writeFloat(shotThreshold);
        buf.writeFloat(suppressiveMin);
        buf.writeFloat(adsProgress);
        buf.writeUtf(aimPointType);
        buf.writeBoolean(bulletPathClear);
        buf.writeBoolean(isSuppressing);
        buf.writeBoolean(suppressionTargetPos != null);
        if (suppressionTargetPos != null) {
            buf.writeDouble(suppressionTargetPos.x);
            buf.writeDouble(suppressionTargetPos.y);
            buf.writeDouble(suppressionTargetPos.z);
        }
    }
    
    public static CombatDebugData decode(FriendlyByteBuf buf) {
        UUID soldierId = buf.readUUID();
        UUID targetId = buf.readUUID();
        Vec3 soldierPos = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        Vec3 targetPos = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        double detectionPoints = buf.readDouble();
        double detectionThreshold = buf.readDouble();
        boolean hasLOS = buf.readBoolean();
        boolean inFocusedArc = buf.readBoolean();
        boolean inPeripheralArc = buf.readBoolean();
        double distanceFactor = buf.readDouble();
        double exposureFactor = buf.readDouble();
        double movementFactor = buf.readDouble();
        double brightnessFactor = buf.readDouble();
        double baseRate = buf.readDouble();
        boolean isDetected = buf.readBoolean();
        double distance = buf.readDouble();
        boolean isLockedTarget = buf.readBoolean();
        float aimQuality = buf.readFloat();
        float targetAimQuality = buf.readFloat();
        float shotThreshold = buf.readFloat();
        float suppressiveMin = buf.readFloat();
        float adsProgress = buf.readFloat();
        String aimPointType = buf.readUtf();
        boolean bulletPathClear = buf.readBoolean();
        boolean isSuppressing = buf.readBoolean();
        Vec3 suppressionTargetPos = null;
        if (buf.readBoolean()) {
            suppressionTargetPos = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        }
        
        return new CombatDebugData(soldierId, targetId, soldierPos, targetPos,
            detectionPoints, detectionThreshold, hasLOS, inFocusedArc, inPeripheralArc,
            distanceFactor, exposureFactor, movementFactor, brightnessFactor, baseRate,
            isDetected, distance, isLockedTarget,
            aimQuality, targetAimQuality, shotThreshold, suppressiveMin, adsProgress, aimPointType, bulletPathClear,
            isSuppressing, suppressionTargetPos);
    }
}

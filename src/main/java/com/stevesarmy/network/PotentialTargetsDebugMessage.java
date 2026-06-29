package com.stevesarmy.network;

import com.stevesarmy.client.PingClientEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class PotentialTargetsDebugMessage {
    private final UUID soldierUUID;
    private final UUID lockedTargetUUID;
    private final Vec3 soldierPos;
    private final Vec3 lockedTargetPos;
    private final double lockedDetectionPoints;
    private final double lockedDistance;
    private final boolean lockedHasLOS;
    private final boolean lockedInFocused;
    private final boolean lockedInPeripheral;
    private final boolean lockedIsDetected;
    private final double lockedDistanceFactor;
    private final double lockedExposureFactor;
    private final double lockedMovementFactor;
    private final double lockedBrightnessFactor;
    private final float lockedTrackingProgress;
    private final float lockedAccuracy;
    private final float lockedShotThreshold;
    private final float lockedAdsProgress;
    private final String lockedAimPointType;
    private final boolean lockedBulletPathClear;
    private final List<PotentialTargetEntry> potentialTargets;
    
    public PotentialTargetsDebugMessage(UUID soldierUUID, UUID lockedTargetUUID, 
                                        Vec3 soldierPos, Vec3 lockedTargetPos,
                                        double lockedDetectionPoints, double lockedDistance,
                                        boolean lockedHasLOS, boolean lockedInFocused, 
                                        boolean lockedInPeripheral, boolean lockedIsDetected,
                                        double lockedDistanceFactor, double lockedExposureFactor,
                                        double lockedMovementFactor, double lockedBrightnessFactor,
                                        float lockedTrackingProgress, float lockedAccuracy,
                                        float lockedShotThreshold, float lockedAdsProgress,
                                        String lockedAimPointType, boolean lockedBulletPathClear,
                                        List<PotentialTargetEntry> potentialTargets) {
        this.soldierUUID = soldierUUID;
        this.lockedTargetUUID = lockedTargetUUID;
        this.soldierPos = soldierPos;
        this.lockedTargetPos = lockedTargetPos;
        this.lockedDetectionPoints = lockedDetectionPoints;
        this.lockedDistance = lockedDistance;
        this.lockedHasLOS = lockedHasLOS;
        this.lockedInFocused = lockedInFocused;
        this.lockedInPeripheral = lockedInPeripheral;
        this.lockedIsDetected = lockedIsDetected;
        this.lockedDistanceFactor = lockedDistanceFactor;
        this.lockedExposureFactor = lockedExposureFactor;
        this.lockedMovementFactor = lockedMovementFactor;
        this.lockedBrightnessFactor = lockedBrightnessFactor;
        this.lockedTrackingProgress = lockedTrackingProgress;
        this.lockedAccuracy = lockedAccuracy;
        this.lockedShotThreshold = lockedShotThreshold;
        this.lockedAdsProgress = lockedAdsProgress;
        this.lockedAimPointType = lockedAimPointType;
        this.lockedBulletPathClear = lockedBulletPathClear;
        this.potentialTargets = potentialTargets;
    }
    
    public PotentialTargetsDebugMessage(FriendlyByteBuf buf) {
        this.soldierUUID = buf.readUUID();
        boolean hasLockedTarget = buf.readBoolean();
        this.lockedTargetUUID = hasLockedTarget ? buf.readUUID() : null;
        this.lockedTargetPos = hasLockedTarget ? new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()) : Vec3.ZERO;
        this.lockedDetectionPoints = hasLockedTarget ? buf.readDouble() : 0;
        this.lockedDistance = hasLockedTarget ? buf.readDouble() : 0;
        this.lockedHasLOS = hasLockedTarget ? buf.readBoolean() : false;
        this.lockedInFocused = hasLockedTarget ? buf.readBoolean() : false;
        this.lockedInPeripheral = hasLockedTarget ? buf.readBoolean() : false;
        this.lockedIsDetected = hasLockedTarget ? buf.readBoolean() : false;
        this.lockedDistanceFactor = hasLockedTarget ? buf.readDouble() : 0;
        this.lockedExposureFactor = hasLockedTarget ? buf.readDouble() : 0;
        this.lockedMovementFactor = hasLockedTarget ? buf.readDouble() : 0;
        this.lockedBrightnessFactor = hasLockedTarget ? buf.readDouble() : 0;
        this.lockedTrackingProgress = hasLockedTarget ? buf.readFloat() : 0;
        this.lockedAccuracy = hasLockedTarget ? buf.readFloat() : 0;
        this.lockedShotThreshold = hasLockedTarget ? buf.readFloat() : 0;
        this.lockedAdsProgress = hasLockedTarget ? buf.readFloat() : 0;
        this.lockedAimPointType = hasLockedTarget ? buf.readUtf() : "";
        this.lockedBulletPathClear = hasLockedTarget ? buf.readBoolean() : false;
        
        this.soldierPos = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        int count = buf.readInt();
        this.potentialTargets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID uuid = buf.readUUID();
            Vec3 position = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
            double detectionPoints = buf.readDouble();
            double distance = buf.readDouble();
            boolean hasLOS = buf.readBoolean();
            boolean inFocused = buf.readBoolean();
            boolean inPeripheral = buf.readBoolean();
            double distanceFactor = buf.readDouble();
            double exposureFactor = buf.readDouble();
            double movementFactor = buf.readDouble();
            double brightnessFactor = buf.readDouble();
            this.potentialTargets.add(new PotentialTargetEntry(uuid, position, detectionPoints, distance, 
                hasLOS, inFocused, inPeripheral, distanceFactor, exposureFactor, movementFactor, brightnessFactor));
        }
    }
    
    public static void encode(PotentialTargetsDebugMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.soldierUUID);
        buf.writeBoolean(msg.lockedTargetUUID != null);
        if (msg.lockedTargetUUID != null) {
            buf.writeUUID(msg.lockedTargetUUID);
            buf.writeDouble(msg.lockedTargetPos.x);
            buf.writeDouble(msg.lockedTargetPos.y);
            buf.writeDouble(msg.lockedTargetPos.z);
            buf.writeDouble(msg.lockedDetectionPoints);
            buf.writeDouble(msg.lockedDistance);
            buf.writeBoolean(msg.lockedHasLOS);
            buf.writeBoolean(msg.lockedInFocused);
            buf.writeBoolean(msg.lockedInPeripheral);
            buf.writeBoolean(msg.lockedIsDetected);
            buf.writeDouble(msg.lockedDistanceFactor);
            buf.writeDouble(msg.lockedExposureFactor);
            buf.writeDouble(msg.lockedMovementFactor);
            buf.writeDouble(msg.lockedBrightnessFactor);
            buf.writeFloat(msg.lockedTrackingProgress);
            buf.writeFloat(msg.lockedAccuracy);
            buf.writeFloat(msg.lockedShotThreshold);
            buf.writeFloat(msg.lockedAdsProgress);
            buf.writeUtf(msg.lockedAimPointType);
            buf.writeBoolean(msg.lockedBulletPathClear);
        }
        buf.writeDouble(msg.soldierPos.x);
        buf.writeDouble(msg.soldierPos.y);
        buf.writeDouble(msg.soldierPos.z);
        buf.writeInt(msg.potentialTargets.size());
        for (PotentialTargetEntry entry : msg.potentialTargets) {
            buf.writeUUID(entry.uuid);
            buf.writeDouble(entry.position.x);
            buf.writeDouble(entry.position.y);
            buf.writeDouble(entry.position.z);
            buf.writeDouble(entry.detectionPoints);
            buf.writeDouble(entry.distance);
            buf.writeBoolean(entry.hasLOS);
            buf.writeBoolean(entry.inFocused);
            buf.writeBoolean(entry.inPeripheral);
            buf.writeDouble(entry.distanceFactor);
            buf.writeDouble(entry.exposureFactor);
            buf.writeDouble(entry.movementFactor);
            buf.writeDouble(entry.brightnessFactor);
        }
    }
    
    public static void handle(PotentialTargetsDebugMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> 
                PingClientEvents.receivePotentialTargetsDebug(msg)
            );
        });
        ctx.get().setPacketHandled(true);
    }
    
    public UUID getSoldierUUID() { return soldierUUID; }
    public UUID getLockedTargetUUID() { return lockedTargetUUID; }
    public Vec3 getSoldierPos() { return soldierPos; }
    public Vec3 getLockedTargetPos() { return lockedTargetPos; }
    public double getLockedDetectionPoints() { return lockedDetectionPoints; }
    public double getLockedDistance() { return lockedDistance; }
    public boolean getLockedHasLOS() { return lockedHasLOS; }
    public boolean getLockedInFocused() { return lockedInFocused; }
    public boolean getLockedInPeripheral() { return lockedInPeripheral; }
    public boolean getLockedIsDetected() { return lockedIsDetected; }
    public double getLockedDistanceFactor() { return lockedDistanceFactor; }
    public double getLockedExposureFactor() { return lockedExposureFactor; }
    public double getLockedMovementFactor() { return lockedMovementFactor; }
    public double getLockedBrightnessFactor() { return lockedBrightnessFactor; }
    public float getLockedTrackingProgress() { return lockedTrackingProgress; }
    public float getLockedAccuracy() { return lockedAccuracy; }
    public float getLockedShotThreshold() { return lockedShotThreshold; }
    public float getLockedAdsProgress() { return lockedAdsProgress; }
    public String getLockedAimPointType() { return lockedAimPointType; }
    public boolean getLockedBulletPathClear() { return lockedBulletPathClear; }
    public List<PotentialTargetEntry> getPotentialTargets() { return potentialTargets; }
    
    public static class PotentialTargetEntry {
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
        
        public PotentialTargetEntry(UUID uuid, Vec3 position, double detectionPoints, double distance,
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
}

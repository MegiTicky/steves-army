package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.CombatDebugData;
import com.stevesarmy.combat.DetectionSystem;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.PotentialTargetsDebugMessage;
import com.stevesarmy.ping.PingManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.Optional;
import java.util.UUID;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID, value = Dist.CLIENT)
public class PingClientEvents {
    
    private static WorldRenderContext lastWorldRenderContext = null;
    private static final Map<UUID, SoldierDebugData> soldierDebugDataMap = new HashMap<>();
    
    public static class SoldierDebugData {
        public final UUID soldierUUID;
        public UUID lockedTargetUUID;
        public net.minecraft.world.phys.Vec3 soldierPos;
        public net.minecraft.world.phys.Vec3 lockedTargetPos;
        public double lockedDetectionPoints;
        public double lockedDistance;
        public boolean lockedHasLOS;
        public boolean lockedInFocused;
        public boolean lockedInPeripheral;
        public boolean lockedIsDetected;
        public double lockedDistanceFactor;
        public double lockedExposureFactor;
        public double lockedMovementFactor;
        public double lockedBrightnessFactor;
        public float lockedTrackingProgress;
        public float lockedAccuracy;
        public float lockedShotThreshold;
        public float lockedAdsProgress;
        public List<PotentialTargetsDebugMessage.PotentialTargetEntry> potentialTargets;
        
        public SoldierDebugData(UUID soldierUUID) {
            this.soldierUUID = soldierUUID;
        }
    }
    
    public static void receivePotentialTargetsDebug(PotentialTargetsDebugMessage msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        
        if (!msg.getSoldierUUID().equals(mc.player.getUUID())) {
            SoldierDebugData data = soldierDebugDataMap.computeIfAbsent(msg.getSoldierUUID(), SoldierDebugData::new);
            data.lockedTargetUUID = msg.getLockedTargetUUID();
            data.soldierPos = msg.getSoldierPos();
            data.lockedTargetPos = msg.getLockedTargetPos();
            data.lockedDetectionPoints = msg.getLockedDetectionPoints();
            data.lockedDistance = msg.getLockedDistance();
            data.lockedHasLOS = msg.getLockedHasLOS();
            data.lockedInFocused = msg.getLockedInFocused();
            data.lockedInPeripheral = msg.getLockedInPeripheral();
            data.lockedIsDetected = msg.getLockedIsDetected();
            data.lockedDistanceFactor = msg.getLockedDistanceFactor();
            data.lockedExposureFactor = msg.getLockedExposureFactor();
            data.lockedMovementFactor = msg.getLockedMovementFactor();
            data.lockedBrightnessFactor = msg.getLockedBrightnessFactor();
            data.lockedTrackingProgress = msg.getLockedTrackingProgress();
            data.lockedAccuracy = msg.getLockedAccuracy();
            data.lockedShotThreshold = msg.getLockedShotThreshold();
            data.lockedAdsProgress = msg.getLockedAdsProgress();
            data.potentialTargets = msg.getPotentialTargets();
        }
    }
    
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        
        PingManager.tick();
        PingWheelHandler.tick();
        
        if (CombatDebugRenderer.getDebugMode() != CombatDebugRenderer.DEBUG_MODE_OFF) {
            updateCombatDebugData();
        }
    }
    
    private static void updateCombatDebugData() {
        CombatDebugRenderer.clearDebugData();
        
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        
        int maxUntargeted = CombatDebugRenderer.getMaxUntargeted();
        
        for (SoldierDebugData soldierData : soldierDebugDataMap.values()) {
            SoldierEntity soldier = null;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e.getUUID().equals(soldierData.soldierUUID) && e instanceof SoldierEntity se) {
                    soldier = se;
                    break;
                }
            }
            
            if (soldier == null) continue;
            if (!soldier.getOwnerUUID().map(uuid -> uuid.equals(mc.player.getUUID())).orElse(false)) continue;
            
            if (soldierData.lockedTargetUUID != null && soldierData.lockedTargetPos != null) {
                boolean inFocused = soldierData.lockedInFocused;
                boolean inPeripheral = soldierData.lockedInPeripheral;
                
                CombatDebugData lockedData = new CombatDebugData(
                    soldierData.soldierUUID,
                    soldierData.lockedTargetUUID,
                    soldierData.soldierPos,
                    soldierData.lockedTargetPos,
                    soldierData.lockedDetectionPoints,
                    DetectionSystem.DETECTION_THRESHOLD,
                    soldierData.lockedHasLOS,
                    inFocused,
                    inPeripheral,
                    soldierData.lockedDistanceFactor,
                    soldierData.lockedExposureFactor,
                    soldierData.lockedMovementFactor,
                    soldierData.lockedBrightnessFactor,
                    inFocused ? DetectionSystem.BASE_FOCUSED_RATE : DetectionSystem.BASE_PERIPHERAL_RATE,
                    soldierData.lockedIsDetected,
                    soldierData.lockedDistance,
                    true,
                    soldierData.lockedTrackingProgress,
                    soldierData.lockedAccuracy,
                    soldierData.lockedShotThreshold,
                    soldierData.lockedAdsProgress
                );
                CombatDebugRenderer.addDebugData(lockedData);
            }
            
            if (soldierData.potentialTargets != null && maxUntargeted > 0) {
                int count = 0;
                for (PotentialTargetsDebugMessage.PotentialTargetEntry entry : soldierData.potentialTargets) {
                    if (count >= maxUntargeted) break;
                    
                    CombatDebugData potentialData = new CombatDebugData(
                        soldierData.soldierUUID,
                        entry.uuid,
                        soldierData.soldierPos,
                        entry.position,
                        entry.detectionPoints,
                        DetectionSystem.DETECTION_THRESHOLD,
                        entry.hasLOS,
                        entry.inFocused,
                        entry.inPeripheral,
                        entry.distanceFactor,
                        entry.exposureFactor,
                        entry.movementFactor,
                        entry.brightnessFactor,
                        entry.inFocused ? DetectionSystem.BASE_FOCUSED_RATE : DetectionSystem.BASE_PERIPHERAL_RATE,
                        entry.detectionPoints >= DetectionSystem.DETECTION_THRESHOLD,
                        entry.distance,
                        false,
                        0,
                        0,
                        0,
                        0
                    );
                    CombatDebugRenderer.addDebugData(potentialData);
                    count++;
                }
            }
        }
    }
    
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            Matrix4f modelViewMatrix = event.getPoseStack().last().pose();
            Matrix4f projectionMatrix = event.getProjectionMatrix();
            float tickDelta = event.getPartialTick();
            
            lastWorldRenderContext = new WorldRenderContext(
                modelViewMatrix,
                projectionMatrix,
                tickDelta,
                event.getCamera()
            );
            
            PingManager.updatePingScreenPositions(lastWorldRenderContext);
        }
        
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            CombatDebugRenderer.render(event.getPoseStack(), event.getCamera(), event.getPartialTick());
        }
    }
    
    @SubscribeEvent
    public static void onRenderGuiPre(RenderGuiOverlayEvent.Pre event) {
        if (event.getOverlay() == VanillaGuiOverlay.VIGNETTE.type()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            
            if (PingWheelHandler.isWheelActive()) {
                PingWheelRenderer.render(event.getGuiGraphics());
            }
            
            PingOverlayRenderer.render(event.getGuiGraphics(), lastWorldRenderContext);
        }
    }
    
    public static WorldRenderContext getLastWorldRenderContext() {
        return lastWorldRenderContext;
    }
}
package com.stevesarmy.network;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.client.ClientCoverStateDebugData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Per-soldier snapshot of the cover state machine for the client debug render:
 * cover/peek/mode state, the seek-loop counters, the search pipeline state,
 * the relocation state, and the follow-blocker chain. Built server-side by
 * {@code CoverTacticalGoal.buildCoverStateDebugPacket} so the card stays live
 * even while the cover goal is stopped on its cooldown — the blind spot this
 * debug view exists for.
 */
public class CoverStateDebugPacket {
    private final boolean renderEnabled;
    private final UUID soldierId;
    private final String role;
    private final Vec3 soldierPos;

    private final String coverState;
    private final String peekState;
    private final String squadMode;
    private final float suppressionLevel;
    private final boolean pinned;

    private final boolean wantCover;
    private final String wantCoverReason;
    private final int goalCooldown;

    private final int seekingTicks;
    private final int seekingTimeout;
    private final int stuckTicks;
    private final int maxStuckTicks;
    private final int noProgressTicks;
    private final int searchExhaustionTicks;
    private final int failedSearches;
    private final int blacklistSize;
    private final int blacklistClearInSec;

    private final boolean searchPending;
    private final String queuedMode;
    private final int schedulerPending;

    private final String relocType;
    private final Vec3 relocCenter;
    private final int relocBackoffTicks;

    private final String followBlockedBy;
    private final float ownerDist;

    private final boolean mgPipeline;
    private final Vec3 mgAnchor;
    private final int mgAnchorAgeTicks;
    private final Vec3 suppressionCenter;
    private final int supCenterFreshTicks;
    private final Vec3 laneTarget;

    private final String posture;
    private final boolean lowCrouching;
    private final boolean emergencyPosture;
    private final float currentCoverHeight;
    private final String currentCoverType;

    private final boolean traveling;
    private final boolean travelCrawl;
    private final boolean crawlHidesHere;
    private final int travelReroutesServed;

    /** Pre-formatted fire-pipeline segment (gate reason, age, shot age, facing
     * error, movement block); empty when nothing worth showing. */
    private final String fireGate;

    public CoverStateDebugPacket(boolean renderEnabled, UUID soldierId, String role, Vec3 soldierPos,
                                 String coverState, String peekState, String squadMode,
                                 float suppressionLevel, boolean pinned,
                                 boolean wantCover, String wantCoverReason, int goalCooldown,
                                 int seekingTicks, int seekingTimeout, int stuckTicks, int maxStuckTicks,
                                 int noProgressTicks, int searchExhaustionTicks, int failedSearches,
                                 int blacklistSize, int blacklistClearInSec,
                                 boolean searchPending, String queuedMode, int schedulerPending,
                                 String relocType, Vec3 relocCenter, int relocBackoffTicks,
                                 String followBlockedBy, float ownerDist,
                                 boolean mgPipeline, Vec3 mgAnchor, int mgAnchorAgeTicks,
                                 Vec3 suppressionCenter, int supCenterFreshTicks, Vec3 laneTarget,
                                 String posture, boolean lowCrouching, boolean emergencyPosture,
                                 float currentCoverHeight, String currentCoverType,
                                 boolean traveling, boolean travelCrawl, boolean crawlHidesHere,
                                 int travelReroutesServed, String fireGate) {
        this.renderEnabled = renderEnabled;
        this.soldierId = soldierId;
        this.role = role;
        this.soldierPos = soldierPos;
        this.coverState = coverState;
        this.peekState = peekState;
        this.squadMode = squadMode;
        this.suppressionLevel = suppressionLevel;
        this.pinned = pinned;
        this.wantCover = wantCover;
        this.wantCoverReason = wantCoverReason;
        this.goalCooldown = goalCooldown;
        this.seekingTicks = seekingTicks;
        this.seekingTimeout = seekingTimeout;
        this.stuckTicks = stuckTicks;
        this.maxStuckTicks = maxStuckTicks;
        this.noProgressTicks = noProgressTicks;
        this.searchExhaustionTicks = searchExhaustionTicks;
        this.failedSearches = failedSearches;
        this.blacklistSize = blacklistSize;
        this.blacklistClearInSec = blacklistClearInSec;
        this.searchPending = searchPending;
        this.queuedMode = queuedMode;
        this.schedulerPending = schedulerPending;
        this.relocType = relocType;
        this.relocCenter = relocCenter;
        this.relocBackoffTicks = relocBackoffTicks;
        this.followBlockedBy = followBlockedBy;
        this.ownerDist = ownerDist;
        this.mgPipeline = mgPipeline;
        this.mgAnchor = mgAnchor;
        this.mgAnchorAgeTicks = mgAnchorAgeTicks;
        this.suppressionCenter = suppressionCenter;
        this.supCenterFreshTicks = supCenterFreshTicks;
        this.laneTarget = laneTarget;
        this.posture = posture;
        this.lowCrouching = lowCrouching;
        this.emergencyPosture = emergencyPosture;
        this.currentCoverHeight = currentCoverHeight;
        this.currentCoverType = currentCoverType;
        this.traveling = traveling;
        this.travelCrawl = travelCrawl;
        this.crawlHidesHere = crawlHidesHere;
        this.travelReroutesServed = travelReroutesServed;
        this.fireGate = fireGate;
    }

    /** Wire view of a shared {@code CombatStateSnapshot}; fields map 1:1. */
    public static CoverStateDebugPacket fromSnapshot(com.stevesarmy.combat.CombatStateSnapshot s) {
        StringBuilder fg = new StringBuilder();
        if (!"none".equals(s.fireGate)) {
            fg.append(s.fireGate).append(' ').append(s.fireGateTicks).append('t');
        }
        if (s.ticksSinceLastShot >= 0) {
            if (fg.length() > 0) fg.append(' ');
            fg.append("shot").append(s.ticksSinceLastShot).append('t');
        }
        if (s.facingErrorDeg >= 0.0f) {
            if (fg.length() > 0) fg.append(' ');
            fg.append(String.format("fe%.0f", s.facingErrorDeg));
        }
        if (!s.movingSupported) {
            if (fg.length() > 0) fg.append(' ');
            fg.append("move:").append(
                s.movementBlockReason.isEmpty() ? "blocked" : s.movementBlockReason);
        }
        return new CoverStateDebugPacket(
            true,
            s.soldierId,
            s.role,
            s.position,
            s.coverState,
            s.peekState,
            s.squadMode,
            s.suppressionLevel,
            s.pinned,
            s.wantCover,
            s.wantCoverReason,
            s.goalCooldown,
            s.seekingTicks,
            s.seekingTimeout,
            s.stuckTicks,
            s.maxStuckTicks,
            s.noProgressTicks,
            s.searchExhaustionTicks,
            s.failedSearches,
            s.blacklistSize,
            s.blacklistClearInSec,
            s.searchPending,
            s.queuedMode,
            s.schedulerPending,
            s.relocType,
            s.relocCenter,
            s.relocBackoffTicks,
            s.followBlockedBy,
            s.ownerDist,
            s.mgPipeline,
            s.mgAnchor,
            s.mgAnchorAgeTicks,
            s.suppressionCenter,
            s.supCenterFreshTicks,
            s.laneTarget,
            s.posture,
            s.lowCrouching,
            s.emergencyPosture,
            s.currentCoverHeight,
            s.currentCoverType,
            s.traveling,
            s.travelCrawl,
            s.crawlHidesHere,
            s.travelReroutesServed,
            fg.toString());
    }

    public CoverStateDebugPacket(FriendlyByteBuf buf) {        this.renderEnabled = buf.readBoolean();
        this.soldierId = buf.readUUID();
        this.role = buf.readUtf();
        this.soldierPos = readVec(buf);
        this.coverState = buf.readUtf();
        this.peekState = buf.readUtf();
        this.squadMode = buf.readUtf();
        this.suppressionLevel = buf.readFloat();
        this.pinned = buf.readBoolean();
        this.wantCover = buf.readBoolean();
        this.wantCoverReason = buf.readUtf();
        this.goalCooldown = buf.readVarInt();
        this.seekingTicks = buf.readVarInt();
        this.seekingTimeout = buf.readVarInt();
        this.stuckTicks = buf.readVarInt();
        this.maxStuckTicks = buf.readVarInt();
        this.noProgressTicks = buf.readVarInt();
        this.searchExhaustionTicks = buf.readVarInt();
        this.failedSearches = buf.readVarInt();
        this.blacklistSize = buf.readVarInt();
        this.blacklistClearInSec = buf.readVarInt();
        this.searchPending = buf.readBoolean();
        this.queuedMode = buf.readUtf();
        this.schedulerPending = buf.readVarInt();
        this.relocType = buf.readUtf();
        this.relocCenter = buf.readBoolean() ? readVec(buf) : null;
        this.relocBackoffTicks = buf.readVarInt();
        this.followBlockedBy = buf.readUtf();
        this.ownerDist = buf.readFloat();
        this.mgPipeline = buf.readBoolean();
        this.mgAnchor = buf.readBoolean() ? readVec(buf) : null;
        this.mgAnchorAgeTicks = buf.readVarInt();
        this.suppressionCenter = buf.readBoolean() ? readVec(buf) : null;
        this.supCenterFreshTicks = buf.readVarInt();
        this.laneTarget = buf.readBoolean() ? readVec(buf) : null;
        this.posture = buf.readUtf();
        this.lowCrouching = buf.readBoolean();
        this.emergencyPosture = buf.readBoolean();
        this.currentCoverHeight = buf.readFloat();
        this.currentCoverType = buf.readUtf();
        this.traveling = buf.readBoolean();
        this.travelCrawl = buf.readBoolean();
        this.crawlHidesHere = buf.readBoolean();
        this.travelReroutesServed = buf.readVarInt();
        this.fireGate = buf.readUtf();
    }

    private static Vec3 readVec(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    private static void writeVec(FriendlyByteBuf buf, Vec3 v) {
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
    }

    public static void encode(CoverStateDebugPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.renderEnabled);
        buf.writeUUID(msg.soldierId);
        buf.writeUtf(msg.role);
        writeVec(buf, msg.soldierPos);
        buf.writeUtf(msg.coverState);
        buf.writeUtf(msg.peekState);
        buf.writeUtf(msg.squadMode);
        buf.writeFloat(msg.suppressionLevel);
        buf.writeBoolean(msg.pinned);
        buf.writeBoolean(msg.wantCover);
        buf.writeUtf(msg.wantCoverReason);
        buf.writeVarInt(msg.goalCooldown);
        buf.writeVarInt(msg.seekingTicks);
        buf.writeVarInt(msg.seekingTimeout);
        buf.writeVarInt(msg.stuckTicks);
        buf.writeVarInt(msg.maxStuckTicks);
        buf.writeVarInt(msg.noProgressTicks);
        buf.writeVarInt(msg.searchExhaustionTicks);
        buf.writeVarInt(msg.failedSearches);
        buf.writeVarInt(msg.blacklistSize);
        buf.writeVarInt(msg.blacklistClearInSec);
        buf.writeBoolean(msg.searchPending);
        buf.writeUtf(msg.queuedMode);
        buf.writeVarInt(msg.schedulerPending);
        buf.writeUtf(msg.relocType);
        buf.writeBoolean(msg.relocCenter != null);
        if (msg.relocCenter != null) writeVec(buf, msg.relocCenter);
        buf.writeVarInt(msg.relocBackoffTicks);
        buf.writeUtf(msg.followBlockedBy);
        buf.writeFloat(msg.ownerDist);
        buf.writeBoolean(msg.mgPipeline);
        buf.writeBoolean(msg.mgAnchor != null);
        if (msg.mgAnchor != null) writeVec(buf, msg.mgAnchor);
        buf.writeVarInt(msg.mgAnchorAgeTicks);
        buf.writeBoolean(msg.suppressionCenter != null);
        if (msg.suppressionCenter != null) writeVec(buf, msg.suppressionCenter);
        buf.writeVarInt(msg.supCenterFreshTicks);
        buf.writeBoolean(msg.laneTarget != null);
        if (msg.laneTarget != null) writeVec(buf, msg.laneTarget);
        buf.writeUtf(msg.posture);
        buf.writeBoolean(msg.lowCrouching);
        buf.writeBoolean(msg.emergencyPosture);
        buf.writeFloat(msg.currentCoverHeight);
        buf.writeUtf(msg.currentCoverType == null ? "" : msg.currentCoverType);
        buf.writeBoolean(msg.traveling);
        buf.writeBoolean(msg.travelCrawl);
        buf.writeBoolean(msg.crawlHidesHere);
        buf.writeVarInt(msg.travelReroutesServed);
        buf.writeUtf(msg.fireGate == null ? "" : msg.fireGate);
    }

    public static void handle(CoverStateDebugPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                ClientCoverStateDebugData.INSTANCE.receive(msg);
                if (StevesArmyMod.LOGGER.isDebugEnabled()) {
                    StevesArmyMod.LOGGER.debug("[CoverStateDebug] received for {}", msg.soldierId);
                }
            });
        });
        ctx.get().setPacketHandled(true);
    }

    public boolean renderEnabled() { return renderEnabled; }
    public UUID soldierId() { return soldierId; }
    public String role() { return role; }
    public Vec3 soldierPos() { return soldierPos; }
    public String coverState() { return coverState; }
    public String peekState() { return peekState; }
    public String squadMode() { return squadMode; }
    public float suppressionLevel() { return suppressionLevel; }
    public boolean pinned() { return pinned; }
    public boolean wantCover() { return wantCover; }
    public String wantCoverReason() { return wantCoverReason; }
    public int goalCooldown() { return goalCooldown; }
    public int seekingTicks() { return seekingTicks; }
    public int seekingTimeout() { return seekingTimeout; }
    public int stuckTicks() { return stuckTicks; }
    public int maxStuckTicks() { return maxStuckTicks; }
    public int noProgressTicks() { return noProgressTicks; }
    public int searchExhaustionTicks() { return searchExhaustionTicks; }
    public int failedSearches() { return failedSearches; }
    public int blacklistSize() { return blacklistSize; }
    public int blacklistClearInSec() { return blacklistClearInSec; }
    public boolean searchPending() { return searchPending; }
    public String queuedMode() { return queuedMode; }
    public int schedulerPending() { return schedulerPending; }
    public String relocType() { return relocType; }
    public Vec3 relocCenter() { return relocCenter; }
    public int relocBackoffTicks() { return relocBackoffTicks; }
    public String followBlockedBy() { return followBlockedBy; }
    public float ownerDist() { return ownerDist; }
    public boolean mgPipeline() { return mgPipeline; }
    public Vec3 mgAnchor() { return mgAnchor; }
    public int mgAnchorAgeTicks() { return mgAnchorAgeTicks; }
    public Vec3 suppressionCenter() { return suppressionCenter; }
    public int supCenterFreshTicks() { return supCenterFreshTicks; }
    public Vec3 laneTarget() { return laneTarget; }
    public String posture() { return posture; }
    public boolean lowCrouching() { return lowCrouching; }
    public boolean emergencyPosture() { return emergencyPosture; }
    public float currentCoverHeight() { return currentCoverHeight; }
    public String currentCoverType() { return currentCoverType; }
    public boolean traveling() { return traveling; }
    public boolean travelCrawl() { return travelCrawl; }
    public boolean crawlHidesHere() { return crawlHidesHere; }
    public int travelReroutesServed() { return travelReroutesServed; }
    public String fireGate() { return fireGate; }
}

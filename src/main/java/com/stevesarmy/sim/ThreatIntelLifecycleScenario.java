package com.stevesarmy.sim;

import com.mojang.authlib.GameProfile;
import com.stevesarmy.sim.scenario.SimContext;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.util.FakePlayer;

import java.util.UUID;

/** Regression probe for persisted player tombstones and runtime intel expiry. */
public final class ThreatIntelLifecycleScenario {
    private static final UUID LEADER_ID = UUID.fromString("00000000-0000-0000-0000-000000000027");

    private ThreatIntelLifecycleScenario() {}

    private static final class Probe {
        boolean positionReportPreservedDead;
        boolean creativeReportPreservedDead;
        boolean spectatorReportPreservedDead;
        boolean deadPlayerReportPreservedDead;
        boolean survivalReportRestored;
        boolean restoredMetadataCleared;
        boolean cleanupRemovedOldDead;
        boolean cleanupRemovedStaleLive;
        boolean cleanupKeptFreshLive;
        UUID oldDeadId;
        UUID staleLiveId;
        UUID freshLiveId;
    }

    public static SimScenario create() {
        Probe[] probe = new Probe[1];
        return SimScenario.builder("threat_intel_lifecycle")
            .description("Persisted dead player contacts stay tombstoned until a valid survival sighting; runtime cleanup expires old intel.")
            .duration(25)
            .setup(ctx -> {
                Probe p = new Probe();
                probe[0] = p;

                SquadManager manager = SquadManager.get(ctx.level);
                SquadData squad = manager.createSquad(LEADER_ID);
                SquadThreatIntel intel = squad.getThreatIntel();
                UUID targetId = UUID.randomUUID();
                BlockPos original = ctx.terrain.at(20, 0, 20);

                intel.reportThreatPosition(LEADER_ID, targetId, original, 1.0f, ctx.level);
                intel.markThreatDead(targetId);
                SquadThreatIntel restored = SquadThreatIntel.fromNBT(intel.toNBT());
                long oldSeen = ctx.level.getGameTime() - 101;
                long staleSeen = ctx.level.getGameTime() - 601;
                SquadThreatIntel.ThreatKnowledge restoredDead = restored.getThreat(targetId).orElseThrow();
                restoredDead.lastSeenTime = oldSeen;
                restoredDead.isSuppressed = true;
                restoredDead.isHardTarget = true;
                restoredDead.suppressedBy = LEADER_ID;
                restoredDead.suppressors.add(LEADER_ID);

                restored.reportThreatPosition(LEADER_ID, targetId, original.offset(4, 0, 0), 1.0f, ctx.level);
                SquadThreatIntel.ThreatKnowledge afterPosition = restored.getThreat(targetId).orElseThrow();
                p.positionReportPreservedDead = !afterPosition.isAlive
                    && original.equals(afterPosition.lastKnownPosition);

                FakePlayer creative = fakePlayer(ctx, targetId, "Creative", GameType.CREATIVE);
                reportAgainstTombstone(restored, creative, original);
                p.creativeReportPreservedDead = !restored.getThreat(targetId).orElseThrow().isAlive;

                UUID spectatorId = UUID.randomUUID();
                restored.reportThreatPosition(LEADER_ID, spectatorId, original, 1.0f, ctx.level);
                restored.markThreatDead(spectatorId);
                FakePlayer spectator = fakePlayer(ctx, spectatorId, "Spectator", GameType.SPECTATOR);
                reportAgainstTombstone(restored, spectator, original);
                p.spectatorReportPreservedDead = !restored.getThreat(spectatorId).orElseThrow().isAlive;

                UUID deadId = UUID.randomUUID();
                restored.reportThreatPosition(LEADER_ID, deadId, original, 1.0f, ctx.level);
                restored.markThreatDead(deadId);
                FakePlayer dead = fakePlayer(ctx, deadId, "Dead", GameType.SURVIVAL);
                dead.setHealth(0.0f);
                reportAgainstTombstone(restored, dead, original);
                p.deadPlayerReportPreservedDead = !restored.getThreat(deadId).orElseThrow().isAlive;

                FakePlayer survivor = fakePlayer(ctx, targetId, "Survivor", GameType.SURVIVAL);
                restored.reportThreat(LEADER_ID, survivor, original.offset(2, 0, 0),
                    survivor.getEyePosition(), survivor.getEyePosition(), 1.0f);
                SquadThreatIntel.ThreatKnowledge revived = restored.getThreat(targetId).orElseThrow();
                p.survivalReportRestored = revived.isAlive
                    && original.offset(2, 0, 0).equals(revived.lastKnownPosition);
                p.restoredMetadataCleared = !revived.isSuppressed && revived.suppressedBy == null
                    && revived.suppressors.isEmpty() && !revived.isHardTarget;

                p.oldDeadId = UUID.randomUUID();
                p.staleLiveId = UUID.randomUUID();
                p.freshLiveId = UUID.randomUUID();
                intel.reportThreatPosition(LEADER_ID, p.oldDeadId, original, 1.0f, ctx.level);
                intel.markThreatDead(p.oldDeadId);
                intel.reportThreatPosition(LEADER_ID, p.staleLiveId, original.offset(1, 0, 0), 1.0f, ctx.level);
                intel.reportThreatPosition(LEADER_ID, p.freshLiveId, original.offset(2, 0, 0), 1.0f, ctx.level);

                // Exercise the regular server tick driver's cleanup path.
                setLastSeen(intel, p.oldDeadId, oldSeen);
                setLastSeen(intel, p.staleLiveId, staleSeen);
            })
            .action(24, "observe the server cleanup driver after a full interval", ctx -> {
                SquadData squad = SquadManager.get(ctx.level).getSquadByLeader(LEADER_ID).orElseThrow();
                SquadThreatIntel intel = squad.getThreatIntel();
                probe[0].cleanupRemovedOldDead = intel.getThreat(probe[0].oldDeadId).isEmpty();
                probe[0].cleanupRemovedStaleLive = intel.getThreat(probe[0].staleLiveId).isEmpty();
                probe[0].cleanupKeptFreshLive = intel.getThreat(probe[0].freshLiveId)
                    .map(knowledge -> knowledge.isAlive && knowledge.lastKnownPosition != null
                        && knowledge.lastKnownPosition.equals(ctx.terrain.at(22, 0, 20)))
                    .orElse(false);
            })
            .assertThat("position-only sightings do not revive persisted dead UUIDs", view -> probe[0].positionReportPreservedDead)
            .assertThat("creative-player sightings do not revive dead UUIDs", view -> probe[0].creativeReportPreservedDead)
            .assertThat("spectator sightings do not revive dead UUIDs", view -> probe[0].spectatorReportPreservedDead)
            .assertThat("dead-player sightings do not revive dead UUIDs", view -> probe[0].deadPlayerReportPreservedDead)
            .assertThat("a fresh survival-player sighting restores the same UUID", view -> probe[0].survivalReportRestored)
            .assertThat("restored player intel clears old suppression and hard-target metadata", view -> probe[0].restoredMetadataCleared)
            .assertThat("runtime cleanup removes persisted dead contacts older than 100 ticks", view -> probe[0].cleanupRemovedOldDead)
            .assertThat("runtime cleanup removes live contacts older than 600 ticks", view -> probe[0].cleanupRemovedStaleLive)
            .assertThat("runtime cleanup retains a fresh live contact", view -> probe[0].cleanupKeptFreshLive)
            .build();
    }

    private static FakePlayer fakePlayer(SimContext ctx, UUID id, String label, GameType gameType) {
        FakePlayer player = new FakePlayer((ServerLevel) ctx.level,
            new GameProfile(id, "Intel" + label));
        player.setGameMode(gameType);
        player.moveTo(ctx.terrain.at(20, 0, 20).getX() + 0.5,
            ctx.terrain.at(20, 0, 20).getY(), ctx.terrain.at(20, 0, 20).getZ() + 0.5,
            0.0f, 0.0f);
        return player;
    }

    private static void reportAgainstTombstone(SquadThreatIntel intel,
                                                FakePlayer player,
                                                BlockPos pos) {
        intel.reportThreat(LEADER_ID, player, pos, player.getEyePosition(), player.getEyePosition(), 1.0f);
    }

    private static void setLastSeen(SquadThreatIntel intel, UUID id, long time) {
        intel.getThreat(id).orElseThrow().lastSeenTime = time;
    }
}

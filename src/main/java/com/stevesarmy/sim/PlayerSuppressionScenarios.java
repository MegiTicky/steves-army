package com.stevesarmy.sim;

import com.mojang.authlib.GameProfile;
import com.stevesarmy.combat.TargetAcquisition;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.SoldierCombatGoal;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimContext;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;

import java.util.UUID;

/** Last-seen suppression probes for player targets and persisted dead contacts. */
public final class PlayerSuppressionScenarios {
    private static final int HIDE_TICK = 70;
    private static final int DURATION = 220;

    private PlayerSuppressionScenarios() {}

    public static SimScenario freshPlayer() {
        return lastSeenScenario("player_last_seen_suppression", TargetKind.PLAYER, false);
    }

    public static SimScenario npcControl() {
        return lastSeenScenario("npc_last_seen_suppression", TargetKind.NPC, false);
    }

    public static SimScenario respawnedPlayer() {
        return lastSeenScenario("respawned_player_last_seen_suppression", TargetKind.PLAYER, true);
    }

    private enum TargetKind { PLAYER, NPC }

    private static final class Probe {
        EnemySoldierEntity shooter;
        SoldierCombatGoal combat;
        LivingEntity target;
        UUID targetId;
        Vec3 oldAnchor;
        int hiddenAtTick = -1;
        boolean started;
        boolean initiallyVisible;
        boolean deadPositionReportStayedDead;
        boolean deadSurvivesNbtRoundTrip;
        boolean freshEntityReportRevived;
        boolean hiddenFromShooter;
        boolean rememberedOldAnchor;
        boolean shotAtOldAnchorAfterHide;
        boolean directFireBeforeHide;
        boolean stoppedAfterContactExpired;
        boolean hiddenPositionNotLearned;
    }

    private static SimScenario lastSeenScenario(String name, TargetKind kind, boolean respawned) {
        Probe[] probe = new Probe[1];
        SimScenario.Builder builder = SimScenario.builder(name)
            .description("A stationary enemy records a visible " + kind.name().toLowerCase()
                + " position, then fires at that clear old anchor after the target moves behind a wall.")
            .field(48, 48)
            .duration(DURATION)
            .armed()
            .terrain(terrain -> {
                terrain.floorOnly();
                // A newly generated world can have terrain above the sky pad.
                terrain.wall(8, 20, 38, 28, 4,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            })
            .setup(ctx -> {
                Probe p = new Probe();
                p.shooter = ctx.spawnEnemyLabeled("shooter", 12, 0, 24);
                // Scenario actions can precede the enemy's first entity tick.
                SquadManager manager = SquadManager.get(ctx.level);
                SquadData squad = manager.createSquad(UUID.fromString("00000000-0000-0000-0000-000000000001"));
                manager.addMemberToSquad(squad.getSquadId(), p.shooter.getUUID());
                p.shooter.setSquadId(squad.getSquadId());
                p.shooter.setNoAi(true);
                p.shooter.setInvulnerable(true);
                p.shooter.setYRot(-90.0f);
                // The virtual gun has no inventory ItemStack. Expose its real
                // magazine to the production suppression ammo gate.
                p.combat = new SoldierCombatGoal(p.shooter) {
                    @Override public int getTotalAmmo() {
                        return com.stevesarmy.combat.GunIntegration.getCurrentAmmo(p.shooter);
                    }
                };
                SimGunHandler.useM4A1MovementProfile(p.shooter);
                p.target = spawnAt(ctx, 26, 24, kind, null);
                p.oldAnchor = p.target.position();
                p.targetId = p.target.getUUID();
                if (p.target instanceof Mob mob) mob.setNoAi(true);
                p.target.setInvulnerable(true);
                probe[0] = p;
            })
            .action(1, respawned ? "restore dead UUID then spawn player again" : "start visible target tracking", ctx -> {
                Probe p = probe[0];
                if (respawned) {
                    SquadThreatIntel intel = enemyIntel(ctx, p.shooter);
                    BlockPos original = p.target.blockPosition();
                    intel.reportThreat(p.shooter.getUUID(), p.target, original, 1.0f);
                    intel.markThreatDead(p.targetId);
                    SquadThreatIntel reloaded = SquadThreatIntel.fromNBT(intel.toNBT());
                    SquadThreatIntel.ThreatKnowledge fromSave = reloaded.getThreat(p.targetId).orElse(null);
                    p.deadSurvivesNbtRoundTrip = fromSave != null && !fromSave.isAlive
                        && original.equals(fromSave.lastKnownPosition);
                    intel.reportThreatPosition(p.shooter.getUUID(), p.targetId,
                        original.offset(3, 0, 0), 1.0f, ctx.level);
                    SquadThreatIntel.ThreatKnowledge afterPositionOnly = intel.getThreat(p.targetId).orElse(null);
                    p.deadPositionReportStayedDead = afterPositionOnly != null && !afterPositionOnly.isAlive
                        && original.equals(afterPositionOnly.lastKnownPosition);

                    p.target.discard();
                    p.target = null;
                }
            })
            .action(2, "respawn a new survival player entity with the same UUID", ctx -> {
                if (respawned) {
                    Probe p = probe[0];
                    p.target = spawnAt(ctx, 26, 24, TargetKind.PLAYER, p.targetId);
                    p.target.setInvulnerable(true);
                }
            })
            .action(HIDE_TICK, "target moves behind the wall; old contact remains open", ctx -> {
                Probe p = probe[0];
                ctx.terrain.wall(30, 21, 30, 27, 3,
                    net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
                p.target.setPos(ctx.terrain.at(34, 0, 24).getX() + 0.5,
                    ctx.terrain.at(34, 0, 24).getY(), ctx.terrain.at(34, 0, 24).getZ() + 0.5);
                p.hiddenAtTick = p.shooter.tickCount;
                p.hiddenFromShooter = !TargetAcquisition.hasLineOfSight(p.shooter, p.target);
            });
        for (int tick = 1; tick <= DURATION; tick++) {
            final int at = tick;
            builder.action(at, "manual enemy combat tick", ctx -> {
                Probe p = probe[0];
                if (p.target == null) return;
                if (at == (respawned ? 2 : 1)) {
                    p.initiallyVisible = TargetAcquisition.hasLineOfSight(p.shooter, p.target);
                }
                tickCombatGoal(p);
                if (respawned && at == 2) {
                    SquadThreatIntel intel = enemyIntel(ctx, p.shooter);
                    intel.reportThreat(p.shooter.getUUID(), p.target, p.target.blockPosition(),
                        p.target.getEyePosition(), p.target.getEyePosition(), 1.0f);
                    p.freshEntityReportRevived = intel.getThreat(p.targetId)
                        .map(threat -> threat.isAlive && threat.lastKnownPosition.equals(p.target.blockPosition()))
                        .orElse(false);
                }
                if (at == HIDE_TICK) {
                    SquadThreatIntel.ThreatKnowledge memory = enemyIntel(ctx, p.shooter)
                        .getThreat(p.targetId).orElse(null);
                    p.rememberedOldAnchor = memory != null && memory.isAlive
                        && memory.lastKnownPosition != null
                        && horizontalDistanceSqr(memory.lastKnownPosition.getCenter(), p.oldAnchor) <= 4.0;
                    StevesArmyMod.LOGGER.info("[PlayerSuppressionProbe] kind={} respawn={} target={} intelAlive={} intelPos={} oldAnchor={} now={} seen={} gun={} suppressing={} moving={} constrained={}",
                        kind, respawned, p.shooter.getTarget(), memory != null ? memory.isAlive : null,
                        memory != null ? memory.lastKnownPosition : null, p.oldAnchor, ctx.level.getGameTime(),
                        memory != null ? memory.lastSeenTime : null,
                        com.stevesarmy.combat.GunIntegration.getCurrentAmmo(p.shooter),
                        p.combat.isSuppressing(), p.shooter.getMovementController().hasMovingIntent(),
                        p.shooter.getMovementController().isStationaryFireConstrained());
                }
            });
        }
        builder.action(DURATION, "capture old-anchor fire and discard target", ctx -> {
            Probe p = probe[0];
            p.directFireBeforeHide = SimGunHandler.acceptedShots().stream()
                .anyMatch(shot -> shot.shooterId().equals(p.shooter.getUUID())
                    && shot.tick() < p.hiddenAtTick);
            p.shotAtOldAnchorAfterHide = SimGunHandler.acceptedShots().stream()
                .anyMatch(shot -> shot.shooterId().equals(p.shooter.getUUID())
                    && shot.tick() > p.hiddenAtTick
                    && horizontalDistanceSqr(shot.aimPosition(), p.oldAnchor) <= 16.0);
            p.stoppedAfterContactExpired = SimGunHandler.acceptedShots().stream()
                .noneMatch(shot -> shot.shooterId().equals(p.shooter.getUUID())
                    && shot.tick() > p.hiddenAtTick + com.stevesarmy.combat.FireControl.SUPPRESSION_CONTACT_MAX_TICKS);
            p.hiddenPositionNotLearned = enemyIntel(ctx, p.shooter).getThreat(p.targetId)
                .map(threat -> threat.lastKnownPosition != null
                    && horizontalDistanceSqr(threat.lastKnownPosition.getCenter(), p.oldAnchor) <= 4.0)
                .orElse(false);
            p.target.discard();
        });
        return builder
            .assertThat("the target was visible before it moved behind cover", view -> probe[0].initiallyVisible)
            .assertThat("the target is hidden while its old sighting point remains visible", view -> {
                Probe p = probe[0];
                return p.hiddenFromShooter
                    && TargetAcquisition.hasLineOfSightToPosition(p.shooter, p.oldAnchor);
            })
            .assertThat("combat memory still points at the visible old anchor", view -> probe[0].rememberedOldAnchor)
            .assertThat("enemy gun accepts shots aimed at the old contact after it is hidden",
                view -> probe[0].shotAtOldAnchorAfterHide)
            .assertThat("visible target receives direct fire before hiding", view -> probe[0].directFireBeforeHide)
            .assertThat("last-seen fire stops when contact expires", view -> probe[0].stoppedAfterContactExpired)
            .assertThat("squad intel never learns the hidden position", view -> probe[0].hiddenPositionNotLearned)
            .assertThat("scenario target is removed at the end of the run", view -> probe[0].target.isRemoved())
            .assertThat("position-only reports preserve dead UUIDs before a respawn sighting",
                view -> !respawned || probe[0].deadPositionReportStayedDead)
            .assertThat("dead UUID state survives the squad-intel NBT round trip",
                view -> !respawned || probe[0].deadSurvivesNbtRoundTrip)
            .assertThat("fresh entity sighting revives a respawned player's UUID",
                view -> !respawned || probe[0].freshEntityReportRevived)
            .build();
    }

    private static LivingEntity spawnAt(SimContext ctx, int x, int z, TargetKind kind, UUID uuid) {
        BlockPos pos = ctx.terrain.at(x, 0, z);
        if (kind == TargetKind.NPC) {
            SoldierEntity npc = ModEntities.SOLDIER.get().create(ctx.level);
            npc.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 180.0f, 0.0f);
            npc.setPersistenceRequired();
            ctx.level.addFreshEntity(npc);
            return npc;
        }

        UUID playerId = uuid != null ? uuid : UUID.randomUUID();
        FakePlayer player = new FakePlayer((ServerLevel) ctx.level,
            new GameProfile(playerId, "SimPlayer" + playerId.toString().substring(0, 8)));
        player.setGameMode(GameType.SURVIVAL);
        player.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 180.0f, 0.0f);
        if (!ctx.level.addFreshEntity(player)) {
            throw new IllegalStateException("could not add sim survival player");
        }
        return player;
    }

    private static SquadThreatIntel enemyIntel(SimContext ctx, EnemySoldierEntity shooter) {
        if (shooter.getSquadId() == null) {
            throw new IllegalStateException("enemy squad membership was not initialized");
        }
        SquadData squad = SquadManager.get(ctx.level).getSquadById(shooter.getSquadId())
            .orElseThrow(() -> new IllegalStateException("enemy squad missing"));
        return squad.getThreatIntel();
    }

    private static void tickCombatGoal(Probe p) {
        SoldierCombatGoal goal = p.combat;
        if (!p.started) {
            if (!goal.canUse()) return;
            goal.start();
            p.started = true;
        }
        goal.tick();
    }

    private static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }
}

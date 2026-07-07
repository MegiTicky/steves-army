package com.stevesarmy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.client.CombatDebugRenderer;
import com.stevesarmy.client.model.PoseConfig;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.CoverDebugManager;
import com.stevesarmy.combat.cover.CoverFinder;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverQualityEvaluator;
import com.stevesarmy.combat.cover.CoverReservationManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.TargetEntity;
import com.stevesarmy.entity.ai.CoverPositionController;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import com.stevesarmy.entity.ai.PeekController;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import com.stevesarmy.squad.SquadThreatIntel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class CombatDebugCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("stevesarmy_debug")
            .requires(source -> source.hasPermission(2))
            // Help
            .executes(CombatDebugCommand::showHelp)

            // === MASTER TOGGLE ===
            .then(Commands.literal("all")
                .executes(CombatDebugCommand::enableAllDebug))
            .then(Commands.literal("none")
                .executes(CombatDebugCommand::disableAllDebug))

            // === LOG TOGGLES ===
            .then(Commands.literal("log")
                .then(Commands.literal("cover")
                    .executes(ctx -> toggleCoverLogging(ctx, null))
                    .then(Commands.literal("on")
                        .executes(ctx -> toggleCoverLogging(ctx, true)))
                    .then(Commands.literal("off")
                        .executes(ctx -> toggleCoverLogging(ctx, false)))
                ))

            // === RENDER TOGGLES ===
            .then(Commands.literal("render")
                .then(Commands.literal("soldiers")
                    .executes(CombatDebugCommand::toggleSoldierVisualization))
                .then(Commands.literal("peekcandidates")
                    .executes(CombatDebugCommand::togglePeekCandidates))
                .then(Commands.literal("rays")
                    .executes(CombatDebugCommand::toggleRayVisualization))
                .then(Commands.literal("solid")
                    .executes(CombatDebugCommand::toggleSolidVisualization))
                .then(Commands.literal("coverpoints")
                    .executes(CombatDebugCommand::toggleCoverPointVisualization))
                .then(Commands.literal("mode")
                    .executes(ctx -> cycleCombatMode(ctx))
                    .then(Commands.literal("off")
                        .executes(ctx -> setCombatMode(ctx, CombatDebugRenderer.DEBUG_MODE_OFF)))
                    .then(Commands.literal("minimal")
                        .executes(ctx -> setCombatMode(ctx, CombatDebugRenderer.DEBUG_MODE_MINIMAL)))
                    .then(Commands.literal("verbose")
                        .executes(ctx -> setCombatMode(ctx, CombatDebugRenderer.DEBUG_MODE_VERBOSE))))
                .then(Commands.literal("untargeted")
                    .executes(CombatDebugCommand::showUntargeted)
                    .then(Commands.argument("count", IntegerArgumentType.integer(0, 10))
                        .executes(CombatDebugCommand::setUntargeted)))
                .then(Commands.literal("status")
                    .executes(CombatDebugCommand::renderStatus))
            )

            // === INFO (one-time readouts) ===
            .then(Commands.literal("info")
                .then(Commands.literal("state")
                    .executes(CombatDebugCommand::showCoverState))
                .then(Commands.literal("threats")
                    .executes(CombatDebugCommand::showThreats))
                .then(Commands.literal("reservations")
                    .executes(CombatDebugCommand::showReservations))
                .then(Commands.literal("suppression")
                    .executes(CombatDebugCommand::showSuppression))
                .then(Commands.literal("intel")
                    .executes(CombatDebugCommand::showSquadIntel))
                .then(Commands.literal("scan")
                    .executes(ctx -> scanDefault(ctx))
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1, 20))
                        .executes(ctx -> scanWithRadius(ctx))))
                .then(Commands.literal("target")
                    .executes(ctx -> scanWithAutoTarget(ctx))
                    .then(Commands.argument("entity", EntityArgument.entity())
                        .executes(ctx -> scanWithTarget(ctx))))
                .then(Commands.literal("best")
                    .executes(ctx -> findBest(ctx))
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1, 20))
                        .executes(ctx -> findBestWithRadius(ctx))))
                .then(Commands.literal("debug")
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(CombatDebugCommand::debugSpecificPosition)))))
            )

            // === CONTROL (soldier instructions) ===
            .then(Commands.literal("control")
                .then(Commands.literal("peek")
                    .executes(CombatDebugCommand::forcePeek))
                .then(Commands.literal("reposition")
                    .executes(CombatDebugCommand::forceReposition))
                .then(Commands.literal("teleport_mode")
                    .executes(ctx -> toggleTeleportMode(ctx, null))
                    .then(Commands.literal("on")
                        .executes(ctx -> toggleTeleportMode(ctx, true)))
                    .then(Commands.literal("off")
                        .executes(ctx -> toggleTeleportMode(ctx, false))))
                .then(Commands.literal("pose")
                    .executes(CombatDebugCommand::poseStatus)
                    .then(Commands.literal("crawl")
                        .executes(CombatDebugCommand::forceCrawl))
                    .then(Commands.literal("stand")
                        .executes(CombatDebugCommand::forceStand))
                    .then(Commands.literal("status")
                        .executes(CombatDebugCommand::poseStatus))
                    .then(Commands.literal("noai")
                        .executes(CombatDebugCommand::toggleNoAi))
                    .then(Commands.literal("angles")
                        .executes(CombatDebugCommand::showPoseAngles))
                    .then(Commands.literal("deg")
                        .executes(CombatDebugCommand::showPoseDegrees))
                    .then(Commands.literal("set")
                        .then(Commands.argument("part", StringArgumentType.word())
                            .then(Commands.argument("axis", StringArgumentType.word())
                                .then(Commands.argument("value", FloatArgumentType.floatArg())
                                    .executes(CombatDebugCommand::setPoseAngle)))))
                    .then(Commands.literal("reset")
                        .executes(CombatDebugCommand::resetPoseAngles)))
            )

            // === STATUS (show all toggle states) ===
            .then(Commands.literal("status")
                .executes(CombatDebugCommand::showDebugStatus))
        );
    }

    // ======================================================================
    // HELP
    // ======================================================================
    private static int showHelp(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
            "=== /stevesarmy_debug ===\n" +
            "  all                 - Enable ALL debug (logging + render + combat overlay)\n" +
            "  none                - Disable ALL debug (logging + render + overlays)\n" +
            "  log cover [on|off]  - Toggle cover behavior logging\n" +
            "  render soldiers     - Toggle soldier cover visualization lines/labels\n" +
            "  render peekcandidates - Toggle peek candidate boxes/LOS rays\n" +
            "  render rays         - Toggle cover raycast visualization\n" +
            "  render solid        - Toggle solid block visualization\n" +
            "  render coverpoints  - Toggle cover point visualization\n" +
            "  render mode [off|minimal|verbose] - Combat detection overlay\n" +
            "  render untargeted [count] - Show/set max untargeted targets\n" +
            "  render status       - Show current render settings\n" +
            "  info state          - Show cover state + movement for nearby soldiers\n" +
            "  info threats        - Show threat direction analysis\n" +
            "  info reservations   - Show cover reservations\n" +
            "  info suppression    - Show suppression levels\n" +
            "  info intel          - Show squad threat intel (shared enemy positions)\n" +
            "  info scan [radius]  - Scan for cover points\n" +
            "  info target [entity]- Scan with threat\n" +
            "  info best [radius]  - Find best cover point\n" +
            "  info debug <x> <y> <z> - Debug specific position\n" +
            "  control peek        - Force nearest soldier to peek\n" +
            "  control reposition  - Force nearest soldier to abandon cover\n" +
            "  control teleport_mode [on|off] - Toggle teleport-only movement mode\n" +
            "  control pose [...]  - Soldier pose commands (crawl, stand, status, noai, angles, set, reset)\n" +
            "  status              - Show current debug toggle states"
        ), false);
        return 1;
    }

    // ======================================================================
    // ALL
    // ======================================================================
    private static int enableAllDebug(CommandContext<CommandSourceStack> context) {
        CombatDebugRenderer.setDebugMode(CombatDebugRenderer.DEBUG_MODE_VERBOSE);
        CoverDebugManager.setShowSoldierCover(true);
        CoverDebugManager.setShowPeekCandidates(true);
        CoverDebugManager.setVisualizationEnabled(true);
        CoverTacticalGoal.setDebugLogging(true);

        context.getSource().sendSuccess(() -> Component.literal(
            "=== Steve's Army Debug: ALL ON ===\n" +
            "  Combat debug overlay: VERBOSE\n" +
            "  Soldier cover visualization: ON\n" +
            "  Peek candidate visualization: ON\n" +
            "  Cover behavior logging: ON\n" +
            "Use /stevesarmy_debug render mode minimal for compact display\n" +
            "Use /stevesarmy_debug log cover off to disable console logs"
        ), true);
        return 1;
    }

    private static int disableAllDebug(CommandContext<CommandSourceStack> context) {
        CombatDebugRenderer.setDebugMode(CombatDebugRenderer.DEBUG_MODE_OFF);
        CoverDebugManager.setShowSoldierCover(false);
        CoverDebugManager.setShowPeekCandidates(false);
        CoverDebugManager.setVisualizationEnabled(false);
        CoverTacticalGoal.setDebugLogging(false);
        CoverDebugManager.setShowRays(false);
        CoverDebugManager.setShowSolidBlocks(false);

        context.getSource().sendSuccess(() -> Component.literal(
            "=== Steve's Army Debug: ALL OFF ===\n" +
            "  Combat debug overlay: OFF\n" +
            "  Soldier cover visualization: OFF\n" +
            "  Peek candidate visualization: OFF\n" +
            "  Cover point visualization: OFF\n" +
            "  Raycast visualization: OFF\n" +
            "  Solid block visualization: OFF\n" +
            "  Cover behavior logging: OFF"
        ), true);
        return 1;
    }

    // ======================================================================
    // LOG TOGGLES
    // ======================================================================
    private static int toggleCoverLogging(CommandContext<CommandSourceStack> context, Boolean enable) {
        boolean newState;
        if (enable != null) {
            newState = enable;
        } else {
            newState = !CoverTacticalGoal.isDebugLoggingEnabled();
        }
        CoverTacticalGoal.setDebugLogging(newState);
        context.getSource().sendSuccess(() -> Component.literal(
            "Cover behavior logging: " + (newState ? "ON" : "OFF")
        ), true);
        return 1;
    }

    // ======================================================================
    // RENDER TOGGLES
    // ======================================================================
    private static int toggleSoldierVisualization(CommandContext<CommandSourceStack> context) {
        boolean enabled = !CoverDebugManager.isShowSoldierCover();
        CoverDebugManager.setShowSoldierCover(enabled);
        CoverDebugManager.setVisualizationEnabled(true);
        context.getSource().sendSuccess(() -> Component.literal(
            "Soldier cover visualization: " + (enabled ? "ON" : "OFF")
        ), true);
        return 1;
    }

    private static int togglePeekCandidates(CommandContext<CommandSourceStack> context) {
        boolean enabled = !CoverDebugManager.isShowPeekCandidates();
        CoverDebugManager.setShowPeekCandidates(enabled);
        CoverDebugManager.setVisualizationEnabled(true);
        context.getSource().sendSuccess(() -> Component.literal(
            "Peek candidate visualization: " + (enabled ? "ON" : "OFF")
        ), true);
        return 1;
    }

    private static int toggleRayVisualization(CommandContext<CommandSourceStack> context) {
        boolean enabled = !CoverDebugManager.isShowRays();
        CoverDebugManager.setShowRays(enabled);
        context.getSource().sendSuccess(() -> Component.literal(
            "Raycast visualization: " + (enabled ? "ON" : "OFF")
        ), true);
        return 1;
    }

    private static int toggleSolidVisualization(CommandContext<CommandSourceStack> context) {
        boolean enabled = !CoverDebugManager.isShowSolidBlocks();
        CoverDebugManager.setShowSolidBlocks(enabled);
        context.getSource().sendSuccess(() -> Component.literal(
            "Solid block visualization: " + (enabled ? "ON" : "OFF")
        ), true);
        return 1;
    }

    private static int toggleCoverPointVisualization(CommandContext<CommandSourceStack> context) {
        boolean enabled = !CoverDebugManager.isVisualizationEnabled();
        CoverDebugManager.setVisualizationEnabled(enabled);
        context.getSource().sendSuccess(() -> Component.literal(
            "Cover point visualization: " + (enabled ? "ON" : "OFF")
        ), true);
        return 1;
    }

    private static int cycleCombatMode(CommandContext<CommandSourceStack> context) {
        CombatDebugRenderer.cycleDebugMode();
        context.getSource().sendSuccess(() -> Component.literal(
            "Combat debug mode: " + CombatDebugRenderer.getDebugModeName()
        ), true);
        return 1;
    }

    private static int setCombatMode(CommandContext<CommandSourceStack> context, int mode) {
        CombatDebugRenderer.setDebugMode(mode);
        context.getSource().sendSuccess(() -> Component.literal(
            "Combat debug mode set to: " + CombatDebugRenderer.getDebugModeName()
        ), true);
        return 1;
    }

    private static int showUntargeted(CommandContext<CommandSourceStack> context) {
        int count = CombatDebugRenderer.getMaxUntargeted();
        context.getSource().sendSuccess(() -> Component.literal(
            "Max untargeted to render: " + count
        ), false);
        return 1;
    }

    private static int setUntargeted(CommandContext<CommandSourceStack> context) {
        int count = IntegerArgumentType.getInteger(context, "count");
        CombatDebugRenderer.setMaxUntargeted(count);
        context.getSource().sendSuccess(() -> Component.literal(
            "Max untargeted to render set to: " + count
        ), true);
        return 1;
    }

    private static int renderStatus(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
            "Combat debug mode: " + CombatDebugRenderer.getDebugModeName() +
            " | Max untargeted: " + CombatDebugRenderer.getMaxUntargeted()
        ), false);
        return 1;
    }

    // ======================================================================
    // INFO
    // ======================================================================
    private static int showCoverState(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("Player only command"));
            return 0;
        }

        List<SoldierEntity> nearbySoldiers = player.level().getEntitiesOfClass(
            SoldierEntity.class, player.getBoundingBox().inflate(30));

        if (nearbySoldiers.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No soldiers within 30 blocks"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("=== COVER STATE ==="), false);
        for (SoldierEntity soldier : nearbySoldiers) {
            CoverBehaviorManager manager = soldier.getCoverBehaviorManager();
            PeekController peekCtrl = soldier.getPeekController();
            CoverPositionController ctrl = (CoverPositionController) soldier.getMoveControl();

            PeekController.State peekState = peekCtrl.getState();
            long timeInPeekState = peekCtrl.getTimeInCurrentState();
            long timeSinceLastPeek = peekCtrl.getTimeSinceLastPeek();

            CoverPositionController.MovementResult moveResult = ctrl.getLastResult();
            Vec3 ctrlTarget = ctrl.getDebugTargetPos();
            double ctrlTolerance = ctrl.getDebugTolerance();

            BlockPos coverPos = manager.getCurrentCover() != null ? manager.getCurrentCover().getPosition() : null;
            double distToCover = coverPos != null ? soldier.position().distanceTo(coverPos.getCenter()) : -1;
            Vec3 velocity = soldier.getDeltaMovement();
            double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
            Vec3 ctrlTargetDist = ctrlTarget != null ? ctrlTarget.subtract(soldier.position()) : Vec3.ZERO;
            double ctrlDist = Math.sqrt(ctrlTargetDist.x * ctrlTargetDist.x + ctrlTargetDist.z * ctrlTargetDist.z);
            boolean navDone = soldier.getNavigation().isDone();

            source.sendSuccess(() -> Component.literal(
                "Soldier " + soldier.getId() +
                " | State: " + manager.getState() +
                " | Peek: " + peekState + "(" + timeInPeekState + "ms, last=" + timeSinceLastPeek + "ms)" +
                " | ctrlResult: " + moveResult +
                " | ctrlDist=" + String.format("%.2f", ctrlDist) +
                " | tol=" + String.format("%.2f", ctrlTolerance) +
                " | toCover=" + String.format("%.2f", distToCover) +
                " | speed=" + String.format("%.4f", speed) +
                " | nav=" + navDone +
                " | sup=" + String.format("%.2f", manager.getSuppressionTracker().getSuppressionLevel())
            ), false);
        }
        return 1;
    }

    private static int showThreats(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("Player only command"));
            return 0;
        }

        List<SoldierEntity> nearbySoldiers = player.level().getEntitiesOfClass(
            SoldierEntity.class, player.getBoundingBox().inflate(30));
        List<TargetEntity> nearbyTargets = player.level().getEntitiesOfClass(
            TargetEntity.class, player.getBoundingBox().inflate(50));
        List<LivingEntity> threats = new ArrayList<>(nearbyTargets);

        if (nearbySoldiers.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No soldiers within 30 blocks"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("=== THREAT ANALYSIS ==="), false);
        source.sendSuccess(() -> Component.literal("Threats found: " + threats.size()), false);

        for (SoldierEntity soldier : nearbySoldiers) {
            ThreatAwareness soldierThreats = soldier.getThreatAwareness();
            Vec3 threatDir = soldierThreats.getPrimaryDirection(soldier.position());
            source.sendSuccess(() -> Component.literal(
                "Soldier " + soldier.getId() +
                " | Threat dir: " + String.format("%.2f, %.2f, %.2f", threatDir.x, threatDir.y, threatDir.z) +
                " | Flanked: " + soldierThreats.isBeingFlanked(soldier.position()) +
                " | Count: " + soldierThreats.getActiveThreatCount() +
                " | Level: " + String.format("%.2f", soldierThreats.getThreatLevel())
            ), false);
        }
        return 1;
    }

    private static int showReservations(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        var reservations = CoverReservationManager.getAllReservationCounts();

        if (reservations.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No cover reservations"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("=== COVER RESERVATIONS ==="), false);
        source.sendSuccess(() -> Component.literal("Total: " + reservations.size()), false);
        for (var entry : reservations.entrySet()) {
            BlockPos pos = entry.getKey();
            source.sendSuccess(() -> Component.literal(
                "  " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + " | Soldiers: " + entry.getValue()
            ), false);
        }
        return 1;
    }

    private static int showSuppression(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("Player only command"));
            return 0;
        }

        List<SoldierEntity> nearbySoldiers = player.level().getEntitiesOfClass(
            SoldierEntity.class, player.getBoundingBox().inflate(30));

        if (nearbySoldiers.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No soldiers within 30 blocks"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("=== SUPPRESSION ==="), false);
        for (SoldierEntity soldier : nearbySoldiers) {
            CoverBehaviorManager manager = soldier.getCoverBehaviorManager();
            if (manager != null) {
                var tracker = manager.getSuppressionTracker();
                source.sendSuccess(() -> Component.literal(
                    "Soldier " + soldier.getId() +
                    " | Level: " + String.format("%.2f", tracker.getSuppressionLevel()) +
                    " | Suppressed: " + tracker.isSuppressed() +
                    " | Pinned: " + tracker.isPinned() +
                    " | Since: " + tracker.getTimeSinceLastSuppression() + "ms"
                ), false);
            }
        }
        return 1;
    }

    private static int showSquadIntel(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("Player only command"));
            return 0;
        }
        
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("Server side only"));
            return 0;
        }
        
        SquadManager manager = SquadManager.get(serverLevel);
        Optional<SquadData> squadOpt = manager.getSquadByLeader(player.getUUID());
        
        if (!squadOpt.isPresent()) {
            source.sendSuccess(() -> Component.literal("No squad found for player"), false);
            return 0;
        }
        
        SquadData squad = squadOpt.get();
        SquadThreatIntel intel = squad.getThreatIntel();
        
        source.sendSuccess(() -> Component.literal("=== SQUAD THREAT INTEL ==="), false);
        
        List<SquadThreatIntel.ThreatKnowledge> threats = intel.getAllThreats();
        source.sendSuccess(() -> Component.literal("Total threats: " + threats.size()), false);
        
        for (SquadThreatIntel.ThreatKnowledge threat : threats) {
            BlockPos pos = threat.lastKnownPosition;
            String posStr = pos != null ? (pos.getX() + "," + pos.getY() + "," + pos.getZ()) : "unknown";
            String status = threat.isAlive ? (threat.isSuppressed ? "SUPPRESSED" : "ACTIVE") : "DEAD";
            String suppressedBy = threat.suppressedBy != null ? threat.suppressedBy.toString().substring(0, 8) : "none";
            
            source.sendSuccess(() -> Component.literal(
                "  Threat " + threat.threatEntityId.toString().substring(0, 8) +
                " | Pos: " + posStr +
                " | Acc: " + String.format("%.2f", threat.accuracy) +
                " | Status: " + status +
                " | SuppressedBy: " + suppressedBy
            ), false);
        }
        
        List<SoldierEntity> nearbySoldiers = player.level().getEntitiesOfClass(
            SoldierEntity.class, player.getBoundingBox().inflate(30));
        
        source.sendSuccess(() -> Component.literal("=== SOLDIER ASSIGNMENTS ==="), false);
        for (SoldierEntity soldier : nearbySoldiers) {
            UUID squadId = soldier.getSquadId();
            if (squadId == null || !squadId.equals(squad.getSquadId())) continue;
            
            Optional<SquadThreatIntel.ThreatKnowledge> assignment = intel.getAssignedThreatForSoldier(soldier.getUUID());
            String assignmentStr = assignment
                .map(t -> "suppressing " + t.threatEntityId.toString().substring(0, 8))
                .orElse("none");
            
            source.sendSuccess(() -> Component.literal(
                "  Soldier " + soldier.getId() +
                " | Assignment: " + assignmentStr
            ), false);
        }
        
        return 1;
    }

    // --- Scan sub-commands ---
    private static int scanDefault(CommandContext<CommandSourceStack> context) {
        return scanWithRadiusInternal(context, 10);
    }

    private static int scanWithRadius(CommandContext<CommandSourceStack> context) {
        return scanWithRadiusInternal(context, IntegerArgumentType.getInteger(context, "radius"));
    }

    private static int scanWithRadiusInternal(CommandContext<CommandSourceStack> context, int radius) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("Player only command"));
            return 0;
        }

        BlockPos center = player.blockPosition();
        CoverFinder finder = new CoverFinder(player.level());
        List<CoverPoint> coverPoints = finder.findCoverPoints(center, radius);
        CoverDebugManager.setCoverPoints(coverPoints);
        CoverDebugManager.setVisualizationEnabled(true);

        int full = 0, half = 0, conceal = 0;
        for (CoverPoint cp : coverPoints) {
            switch (cp.getType()) {
                case FULL -> full++;
                case HALF -> half++;
                case CONCEALMENT -> conceal++;
            }
        }

        int f = full, h = half, c = conceal;
        source.sendSuccess(() -> Component.literal(
            "Found " + coverPoints.size() + " cover points (r=" + radius + ")\n" +
            "  FULL: " + f + " | HALF: " + h + " | CONCEALMENT: " + c
        ), true);
        return 1;
    }

    private static int scanWithAutoTarget(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("Player only command"));
            return 0;
        }

        List<TargetEntity> targets = player.level().getEntitiesOfClass(
            TargetEntity.class, player.getBoundingBox().inflate(30));
        if (targets.isEmpty()) {
            source.sendFailure(Component.literal("No TargetEntity within 30 blocks"));
            return 0;
        }

        TargetEntity closest = targets.stream().min((a, b) ->
            Double.compare(a.distanceToSqr(player), b.distanceToSqr(player))).orElse(null);
        if (closest == null) return 0;

        source.sendSuccess(() -> Component.literal("Auto-targeting nearest TargetEntity"), true);
        return scanWithTargetEntity(player, closest);
    }

    private static int scanWithTarget(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("Player only command"));
            return 0;
        }
        LivingEntity target;
        try {
            target = (LivingEntity) EntityArgument.getEntity(context, "entity");
        } catch (Exception e) {
            source.sendFailure(Component.literal("Invalid target entity"));
            return 0;
        }
        return scanWithTargetEntity(player, target);
    }

    private static int scanWithTargetEntity(Player player, LivingEntity target) {
        BlockPos center = player.blockPosition();
        CoverFinder finder = new CoverFinder(player.level());
        List<CoverPoint> coverPoints = finder.findCoverPoints(center, 12, target);
        CoverDebugManager.setCoverPoints(coverPoints);
        CoverDebugManager.setThreatEntity(target);
        CoverDebugManager.setVisualizationEnabled(true);

        int full = 0, half = 0, conceal = 0;
        for (CoverPoint cp : coverPoints) {
            switch (cp.getType()) {
                case FULL -> full++;
                case HALF -> half++;
                case CONCEALMENT -> conceal++;
            }
        }
        int f = full, h = half, c = conceal;
        player.createCommandSourceStack().sendSuccess(() -> Component.literal(
            "Found " + coverPoints.size() + " cover points vs target\n" +
            "  FULL: " + f + " | HALF: " + h + " | CONCEALMENT: " + c
        ), true);
        return 1;
    }

    // --- Best sub-commands ---
    private static int findBest(CommandContext<CommandSourceStack> context) {
        return findBestInternal(context, 12);
    }

    private static int findBestWithRadius(CommandContext<CommandSourceStack> context) {
        return findBestInternal(context, IntegerArgumentType.getInteger(context, "radius"));
    }

    private static int findBestInternal(CommandContext<CommandSourceStack> context, int radius) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("Player only command"));
            return 0;
        }

        LivingEntity threat = CoverDebugManager.getThreatEntity();
        BlockPos center = player.blockPosition();
        CoverFinder finder = new CoverFinder(player.level());
        var bestCover = finder.findBestCover(center, radius, threat);

        if (bestCover.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No cover found within radius " + radius), false);
            return 0;
        }

        CoverPoint best = bestCover.get();
        CoverDebugManager.setBestCoverPoint(best);
        CoverDebugManager.setVisualizationEnabled(true);

        BlockPos pos = best.getPosition();
        source.sendSuccess(() -> Component.literal(
            "Best cover: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() +
            " | Type: " + best.getType() +
            " | Quality: " + String.format("%.2f", best.getQuality()) +
            " | Can shoot: " + best.canShootFrom() +
            " | Height: " + String.format("%.2f", best.getCoverHeight())
        ), true);
        return 1;
    }

    // --- Debug specific position ---
    private static int debugSpecificPosition(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("Player only command"));
            return 0;
        }

        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        BlockPos debugPos = new BlockPos(x, y, z);

        LivingEntity threat = CoverDebugManager.getThreatEntity();
        if (threat == null) {
            source.sendFailure(Component.literal("No threat set. Use /stevesarmy_debug info target first"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("=== DEBUG COVER " + debugPos + " ==="), false);
        CoverFinder finder = new CoverFinder(player.level());

        boolean isValid = finder.isValidCoverPositionPublic(debugPos);
        source.sendSuccess(() -> Component.literal("Valid position: " + isValid), false);

        if (!isValid) {
            finder.debugWhyInvalid(debugPos, player);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("Heights:"), false);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            float height = finder.calculateCoverHeightPublic(debugPos, dir);
            source.sendSuccess(() -> Component.literal("  " + dir + ": " + String.format("%.2f", height)), false);
        }

        CoverPoint result = finder.evaluatePosition(debugPos, threat);
        if (result == null) {
            source.sendSuccess(() -> Component.literal("evaluatePosition returned NULL"), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal(
            "Type: " + result.getType() +
            " | Quality: " + String.format("%.2f", result.getQuality()) +
            " | Protected: " + result.getProtectedDirections() +
            " | Info: " + result.getDebugInfo()
        ), false);
        return 1;
    }

    // ======================================================================
    // CONTROL
    // ======================================================================
    private static int forcePeek(CommandContext<CommandSourceStack> context) {
        Player player = context.getSource().getPlayer();
        if (player == null) { context.getSource().sendFailure(Component.literal("Player only")); return 0; }

        SoldierEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (SoldierEntity s : player.level().getEntitiesOfClass(SoldierEntity.class, player.getBoundingBox().inflate(32))) {
            double d = s.distanceToSqr(player);
            if (d < nearestDist && s.getCoverBehaviorManager().isInCover()) {
                nearestDist = d;
                nearest = s;
            }
        }

        if (nearest == null) {
            context.getSource().sendFailure(Component.literal("No soldier in cover within 32 blocks"));
            return 0;
        }

        CoverBehaviorManager manager = nearest.getCoverBehaviorManager();
        manager.resetPeekState();
        manager.setNonPeekableCover(false);
        CoverTacticalGoal.setDebugLogging(true);

        final SoldierEntity target = nearest;
        context.getSource().sendSuccess(() -> Component.literal(
            "Forcing peek for soldier " + target.getId()
        ), true);
        return 1;
    }

    private static int forceReposition(CommandContext<CommandSourceStack> context) {
        Player player = context.getSource().getPlayer();
        if (player == null) { context.getSource().sendFailure(Component.literal("Player only")); return 0; }

        SoldierEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (SoldierEntity s : player.level().getEntitiesOfClass(SoldierEntity.class, player.getBoundingBox().inflate(32))) {
            double d = s.distanceToSqr(player);
            if (d < nearestDist) { nearestDist = d; nearest = s; }
        }

        if (nearest == null) {
            context.getSource().sendFailure(Component.literal("No soldier within 32 blocks"));
            return 0;
        }

        CoverBehaviorManager manager = nearest.getCoverBehaviorManager();
        CoverPoint oldCover = manager.getCurrentCover();
        if (oldCover != null) CoverReservationManager.release(oldCover.getPosition(), nearest);
        manager.clearCover();
        manager.setNonPeekableCover(false);
        manager.resetPeekState();
        CoverTacticalGoal.setDebugLogging(true);

        final SoldierEntity target = nearest;
        context.getSource().sendSuccess(() -> Component.literal(
            "Forced reposition for soldier " + target.getId()
        ), true);
        return 1;
    }

    private static int toggleTeleportMode(CommandContext<CommandSourceStack> context, Boolean enable) {
        boolean newState;
        if (enable != null) {
            newState = enable;
        } else {
            newState = !StevesArmyMod.teleportOnlyMode;
        }
        StevesArmyMod.teleportOnlyMode = newState;
        context.getSource().sendSuccess(() -> Component.literal(
            "Teleport-only movement mode: " + (newState ? "ON" : "OFF") +
            "\nSoldiers will teleport instead of normal movement"
        ), true);
        return 1;
    }

    // --- Pose sub-commands ---
    private static SoldierEntity getNearestSoldier(Player player, double maxDist) {
        SoldierEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (SoldierEntity s : player.level().getEntitiesOfClass(SoldierEntity.class, player.getBoundingBox().inflate(maxDist))) {
            double d = s.distanceToSqr(player);
            if (d < nearestDist) { nearestDist = d; nearest = s; }
        }
        return nearest;
    }

    private static int forceCrawl(CommandContext<CommandSourceStack> context) {
        Player player = context.getSource().getPlayer();
        if (player == null) { context.getSource().sendFailure(Component.literal("Player only")); return 0; }
        SoldierEntity soldier = getNearestSoldier(player, 32);
        if (soldier == null) { context.getSource().sendFailure(Component.literal("No soldier within 32 blocks")); return 0; }
        GunIntegration.crawl(soldier, true);
        context.getSource().sendSuccess(() -> Component.literal(
            "Forced crawl soldier " + soldier.getId() + " | Pose: " + soldier.getPose()
        ), true);
        return 1;
    }

    private static int forceStand(CommandContext<CommandSourceStack> context) {
        Player player = context.getSource().getPlayer();
        if (player == null) { context.getSource().sendFailure(Component.literal("Player only")); return 0; }
        SoldierEntity soldier = getNearestSoldier(player, 32);
        if (soldier == null) { context.getSource().sendFailure(Component.literal("No soldier within 32 blocks")); return 0; }
        GunIntegration.crawl(soldier, false);
        context.getSource().sendSuccess(() -> Component.literal(
            "Forced stand soldier " + soldier.getId() + " | Pose: " + soldier.getPose()
        ), true);
        return 1;
    }

    private static int poseStatus(CommandContext<CommandSourceStack> context) {
        Player player = context.getSource().getPlayer();
        if (player == null) { context.getSource().sendFailure(Component.literal("Player only")); return 0; }
        context.getSource().sendSuccess(() -> Component.literal("=== SOLDIER POSE STATUS ==="), false);
        for (SoldierEntity s : player.level().getEntitiesOfClass(SoldierEntity.class, player.getBoundingBox().inflate(32))) {
            CoverBehaviorManager manager = s.getCoverBehaviorManager();
            CoverPoint cover = manager != null ? manager.getCurrentCover() : null;
            context.getSource().sendSuccess(() -> Component.literal(
                "Soldier " + s.getId() +
                " | Pose: " + s.getPose() +
                " | Crawl: " + GunIntegration.isCrawling(s) +
                " | NoAI: " + s.isNoAi() +
                " | Cover: " + (cover != null ? cover.getType().name() : "NONE") +
                " | State: " + (manager != null ? manager.getState().name() : "N/A")
            ), false);
        }
        return 1;
    }

    private static int toggleNoAi(CommandContext<CommandSourceStack> context) {
        Player player = context.getSource().getPlayer();
        if (player == null) { context.getSource().sendFailure(Component.literal("Player only")); return 0; }
        SoldierEntity soldier = getNearestSoldier(player, 32);
        if (soldier == null) { context.getSource().sendFailure(Component.literal("No soldier within 32 blocks")); return 0; }
        boolean newState = !soldier.isNoAi();
        soldier.setNoAi(newState);
        context.getSource().sendSuccess(() -> Component.literal(
            "Soldier " + soldier.getId() + " NoAI: " + newState + " | Pose: " + soldier.getPose()
        ), true);
        return 1;
    }

    private static int showPoseAngles(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
            "=== PRONE POSE ANGLES (radians) ===" +
            "\n" + PoseConfig.getAngleReport()
        ), false);
        return 1;
    }

    private static int showPoseDegrees(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
            "=== PRONE POSE IN DEGREES ===" +
            "\n" + PoseConfig.getDegreesReport()
        ), false);
        return 1;
    }

    private static int setPoseAngle(CommandContext<CommandSourceStack> context) {
        String part = StringArgumentType.getString(context, "part");
        String axis = StringArgumentType.getString(context, "axis");
        float value = FloatArgumentType.getFloat(context, "value");

        String fieldName = partToFieldPrefix(part) + "_" + axisToFieldSuffix(axis);
        if (fieldName == null) {
            context.getSource().sendFailure(Component.literal("Unknown part/axis"));
            return 0;
        }
        boolean found = setField(fieldName, value);
        if (!found) {
            context.getSource().sendFailure(Component.literal("Unknown field"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal(
            "Set " + part + "." + axis + " = " + String.format("%.4f", value)
        ), false);
        return 1;
    }

    private static int resetPoseAngles(CommandContext<CommandSourceStack> context) {
        PoseConfig.RA_X = -3.14F; PoseConfig.RA_Y = 0.1F; PoseConfig.RA_Z = 0.1F;
        PoseConfig.LA_X = -3.14F; PoseConfig.LA_Y = 0.2F; PoseConfig.LA_Z = -0.5F;
        PoseConfig.H_X = -3.0F; PoseConfig.B_X = -0.1F; PoseConfig.B_Y = 0.0F; PoseConfig.B_Z = 0.0F;
        PoseConfig.RL_X = 0.0F; PoseConfig.RL_Y = 0.0F; PoseConfig.RL_Z = 0.3F; PoseConfig.RL_POS_Z = -1.5F;
        PoseConfig.LL_X = 0.0F; PoseConfig.LL_Y = 0.0F; PoseConfig.LL_Z = -0.3F; PoseConfig.LL_POS_Z = -1.5F;
        PoseConfig.RA_POS_X = 0.8F; PoseConfig.RA_POS_Y = 0.0F; PoseConfig.RA_POS_Z = -2.0F;
        PoseConfig.LA_POS_X = -2.0F; PoseConfig.LA_POS_Y = -2.0F; PoseConfig.LA_POS_Z = -2.0F;
        PoseConfig.H_CLAMP_MIN = -1.5F; PoseConfig.H_CLAMP_MAX = 0.3F;
        context.getSource().sendSuccess(() -> Component.literal("Pose angles reset to defaults"), false);
        return 1;
    }

    private static String partToFieldPrefix(String part) {
        return switch (part.toLowerCase()) {
            case "rightarm" -> "RA"; case "leftarm" -> "LA"; case "head" -> "H";
            case "body" -> "B"; case "rightleg" -> "RL"; case "leftleg" -> "LL";
            default -> null;
        };
    }

    private static String axisToFieldSuffix(String axis) {
        return switch (axis.toLowerCase()) {
            case "xrot" -> "X"; case "yrot" -> "Y"; case "zrot" -> "Z";
            case "xpos" -> "POS_X"; case "ypos" -> "POS_Y"; case "zpos" -> "POS_Z";
            case "hclampmin" -> "CLAMP_MIN"; case "hclampmax" -> "CLAMP_MAX";
            default -> null;
        };
    }

    private static boolean setField(String name, float value) {
        switch (name) {
            case "RA_X": PoseConfig.RA_X = value; return true;
            case "RA_Y": PoseConfig.RA_Y = value; return true;
            case "RA_Z": PoseConfig.RA_Z = value; return true;
            case "LA_X": PoseConfig.LA_X = value; return true;
            case "LA_Y": PoseConfig.LA_Y = value; return true;
            case "LA_Z": PoseConfig.LA_Z = value; return true;
            case "H_X": PoseConfig.H_X = value; return true;
            case "B_X": PoseConfig.B_X = value; return true;
            case "B_Y": PoseConfig.B_Y = value; return true;
            case "B_Z": PoseConfig.B_Z = value; return true;
            case "RL_X": PoseConfig.RL_X = value; return true;
            case "RL_Y": PoseConfig.RL_Y = value; return true;
            case "RL_Z": PoseConfig.RL_Z = value; return true;
            case "LL_X": PoseConfig.LL_X = value; return true;
            case "LL_Y": PoseConfig.LL_Y = value; return true;
            case "LL_Z": PoseConfig.LL_Z = value; return true;
            case "RA_POS_X": PoseConfig.RA_POS_X = value; return true;
            case "RA_POS_Y": PoseConfig.RA_POS_Y = value; return true;
            case "RA_POS_Z": PoseConfig.RA_POS_Z = value; return true;
            case "LA_POS_X": PoseConfig.LA_POS_X = value; return true;
            case "LA_POS_Y": PoseConfig.LA_POS_Y = value; return true;
            case "LA_POS_Z": PoseConfig.LA_POS_Z = value; return true;
            case "H_CLAMP_MIN": PoseConfig.H_CLAMP_MIN = value; return true;
            case "H_CLAMP_MAX": PoseConfig.H_CLAMP_MAX = value; return true;
            default: return false;
        }
    }

    // ======================================================================
    // STATUS (show all toggle states)
    // ======================================================================
    private static int showDebugStatus(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
            "=== DEBUG STATUS ===\n" +
            "  Cover logging: " + (CoverTacticalGoal.isDebugLoggingEnabled() ? "ON" : "OFF") + "\n" +
            "  Combat overlay: " + CombatDebugRenderer.getDebugModeName() + "\n" +
            "  Soldier viz: " + (CoverDebugManager.isShowSoldierCover() ? "ON" : "OFF") + "\n" +
            "  Peek candidates: " + (CoverDebugManager.isShowPeekCandidates() ? "ON" : "OFF") + "\n" +
            "  Cover points: " + (CoverDebugManager.isVisualizationEnabled() ? "ON" : "OFF") + "\n" +
            "  Rays: " + (CoverDebugManager.isShowRays() ? "ON" : "OFF") + "\n" +
            "  Solid blocks: " + (CoverDebugManager.isShowSolidBlocks() ? "ON" : "OFF") + "\n" +
            "  Untargeted: " + CombatDebugRenderer.getMaxUntargeted()
        ), false);
        return 1;
    }
}
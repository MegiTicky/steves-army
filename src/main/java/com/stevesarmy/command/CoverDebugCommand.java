package com.stevesarmy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.stevesarmy.client.model.PoseConfig;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.ThreatAwareness;
import com.stevesarmy.combat.cover.CoverFinder;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverDebugManager;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.CoverReservationManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.TargetEntity;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.ArrayList;
import java.util.function.BiConsumer;

public class CoverDebugCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("stevesarmy_cover")
            .requires(source -> source.hasPermission(2))
            .executes(CoverDebugCommand::showHelp)
            .then(Commands.literal("scan")
                .executes(CoverDebugCommand::scanDefault)
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 20))
                    .executes(CoverDebugCommand::scanWithRadius))
            )
            .then(Commands.literal("target")
                .executes(CoverDebugCommand::scanWithAutoTarget)
                .then(Commands.argument("entity", EntityArgument.entity())
                    .executes(CoverDebugCommand::scanWithTarget))
            )
            .then(Commands.literal("rays")
                .executes(CoverDebugCommand::toggleRayVisualization)
            )
            .then(Commands.literal("solid")
                .executes(CoverDebugCommand::toggleSolidVisualization)
            )
            .then(Commands.literal("best")
                .executes(CoverDebugCommand::findBest)
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 20))
                    .executes(CoverDebugCommand::findBestWithRadius))
            )
            .then(Commands.literal("debug")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                    .then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(CoverDebugCommand::debugSpecificPosition))))
            )
            .then(Commands.literal("clear")
                .executes(CoverDebugCommand::clearVisualization)
            )
            .then(Commands.literal("toggle")
                .executes(CoverDebugCommand::toggleVisualization)
            )
            .then(Commands.literal("suppression")
                .executes(CoverDebugCommand::showSuppression)
            )
            .then(Commands.literal("state")
                .executes(CoverDebugCommand::showCoverState)
            )
            .then(Commands.literal("threats")
                .executes(CoverDebugCommand::showThreats)
            )
            .then(Commands.literal("reservations")
                .executes(CoverDebugCommand::showReservations)
            )
            .then(Commands.literal("log")
                .executes(CoverDebugCommand::toggleLogging)
                .then(Commands.literal("on")
                    .executes(CoverDebugCommand::enableLogging))
                .then(Commands.literal("off")
                    .executes(CoverDebugCommand::disableLogging))
            )
            .then(Commands.literal("soldiers")
                .executes(CoverDebugCommand::toggleSoldierVisualization)
            )
            .then(Commands.literal("peekcandidates")
                .executes(CoverDebugCommand::togglePeekCandidates)
            )
            .then(Commands.literal("peek")
                .executes(CoverDebugCommand::forcePeek)
            )
            .then(Commands.literal("pose")
                .executes(CoverDebugCommand::poseStatus)
                .then(Commands.literal("crawl")
                    .executes(CoverDebugCommand::forceCrawl))
                .then(Commands.literal("stand")
                    .executes(CoverDebugCommand::forceStand))
                .then(Commands.literal("status")
                    .executes(CoverDebugCommand::poseStatus))
                .then(Commands.literal("noai")
                    .executes(CoverDebugCommand::toggleNoAi))
                .then(Commands.literal("angles")
                    .executes(CoverDebugCommand::showPoseAngles))
                .then(Commands.literal("deg")
                    .executes(CoverDebugCommand::showPoseDegrees))
                .then(Commands.literal("set")
                    .then(Commands.argument("part", StringArgumentType.word())
                        .then(Commands.argument("axis", StringArgumentType.word())
                            .then(Commands.argument("value", FloatArgumentType.floatArg())
                                .executes(CoverDebugCommand::setPoseAngle)))))
                .then(Commands.literal("reset")
                    .executes(CoverDebugCommand::resetPoseAngles))
            )
            .then(Commands.literal("reposition")
                .executes(CoverDebugCommand::forceReposition)
            )
        );
    }
    
    private static int showHelp(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
            "Cover Debug Commands:\n" +
            "  /stevesarmy_cover scan [radius] - Scan for cover points\n" +
            "  /stevesarmy_cover target [entity] - Scan with threat (auto-finds TargetEntity)\n" +
            "  /stevesarmy_cover rays - Toggle raycast visualization\n" +
            "  /stevesarmy_cover solid - Toggle solid block visualization\n" +
            "  /stevesarmy_cover best [radius] - Find best cover point\n" +
            "  /stevesarmy_cover debug <x> <y> <z> - Debug specific position\n" +
            "  /stevesarmy_cover clear - Clear visualization\n" +
            "  /stevesarmy_cover toggle - Toggle visualization on/off\n" +
            "  /stevesarmy_cover suppression - Show suppression levels for nearby soldiers\n" +
            "  /stevesarmy_cover state - Show cover state for nearby soldiers\n" +
            "  /stevesarmy_cover threats - Show threat direction analysis\n" +
            "  /stevesarmy_cover reservations - Show cover reservations\n" +
            "  /stevesarmy_cover log [on|off] - Toggle cover behavior logging\n" +
            "  /stevesarmy_cover soldiers - Toggle soldier cover visualization (state, cover, threats)\n" +
            "  /stevesarmy_cover peekcandidates - Toggle peek candidate visualization\n" +
            "  /stevesarmy_cover peek - Force nearest soldier to peek from cover\n" +
            "  /stevesarmy_cover pose [crawl|stand|status|noai] - Force crawl/stand, show pose, toggle AI\n" +
            "  /stevesarmy_cover reposition - Force nearest soldier to abandon cover and find new one"
            ), false);
        return 1;
    }
    
    private static int scanDefault(CommandContext<CommandSourceStack> context) {
        return scanWithRadius(context, 10);
    }
    
    private static int scanWithRadius(CommandContext<CommandSourceStack> context) {
        int radius = IntegerArgumentType.getInteger(context, "radius");
        return scanWithRadius(context, radius);
    }
    
    private static int scanWithRadius(CommandContext<CommandSourceStack> context, int radius) {
        CommandSourceStack source = context.getSource();
        
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("This command must be executed by a player"));
            return 0;
        }
        
        BlockPos center = player.blockPosition();
        CoverFinder finder = new CoverFinder(player.level());
        List<CoverPoint> coverPoints = finder.findCoverPoints(center, radius);
        
        CoverDebugManager.setCoverPoints(coverPoints);
        CoverDebugManager.setVisualizationEnabled(true);
        
        int fullCover = 0;
        int half = 0;
        int concealment = 0;
        
        for (CoverPoint cp : coverPoints) {
            switch (cp.getType()) {
                case FULL -> fullCover++;
                case HALF -> half++;
                case CONCEALMENT -> concealment++;
                default -> {}
            }
        }
        
        int finalFullCover = fullCover;
        int finalHalf = half;
        int finalConcealment = concealment;
        source.sendSuccess(() -> Component.literal(
            "Found " + coverPoints.size() + " cover points (radius=" + radius + ")\n" +
            "  FULL: " + finalFullCover + " | HALF: " + finalHalf + " | CONCEALMENT: " + finalConcealment
        ), true);
        
        return 1;
    }
    
    private static int scanWithTarget(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("This command must be executed by a player"));
            return 0;
        }
        
        LivingEntity target;
        try {
            target = (LivingEntity) EntityArgument.getEntity(context, "entity");
        } catch (Exception e) {
            source.sendFailure(Component.literal("Invalid target entity - must be a living entity"));
            return 0;
        }
        
        return scanWithTargetEntity(player, target);
    }
    
    private static int scanWithAutoTarget(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("This command must be executed by a player"));
            return 0;
        }
        
        List<TargetEntity> nearbyTargets = player.level().getEntitiesOfClass(
            TargetEntity.class,
            player.getBoundingBox().inflate(30)
        );
        
        if (nearbyTargets.isEmpty()) {
            source.sendFailure(Component.literal("No TargetEntity found within 30 blocks. Spawn one with the target spawn egg."));
            return 0;
        }
        
        TargetEntity closest = nearbyTargets.stream()
            .min((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)))
            .orElse(null);
        
        if (closest == null) {
            source.sendFailure(Component.literal("Could not find nearest TargetEntity"));
            return 0;
        }
        
        source.sendSuccess(() -> Component.literal("Auto-targeting nearest TargetEntity at " + 
            closest.getBlockX() + ", " + closest.getBlockY() + ", " + closest.getBlockZ()), true);
        
        return scanWithTargetEntity(player, closest);
    }
    
    private static int scanWithTargetEntity(Player player, LivingEntity target) {
        BlockPos center = player.blockPosition();
        CoverFinder finder = new CoverFinder(player.level());
        List<CoverPoint> coverPoints = finder.findCoverPoints(center, 12, target);
        
        CoverDebugManager.setCoverPoints(coverPoints);
        CoverDebugManager.setThreatEntity(target);
        CoverDebugManager.setVisualizationEnabled(true);
        
        int fullCover = 0;
        int half = 0;
        int concealment = 0;
        
        for (CoverPoint cp : coverPoints) {
            switch (cp.getType()) {
                case FULL -> fullCover++;
                case HALF -> half++;
                case CONCEALMENT -> concealment++;
                default -> {}
            }
        }
        
        int finalFull = fullCover;
        int finalHalf = half;
        int finalConcealment = concealment;
        CommandSourceStack source = player.createCommandSourceStack();
        source.sendSuccess(() -> Component.literal(
            "Found " + coverPoints.size() + " cover points relative to target\n" +
            "  FULL: " + finalFull + " | HALF: " + finalHalf + " | CONCEALMENT: " + finalConcealment
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
    
    private static int findBest(CommandContext<CommandSourceStack> context) {
        return findBestInternal(context, 12);
    }
    
    private static int findBestWithRadius(CommandContext<CommandSourceStack> context) {
        int radius = IntegerArgumentType.getInteger(context, "radius");
        return findBestInternal(context, radius);
    }
    
    private static int findBestInternal(CommandContext<CommandSourceStack> context, int radius) {
        CommandSourceStack source = context.getSource();
        
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("This command must be executed by a player"));
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
            "Best cover: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "\n" +
            "  Type: " + best.getType() + "\n" +
            "  Quality: " + String.format("%.2f", best.getQuality()) + "\n" +
            "  Can shoot: " + best.canShootFrom() + "\n" +
            "  Cover height: " + String.format("%.2f", best.getCoverHeight()) + "\n" +
            "  Debug: " + best.getDebugInfo()
        ), true);
        
        return 1;
    }
    
    private static int debugSpecificPosition(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("This command must be executed by a player"));
            return 0;
        }
        
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        BlockPos debugPos = new BlockPos(x, y, z);
        
        LivingEntity threat = CoverDebugManager.getThreatEntity();
        if (threat == null) {
            source.sendFailure(Component.literal("No threat set. Run /stevesarmy_cover target first"));
            return 0;
        }
        
        source.sendSuccess(() -> Component.literal("=== DEBUG COVER POSITION " + debugPos + " ==="), false);
        
        CoverFinder finder = new CoverFinder(player.level());
        
        boolean isValidPos = finder.isValidCoverPositionPublic(debugPos);
        source.sendSuccess(() -> Component.literal("1. Valid cover position: " + isValidPos), false);
        
        if (!isValidPos) {
            finder.debugWhyInvalid(debugPos, player);
            return 1;
        }
        
        CoverPoint coverPoint = new CoverPoint(debugPos);
        
        source.sendSuccess(() -> Component.literal("2. Checking cover heights in 4 directions:"), false);
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            float height = finder.calculateCoverHeightPublic(debugPos, dir);
            source.sendSuccess(() -> Component.literal("   " + dir + ": height=" + String.format("%.2f", height)), false);
        }
        
        CoverPoint result = finder.evaluatePosition(debugPos, threat);
        if (result == null) {
            source.sendSuccess(() -> Component.literal("3. evaluatePosition returned NULL"), false);
            return 1;
        }
        
        source.sendSuccess(() -> Component.literal("3. Cover type: " + result.getType()), false);
        source.sendSuccess(() -> Component.literal("4. Quality: " + String.format("%.2f", result.getQuality())), false);
        source.sendSuccess(() -> Component.literal("5. Protected dirs: " + result.getProtectedDirections()), false);
        source.sendSuccess(() -> Component.literal("6. Debug info: " + result.getDebugInfo()), false);
        
        return 1;
    }
    
    private static int clearVisualization(CommandContext<CommandSourceStack> context) {
        CoverDebugManager.clear();
        context.getSource().sendSuccess(() -> Component.literal("Cover visualization cleared"), true);
        return 1;
    }
    
    private static int toggleVisualization(CommandContext<CommandSourceStack> context) {
        boolean enabled = !CoverDebugManager.isVisualizationEnabled();
        CoverDebugManager.setVisualizationEnabled(enabled);
        context.getSource().sendSuccess(() -> Component.literal(
            "Cover visualization: " + (enabled ? "ON" : "OFF")
        ), true);
        return 1;
    }
    
    private static int showSuppression(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("This command must be executed by a player"));
            return 0;
        }
        
        List<SoldierEntity> nearbySoldiers = player.level().getEntitiesOfClass(
            SoldierEntity.class,
            player.getBoundingBox().inflate(30)
        );
        
        if (nearbySoldiers.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No soldiers found within 30 blocks"), false);
            return 0;
        }
        
        source.sendSuccess(() -> Component.literal("=== SUPPRESSION STATUS ==="), false);
        for (SoldierEntity soldier : nearbySoldiers) {
            CoverBehaviorManager manager = soldier.getCoverBehaviorManager();
            if (manager != null) {
                var tracker = manager.getSuppressionTracker();
                float level = tracker.getSuppressionLevel();
                boolean suppressed = tracker.isSuppressed();
                boolean pinned = tracker.isPinned();
                long timeSince = tracker.getTimeSinceLastSuppression();
                
                source.sendSuccess(() -> Component.literal(
                    "Soldier: " + soldier.getUUID().toString().substring(0, 8) + "..." +
                    " | Level: " + String.format("%.2f", level) +
                    " | Suppressed: " + suppressed +
                    " | Pinned: " + pinned +
                    " | Time since: " + timeSince + "ms"
                ), false);
            }
        }
        
        return 1;
    }
    
    private static int showCoverState(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("This command must be executed by a player"));
            return 0;
        }
        
        List<SoldierEntity> nearbySoldiers = player.level().getEntitiesOfClass(
            SoldierEntity.class,
            player.getBoundingBox().inflate(30)
        );
        
        if (nearbySoldiers.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No soldiers found within 30 blocks"), false);
            return 0;
        }
        
        source.sendSuccess(() -> Component.literal("=== COVER STATE ==="), false);
        for (SoldierEntity soldier : nearbySoldiers) {
            CoverBehaviorManager manager = soldier.getCoverBehaviorManager();
            if (manager != null) {
                CoverBehaviorManager.CoverState state = manager.getState();
                CoverPoint cover = manager.getCurrentCover();
                long timeInCover = manager.getTimeInCover();
                long timeSeeking = manager.getTimeSeeking();
                
                final String coverInfo;
                if (cover != null) {
                    BlockPos pos = cover.getPosition();
                    coverInfo = pos.getX() + "," + pos.getY() + "," + pos.getZ() +
                        " (" + cover.getType() + ")";
                } else {
                    coverInfo = "None";
                }
                
                final String timeInfo;
                if (state == CoverBehaviorManager.CoverState.SEEKING_COVER) {
                    timeInfo = "seeking=" + timeSeeking + "ms";
                } else if (state == CoverBehaviorManager.CoverState.IN_COVER || 
                           state == CoverBehaviorManager.CoverState.SUPPRESSED_IN_COVER) {
                    timeInfo = "inCover=" + timeInCover + "ms";
                } else {
                    timeInfo = "N/A";
                }
                
                source.sendSuccess(() -> Component.literal(
                    "Soldier: " + soldier.getUUID().toString().substring(0, 8) + "..." +
                    " | State: " + state +
                    " | Cover: " + coverInfo +
                    " | Time: " + timeInfo
                ), false);
            }
        }
        
        return 1;
    }
    
    private static int showThreats(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("This command must be executed by a player"));
            return 0;
        }
        
        List<SoldierEntity> nearbySoldiers = player.level().getEntitiesOfClass(
            SoldierEntity.class,
            player.getBoundingBox().inflate(30)
        );
        
        List<TargetEntity> nearbyTargets = player.level().getEntitiesOfClass(
            TargetEntity.class,
            player.getBoundingBox().inflate(50)
        );
        
        List<LivingEntity> threats = new ArrayList<>(nearbyTargets);
        
        if (nearbySoldiers.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No soldiers found within 30 blocks"), false);
            return 0;
        }
        
        source.sendSuccess(() -> Component.literal("=== THREAT ANALYSIS ==="), false);
        source.sendSuccess(() -> Component.literal("Threats found: " + threats.size()), false);
        
        for (SoldierEntity soldier : nearbySoldiers) {
            ThreatAwareness soldierThreats = soldier.getThreatAwareness();
            Vec3 threatDir = soldierThreats.getPrimaryDirection(soldier.position());
            String dirStr = String.format("%.2f, %.2f, %.2f", 
                threatDir.x, threatDir.y, threatDir.z);
            
            source.sendSuccess(() -> Component.literal(
                "Soldier: " + soldier.getUUID().toString().substring(0, 8) + "..." +
                " | Threat dir: " + dirStr +
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
        source.sendSuccess(() -> Component.literal("Total reserved positions: " + reservations.size()), false);
        
        for (var entry : reservations.entrySet()) {
            BlockPos pos = entry.getKey();
            int count = entry.getValue();
            source.sendSuccess(() -> Component.literal(
                "Position: " + pos.getX() + "," + pos.getY() + "," + pos.getZ() +
                " | Soldiers: " + count
            ), false);
        }
        
        return 1;
    }
    
    private static int toggleLogging(CommandContext<CommandSourceStack> context) {
        boolean enabled = !CoverTacticalGoal.isDebugLoggingEnabled();
        CoverTacticalGoal.setDebugLogging(enabled);
        context.getSource().sendSuccess(() -> Component.literal(
            "Cover behavior logging: " + (enabled ? "ON" : "OFF") +
            "\nUse /stevesarmy_cover log on|off to set explicitly"
        ), true);
        return 1;
    }
    
    private static int enableLogging(CommandContext<CommandSourceStack> context) {
        CoverTacticalGoal.setDebugLogging(true);
        context.getSource().sendSuccess(() -> Component.literal(
            "Cover behavior logging: ON\n" +
            "Logs will show cover selection, switching, and hysteresis decisions"
        ), true);
        return 1;
    }
    
    private static int disableLogging(CommandContext<CommandSourceStack> context) {
        CoverTacticalGoal.setDebugLogging(false);
        context.getSource().sendSuccess(() -> Component.literal("Cover behavior logging: OFF"), true);
        return 1;
    }
    
    private static int toggleSoldierVisualization(CommandContext<CommandSourceStack> context) {
        boolean enabled = !CoverDebugManager.isShowSoldierCover();
        CoverDebugManager.setShowSoldierCover(enabled);
        CoverDebugManager.setVisualizationEnabled(true);
        
        context.getSource().sendSuccess(() -> Component.literal(
            "Soldier cover visualization: " + (enabled ? "ON" : "OFF") + "\n" +
            "Lines:\n" +
            "  GREEN = current cover\n" +
            "  YELLOW = target cover (seeking)\n" +
            "  GRAY = last cover (abandoned)\n" +
            "  RED = threat direction\n" +
            "  ORANGE = target enemy\n" +
            "Labels:\n" +
            "  State, Cover info, Suppression %, Squad mode"
        ), true);
        return 1;
    }
    
    private static int togglePeekCandidates(CommandContext<CommandSourceStack> context) {
        boolean enabled = !CoverDebugManager.isShowPeekCandidates();
        CoverDebugManager.setShowPeekCandidates(enabled);
        CoverDebugManager.setVisualizationEnabled(true);
        
        context.getSource().sendSuccess(() -> Component.literal(
            "Peek candidate visualization: " + (enabled ? "ON" : "OFF") + "\n" +
            "Shows each candidate block considered for full-cover peeking:\n" +
            "  GREEN = accepted (passed all checks)\n" +
            "  CYAN = chosen best candidate\n" +
            "  YELLOW = bad angle (not 45-135deg from threat)\n" +
            "  RED = no LOS to target\n" +
            "  GRAY = invalid position (blocked or no ground)\n" +
            "  DARK_RED = protected direction (stepping into cover)"
        ), true);
        return 1;
    }
    
    private static int forcePeek(CommandContext<CommandSourceStack> context) {
        Player player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Player only command"));
            return 0;
        }
        
        SoldierEntity nearestSoldier = null;
        double nearestDist = Double.MAX_VALUE;
        
        for (SoldierEntity soldier : player.level().getEntitiesOfClass(
                SoldierEntity.class, player.getBoundingBox().inflate(32))) {
            double dist = soldier.distanceToSqr(player);
            if (dist < nearestDist && soldier.getCoverBehaviorManager().isInCover()) {
                nearestDist = dist;
                nearestSoldier = soldier;
            }
        }
        
        if (nearestSoldier == null) {
            context.getSource().sendFailure(Component.literal("No soldier in cover within 32 blocks"));
            return 0;
        }
        
        CoverBehaviorManager manager = nearestSoldier.getCoverBehaviorManager();
        CoverPoint cover = manager.getCurrentCover();
        
        if (cover == null) {
            context.getSource().sendFailure(Component.literal("Soldier has no current cover"));
            return 0;
        }
        
        manager.resetPeekState();
        manager.setNonPeekableCover(false);
        
        CoverTacticalGoal.setDebugLogging(true);
        
        final SoldierEntity targetSoldier = nearestSoldier;
        final CoverPoint targetCover = cover;
        
        context.getSource().sendSuccess(() -> Component.literal(
            "Forcing peek for soldier " + targetSoldier.getId() + 
            " at " + targetCover.getPosition() + " (" + targetCover.getType() + ")"
        ), true);
        return 1;
    }
    
    private static SoldierEntity getNearestSoldier(Player player, double maxDist) {
        SoldierEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (SoldierEntity soldier : player.level().getEntitiesOfClass(
                SoldierEntity.class, player.getBoundingBox().inflate(maxDist))) {
            double dist = soldier.distanceToSqr(player);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = soldier;
            }
        }
        return nearest;
    }
    
    private static int forceCrawl(CommandContext<CommandSourceStack> context) {
        Player player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Player only command"));
            return 0;
        }
        
        SoldierEntity soldier = getNearestSoldier(player, 32);
        if (soldier == null) {
            context.getSource().sendFailure(Component.literal("No soldier within 32 blocks"));
            return 0;
        }
        
        GunIntegration.crawl(soldier, true);
        
        final SoldierEntity target = soldier;
        context.getSource().sendSuccess(() -> Component.literal(
            "Forced crawl for soldier " + target.getId() +
            " | Pose: " + target.getPose() +
            " | isCrawling: " + GunIntegration.isCrawling(target)
        ), true);
        return 1;
    }
    
    private static int forceStand(CommandContext<CommandSourceStack> context) {
        Player player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Player only command"));
            return 0;
        }
        
        SoldierEntity soldier = getNearestSoldier(player, 32);
        if (soldier == null) {
            context.getSource().sendFailure(Component.literal("No soldier within 32 blocks"));
            return 0;
        }
        
        GunIntegration.crawl(soldier, false);
        
        final SoldierEntity target = soldier;
        context.getSource().sendSuccess(() -> Component.literal(
            "Forced stand for soldier " + target.getId() +
            " | Pose: " + target.getPose() +
            " | isCrawling: " + GunIntegration.isCrawling(target)
        ), true);
        return 1;
    }
    
    private static int poseStatus(CommandContext<CommandSourceStack> context) {
        Player player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Player only command"));
            return 0;
        }
        
        context.getSource().sendSuccess(() -> Component.literal("=== SOLDIER POSE STATUS ==="), false);
        
        for (SoldierEntity soldier : player.level().getEntitiesOfClass(
                SoldierEntity.class, player.getBoundingBox().inflate(32))) {
            CoverBehaviorManager manager = soldier.getCoverBehaviorManager();
            CoverPoint cover = manager != null ? manager.getCurrentCover() : null;
            String coverType = cover != null ? cover.getType().name() : "NONE";
            String state = manager != null ? manager.getState().name() : "N/A";
            
            final SoldierEntity s = soldier;
            context.getSource().sendSuccess(() -> Component.literal(
                "Soldier " + s.getId() +
                " | Pose: " + s.getPose() +
                " | isCrawling: " + GunIntegration.isCrawling(s) +
                " | CrawlFlag: " + s.isCrawling() +
                " | isInWater: " + s.isInWater() +
                " | NoAI: " + s.isNoAi() +
                " | Cover: " + coverType +
                " | State: " + state
            ), false);
        }
        
        return 1;
    }
    
    private static int toggleNoAi(CommandContext<CommandSourceStack> context) {
        Player player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Player only command"));
            return 0;
        }
        
        SoldierEntity soldier = getNearestSoldier(player, 32);
        if (soldier == null) {
            context.getSource().sendFailure(Component.literal("No soldier within 32 blocks"));
            return 0;
        }
        
        boolean newState = !soldier.isNoAi();
        soldier.setNoAi(newState);
        
        final SoldierEntity target = soldier;
        context.getSource().sendSuccess(() -> Component.literal(
            "Soldier " + target.getId() + " NoAI: " + target.isNoAi() +
            " | Pose: " + target.getPose()
        ), true);
        return 1;
    }
    
    private static int forceReposition(CommandContext<CommandSourceStack> context) {
        Player player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Player only command"));
            return 0;
        }
        
        SoldierEntity nearestSoldier = null;
        double nearestDist = Double.MAX_VALUE;
        
        for (SoldierEntity soldier : player.level().getEntitiesOfClass(
                SoldierEntity.class, player.getBoundingBox().inflate(32))) {
            double dist = soldier.distanceToSqr(player);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestSoldier = soldier;
            }
        }
        
        if (nearestSoldier == null) {
            context.getSource().sendFailure(Component.literal("No soldier within 32 blocks"));
            return 0;
        }
        
        CoverBehaviorManager manager = nearestSoldier.getCoverBehaviorManager();
        
        CoverPoint oldCover = manager.getCurrentCover();
        if (oldCover != null) {
            CoverReservationManager.release(oldCover.getPosition(), nearestSoldier);
        }
        manager.clearCover();
        manager.setNonPeekableCover(false);
        manager.resetPeekState();
        
        CoverTacticalGoal.setDebugLogging(true);
        
        final SoldierEntity targetSoldier = nearestSoldier;
        final String oldPos = (oldCover != null ? oldCover.getPosition().toString() : "no cover");
        
        context.getSource().sendSuccess(() -> Component.literal(
            "Forced reposition for soldier " + targetSoldier.getId() + 
            " (was at " + oldPos + ")"
        ), true);
        return 1;
    }

    // Prone pose design helpers — prints target angles for Blockbench tuning
    private static final String[][] POSE_PARTS = {
        {"rightArm.xRot", "3.1416"},
        {"rightArm.yRot", "0.0"},
        {"rightArm.zRot", "0.0"},
        {"leftArm.xRot", "2.9"},
        {"leftArm.yRot", "-0.06"},
        {"leftArm.zRot", "-0.72"},
        {"head.xRot", "-1.35"},
        {"rightLeg.xRot", "-0.34"},
        {"rightLeg.yRot", "1.13"},
        {"rightLeg.zRot", "-0.76"},
        {"leftLeg.xRot", "-0.19"},
        {"leftLeg.yRot", "-0.07"},
        {"leftLeg.zRot", "0.0"},
        {"body.xRot", "-0.14"},
        {"body.yRot", "0.28"},
        {"body.zRot", "-0.04"},
    };

    private static int showPoseAngles(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
            "=== LIVE PRONE POSE ANGLES (radians) ===" +
            "\nUse /stevesarmy_cover pose set <part> <axis> <value> to adjust" +
            "\n" + PoseConfig.getAngleReport() +
            "\n---" +
            "\nParts: rightArm, leftArm, head, body, rightLeg, leftLeg" +
            "\nAxes: xRot, yRot, zRot (also: xPos, yPos, zPos for arm positions)" +
            "\nAlso: hClampMin, hClampMax for head rotation limits"
        ), false);
        return 1;
    }

    private static int showPoseDegrees(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
            "=== PRONE POSE IN DEGREES (Blockbench format) ===" +
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
            context.getSource().sendFailure(Component.literal(
                "Unknown part '" + part + "' or axis '" + axis + "'"));
            return 0;
        }
        
        boolean found = setField(fieldName, value);
        if (!found) {
            context.getSource().sendFailure(Component.literal(
                "Unknown field '" + fieldName + "'"));
            return 0;
        }
        
        context.getSource().sendSuccess(() -> Component.literal(
            "Set " + part + "." + axis + " = " + String.format("%.4f", value) +
            " (" + String.format("%.1f", value * 180.0F / (float)Math.PI) + "°)"
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
            case "rightarm" -> "RA";
            case "leftarm" -> "LA";
            case "head" -> "H";
            case "body" -> "B";
            case "rightleg" -> "RL";
            case "leftleg" -> "LL";
            default -> null;
        };
    }

    private static String axisToFieldSuffix(String axis) {
        return switch (axis.toLowerCase()) {
            case "xrot" -> "X";
            case "yrot" -> "Y";
            case "zrot" -> "Z";
            case "xpos" -> "POS_X";
            case "ypos" -> "POS_Y";
            case "zpos" -> "POS_Z";
            case "hclampmin" -> "CLAMP_MIN";
            case "hclampmax" -> "CLAMP_MAX";
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
    
    }
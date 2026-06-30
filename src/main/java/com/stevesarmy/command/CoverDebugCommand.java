package com.stevesarmy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.stevesarmy.combat.cover.CoverFinder;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.combat.cover.CoverDebugManager;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.combat.cover.CoverReservationManager;
import com.stevesarmy.combat.cover.ThreatDirectionCalculator;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.TargetEntity;
import com.stevesarmy.entity.ai.SeekCoverGoal;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.ArrayList;

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
            "  /stevesarmy_cover soldiers - Toggle soldier cover visualization (state, cover, threats)"
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
            CoverBehaviorManager manager = soldier.getCoverBehaviorManager();
            if (manager != null) {
                ThreatDirectionCalculator.ThreatAnalysis analysis = 
                    manager.analyzeThreats(soldier, threats);
                
                Vec3 threatDir = analysis.primaryDirection;
                String dirStr = String.format("%.2f, %.2f, %.2f", 
                    threatDir.x, threatDir.y, threatDir.z);
                
                source.sendSuccess(() -> Component.literal(
                    "Soldier: " + soldier.getUUID().toString().substring(0, 8) + "..." +
                    " | Threat dir: " + dirStr +
                    " | Flanked: " + analysis.isFlanked +
                    " | Count: " + analysis.threatCount +
                    " | Closest: " + String.format("%.1f", analysis.closestThreatDistance) + "m"
                ), false);
            }
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
        boolean enabled = !SeekCoverGoal.isDebugLoggingEnabled();
        SeekCoverGoal.setDebugLogging(enabled);
        context.getSource().sendSuccess(() -> Component.literal(
            "Cover behavior logging: " + (enabled ? "ON" : "OFF") +
            "\nUse /stevesarmy_cover log on|off to set explicitly"
        ), true);
        return 1;
    }
    
    private static int enableLogging(CommandContext<CommandSourceStack> context) {
        SeekCoverGoal.setDebugLogging(true);
        context.getSource().sendSuccess(() -> Component.literal(
            "Cover behavior logging: ON\n" +
            "Logs will show cover selection, switching, and hysteresis decisions"
        ), true);
        return 1;
    }
    
    private static int disableLogging(CommandContext<CommandSourceStack> context) {
        SeekCoverGoal.setDebugLogging(false);
        context.getSource().sendSuccess(() -> Component.literal("Cover behavior logging: OFF"), true);
        return 1;
    }
    
    private static int toggleSoldierVisualization(CommandContext<CommandSourceStack> context) {
        boolean enabled = !CoverDebugManager.isShowSoldierCover();
        CoverDebugManager.setShowSoldierCover(enabled);
        CoverDebugManager.setVisualizationEnabled(true);
        
        context.getSource().sendSuccess(() -> Component.literal(
            "Soldier cover visualization: " + (enabled ? "ON" : "OFF") + "\n" +
            "Shows: Cover state (color X), current cover (line), threat direction (red arrow), target (orange line)\n" +
            "Colors: GREEN=IN_COVER, YELLOW=SEEKING, RED=SUPPRESSED, GRAY=NO_COVER"
        ), true);
        return 1;
    }
}
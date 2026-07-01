package com.stevesarmy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.stevesarmy.client.CombatDebugRenderer;
import com.stevesarmy.combat.cover.CoverDebugManager;
import com.stevesarmy.entity.ai.CoverTacticalGoal;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class StevesArmyCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("stevesarmy")
            .requires(source -> source.hasPermission(2))
            .executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "Steve's Army Commands:\n" +
                    "  /stevesarmy debug - Enable all debug rendering and logging\n" +
                    "  /stevesarmy_debug mode - Combat detection debug overlay\n" +
                    "  /stevesarmy_cover - Cover system debug commands"
                ), false);
                return 1;
            })
            .then(Commands.literal("debug")
                .executes(StevesArmyCommand::enableAllDebug)
            )
        );
    }
    
    private static int enableAllDebug(CommandContext<CommandSourceStack> context) {
        CombatDebugRenderer.setDebugMode(CombatDebugRenderer.DEBUG_MODE_MINIMAL);
        CoverDebugManager.setShowSoldierCover(true);
        CoverDebugManager.setVisualizationEnabled(true);
        CoverTacticalGoal.setDebugLogging(true);
        
        context.getSource().sendSuccess(() -> Component.literal(
            "=== Steve's Army Debug: ALL ON ===\n" +
            "  Combat debug overlay: MINIMAL (red lines in world)\n" +
            "  Soldier cover visualization: ON (colored lines)\n" +
            "  Cover behavior logging: ON (console logs)\n" +
            "Use /stevesarmy_debug mode verbose for arc visualization\n" +
            "Use /stevesarmy_cover log off to disable console logs"
        ), true);
        return 1;
    }
}
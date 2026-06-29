package com.stevesarmy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.stevesarmy.client.CombatDebugRenderer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class CombatDebugCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("stevesarmy_debug")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("mode")
                .executes(CombatDebugCommand::cycleMode)
                .then(Commands.literal("off")
                    .executes(ctx -> setMode(ctx, CombatDebugRenderer.DEBUG_MODE_OFF)))
                .then(Commands.literal("minimal")
                    .executes(ctx -> setMode(ctx, CombatDebugRenderer.DEBUG_MODE_MINIMAL)))
                .then(Commands.literal("verbose")
                    .executes(ctx -> setMode(ctx, CombatDebugRenderer.DEBUG_MODE_VERBOSE)))
                .then(Commands.literal("arcs")
                    .executes(ctx -> setMode(ctx, CombatDebugRenderer.DEBUG_MODE_ARCS)))
            )
            .then(Commands.literal("untargeted")
                .executes(CombatDebugCommand::showUntargeted)
                .then(Commands.argument("count", IntegerArgumentType.integer(0, 10))
                    .executes(CombatDebugCommand::setUntargeted))
            )
            .then(Commands.literal("status")
                .executes(CombatDebugCommand::showStatus)
            )
        );
    }
    
    private static int cycleMode(CommandContext<CommandSourceStack> context) {
        CombatDebugRenderer.cycleDebugMode();
        String modeName = CombatDebugRenderer.getDebugModeName();
        context.getSource().sendSuccess(() -> Component.literal("Combat debug mode: " + modeName), true);
        return 1;
    }
    
    private static int setMode(CommandContext<CommandSourceStack> context, int mode) {
        CombatDebugRenderer.setDebugMode(mode);
        String modeName = CombatDebugRenderer.getDebugModeName();
        context.getSource().sendSuccess(() -> Component.literal("Combat debug mode set to: " + modeName), true);
        return 1;
    }
    
    private static int showUntargeted(CommandContext<CommandSourceStack> context) {
        int count = CombatDebugRenderer.getMaxUntargeted();
        context.getSource().sendSuccess(() -> Component.literal("Max untargeted to render: " + count), false);
        return 1;
    }
    
    private static int setUntargeted(CommandContext<CommandSourceStack> context) {
        int count = IntegerArgumentType.getInteger(context, "count");
        CombatDebugRenderer.setMaxUntargeted(count);
        context.getSource().sendSuccess(() -> Component.literal("Max untargeted to render set to: " + count), true);
        return 1;
    }
    
    private static int showStatus(CommandContext<CommandSourceStack> context) {
        String modeName = CombatDebugRenderer.getDebugModeName();
        int untargeted = CombatDebugRenderer.getMaxUntargeted();
        context.getSource().sendSuccess(() -> Component.literal("Debug mode: " + modeName + ", Max untargeted: " + untargeted), false);
        return 1;
    }
}
package com.stevesarmy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.stevesarmy.sim.SimManager;
import com.stevesarmy.sim.SimRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /stevesarmy_sim} — scenario harness control. Runs the real state
 * machine against programmatic terrain with scripted pressure; reports land
 * in {@code run/sim_reports/}. Also driven headless by {@code -Dsim.autorun=true}.
 */
public final class SimCommand {

    private SimCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("stevesarmy_sim")
            .requires(source -> source.hasPermission(2))
            .executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "usage: /stevesarmy_sim run <all|scenario> — reports in run/sim_reports/"
                        + " (scenarios: " + String.join(", ", SimRegistry.names()) + ")"), false);
                return 1;
            })
            .then(Commands.literal("list")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                        "scenarios: " + String.join(", ", SimRegistry.names())), false);
                    return 1;
                }))
            .then(Commands.literal("run")
                .then(Commands.argument("scenario", StringArgumentType.word())
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "scenario");
                        CommandSourceStack source = ctx.getSource();
                        if (SimManager.running()) {
                            source.sendFailure(Component.literal("a simulation is already running"));
                            return 0;
                        }
                        java.util.List<com.stevesarmy.sim.scenario.SimScenario> toRun;
                        if (name.equalsIgnoreCase("all")) {
                            toRun = SimRegistry.all();
                        } else {
                            com.stevesarmy.sim.scenario.SimScenario single = SimRegistry.byName(name);
                            if (single == null) {
                                source.sendFailure(Component.literal(
                                    "unknown scenario '" + name + "' — try /stevesarmy_sim list"));
                                return 0;
                            }
                            toRun = java.util.List.of(single);
                        }
                        boolean started = SimManager.start(source.getLevel(), toRun, false);
                        if (!started) {
                            source.sendFailure(Component.literal(
                                "simulation could not start (check difficulty is not peaceful)"));
                            return 0;
                        }
                        final int count = toRun.size();
                        source.sendSuccess(() -> Component.literal(
                            "simulation started (" + count + " scenario" + (count == 1 ? "" : "s")
                                + "); reports will appear in run/sim_reports/"), false);
                        return 1;
                    }))));
    }
}

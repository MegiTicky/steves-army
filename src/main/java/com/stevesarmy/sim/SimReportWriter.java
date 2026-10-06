package com.stevesarmy.sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Scenario report output: a human-readable timeline + violation list (.txt)
 * and a machine-parseable mirror (.json) under {@code <world>/sim_reports/}.
 * The final log line "SIM RESULT: ..." is the grep anchor for CI-style runs.
 */
public final class SimReportWriter {
    private SimReportWriter() {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public static Path reportDir() {
        return Path.of("sim_reports");
    }

    public static void write(SimRunResult result, int simTick) {
        String stamp = LocalDateTime.now().format(STAMP);
        String base = result.scenario.name + "_" + stamp;
        try {
            Path dir = reportDir();
            Files.createDirectories(dir);
            String txt = renderText(result, simTick);
            Files.writeString(dir.resolve(base + ".txt"), txt, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(base + ".json"), renderJson(result, simTick), StandardCharsets.UTF_8);
            com.stevesarmy.StevesArmyMod.LOGGER.info("[Sim] report: {}", dir.resolve(base + ".txt").toAbsolutePath());
        } catch (IOException e) {
            com.stevesarmy.StevesArmyMod.LOGGER.error("[Sim] failed to write report: {}", e.toString());
        }
    }

    private static String renderText(SimRunResult result, int simTick) {
        StringBuilder sb = new StringBuilder();
        sb.append("SIM SCENARIO REPORT\n");
        sb.append("===================\n");
        sb.append("scenario : ").append(result.scenario.name).append('\n');
        sb.append("desc     : ").append(result.scenario.description).append('\n');
        sb.append("duration : ").append(result.durationTicks).append(" ticks (").append(simTick).append(" simulated)\n");
        sb.append("guns     : ").append(result.gunsLoaded ? "LOADED (armed scenario)" : "ABSENT — firing assertions skipped\n");
        sb.append("outcome  : ").append(result.failed() ? "FAIL" : "PASS").append('\n');
        sb.append("samples  : ").append(result.timeline.sampleCount()).append('\n');
        sb.append('\n');

        if (!result.assertionFailures.isEmpty()) {
            sb.append("ASSERTION FAILURES\n");
            sb.append("------------------\n");
            for (String failure : result.assertionFailures) sb.append("- ").append(failure).append('\n');
            sb.append('\n');
        }

        sb.append("DETECTOR VIOLATIONS\n");
        sb.append("-------------------\n");
        if (result.violations.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (SimViolation v : result.violations) {
                sb.append(v.toString()).append('\n');
                for (var e : v.evidence.entrySet()) {
                    sb.append("    ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
                }
            }
        }
        sb.append('\n');

        sb.append("PER-SOLDIER STATE TIMELINE (segments; '!' = violation tick)\n");
        sb.append("------------------------------------------------------------\n");
        for (String label : result.timeline.soldierLabels()) {
            sb.append(label).append(":\n");
            appendSegmentedTimeline(sb, result, label);
        }
        return sb.toString();
    }

    /** Collapses the sampled series into readable state segments. */
    private static void appendSegmentedTimeline(StringBuilder sb, SimRunResult result, String label) {
        List<SimSample> series = result.timeline.series(label);
        if (series.isEmpty()) return;
        int segStart = series.get(0).tick;
        String segState = series.get(0).state.coverState + "/" + series.get(0).state.peekState;
        float segSup = series.get(0).state.suppressionLevel;
        String segBlocked = series.get(0).state.followBlockedBy;
        List<Integer> violationTicks = result.violations.stream()
            .filter(v -> label.equals(v.soldierLabel))
            .map(v -> v.tick).toList();

        for (SimSample s : series) {
            String state = s.state.coverState + "/" + s.state.peekState;
            if (!state.equals(segState) || !s.state.followBlockedBy.equals(segBlocked)) {
                sb.append("  t").append(segStart).append('-').append(s.tick).append(' ').append(segState);
                if (!segBlocked.isEmpty()) sb.append(" [blocked:").append(segBlocked).append(']');
                sb.append(" sup=").append(String.format("%.2f", segSup)).append('\n');
                segStart = s.tick;
                segState = state;
                segBlocked = s.state.followBlockedBy;
            }
            segSup = s.state.suppressionLevel;
        }
        sb.append("  t").append(segStart).append('-').append(series.get(series.size() - 1).tick)
            .append(' ').append(segState);
        if (!segBlocked.isEmpty()) sb.append(" [blocked:").append(segBlocked).append(']');
        sb.append(" sup=").append(String.format("%.2f", segSup)).append('\n');

        if (!violationTicks.isEmpty()) {
            sb.append("  violations at ticks: ").append(violationTicks).append('\n');
        }
        sb.append('\n');
    }

    private static String renderJson(SimRunResult result, int simTick) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"scenario\": \"").append(escape(result.scenario.name)).append("\",\n");
        sb.append("  \"durationTicks\": ").append(result.durationTicks).append(",\n");
        sb.append("  \"simulatedTicks\": ").append(simTick).append(",\n");
        sb.append("  \"gunsLoaded\": ").append(result.gunsLoaded).append(",\n");
        sb.append("  \"passed\": ").append(!result.failed()).append(",\n");
        sb.append("  \"enemyHealthStart\": ").append(result.enemyHealthStart).append(",\n");
        sb.append("  \"enemyHealthEnd\": ").append(result.enemyHealthEnd).append(",\n");
        sb.append("  \"assertionFailures\": [");
        for (int i = 0; i < result.assertionFailures.size(); i++) {
            sb.append(i > 0 ? ", " : "").append('"').append(escape(result.assertionFailures.get(i))).append('"');
        }
        sb.append("],\n");
        sb.append("  \"violations\": [\n");
        List<SimViolation> vs = result.violations;
        for (int i = 0; i < vs.size(); i++) {
            SimViolation v = vs.get(i);
            sb.append("    { \"severity\": \"").append(v.severity).append("\", \"detector\": \"")
                .append(escape(v.detector)).append("\", \"tick\": ").append(v.tick)
                .append(", \"soldier\": \"").append(escape(v.soldierLabel))
                .append("\", \"message\": \"").append(escape(v.message)).append("\", \"evidence\": {");
            int j = 0;
            for (var e : v.evidence.entrySet()) {
                sb.append(j++ > 0 ? ", " : "").append('"').append(escape(e.getKey())).append("\": \"")
                    .append(escape(e.getValue())).append('"');
            }
            sb.append(" } }").append(i < vs.size() - 1 ? "," : "").append('\n');
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}

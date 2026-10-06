package com.stevesarmy.sim;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One detector finding. Carries the tick, the soldier, and the full counter
 * chain as evidence so the report can answer "why" without re-running.
 */
public final class SimViolation {
    public enum Severity { FAIL, WARN }

    public final Severity severity;
    public final String detector;
    public final int tick;
    public final String soldierLabel;
    public final String message;
    public final Map<String, String> evidence = new LinkedHashMap<>();

    public SimViolation(Severity severity, String detector, int tick, String soldierLabel, String message) {
        this.severity = severity;
        this.detector = detector;
        this.tick = tick;
        this.soldierLabel = soldierLabel;
        this.message = message;
    }

    public SimViolation with(String key, Object value) {
        evidence.put(key, String.valueOf(value));
        return this;
    }

    @Override
    public String toString() {
        return "[" + severity + "] " + detector + " @" + tick + "t " + soldierLabel + ": " + message;
    }
}

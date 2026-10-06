package com.stevesarmy.sim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-soldier sample series collected during one scenario run. Detectors are
 * pure functions over this timeline so they can be unit-reasoned about without
 * any world state.
 */
public final class SimTimeline {
    private final Map<String, List<SimSample>> bySoldier = new LinkedHashMap<>();

    public void add(SimSample sample) {
        bySoldier.computeIfAbsent(sample.label, k -> new ArrayList<>()).add(sample);
    }

    public Collection<String> soldierLabels() {
        return bySoldier.keySet();
    }

    public List<SimSample> series(String label) {
        return bySoldier.getOrDefault(label, List.of());
    }

    public List<SimSample> first(String label) {
        List<SimSample> s = series(label);
        return s.isEmpty() ? List.of() : s;
    }

    public int sampleCount() {
        return bySoldier.values().stream().mapToInt(List::size).sum();
    }

    /** Total positional path length (block units) walked by the soldier between two ticks. */
    public double travel(String label, int fromTick, int toTick) {
        List<SimSample> series = series(label);
        double total = 0.0;
        SimSample prev = null;
        for (SimSample sample : series) {
            if (sample.tick < fromTick) continue;
            if (sample.tick > toTick) break;
            if (prev != null && prev.state.position != null && sample.state.position != null) {
                total += prev.state.position.distanceTo(sample.state.position);
            }
            prev = sample;
        }
        return total;
    }
}

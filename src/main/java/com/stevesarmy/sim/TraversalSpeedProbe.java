package com.stevesarmy.sim;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.CoverPositionController;
import com.stevesarmy.entity.ai.SoldierGroundNavigation;
import com.stevesarmy.entity.ai.SoldierSpeedController;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.sim.scenario.SimContext;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Per-tick physical traversal evidence for scenarios that exercise ordinary movement. */
public final class TraversalSpeedProbe {
    /** Warning band above the expected roughly 0.4-0.6 blocks/tick normal travel equilibrium. */
    public static final double WARNING_BLOCKS_PER_TICK = 0.75;
    public static final double SEVERE_SPIKE_BLOCKS_PER_TICK = 1.00;
    /** Sustained candidate threshold; all actor samples, including collisions and shoves, count. */
    public static final double FAST_TRAVERSAL_BLOCKS_PER_TICK = 0.60;
    public static final int SUSTAINED_TICKS = 3;
    private static final int TOP_SPIKES = 8;

    private record Previous(double x, double y, double z) {}
    private record Sample(int tick, String label, double displacement, double spatialDisplacement,
                          double verticalDisplacement,
                          double y, double vx, double vz,
                          double requestedSpeed, double appliedSpeed, double baseAttribute,
                          double effectiveAttribute, double targetIntent, double appliedControllerIntent,
                          String limitingReason, boolean onGround, boolean horizontalCollision,
                          boolean minorCollision, boolean attackOrder, String coverState, String peekState,
                          String posture, String movementOwner) {
        String evidence() {
            return String.format("t%d %s dxz=%.3f dxyz=%.3f dy=%.3f y=%.2f v=(%.3f,%.3f) navRequest=%.3f "
                    + "mobIntent=%.3f speedTarget=%.3f speedApplied=%.3f limit=%s attr=%.3f/%.3f "
                    + "ground=%s collision=%s/%s attack=%s cover=%s peek=%s pose=%s owner=%s",
                tick, label, displacement, spatialDisplacement, verticalDisplacement, y, vx, vz,
                requestedSpeed, appliedSpeed, targetIntent, appliedControllerIntent, limitingReason,
                effectiveAttribute,
                baseAttribute, onGround, horizontalCollision, minorCollision, attackOrder,
                coverState, peekState, posture, movementOwner);
        }
    }

    private final Map<SoldierEntity, Previous> previous = new IdentityHashMap<>();
    private final List<Sample> samples = new ArrayList<>();
    private final int durationTicks;
    private int friendlyCount;
    private int sampledTicks;

    private TraversalSpeedProbe(int durationTicks) { this.durationTicks = durationTicks; }

    /** Install the probe into a scenario builder. The first tick only establishes spawn positions. */
    public static TraversalSpeedProbe attach(SimScenario.Builder builder, int durationTicks) {
        TraversalSpeedProbe probe = new TraversalSpeedProbe(durationTicks);
        for (int tick = 1; tick <= durationTicks; tick++) {
            final int sampleTick = tick;
            builder.action(tick, "traversal speed sample", ctx -> probe.sample(ctx, sampleTick));
        }
        builder.assertThat("traversal probe sampled actors; investigate any >=1.00 block/tick horizontal spike "
                + "or >=0.60 blocks/tick for 3 ticks (jump/height/collision evidence is retained)",
            view -> probe.sampledTicks > 0 && probe.friendlyCount > 0 && !probe.samples.isEmpty()
                && !probe.hasSevereSpike()
                && !probe.hasSustainedFastTraversal());
        return probe;
    }

    private void sample(SimContext ctx, int tick) {
        if (tick == 1) {
            friendlyCount = ctx.friendlies.size();
            previous.clear();
            samples.clear();
            sampledTicks = 0;
        }
        sampledTicks++;
        for (SoldierEntity soldier : ctx.friendlies) {
            if (soldier == null || soldier.isRemoved()) continue;
            Previous prior = previous.put(soldier, new Previous(soldier.getX(), soldier.getY(), soldier.getZ()));
            if (prior == null) continue; // Spawn placement is not traversal.
            double displacement = Math.hypot(soldier.getX() - prior.x(), soldier.getZ() - prior.z());
            double verticalDisplacement = soldier.getY() - prior.y();
            double spatialDisplacement = Math.sqrt(displacement * displacement
                + verticalDisplacement * verticalDisplacement);
            Vec3 velocity = soldier.getDeltaMovement();
            double base = soldier.getAttribute(Attributes.MOVEMENT_SPEED) == null ? 0
                : soldier.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue();
            var state = ctx.snapshotOf(soldier);
            double request = soldier.getNavigation() instanceof SoldierGroundNavigation nav
                ? nav.getNavSpeedModifier() : 0;
            SoldierSpeedController.Telemetry speedTelemetry = soldier.getMoveControl() instanceof CoverPositionController ctrl
                ? ctrl.getSpeedController().snapshot() : null;
            samples.add(new Sample(tick, label(ctx, soldier), displacement, spatialDisplacement,
                verticalDisplacement,
                soldier.getY(), velocity.x, velocity.z,
                request, soldier.getSpeed(), base, soldier.getAttributeValue(Attributes.MOVEMENT_SPEED),
                speedTelemetry == null ? Double.NaN : speedTelemetry.targetSpeed(),
                speedTelemetry == null ? Double.NaN : speedTelemetry.appliedSpeed(),
                speedTelemetry == null ? "unavailable" : speedTelemetry.limitingReason(),
                soldier.onGround(), soldier.horizontalCollision, soldier.minorHorizontalCollision,
                soldier.hasValidAttackTarget(), state == null ? "none" : state.coverState,
                state == null ? "none" : state.peekState, soldier.getPose().name(),
                state == null ? "unknown" : state.movementOwner));
        }
        if (tick >= durationTicks) {
            String summary = report();
            StevesArmyMod.LOGGER.info("[SimTraversalSpeed] {}", summary);
        }
    }

    private static String label(SimContext ctx, SoldierEntity soldier) {
        return ctx.byLabel.entrySet().stream().filter(e -> e.getValue() == soldier)
            .map(Map.Entry::getKey).findFirst().orElse("S" + soldier.getId());
    }

    public boolean hasSustainedFastTraversal() {
        Map<String, List<Sample>> byActor = samples.stream().collect(Collectors.groupingBy(
            Sample::label, java.util.LinkedHashMap::new, Collectors.toList()));
        for (List<Sample> actorSamples : byActor.values()) {
            actorSamples.sort(Comparator.comparingInt(Sample::tick));
            int run = 0;
            for (Sample sample : actorSamples) {
                run = sample.displacement() >= FAST_TRAVERSAL_BLOCKS_PER_TICK ? run + 1 : 0;
                if (run >= SUSTAINED_TICKS) return true;
            }
        }
        return false;
    }

    public boolean hasSevereSpike() {
        return samples.stream().anyMatch(s -> s.displacement() >= SEVERE_SPIKE_BLOCKS_PER_TICK);
    }

    /** Compact distribution plus the largest physical displacement samples with full state context. */
    public String report() {
        if (samples.isEmpty()) return "samples=0 actors=" + friendlyCount + " (no post-spawn movement samples)";
        List<Double> values = samples.stream().map(Sample::displacement).sorted().toList();
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        List<String> spikes = samples.stream().filter(s -> s.displacement() >= WARNING_BLOCKS_PER_TICK
                || s.spatialDisplacement() >= 1.0 || s.appliedSpeed() > 0.001
                || s.requestedSpeed() > 0.001)
            .sorted(Comparator.comparingDouble(Sample::displacement).reversed()).limit(TOP_SPIKES)
            .map(Sample::evidence).toList();
        String top = spikes.isEmpty() ? "none" : String.join(" | ", spikes);
        return String.format("actors=%d samples=%d horizontal blocks/tick mean=%.3f p50=%.3f p90=%.3f p99=%.3f max=%.3f "
                + "warning(horizontal>=%.2f)=%d candidate(horizontal>=1.00)=%d spatial>=1.00=%d "
                + "sustained(horizontal>=%.2f x %dt)=%s top=%s",
            friendlyCount, values.size(), mean, percentile(values, .50), percentile(values, .90),
            percentile(values, .99), values.get(values.size() - 1),
            WARNING_BLOCKS_PER_TICK,
            samples.stream().filter(s -> s.displacement() >= WARNING_BLOCKS_PER_TICK).count(),
            samples.stream().filter(s -> s.displacement() >= 1.0).count(),
            samples.stream().filter(s -> s.spatialDisplacement() >= 1.0).count(),
            FAST_TRAVERSAL_BLOCKS_PER_TICK, SUSTAINED_TICKS, hasSustainedFastTraversal(), top);
    }

    private static double percentile(List<Double> sorted, double p) {
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }
}

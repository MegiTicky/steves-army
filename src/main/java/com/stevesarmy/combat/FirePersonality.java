package com.stevesarmy.combat;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Per-engagement firing personality, rolled when a soldier acquires a target.
 * Two correlated traits — aggression and steadiness — drive every pacing bias,
 * so a given soldier paces bursts consistently within an engagement while
 * squadmates differ, instead of every rifleman bursting with identical timing.
 *
 * Ephemeral by design: re-rolled on target switch, never persisted. Traits are
 * state (the soldier's current nerve), not identity — the same soldier can be
 * bold against one target and cautious against the next.
 */
public final class FirePersonality {
    private final float aggression;
    private final float steadiness;

    private FirePersonality(float aggression, float steadiness) {
        this.aggression = aggression;
        this.steadiness = steadiness;
    }

    public static FirePersonality roll(RandomSource random) {
        return new FirePersonality(random.nextFloat(), random.nextFloat());
    }

    public float aggression() {
        return aggression;
    }

    public float steadiness() {
        return steadiness;
    }

    /** Burst length multiplier: eager soldiers over-hold the trigger. */
    public float burstBias() {
        return Mth.lerp(aggression, 0.8f, 1.3f);
    }

    /** Gap-after-burst multiplier: eager soldiers re-engage sooner. */
    public float gapBias() {
        return Mth.lerp(1.0f - aggression, 0.8f, 1.25f);
    }

    /** Aim-quality threshold multiplier: jittery soldiers accept sloppier solutions. */
    public float thresholdBias() {
        return Mth.lerp(1.0f - steadiness, 0.9f, 1.1f);
    }

    /** In-burst cadence multiplier: steady soldiers keep a metronome rhythm. */
    public float cadenceBias() {
        return Mth.lerp(1.0f - steadiness, 0.9f, 1.15f);
    }

    /** Aim build-rate multiplier: steady soldiers settle onto a target faster. */
    public float buildBias() {
        return Mth.lerp(steadiness, 0.85f, 1.2f);
    }
}

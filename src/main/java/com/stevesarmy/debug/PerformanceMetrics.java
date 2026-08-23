package com.stevesarmy.debug;

import com.stevesarmy.StevesArmyConfig;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Opt-in counters for diagnosing server-thread performance in live tests. */
public final class PerformanceMetrics {
    private static volatile boolean enabled;

    private static final LongAdder visibilityCacheHits = new LongAdder();
    private static final LongAdder visibilityCacheMisses = new LongAdder();
    private static final LongAdder exposureCacheHits = new LongAdder();
    private static final LongAdder exposureCacheMisses = new LongAdder();
    private static final LongAdder exposureCalculations = new LongAdder();
    private static final LongAdder detectionTicks = new LongAdder();
    private static final LongAdder detectionCandidates = new LongAdder();
    private static final LongAdder targetRefreshes = new LongAdder();
    private static final LongAdder targetCandidates = new LongAdder();
    private static final LongAdder coverSearches = new LongAdder();
    private static final LongAdder coverCandidatesDiscovered = new LongAdder();
    private static final LongAdder coverCandidatesEvaluated = new LongAdder();
    private static final LongAdder coverCandidatesScored = new LongAdder();
    private static final LongAdder coverSearchNanos = new LongAdder();
    private static final LongAdder visibilityRays = new LongAdder();
    private static final LongAdder visibilityRayCacheHits = new LongAdder();
    private static final LongAdder visibilityRayCacheMisses = new LongAdder();
    private static final LongAdder visibilityRayRequests = new LongAdder();
    private static final LongAdder aimPointCacheHits = new LongAdder();
    private static final LongAdder aimPointCacheMisses = new LongAdder();
    private static final LongAdder targetQueryCacheHits = new LongAdder();
    private static final LongAdder targetQueryCacheMisses = new LongAdder();
    private static final LongAdder targetQueryCacheEntities = new LongAdder();
    private static final LongAdder threatReportAttempts = new LongAdder();
    private static final LongAdder threatReportPublished = new LongAdder();
    private static final LongAdder threatReportGeometryCalculations = new LongAdder();
    private static final LongAdder threatReportDeduplicated = new LongAdder();
    private static final LongAdder coverTicks = new LongAdder();
    private static final LongAdder coverSeekingTicks = new LongAdder();
    private static final LongAdder coverRepositioningTicks = new LongAdder();
    private static final LongAdder coverInCoverTicks = new LongAdder();
    private static final LongAdder coverSuppressedTicks = new LongAdder();
    private static final LongAdder coverPathRequests = new LongAdder();
    private static final LongAdder coverPathRetries = new LongAdder();
    private static final LongAdder coverPathFailures = new LongAdder();
    private static final LongAdder coverSearchCooldownSkips = new LongAdder();
    private static final LongAdder coverMaintenanceRuns = new LongAdder();
    private static final LongAdder coverMaintenanceSkips = new LongAdder();
    private static final LongAdder coverValidationRuns = new LongAdder();
    private static final LongAdder coverValidationSkips = new LongAdder();
    private static final LongAdder coverActiveMovementTicks = new LongAdder();
    private static final LongAdder coverFullSearchAttempts = new LongAdder();
    private static final LongAdder suppressedCoverDeferredSkips = new LongAdder();
    private static final LongAdder squadThreatSnapshotRequests = new LongAdder();
    private static final LongAdder threatSortSelectionPasses = new LongAdder();
    private static final LongAdder squadMemberFilterPasses = new LongAdder();
    private static final LongAdder temporaryCollectionsAvoided = new LongAdder();
    private static final Map<String, LongAdder> coverInvalidationReasons = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> passiveMaintenanceRunsByState = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> passiveMaintenanceSkipsByState = new ConcurrentHashMap<>();
    private static final LongAdder coverStateNanos = new LongAdder();
    private static final LongAdder coverSeekingNanos = new LongAdder();
    private static final LongAdder coverRepositioningNanos = new LongAdder();
    private static final LongAdder coverInCoverNanos = new LongAdder();
    private static final LongAdder coverSuppressedNanos = new LongAdder();
    private static final LongAdder suppressionPreemptionNanos = new LongAdder();

    private PerformanceMetrics() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static void reset() {
        visibilityCacheHits.reset();
        visibilityCacheMisses.reset();
        exposureCacheHits.reset();
        exposureCacheMisses.reset();
        exposureCalculations.reset();
        detectionTicks.reset();
        detectionCandidates.reset();
        targetRefreshes.reset();
        targetCandidates.reset();
        coverSearches.reset();
        coverCandidatesDiscovered.reset();
        coverCandidatesEvaluated.reset();
        coverCandidatesScored.reset();
        coverSearchNanos.reset();
        visibilityRays.reset();
        visibilityRayCacheHits.reset();
        visibilityRayCacheMisses.reset();
        visibilityRayRequests.reset();
        aimPointCacheHits.reset();
        aimPointCacheMisses.reset();
        targetQueryCacheHits.reset();
        targetQueryCacheMisses.reset();
        targetQueryCacheEntities.reset();
        threatReportAttempts.reset();
        threatReportPublished.reset();
        threatReportGeometryCalculations.reset();
        threatReportDeduplicated.reset();
        coverTicks.reset();
        coverSeekingTicks.reset();
        coverRepositioningTicks.reset();
        coverInCoverTicks.reset();
        coverSuppressedTicks.reset();
        coverPathRequests.reset();
        coverPathRetries.reset();
        coverPathFailures.reset();
        coverSearchCooldownSkips.reset();
        coverMaintenanceRuns.reset();
        coverMaintenanceSkips.reset();
        coverValidationRuns.reset();
        coverValidationSkips.reset();
        coverActiveMovementTicks.reset();
        coverFullSearchAttempts.reset();
        suppressedCoverDeferredSkips.reset();
        squadThreatSnapshotRequests.reset();
        threatSortSelectionPasses.reset();
        squadMemberFilterPasses.reset();
        temporaryCollectionsAvoided.reset();
        coverInvalidationReasons.clear();
        passiveMaintenanceRunsByState.clear();
        passiveMaintenanceSkipsByState.clear();
        coverStateNanos.reset();
        coverSeekingNanos.reset();
        coverRepositioningNanos.reset();
        coverInCoverNanos.reset();
        coverSuppressedNanos.reset();
        suppressionPreemptionNanos.reset();
    }

    public static void recordVisibilityCacheHit() {
        if (enabled) visibilityCacheHits.increment();
    }

    public static void recordVisibilityCacheMiss() {
        if (enabled) visibilityCacheMisses.increment();
    }

    public static void recordVisibilityRay() {
        if (!enabled) return;
        visibilityRayRequests.increment();
        visibilityRays.increment();
    }

    public static void recordVisibilityRayCacheHit() {
        if (!enabled) return;
        visibilityRayRequests.increment();
        visibilityRayCacheHits.increment();
    }

    public static void recordVisibilityRayCacheMiss() {
        if (!enabled) return;
        visibilityRayRequests.increment();
        visibilityRays.increment();
        visibilityRayCacheMisses.increment();
    }

    public static void recordExposureCacheHit() {
        if (enabled) exposureCacheHits.increment();
    }

    public static void recordExposureCacheMiss() {
        if (enabled) exposureCacheMisses.increment();
    }

    public static void recordExposureCalculation() {
        if (enabled) exposureCalculations.increment();
    }

    public static void recordAimPointCacheHit() {
        if (enabled) aimPointCacheHits.increment();
    }

    public static void recordAimPointCacheMiss() {
        if (enabled) aimPointCacheMisses.increment();
    }

    public static void recordTargetQueryCacheHit(int entityCount) {
        if (!enabled) return;
        targetQueryCacheHits.increment();
        targetQueryCacheEntities.add(entityCount);
    }

    public static void recordTargetQueryCacheMiss(int entityCount) {
        if (!enabled) return;
        targetQueryCacheMisses.increment();
        targetQueryCacheEntities.add(entityCount);
    }

    public static void recordThreatReportAttempt() {
        if (enabled) threatReportAttempts.increment();
    }

    public static void recordThreatReportPublished() {
        if (enabled) threatReportPublished.increment();
    }

    public static void recordThreatReportGeometryCalculation() {
        if (enabled) threatReportGeometryCalculations.increment();
    }

    public static void recordThreatReportDeduplicated() {
        if (enabled) threatReportDeduplicated.increment();
    }

    public static void recordCoverTick(String state) {
        if (!enabled) return;
        coverTicks.increment();
        switch (state) {
            case "SEEKING_COVER" -> coverSeekingTicks.increment();
            case "REPOSITIONING" -> coverRepositioningTicks.increment();
            case "IN_COVER" -> coverInCoverTicks.increment();
            case "SUPPRESSED_IN_COVER" -> coverSuppressedTicks.increment();
            default -> { }
        }
    }

    public static void recordCoverPathRequest() {
        if (enabled) coverPathRequests.increment();
    }

    public static void recordCoverPathRetry() {
        if (enabled) coverPathRetries.increment();
    }

    public static void recordCoverPathFailure() {
        if (enabled) coverPathFailures.increment();
    }

    public static void recordCoverSearchCooldownSkip() {
        if (enabled) coverSearchCooldownSkips.increment();
    }

    public static void recordCoverMaintenanceRun() {
        if (enabled) coverMaintenanceRuns.increment();
    }

    public static void recordCoverMaintenanceSkip() {
        if (enabled) coverMaintenanceSkips.increment();
    }

    public static void recordCoverValidationRun() {
        if (enabled) coverValidationRuns.increment();
    }

    public static void recordCoverValidationSkip() {
        if (enabled) coverValidationSkips.increment();
    }

    public static void recordCoverInvalidation(String reason) {
        if (!enabled) return;
        coverInvalidationReasons.computeIfAbsent(reason, ignored -> new LongAdder()).increment();
    }

    public static void recordCoverPassiveMaintenance(String state, boolean ran) {
        if (!enabled) return;
        Map<String, LongAdder> counters = ran ? passiveMaintenanceRunsByState : passiveMaintenanceSkipsByState;
        counters.computeIfAbsent(state, ignored -> new LongAdder()).increment();
        if (ran) {
            coverMaintenanceRuns.increment();
        } else {
            coverMaintenanceSkips.increment();
        }
    }

    public static void recordCoverActiveMovementTick() {
        if (enabled) coverActiveMovementTicks.increment();
    }

    public static void recordCoverFullSearchAttempt() {
        if (enabled) coverFullSearchAttempts.increment();
    }

    public static void recordSuppressedCoverDeferredSkip() {
        if (enabled) suppressedCoverDeferredSkips.increment();
    }

    public static void recordSquadThreatSnapshotRequest() {
        if (enabled) squadThreatSnapshotRequests.increment();
    }

    public static void recordThreatSortSelectionPass() {
        if (enabled) threatSortSelectionPasses.increment();
    }

    public static void recordSquadMemberFilterPass() {
        if (enabled) squadMemberFilterPasses.increment();
    }

    public static void recordTemporaryCollectionAvoided() {
        if (enabled) temporaryCollectionsAvoided.increment();
    }

    public static void recordCoverStateTime(String state, long nanos) {
        if (!enabled) return;
        coverStateNanos.add(nanos);
        switch (state) {
            case "SEEKING_COVER" -> coverSeekingNanos.add(nanos);
            case "REPOSITIONING" -> coverRepositioningNanos.add(nanos);
            case "IN_COVER" -> coverInCoverNanos.add(nanos);
            case "SUPPRESSED_IN_COVER" -> coverSuppressedNanos.add(nanos);
            default -> { }
        }
    }

    public static void recordSuppressionPreemptionTime(long nanos) {
        if (enabled) suppressionPreemptionNanos.add(nanos);
    }

    public static void recordDetectionTick(int candidates) {
        if (!enabled) return;
        detectionTicks.increment();
        detectionCandidates.add(candidates);
    }

    public static void recordTargetRefresh(int candidates) {
        if (!enabled) return;
        targetRefreshes.increment();
        targetCandidates.add(candidates);
    }

    public static void recordCoverSearch(long nanos, int candidates) {
        if (!enabled) return;
        coverSearches.increment();
        coverSearchNanos.add(nanos);
        coverCandidatesDiscovered.add(candidates);
    }

    public static void recordCoverCandidatesEvaluated(int candidates) {
        if (enabled) coverCandidatesEvaluated.add(candidates);
    }

    public static void recordCoverCandidatesScored(int candidates) {
        if (enabled) coverCandidatesScored.add(candidates);
    }

    public static String report() {
        long visibilityHits = visibilityCacheHits.sum();
        long visibilityMisses = visibilityCacheMisses.sum();
        long exposureHits = exposureCacheHits.sum();
        long exposureMisses = exposureCacheMisses.sum();
        long searches = coverSearches.sum();

        return "=== PERFORMANCE METRICS ===\n"
            + "  Enabled: " + enabled + "\n"
            + "  Optimization profile: " + StevesArmyConfig.getOptimizationProfile().displayName() + "\n"
            + "  Visibility cache: " + visibilityHits + " hits, " + visibilityMisses + " misses\n"
            + "  Visibility ray requests: " + visibilityRayRequests.sum()
            + " (" + visibilityRays.sum() + " actual traces, "
            + visibilityRayCacheHits.sum() + " cache hits, "
            + visibilityRayCacheMisses.sum() + " cache misses)\n"
            + "  Aim-point cache: " + aimPointCacheHits.sum() + " hits, "
            + aimPointCacheMisses.sum() + " misses\n"
            + "  Exposure cache: " + exposureHits + " hits, " + exposureMisses + " misses\n"
            + "  Exposure calculations: " + exposureCalculations.sum() + "\n"
            + "  Target query cache: " + targetQueryCacheHits.sum() + " hits, "
            + targetQueryCacheMisses.sum() + " misses, " + targetQueryCacheEntities.sum() + " entities\n"
            + "  Threat reports: " + threatReportAttempts.sum() + " attempts, "
            + threatReportPublished.sum() + " published, "
            + threatReportGeometryCalculations.sum() + " geometry calculations, "
            + threatReportDeduplicated.sum() + " deduplicated\n"
            + "  Squad perception: " + squadThreatSnapshotRequests.sum() + " threat snapshots, "
            + threatSortSelectionPasses.sum() + " sort/selection passes, "
            + squadMemberFilterPasses.sum() + " member filter passes, "
            + temporaryCollectionsAvoided.sum() + " temporary collections avoided\n"
            + "  Detection ticks: " + detectionTicks.sum() + "\n"
            + "  Detection candidates: " + detectionCandidates.sum() + "\n"
            + "  Target refreshes: " + targetRefreshes.sum() + "\n"
            + "  Target candidates: " + targetCandidates.sum() + "\n"
            + "  Cover searches: " + searches + "\n"
            + "  Cover candidates discovered: " + coverCandidatesDiscovered.sum() + "\n"
            + "  Cover candidates evaluated: " + coverCandidatesEvaluated.sum() + "\n"
            + "  Cover candidates scored: " + coverCandidatesScored.sum() + "\n"
            + "  Cover ticks: " + coverTicks.sum() + " (seeking=" + coverSeekingTicks.sum()
            + ", repositioning=" + coverRepositioningTicks.sum()
            + ", in-cover=" + coverInCoverTicks.sum()
            + ", suppressed=" + coverSuppressedTicks.sum() + ")\n"
            + "  Cover paths: " + coverPathRequests.sum() + " requests, "
            + coverPathRetries.sum() + " retries, " + coverPathFailures.sum() + " failures\n"
            + "  Cover search cooldown skips: " + coverSearchCooldownSkips.sum() + "\n"
            + "  Cover maintenance: " + coverMaintenanceRuns.sum() + " runs, "
            + coverMaintenanceSkips.sum() + " skips\n"
            + "  Cover validation: " + coverValidationRuns.sum() + " runs, "
            + coverValidationSkips.sum() + " skips\n"
            + "  Cover invalidations: " + formatCounters(coverInvalidationReasons) + "\n"
            + "  Passive maintenance runs by state: " + formatCounters(passiveMaintenanceRunsByState) + "\n"
            + "  Passive maintenance skips by state: " + formatCounters(passiveMaintenanceSkipsByState) + "\n"
            + "  Cover movement: " + coverActiveMovementTicks.sum() + " active ticks, "
            + coverFullSearchAttempts.sum() + " full-search attempts\n"
            + "  Suppressed-cover deferred skips: " + suppressedCoverDeferredSkips.sum() + "\n"
            + "  Cover state time: " + formatMillis(coverStateNanos.sum()) + " ms total"
            + " (seeking=" + formatMillis(coverSeekingNanos.sum())
            + ", repositioning=" + formatMillis(coverRepositioningNanos.sum())
            + ", in-cover=" + formatMillis(coverInCoverNanos.sum())
            + ", suppressed=" + formatMillis(coverSuppressedNanos.sum()) + ")\n"
            + "  Suppression preemption time: " + formatMillis(suppressionPreemptionNanos.sum()) + " ms\n"
            + "  Cover search time: " + formatMillis(coverSearchNanos.sum())
            + " ms total, " + formatMillis(searches == 0 ? 0 : coverSearchNanos.sum() / (double) searches)
            + " ms/search";
    }

    private static String formatMillis(double nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0);
    }

    private static String formatCounters(Map<String, LongAdder> counters) {
        if (counters.isEmpty()) return "none";
        StringBuilder result = new StringBuilder();
        counters.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (result.length() > 0) result.append(", ");
            result.append(entry.getKey()).append('=').append(entry.getValue().sum());
        });
        return result.toString();
    }
}

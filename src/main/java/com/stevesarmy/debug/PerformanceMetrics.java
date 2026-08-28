package com.stevesarmy.debug;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final LongAdder goToCoverSearches = new LongAdder();
    private static final LongAdder goToCoverSearchNanos = new LongAdder();
    private static final AtomicLong goToCoverSearchMaxNanos = new AtomicLong();
    private static final LongAdder goToCoverDiscoveryNanos = new LongAdder();
    private static final LongAdder goToCoverScoringNanos = new LongAdder();
    private static final LongAdder goToCoverPathNanos = new LongAdder();
    private static final LongAdder goToCoverCandidatesDiscovered = new LongAdder();
    private static final LongAdder goToCoverCandidatesEvaluated = new LongAdder();
    private static final LongAdder goToCoverCandidatesScored = new LongAdder();
    private static final LongAdder goToCoverPathValidations = new LongAdder();
    private static final LongAdder goToCoverPathValidationFailures = new LongAdder();
    private static final LongAdder goToCoverPathValidationBudgetExhausted = new LongAdder();
    private static final LongAdder visibilityRays = new LongAdder();
    private static final LongAdder visibilityRayCacheHits = new LongAdder();
    private static final LongAdder visibilityRayCacheMisses = new LongAdder();
    private static final LongAdder visibilityRayRequests = new LongAdder();
    private static final LongAdder aimPointCacheHits = new LongAdder();
    private static final LongAdder aimPointCacheMisses = new LongAdder();
    private static final LongAdder targetQueryCacheHits = new LongAdder();
    private static final LongAdder targetQueryCacheMisses = new LongAdder();
    private static final LongAdder targetQueryCacheEntities = new LongAdder();
    private static final LongAdder sameTickPotentialTargetCacheHits = new LongAdder();
    private static final LongAdder coverDiscoveryCacheHits = new LongAdder();
    private static final LongAdder coverPathReuseHits = new LongAdder();
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
    private static final LongAdder asyncCoverSnapshotRequests = new LongAdder();
    private static final LongAdder asyncCoverSnapshotNanos = new LongAdder();
    private static final LongAdder asyncCoverWorkerRequests = new LongAdder();
    private static final LongAdder asyncCoverWorkerCompleted = new LongAdder();
    private static final LongAdder asyncCoverWorkerFailures = new LongAdder();
    private static final LongAdder asyncCoverWorkerCancelled = new LongAdder();
    private static final LongAdder asyncCoverQueueSkips = new LongAdder();
    private static final LongAdder asyncCoverCoalescedRequests = new LongAdder();
    private static final LongAdder asyncCoverStaleResults = new LongAdder();
    private static final LongAdder asyncCoverValidationRejects = new LongAdder();
    private static final LongAdder asyncCoverWorkerNanos = new LongAdder();
    private static final LongAdder asyncCoverApplyNanos = new LongAdder();
    private static final Map<String, LongAdder> coverInvalidationReasons = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> passiveMaintenanceRunsByState = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> passiveMaintenanceSkipsByState = new ConcurrentHashMap<>();
    private static final LongAdder coverStateNanos = new LongAdder();
    private static final LongAdder coverSeekingNanos = new LongAdder();
    private static final LongAdder coverRepositioningNanos = new LongAdder();
    private static final LongAdder coverInCoverNanos = new LongAdder();
    private static final LongAdder coverSuppressedNanos = new LongAdder();
    private static final LongAdder suppressionPreemptionNanos = new LongAdder();
    private static final LongAdder riflemanCombatTicks = new LongAdder();
    private static final LongAdder machineGunnerCombatTicks = new LongAdder();
    private static final LongAdder riflemanDetectionTicks = new LongAdder();
    private static final LongAdder machineGunnerDetectionTicks = new LongAdder();
    private static final LongAdder riflemanCoverTicks = new LongAdder();
    private static final LongAdder machineGunnerCoverTicks = new LongAdder();
    private static final LongAdder riflemanCoverSearches = new LongAdder();
    private static final LongAdder machineGunnerCoverSearches = new LongAdder();
    private static final LongAdder riflemanPathRequests = new LongAdder();
    private static final LongAdder machineGunnerPathRequests = new LongAdder();
    private static final LongAdder riflemanPathRetries = new LongAdder();
    private static final LongAdder machineGunnerPathRetries = new LongAdder();
    private static final LongAdder riflemanPathFailures = new LongAdder();
    private static final LongAdder machineGunnerPathFailures = new LongAdder();
    private static final LongAdder machineGunnerAsyncRequests = new LongAdder();
    private static final LongAdder coverSearchRequestsQueued = new LongAdder();
    private static final LongAdder coverSearchRequestsExecuted = new LongAdder();
    private static final LongAdder coverSearchRequestsDeferred = new LongAdder();
    private static final LongAdder coverSearchRequestsCoalesced = new LongAdder();
    private static final LongAdder coverSearchRequestsCancelled = new LongAdder();
    private static final LongAdder coverSearchRequestsStale = new LongAdder();
    private static final LongAdder coverSearchRequestAgeSamples = new LongAdder();
    private static final LongAdder coverSearchRequestAgeTicks = new LongAdder();
    private static final LongAdder coverSearchRequestsAged = new LongAdder();
    private static final AtomicLong coverSearchRequestMaxAgeTicks = new AtomicLong();
    private static final LongAdder goToCoverSearchRequestsQueued = new LongAdder();
    private static final LongAdder goToCoverSearchRequestsExecuted = new LongAdder();
    private static final LongAdder goToCoverSearchRequestsDeferred = new LongAdder();
    private static final LongAdder goToCoverSearchRequestsCoalesced = new LongAdder();
    private static final LongAdder goToCoverSearchRequestsCancelled = new LongAdder();
    private static final LongAdder goToCoverSearchRequestsStale = new LongAdder();
    private static final LongAdder goToCoverSearchRequestsAged = new LongAdder();
    private static final LongAdder goToCoverSearchRequestAgeSamples = new LongAdder();
    private static final LongAdder goToCoverSearchRequestAgeTicks = new LongAdder();
    private static final AtomicLong goToCoverSearchRequestMaxAgeTicks = new AtomicLong();
    private static final LongAdder flankSearchAttempts = new LongAdder();
    private static final LongAdder flankSearchFailures = new LongAdder();
    private static final LongAdder flankSearchRetrySkips = new LongAdder();
    private static final LongAdder flankSearchFingerprintChanges = new LongAdder();
    private static final LongAdder emergencyCoverRequestsQueued = new LongAdder();
    private static final LongAdder emergencyCoverRequestsExecuted = new LongAdder();
    private static final LongAdder emergencyCoverRequestsDeferred = new LongAdder();
    private static final LongAdder emergencyCoverRequestsCoalesced = new LongAdder();
    private static final LongAdder emergencyCoverRequestsCancelled = new LongAdder();
    private static final LongAdder emergencyCoverRequestsStale = new LongAdder();
    private static final LongAdder emergencyCoverRequestAgeSamples = new LongAdder();
    private static final LongAdder emergencyCoverRequestAgeTicks = new LongAdder();

    public enum Stage {
        ENTITY_QUERY,
        LOS_BLOCK_TRAVERSAL,
        SMOKE_LOOKUP,
        EXPOSURE,
        COVER_SCORING,
        PATH_REQUEST
    }

    private static final LongAdder[] stageNanos = new LongAdder[Stage.values().length];
    private static final LongAdder[] stageCounts = new LongAdder[Stage.values().length];
    static {
        for (int i = 0; i < Stage.values().length; i++) {
            stageNanos[i] = new LongAdder();
            stageCounts[i] = new LongAdder();
        }
    }
    private static final LongAdder smokeQueries = new LongAdder();
    private static final LongAdder smokeEntitiesTested = new LongAdder();
    private static final LongAdder smokeHits = new LongAdder();
    private static final LongAdder sameTickVisibilityFrameHits = new LongAdder();
    private static final LongAdder sameTickVisibilityFrameMisses = new LongAdder();
    private static final LongAdder sameTickSmokeFrameHits = new LongAdder();
    private static final LongAdder sameTickSmokeFrameMisses = new LongAdder();
    private static final LongAdder phase4Captures = new LongAdder();
    private static final LongAdder phase4CaptureNanos = new LongAdder();
    private static final LongAdder phase4Evaluations = new LongAdder();
    private static final LongAdder phase4EvaluationNanos = new LongAdder();
    private static final LongAdder phase4Candidates = new LongAdder();
    private static final LongAdder phase4Top1Matches = new LongAdder();
    private static final LongAdder phase4Top1Mismatches = new LongAdder();
    private static final LongAdder phase4OrderingMatches = new LongAdder();
    private static final LongAdder phase4OrderingDivergences = new LongAdder();
    private static final LongAdder phase4Skips = new LongAdder();
    private static final LongAdder phase5Snapshots = new LongAdder();
    private static final LongAdder phase5SnapshotNanos = new LongAdder();
    private static final LongAdder phase5SnapshotFailures = new LongAdder();
    private static final LongAdder phase5RequestsQueued = new LongAdder();
    private static final LongAdder phase5RequestsCoalesced = new LongAdder();
    private static final LongAdder phase5QueueSkips = new LongAdder();
    private static final LongAdder phase5WorkerRequests = new LongAdder();
    private static final LongAdder phase5WorkerCompleted = new LongAdder();
    private static final LongAdder phase5WorkerFailures = new LongAdder();
    private static final LongAdder phase5Cancelled = new LongAdder();
    private static final LongAdder phase5ResultCoalesced = new LongAdder();
    private static final LongAdder phase5StaleResults = new LongAdder();
    private static final LongAdder phase5ValidationRejects = new LongAdder();
    private static final LongAdder phase5QueueWaitNanos = new LongAdder();
    private static final LongAdder phase5WorkerNanos = new LongAdder();
    private static final LongAdder phase5ApplyNanos = new LongAdder();
    private static final LongAdder phase5ResultAgeSamples = new LongAdder();
    private static final LongAdder phase5ResultAgeTicks = new LongAdder();
    private static final AtomicLong phase5ResultMaxAgeTicks = new AtomicLong();
    private static final LongAdder phase5Top1Matches = new LongAdder();
    private static final LongAdder phase5Top1Mismatches = new LongAdder();
    private static final LongAdder phase5OrderingMatches = new LongAdder();
    private static final LongAdder phase5OrderingDivergences = new LongAdder();
    private static final LongAdder phase6Snapshots = new LongAdder();
    private static final LongAdder phase6SnapshotNanos = new LongAdder();
    private static final LongAdder phase6SnapshotFailures = new LongAdder();
    private static final LongAdder phase6RequestsQueued = new LongAdder();
    private static final LongAdder phase6RequestsCoalesced = new LongAdder();
    private static final LongAdder phase6QueueSkips = new LongAdder();
    private static final LongAdder phase6WorkerRequests = new LongAdder();
    private static final LongAdder phase6WorkerCompleted = new LongAdder();
    private static final LongAdder phase6WorkerFailures = new LongAdder();
    private static final LongAdder phase6Cancelled = new LongAdder();
    private static final LongAdder phase6StaleResults = new LongAdder();
    private static final LongAdder phase6ValidationRejects = new LongAdder();
    private static final LongAdder phase6QueueWaitNanos = new LongAdder();
    private static final LongAdder phase6WorkerNanos = new LongAdder();
    private static final LongAdder phase6ApplyNanos = new LongAdder();
    private static final LongAdder phase6ResultAgeSamples = new LongAdder();
    private static final LongAdder phase6ResultAgeTicks = new LongAdder();
    private static final AtomicLong phase6ResultMaxAgeTicks = new AtomicLong();
    private static final LongAdder phase6Selections = new LongAdder();
    private static final LongAdder phase6Fallbacks = new LongAdder();
    private static final LongAdder phase6ReservationRejects = new LongAdder();
    private static final LongAdder phase6PathRejects = new LongAdder();

    private static final int TICK_BUFFER_SIZE = 1000;
    private static final long[] tickWorkNanos = new long[TICK_BUFFER_SIZE];
    private static int tickBufferIndex;
    private static int tickWorkSamples;
    private static long currentTickWorkNanos;
    private static int currentGoToSearchTick = Integer.MIN_VALUE;
    private static long currentGoToSearchTickNanos;
    private static int currentGoToSearchTickCount;
    private static final AtomicLong goToCoverSearchMaxTickNanos = new AtomicLong();
    private static final AtomicLong goToCoverSearchMaxTickCount = new AtomicLong();
    private static final ThreadLocal<Boolean> GO_TO_SEARCH_CONTEXT =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

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
        goToCoverSearches.reset();
        goToCoverSearchNanos.reset();
        goToCoverSearchMaxNanos.set(0L);
        goToCoverSearchMaxTickNanos.set(0L);
        goToCoverSearchMaxTickCount.set(0L);
        goToCoverDiscoveryNanos.reset();
        goToCoverScoringNanos.reset();
        goToCoverPathNanos.reset();
        goToCoverCandidatesDiscovered.reset();
        goToCoverCandidatesEvaluated.reset();
        goToCoverCandidatesScored.reset();
        goToCoverPathValidations.reset();
        goToCoverPathValidationFailures.reset();
        goToCoverPathValidationBudgetExhausted.reset();
        visibilityRays.reset();
        visibilityRayCacheHits.reset();
        visibilityRayCacheMisses.reset();
        visibilityRayRequests.reset();
        aimPointCacheHits.reset();
        aimPointCacheMisses.reset();
        targetQueryCacheHits.reset();
        targetQueryCacheMisses.reset();
        targetQueryCacheEntities.reset();
        sameTickPotentialTargetCacheHits.reset();
        coverDiscoveryCacheHits.reset();
        coverPathReuseHits.reset();
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
        asyncCoverSnapshotRequests.reset();
        asyncCoverSnapshotNanos.reset();
        asyncCoverWorkerRequests.reset();
        asyncCoverWorkerCompleted.reset();
        asyncCoverWorkerFailures.reset();
        asyncCoverWorkerCancelled.reset();
        asyncCoverQueueSkips.reset();
        asyncCoverCoalescedRequests.reset();
        asyncCoverStaleResults.reset();
        asyncCoverValidationRejects.reset();
        asyncCoverWorkerNanos.reset();
        asyncCoverApplyNanos.reset();
        coverInvalidationReasons.clear();
        passiveMaintenanceRunsByState.clear();
        passiveMaintenanceSkipsByState.clear();
        coverStateNanos.reset();
        coverSeekingNanos.reset();
        coverRepositioningNanos.reset();
        coverInCoverNanos.reset();
        coverSuppressedNanos.reset();
        suppressionPreemptionNanos.reset();
        riflemanCombatTicks.reset();
        machineGunnerCombatTicks.reset();
        riflemanDetectionTicks.reset();
        machineGunnerDetectionTicks.reset();
        riflemanCoverTicks.reset();
        machineGunnerCoverTicks.reset();
        riflemanCoverSearches.reset();
        machineGunnerCoverSearches.reset();
        riflemanPathRequests.reset();
        machineGunnerPathRequests.reset();
        riflemanPathRetries.reset();
        machineGunnerPathRetries.reset();
        riflemanPathFailures.reset();
        machineGunnerPathFailures.reset();
        machineGunnerAsyncRequests.reset();
        coverSearchRequestsQueued.reset();
        coverSearchRequestsExecuted.reset();
        coverSearchRequestsDeferred.reset();
        coverSearchRequestsCoalesced.reset();
        coverSearchRequestsCancelled.reset();
        coverSearchRequestsStale.reset();
        coverSearchRequestAgeSamples.reset();
        coverSearchRequestAgeTicks.reset();
        coverSearchRequestsAged.reset();
        coverSearchRequestMaxAgeTicks.set(0L);
        goToCoverSearchRequestsQueued.reset();
        goToCoverSearchRequestsExecuted.reset();
        goToCoverSearchRequestsDeferred.reset();
        goToCoverSearchRequestsCoalesced.reset();
        goToCoverSearchRequestsCancelled.reset();
        goToCoverSearchRequestsStale.reset();
        goToCoverSearchRequestsAged.reset();
        goToCoverSearchRequestAgeSamples.reset();
        goToCoverSearchRequestAgeTicks.reset();
        goToCoverSearchRequestMaxAgeTicks.set(0L);
        flankSearchAttempts.reset();
        flankSearchFailures.reset();
        flankSearchRetrySkips.reset();
        flankSearchFingerprintChanges.reset();
        emergencyCoverRequestsQueued.reset();
        emergencyCoverRequestsExecuted.reset();
        emergencyCoverRequestsDeferred.reset();
        emergencyCoverRequestsCoalesced.reset();
        emergencyCoverRequestsCancelled.reset();
        emergencyCoverRequestsStale.reset();
        emergencyCoverRequestAgeSamples.reset();
        emergencyCoverRequestAgeTicks.reset();
        for (LongAdder adder : stageNanos) adder.reset();
        for (LongAdder adder : stageCounts) adder.reset();
        smokeQueries.reset();
        smokeEntitiesTested.reset();
        smokeHits.reset();
        sameTickVisibilityFrameHits.reset();
        sameTickVisibilityFrameMisses.reset();
        sameTickSmokeFrameHits.reset();
        sameTickSmokeFrameMisses.reset();
        phase4Captures.reset();
        phase4CaptureNanos.reset();
        phase4Evaluations.reset();
        phase4EvaluationNanos.reset();
        phase4Candidates.reset();
        phase4Top1Matches.reset();
        phase4Top1Mismatches.reset();
        phase4OrderingMatches.reset();
        phase4OrderingDivergences.reset();
        phase4Skips.reset();
        phase5Snapshots.reset();
        phase5SnapshotNanos.reset();
        phase5SnapshotFailures.reset();
        phase5RequestsQueued.reset();
        phase5RequestsCoalesced.reset();
        phase5QueueSkips.reset();
        phase5WorkerRequests.reset();
        phase5WorkerCompleted.reset();
        phase5WorkerFailures.reset();
        phase5Cancelled.reset();
        phase5ResultCoalesced.reset();
        phase5StaleResults.reset();
        phase5ValidationRejects.reset();
        phase5QueueWaitNanos.reset();
        phase5WorkerNanos.reset();
        phase5ApplyNanos.reset();
        phase5ResultAgeSamples.reset();
        phase5ResultAgeTicks.reset();
        phase5ResultMaxAgeTicks.set(0L);
        phase5Top1Matches.reset();
        phase5Top1Mismatches.reset();
        phase5OrderingMatches.reset();
        phase5OrderingDivergences.reset();
        phase6Snapshots.reset();
        phase6SnapshotNanos.reset();
        phase6SnapshotFailures.reset();
        phase6RequestsQueued.reset();
        phase6RequestsCoalesced.reset();
        phase6QueueSkips.reset();
        phase6WorkerRequests.reset();
        phase6WorkerCompleted.reset();
        phase6WorkerFailures.reset();
        phase6Cancelled.reset();
        phase6StaleResults.reset();
        phase6ValidationRejects.reset();
        phase6QueueWaitNanos.reset();
        phase6WorkerNanos.reset();
        phase6ApplyNanos.reset();
        phase6ResultAgeSamples.reset();
        phase6ResultAgeTicks.reset();
        phase6ResultMaxAgeTicks.set(0L);
        phase6Selections.reset();
        phase6Fallbacks.reset();
        phase6ReservationRejects.reset();
        phase6PathRejects.reset();
        java.util.Arrays.fill(tickWorkNanos, 0L);
        tickBufferIndex = 0;
        tickWorkSamples = 0;
        currentTickWorkNanos = 0L;
        currentGoToSearchTick = Integer.MIN_VALUE;
        currentGoToSearchTickNanos = 0L;
        currentGoToSearchTickCount = 0;
        GO_TO_SEARCH_CONTEXT.remove();
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

    public static void recordSameTickPotentialTargetCacheHit() {
        if (enabled) sameTickPotentialTargetCacheHits.increment();
    }

    public static void recordSameTickVisibilityFrameHit() {
        if (enabled) sameTickVisibilityFrameHits.increment();
    }

    public static void recordSameTickVisibilityFrameMiss() {
        if (enabled) sameTickVisibilityFrameMisses.increment();
    }

    public static void recordSameTickSmokeFrameHit() {
        if (enabled) sameTickSmokeFrameHits.increment();
    }

    public static void recordSameTickSmokeFrameMiss() {
        if (enabled) sameTickSmokeFrameMisses.increment();
    }

    public static void recordCoverDiscoveryCacheHit() {
        if (enabled) coverDiscoveryCacheHits.increment();
    }

    public static void recordCoverPathReuseHit() {
        if (enabled) coverPathReuseHits.increment();
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

    public static void recordCombatTick(boolean machineGunner) {
        if (!enabled) return;
        if (machineGunner) machineGunnerCombatTicks.increment();
        else riflemanCombatTicks.increment();
    }

    public static void recordDetectionTick(int candidates, boolean machineGunner) {
        recordDetectionTick(candidates);
        if (!enabled) return;
        if (machineGunner) machineGunnerDetectionTicks.increment();
        else riflemanDetectionTicks.increment();
    }

    public static void recordCoverTick(String state, boolean machineGunner) {
        recordCoverTick(state);
        if (!enabled) return;
        if (machineGunner) machineGunnerCoverTicks.increment();
        else riflemanCoverTicks.increment();
    }

    public static void recordRoleCoverSearch(boolean machineGunner) {
        if (!enabled) return;
        if (machineGunner) machineGunnerCoverSearches.increment();
        else riflemanCoverSearches.increment();
    }

    public static void recordRolePathRequest(boolean machineGunner) {
        if (!enabled) return;
        if (machineGunner) machineGunnerPathRequests.increment();
        else riflemanPathRequests.increment();
    }

    public static void recordRolePathRetry(boolean machineGunner) {
        if (!enabled) return;
        if (machineGunner) machineGunnerPathRetries.increment();
        else riflemanPathRetries.increment();
    }

    public static void recordRolePathFailure(boolean machineGunner) {
        if (!enabled) return;
        if (machineGunner) machineGunnerPathFailures.increment();
        else riflemanPathFailures.increment();
    }

    public static void recordMachineGunnerAsyncRequest() {
        if (enabled) machineGunnerAsyncRequests.increment();
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

    public static void recordAsyncCoverSnapshot(long nanos) {
        if (!enabled) return;
        asyncCoverSnapshotRequests.increment();
        asyncCoverSnapshotNanos.add(nanos);
    }

    public static void recordAsyncCoverWorkerRequest() {
        if (enabled) {
            asyncCoverWorkerRequests.increment();
            machineGunnerAsyncRequests.increment();
        }
    }

    public static void recordAsyncCoverWorkerCompleted() {
        if (!enabled) return;
        asyncCoverWorkerCompleted.increment();
    }

    public static void recordAsyncCoverWorkerTime(long nanos) {
        if (enabled) asyncCoverWorkerNanos.add(nanos);
    }

    public static void recordAsyncCoverWorkerFailure() {
        if (enabled) asyncCoverWorkerFailures.increment();
    }

    public static void recordAsyncCoverWorkerCancelled() {
        if (enabled) asyncCoverWorkerCancelled.increment();
    }

    public static void recordAsyncCoverQueueSkip() {
        if (enabled) asyncCoverQueueSkips.increment();
    }

    public static void recordAsyncCoverCoalescedRequest() {
        if (enabled) asyncCoverCoalescedRequests.increment();
    }

    public static void recordAsyncCoverStaleResult() {
        if (enabled) asyncCoverStaleResults.increment();
    }

    public static void recordAsyncCoverValidationReject() {
        if (enabled) asyncCoverValidationRejects.increment();
    }

    public static void recordAsyncCoverApply(long nanos) {
        if (enabled) asyncCoverApplyNanos.add(nanos);
    }

    public static void recordCoverSearchRequestQueued() {
        if (enabled) coverSearchRequestsQueued.increment();
    }

    public static void recordGoToCoverSearchRequestQueued() {
        if (enabled) goToCoverSearchRequestsQueued.increment();
    }

    public static void recordEmergencyCoverRequestQueued() {
        if (enabled) emergencyCoverRequestsQueued.increment();
    }

    public static void recordCoverSearchRequestExecuted() {
        if (enabled) coverSearchRequestsExecuted.increment();
    }

    public static void recordGoToCoverSearchRequestExecuted() {
        if (enabled) goToCoverSearchRequestsExecuted.increment();
    }

    public static void recordEmergencyCoverRequestExecuted() {
        if (enabled) emergencyCoverRequestsExecuted.increment();
    }

    public static void recordCoverSearchRequestDeferred() {
        if (enabled) coverSearchRequestsDeferred.increment();
    }

    public static void recordGoToCoverSearchRequestDeferred() {
        if (enabled) goToCoverSearchRequestsDeferred.increment();
    }

    public static void recordEmergencyCoverRequestDeferred() {
        if (enabled) emergencyCoverRequestsDeferred.increment();
    }

    public static void recordCoverSearchRequestCoalesced() {
        if (enabled) coverSearchRequestsCoalesced.increment();
    }

    public static void recordGoToCoverSearchRequestCoalesced() {
        if (enabled) goToCoverSearchRequestsCoalesced.increment();
    }

    public static void recordEmergencyCoverRequestCoalesced() {
        if (enabled) emergencyCoverRequestsCoalesced.increment();
    }

    public static void recordCoverSearchRequestCancelled() {
        if (enabled) coverSearchRequestsCancelled.increment();
    }

    public static void recordGoToCoverSearchRequestCancelled() {
        if (enabled) goToCoverSearchRequestsCancelled.increment();
    }

    public static void recordEmergencyCoverRequestCancelled() {
        if (enabled) emergencyCoverRequestsCancelled.increment();
    }

    public static void recordCoverSearchRequestStale() {
        if (enabled) coverSearchRequestsStale.increment();
    }

    public static void recordGoToCoverSearchRequestStale() {
        if (enabled) goToCoverSearchRequestsStale.increment();
    }

    public static void recordGoToCoverSearchRequestAged() {
        if (enabled) goToCoverSearchRequestsAged.increment();
    }

    public static void recordEmergencyCoverRequestStale() {
        if (enabled) emergencyCoverRequestsStale.increment();
    }

    public static void recordCoverSearchRequestAge(long ageTicks) {
        if (!enabled) return;
        long safeAge = Math.max(0L, ageTicks);
        coverSearchRequestAgeSamples.increment();
        coverSearchRequestAgeTicks.add(safeAge);
        coverSearchRequestMaxAgeTicks.accumulateAndGet(safeAge, Math::max);
    }

    public static void recordGoToCoverSearchRequestAge(long ageTicks) {
        if (!enabled) return;
        long safeAge = Math.max(0L, ageTicks);
        goToCoverSearchRequestAgeSamples.increment();
        goToCoverSearchRequestAgeTicks.add(safeAge);
        goToCoverSearchRequestMaxAgeTicks.accumulateAndGet(safeAge, Math::max);
    }

    public static void recordEmergencyCoverRequestAge(long ageTicks) {
        if (!enabled) return;
        emergencyCoverRequestAgeSamples.increment();
        emergencyCoverRequestAgeTicks.add(Math.max(0L, ageTicks));
    }

    public static void recordCoverSearchRequestAged() {
        if (enabled) coverSearchRequestsAged.increment();
    }

    public static void recordFlankSearchAttempt() {
        if (enabled) flankSearchAttempts.increment();
    }

    public static void recordFlankSearchFailure() {
        if (enabled) flankSearchFailures.increment();
    }

    public static void recordFlankSearchRetrySkip() {
        if (enabled) flankSearchRetrySkips.increment();
    }

    public static void recordFlankSearchFingerprintChange() {
        if (enabled) flankSearchFingerprintChanges.increment();
    }

    public static void recordStageTime(Stage stage, long nanos) {
        if (!enabled) return;
        stageNanos[stage.ordinal()].add(nanos);
        stageCounts[stage.ordinal()].increment();
        currentTickWorkNanos += nanos;
        if (Boolean.TRUE.equals(GO_TO_SEARCH_CONTEXT.get())) {
            if (stage == Stage.COVER_SCORING) {
                goToCoverScoringNanos.add(nanos);
            } else if (stage == Stage.PATH_REQUEST) {
                goToCoverPathNanos.add(nanos);
            }
        }
    }

    public static void recordSmokeQuery(int entitiesTested) {
        if (!enabled) return;
        smokeQueries.increment();
        smokeEntitiesTested.add(entitiesTested);
    }

    public static void recordSmokeHit() {
        if (enabled) smokeHits.increment();
    }

    public static void beginTick() {
        if (enabled) currentTickWorkNanos = 0L;
    }

    public static void endTick() {
        if (!enabled) return;
        tickWorkNanos[tickBufferIndex] = currentTickWorkNanos;
        tickBufferIndex = (tickBufferIndex + 1) % TICK_BUFFER_SIZE;
        if (tickWorkSamples < TICK_BUFFER_SIZE) tickWorkSamples++;
        currentTickWorkNanos = 0L;
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
        if (Boolean.TRUE.equals(GO_TO_SEARCH_CONTEXT.get())) {
            goToCoverDiscoveryNanos.add(nanos);
            goToCoverCandidatesDiscovered.add(candidates);
        }
    }

    public static void recordCoverCandidatesEvaluated(int candidates) {
        if (!enabled) return;
        coverCandidatesEvaluated.add(candidates);
        if (Boolean.TRUE.equals(GO_TO_SEARCH_CONTEXT.get())) {
            goToCoverCandidatesEvaluated.add(candidates);
        }
    }

    public static void recordCoverCandidatesScored(int candidates) {
        if (!enabled) return;
        coverCandidatesScored.add(candidates);
        if (Boolean.TRUE.equals(GO_TO_SEARCH_CONTEXT.get())) {
            goToCoverCandidatesScored.add(candidates);
        }
    }

    public static void beginGoToCoverSearch() {
        if (enabled) GO_TO_SEARCH_CONTEXT.set(Boolean.TRUE);
    }

    public static void endGoToCoverSearch() {
        GO_TO_SEARCH_CONTEXT.remove();
    }

    public static void recordGoToCoverSearch(int serverTick, long nanos) {
        if (!enabled) return;
        long safeNanos = Math.max(0L, nanos);
        goToCoverSearches.increment();
        goToCoverSearchNanos.add(safeNanos);
        goToCoverSearchMaxNanos.accumulateAndGet(safeNanos, Math::max);
        if (currentGoToSearchTick != serverTick) {
            currentGoToSearchTick = serverTick;
            currentGoToSearchTickNanos = 0L;
            currentGoToSearchTickCount = 0;
        }
        currentGoToSearchTickNanos += safeNanos;
        currentGoToSearchTickCount++;
        goToCoverSearchMaxTickNanos.accumulateAndGet(currentGoToSearchTickNanos, Math::max);
        goToCoverSearchMaxTickCount.accumulateAndGet(currentGoToSearchTickCount, Math::max);
    }

    public static void recordGoToCoverPathValidation(boolean reachable) {
        if (!enabled) return;
        goToCoverPathValidations.increment();
        if (!reachable) goToCoverPathValidationFailures.increment();
    }

    public static void recordGoToCoverPathValidationBudgetExhausted() {
        if (enabled) goToCoverPathValidationBudgetExhausted.increment();
    }

    public static void recordPhase4Capture(long nanos, int candidates) {
        if (!enabled) return;
        phase4Captures.increment();
        phase4CaptureNanos.add(nanos);
        phase4Candidates.add(candidates);
    }

    public static void recordPhase4Evaluation(long nanos) {
        if (!enabled) return;
        phase4Evaluations.increment();
        phase4EvaluationNanos.add(nanos);
    }

    public static void recordPhase4Top1Comparison(boolean match) {
        if (!enabled) return;
        if (match) phase4Top1Matches.increment();
        else phase4Top1Mismatches.increment();
    }

    public static void recordPhase4OrderingComparison(boolean match) {
        if (!enabled) return;
        if (match) phase4OrderingMatches.increment();
        else phase4OrderingDivergences.increment();
    }

    public static void recordPhase4Skip() {
        if (enabled) phase4Skips.increment();
    }

    public static void recordPhase5Snapshot(long nanos) {
        if (!enabled) return;
        phase5Snapshots.increment();
        phase5SnapshotNanos.add(Math.max(0L, nanos));
    }

    public static void recordPhase5SnapshotFailure() {
        if (enabled) phase5SnapshotFailures.increment();
    }

    public static void recordPhase5RequestQueued() {
        if (enabled) phase5RequestsQueued.increment();
    }

    public static void recordPhase5RequestCoalesced() {
        if (enabled) phase5RequestsCoalesced.increment();
    }

    public static void recordPhase5QueueSkip() {
        if (enabled) phase5QueueSkips.increment();
    }

    public static void recordPhase5WorkerRequest() {
        if (enabled) phase5WorkerRequests.increment();
    }

    public static void recordPhase5WorkerCompleted(long nanos) {
        if (!enabled) return;
        phase5WorkerCompleted.increment();
        phase5WorkerNanos.add(Math.max(0L, nanos));
    }

    public static void recordPhase5Failure() {
        if (enabled) phase5WorkerFailures.increment();
    }

    public static void recordPhase5Cancelled() {
        if (enabled) phase5Cancelled.increment();
    }

    public static void recordPhase5ResultCoalesced() {
        if (enabled) phase5ResultCoalesced.increment();
    }

    public static void recordPhase5StaleResult() {
        if (enabled) phase5StaleResults.increment();
    }

    public static void recordPhase5ValidationReject() {
        if (enabled) phase5ValidationRejects.increment();
    }

    public static void recordPhase5QueueWait(long nanos) {
        if (enabled) phase5QueueWaitNanos.add(Math.max(0L, nanos));
    }

    public static void recordPhase5Apply(long nanos) {
        if (enabled) phase5ApplyNanos.add(Math.max(0L, nanos));
    }

    public static void recordPhase5ResultAge(long ageTicks) {
        if (!enabled) return;
        long safeAge = Math.max(0L, ageTicks);
        phase5ResultAgeSamples.increment();
        phase5ResultAgeTicks.add(safeAge);
        phase5ResultMaxAgeTicks.accumulateAndGet(safeAge, Math::max);
    }

    public static void recordPhase5Top1Comparison(boolean match) {
        if (!enabled) return;
        if (match) phase5Top1Matches.increment();
        else phase5Top1Mismatches.increment();
    }

    public static void recordPhase5OrderingComparison(boolean match) {
        if (!enabled) return;
        if (match) phase5OrderingMatches.increment();
        else phase5OrderingDivergences.increment();
    }

    public static void recordPhase6Snapshot(long nanos) {
        if (!enabled) return;
        phase6Snapshots.increment();
        phase6SnapshotNanos.add(Math.max(0L, nanos));
    }

    public static void recordPhase6SnapshotFailure() { if (enabled) phase6SnapshotFailures.increment(); }
    public static void recordPhase6RequestQueued() { if (enabled) phase6RequestsQueued.increment(); }
    public static void recordPhase6RequestCoalesced() { if (enabled) phase6RequestsCoalesced.increment(); }
    public static void recordPhase6QueueSkip() { if (enabled) phase6QueueSkips.increment(); }
    public static void recordPhase6WorkerRequest() { if (enabled) phase6WorkerRequests.increment(); }
    public static void recordPhase6WorkerCompleted(long nanos) {
        if (!enabled) return;
        phase6WorkerCompleted.increment();
        phase6WorkerNanos.add(Math.max(0L, nanos));
    }
    public static void recordPhase6Failure() { if (enabled) phase6WorkerFailures.increment(); }
    public static void recordPhase6Cancelled() { if (enabled) phase6Cancelled.increment(); }
    public static void recordPhase6StaleResult() { if (enabled) phase6StaleResults.increment(); }
    public static void recordPhase6ValidationReject() { if (enabled) phase6ValidationRejects.increment(); }
    public static void recordPhase6QueueWait(long nanos) { if (enabled) phase6QueueWaitNanos.add(Math.max(0L, nanos)); }
    public static void recordPhase6Apply(long nanos) { if (enabled) phase6ApplyNanos.add(Math.max(0L, nanos)); }
    public static void recordPhase6ResultAge(long ageTicks) {
        if (!enabled) return;
        long safeAge = Math.max(0L, ageTicks);
        phase6ResultAgeSamples.increment();
        phase6ResultAgeTicks.add(safeAge);
        phase6ResultMaxAgeTicks.accumulateAndGet(safeAge, Math::max);
    }
    public static void recordPhase6Selection() { if (enabled) phase6Selections.increment(); }
    public static void recordPhase6Fallback() { if (enabled) phase6Fallbacks.increment(); }
    public static void recordPhase6ReservationReject() { if (enabled) phase6ReservationRejects.increment(); }
    public static void recordPhase6PathReject() { if (enabled) phase6PathRejects.increment(); }

    public static String report() {
        long visibilityHits = visibilityCacheHits.sum();
        long visibilityMisses = visibilityCacheMisses.sum();
        long exposureHits = exposureCacheHits.sum();
        long exposureMisses = exposureCacheMisses.sum();
        long searches = coverSearches.sum();

        return "=== PERFORMANCE METRICS ===\n"
            + "  Enabled: " + enabled + "\n"
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
             + "  Phase 1 reuse: " + sameTickPotentialTargetCacheHits.sum()
             + " same-tick target hits, " + coverDiscoveryCacheHits.sum()
             + " cover discovery hits, " + coverPathReuseHits.sum() + " path hits\n"
             + "  Phase 3 perception frame: " + sameTickVisibilityFrameHits.sum()
             + " visibility hits, " + sameTickVisibilityFrameMisses.sum()
             + " visibility misses, " + sameTickSmokeFrameHits.sum()
              + " smoke cell hits, " + sameTickSmokeFrameMisses.sum() + " smoke cell misses\n"
             + "  Phase 4 pure cover shadow: " + phase4Captures.sum() + " captures ("
             + formatMillis(phase4CaptureNanos.sum()) + " ms), " + phase4Evaluations.sum()
             + " evaluations (" + formatMillis(phase4EvaluationNanos.sum()) + " ms), "
             + phase4Candidates.sum() + " candidates, top-1=" + phase4Top1Matches.sum()
             + " matches/" + phase4Top1Mismatches.sum() + " mismatches, ordering="
             + phase4OrderingMatches.sum() + " matches/" + phase4OrderingDivergences.sum()
             + " divergences, " + phase4Skips.sum() + " skips\n"
              + "  Phase 5 async rifleman shadow: " + phase5Snapshots.sum() + " snapshots ("
              + formatMillis(phase5SnapshotNanos.sum()) + " ms), " + phase5SnapshotFailures.sum()
              + " snapshot failures, "
             + phase5RequestsQueued.sum() + " queued, " + phase5RequestsCoalesced.sum()
             + " coalesced, " + phase5QueueSkips.sum() + " queue skips, "
             + phase5WorkerRequests.sum() + " worker requests, " + phase5WorkerCompleted.sum()
             + " completed, " + phase5WorkerFailures.sum() + " failures, " + phase5Cancelled.sum()
             + " cancelled, " + phase5ResultCoalesced.sum() + " result coalesced, "
             + phase5StaleResults.sum() + " stale, " + phase5ValidationRejects.sum()
             + " validation rejects\n"
              + "    queue wait="
             + formatMillis(phase5QueueWaitNanos.sum()) + " ms, worker="
             + formatMillis(phase5WorkerNanos.sum()) + " ms, apply="
             + formatMillis(phase5ApplyNanos.sum()) + " ms, result age avg="
             + formatTicks(phase5ResultAgeSamples.sum() == 0 ? 0.0
                 : phase5ResultAgeTicks.sum() / (double) phase5ResultAgeSamples.sum())
             + " ticks, max=" + phase5ResultMaxAgeTicks.get() + " ticks, top-1="
             + phase5Top1Matches.sum() + " matches/" + phase5Top1Mismatches.sum()
             + " mismatches, ordering=" + phase5OrderingMatches.sum() + " matches/"
              + phase5OrderingDivergences.sum() + " divergences\n"
              + "  Phase 6 async cover pilot: " + phase6Snapshots.sum() + " snapshots ("
              + formatMillis(phase6SnapshotNanos.sum()) + " ms), " + phase6SnapshotFailures.sum()
              + " snapshot failures, " + phase6RequestsQueued.sum() + " queued, "
              + phase6RequestsCoalesced.sum() + " coalesced, " + phase6QueueSkips.sum()
              + " queue skips, " + phase6WorkerRequests.sum() + " worker requests, "
              + phase6WorkerCompleted.sum() + " completed, " + phase6WorkerFailures.sum()
              + " failures, " + phase6Cancelled.sum() + " cancelled, " + phase6StaleResults.sum()
              + " stale, " + phase6ValidationRejects.sum() + " validation rejects, selections="
              + phase6Selections.sum() + ", fallbacks=" + phase6Fallbacks.sum()
              + ", reservation rejects=" + phase6ReservationRejects.sum() + ", path rejects="
              + phase6PathRejects.sum() + "\n"
              + "    queue wait=" + formatMillis(phase6QueueWaitNanos.sum()) + " ms, worker="
              + formatMillis(phase6WorkerNanos.sum()) + " ms, apply="
              + formatMillis(phase6ApplyNanos.sum()) + " ms, result age avg="
              + formatTicks(phase6ResultAgeSamples.sum() == 0 ? 0.0
                  : phase6ResultAgeTicks.sum() / (double) phase6ResultAgeSamples.sum())
              + " ticks, max=" + phase6ResultMaxAgeTicks.get() + " ticks\n"
             + "  Threat reports: " + threatReportAttempts.sum() + " attempts, "
            + threatReportPublished.sum() + " published, "
            + threatReportGeometryCalculations.sum() + " geometry calculations, "
            + threatReportDeduplicated.sum() + " deduplicated\n"
            + "  Squad perception: " + squadThreatSnapshotRequests.sum() + " threat snapshots, "
            + threatSortSelectionPasses.sum() + " sort/selection passes, "
            + squadMemberFilterPasses.sum() + " member filter passes, "
            + temporaryCollectionsAvoided.sum() + " temporary collections avoided\n"
            + "  Async cover: " + asyncCoverSnapshotRequests.sum() + " snapshots ("
            + formatMillis(asyncCoverSnapshotNanos.sum()) + " ms), "
            + asyncCoverWorkerRequests.sum() + " requests, "
            + asyncCoverWorkerCompleted.sum() + " completed, "
            + asyncCoverWorkerFailures.sum() + " failures, "
            + asyncCoverWorkerCancelled.sum() + " cancelled\n"
            + "  Async cover queue: " + asyncCoverQueueSkips.sum() + " skips, "
            + asyncCoverCoalescedRequests.sum() + " coalesced, "
            + asyncCoverStaleResults.sum() + " stale, "
            + asyncCoverValidationRejects.sum() + " validation rejects, "
            + formatMillis(asyncCoverWorkerNanos.sum()) + " ms worker, "
            + formatMillis(asyncCoverApplyNanos.sum()) + " ms apply\n"
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
              + "  Cover search queue: " + coverSearchRequestsQueued.sum() + " queued, "
              + coverSearchRequestsExecuted.sum() + " executed, "
             + coverSearchRequestsDeferred.sum() + " deferred, "
             + coverSearchRequestsCoalesced.sum() + " coalesced, "
             + coverSearchRequestsCancelled.sum() + " cancelled, "
             + coverSearchRequestsStale.sum() + " stale, "
             + coverSearchRequestsAged.sum() + " aged, age avg="
             + formatTicks(coverSearchRequestAgeSamples.sum() == 0 ? 0.0
                 : coverSearchRequestAgeTicks.sum() / (double) coverSearchRequestAgeSamples.sum())
              + " ticks, max=" + coverSearchRequestMaxAgeTicks.get() + " ticks\n"
              + "  GO_TO cover search queue: " + goToCoverSearchRequestsQueued.sum() + " queued, "
              + goToCoverSearchRequestsExecuted.sum() + " executed, "
              + goToCoverSearchRequestsDeferred.sum() + " deferred, "
              + goToCoverSearchRequestsCoalesced.sum() + " coalesced, "
              + goToCoverSearchRequestsCancelled.sum() + " cancelled, "
              + goToCoverSearchRequestsStale.sum() + " stale, "
              + goToCoverSearchRequestsAged.sum() + " aged, age avg="
              + formatTicks(goToCoverSearchRequestAgeSamples.sum() == 0 ? 0.0
                  : goToCoverSearchRequestAgeTicks.sum() / (double) goToCoverSearchRequestAgeSamples.sum())
              + " ticks, max=" + goToCoverSearchRequestMaxAgeTicks.get() + " ticks\n"
              + "  GO_TO cover calculations: " + goToCoverSearches.sum() + " searches, "
              + formatMillis(goToCoverSearchNanos.sum()) + " ms total, "
              + formatMillis(goToCoverSearches.sum() == 0 ? 0 : goToCoverSearchNanos.sum()
                  / (double) goToCoverSearches.sum()) + " ms/search, max="
              + formatMillis(goToCoverSearchMaxNanos.get()) + " ms\n"
               + "    discovery=" + formatMillis(goToCoverDiscoveryNanos.sum())
               + " ms, scoring=" + formatMillis(goToCoverScoringNanos.sum())
               + " ms, path=" + formatMillis(goToCoverPathNanos.sum())
               + " ms, exact validations=" + goToCoverPathValidations.sum()
               + " (failed=" + goToCoverPathValidationFailures.sum()
               + ", budget exhausted=" + goToCoverPathValidationBudgetExhausted.sum() + ")"
               + ", candidates discovered/evaluated/scored="
               + goToCoverCandidatesDiscovered.sum() + "/" + goToCoverCandidatesEvaluated.sum()
              + "/" + goToCoverCandidatesScored.sum() + "\n"
              + "    per tick: max=" + formatMillis(goToCoverSearchMaxTickNanos.get())
              + " ms, max searches=" + goToCoverSearchMaxTickCount.get() + "\n"
              + "  Flank searches: " + flankSearchAttempts.sum() + " attempts, "
             + flankSearchFailures.sum() + " failed, " + flankSearchRetrySkips.sum()
             + " retry skips, " + flankSearchFingerprintChanges.sum() + " fingerprint changes\n"
             + "  Emergency cover queue: " + emergencyCoverRequestsQueued.sum() + " queued, "
             + emergencyCoverRequestsExecuted.sum() + " executed, "
             + emergencyCoverRequestsDeferred.sum() + " deferred, "
             + emergencyCoverRequestsCoalesced.sum() + " coalesced, "
             + emergencyCoverRequestsCancelled.sum() + " cancelled, "
             + emergencyCoverRequestsStale.sum() + " stale, age avg="
             + formatTicks(emergencyCoverRequestAgeSamples.sum() == 0 ? 0.0
                 : emergencyCoverRequestAgeTicks.sum() / (double) emergencyCoverRequestAgeSamples.sum())
             + " ticks\n"
            + "  Cover maintenance: " + coverMaintenanceRuns.sum() + " runs, "
            + coverMaintenanceSkips.sum() + " skips\n"
            + "  Cover state time: " + formatMillis(coverStateNanos.sum()) + " ms total"
            + " (seeking=" + formatMillis(coverSeekingNanos.sum())
            + ", repositioning=" + formatMillis(coverRepositioningNanos.sum())
            + ", in-cover=" + formatMillis(coverInCoverNanos.sum())
            + ", suppressed=" + formatMillis(coverSuppressedNanos.sum()) + ")\n"
            + "  Suppression preemption time: " + formatMillis(suppressionPreemptionNanos.sum()) + " ms\n"
            + "  Role combat ticks: rifleman=" + riflemanCombatTicks.sum()
            + ", machine-gunner=" + machineGunnerCombatTicks.sum() + "\n"
            + "  Role detection ticks: rifleman=" + riflemanDetectionTicks.sum()
            + ", machine-gunner=" + machineGunnerDetectionTicks.sum() + "\n"
            + "  Role cover ticks: rifleman=" + riflemanCoverTicks.sum()
            + ", machine-gunner=" + machineGunnerCoverTicks.sum() + "\n"
            + "  Role cover searches: rifleman=" + riflemanCoverSearches.sum()
            + ", machine-gunner=" + machineGunnerCoverSearches.sum() + "\n"
            + "  Role path requests: rifleman=" + riflemanPathRequests.sum()
            + ", machine-gunner=" + machineGunnerPathRequests.sum() + "\n"
            + "  Role path retries: rifleman=" + riflemanPathRetries.sum()
            + ", machine-gunner=" + machineGunnerPathRetries.sum() + "\n"
            + "  Role path failures: rifleman=" + riflemanPathFailures.sum()
            + ", machine-gunner=" + machineGunnerPathFailures.sum() + "\n"
            + "  Machine-gunner async requests: " + machineGunnerAsyncRequests.sum() + "\n"
            + "  Smoke lookups: " + smokeQueries.sum() + " queries, "
            + smokeEntitiesTested.sum() + " entities tested, " + smokeHits.sum() + " hits\n"
            + "  Stage time:\n"
            + stageReport(Stage.ENTITY_QUERY, "Entity query")
            + stageReport(Stage.LOS_BLOCK_TRAVERSAL, "LOS block traversal")
            + stageReport(Stage.SMOKE_LOOKUP, "Smoke lookup")
            + stageReport(Stage.EXPOSURE, "Exposure")
            + stageReport(Stage.COVER_SCORING, "Cover scoring")
            + stageReport(Stage.PATH_REQUEST, "Path request")
            + "  AI work per tick (last " + tickWorkSamples + " ticks): avg "
            + formatMillis(tickAverageNanos()) + " ms, p50 "
            + formatMillis(tickPercentileNanos(0.50)) + " ms, p95 "
            + formatMillis(tickPercentileNanos(0.95)) + " ms, p99 "
            + formatMillis(tickPercentileNanos(0.99)) + " ms, worst "
            + formatMillis(tickMaxNanos()) + " ms\n"
            + "  Cover search time: " + formatMillis(coverSearchNanos.sum())
            + " ms total, " + formatMillis(searches == 0 ? 0 : coverSearchNanos.sum() / (double) searches)
            + " ms/search";
    }

    private static String stageReport(Stage stage, String label) {
        long count = stageCounts[stage.ordinal()].sum();
        long nanos = stageNanos[stage.ordinal()].sum();
        return "    " + label + ": " + count + " calls, "
            + formatMillis(nanos) + " ms total"
            + (count == 0 ? "" : ", " + formatMillis(nanos / (double) count) + " ms/call") + "\n";
    }

    private static long tickAverageNanos() {
        if (tickWorkSamples == 0) return 0L;
        long total = 0L;
        for (int i = 0; i < tickWorkSamples; i++) total += tickWorkNanos[i];
        return total / tickWorkSamples;
    }

    private static long tickPercentileNanos(double percentile) {
        if (tickWorkSamples == 0) return 0L;
        long[] sorted = tickWorkNanos.clone();
        java.util.Arrays.sort(sorted, 0, tickWorkSamples);
        int index = (int) Math.ceil(percentile * tickWorkSamples) - 1;
        if (index < 0) index = 0;
        return sorted[index];
    }

    private static long tickMaxNanos() {
        if (tickWorkSamples == 0) return 0L;
        long max = 0L;
        for (int i = 0; i < tickWorkSamples; i++) {
            if (tickWorkNanos[i] > max) max = tickWorkNanos[i];
        }
        return max;
    }

    private static String formatMillis(double nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0);
    }

    private static String formatTicks(double ticks) {
        return String.format(Locale.ROOT, "%.2f", ticks);
    }
}

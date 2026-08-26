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

    private static final int TICK_BUFFER_SIZE = 1000;
    private static final long[] tickWorkNanos = new long[TICK_BUFFER_SIZE];
    private static int tickBufferIndex;
    private static int tickWorkSamples;
    private static long currentTickWorkNanos;

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
        java.util.Arrays.fill(tickWorkNanos, 0L);
        tickBufferIndex = 0;
        tickWorkSamples = 0;
        currentTickWorkNanos = 0L;
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

    public static void recordEmergencyCoverRequestQueued() {
        if (enabled) emergencyCoverRequestsQueued.increment();
    }

    public static void recordCoverSearchRequestExecuted() {
        if (enabled) coverSearchRequestsExecuted.increment();
    }

    public static void recordEmergencyCoverRequestExecuted() {
        if (enabled) emergencyCoverRequestsExecuted.increment();
    }

    public static void recordCoverSearchRequestDeferred() {
        if (enabled) coverSearchRequestsDeferred.increment();
    }

    public static void recordEmergencyCoverRequestDeferred() {
        if (enabled) emergencyCoverRequestsDeferred.increment();
    }

    public static void recordCoverSearchRequestCoalesced() {
        if (enabled) coverSearchRequestsCoalesced.increment();
    }

    public static void recordEmergencyCoverRequestCoalesced() {
        if (enabled) emergencyCoverRequestsCoalesced.increment();
    }

    public static void recordCoverSearchRequestCancelled() {
        if (enabled) coverSearchRequestsCancelled.increment();
    }

    public static void recordEmergencyCoverRequestCancelled() {
        if (enabled) emergencyCoverRequestsCancelled.increment();
    }

    public static void recordCoverSearchRequestStale() {
        if (enabled) coverSearchRequestsStale.increment();
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

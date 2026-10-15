package com.stevesarmy.entity.ai;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.cover.SuppressionTracker;
import com.stevesarmy.debug.DiagnosticLogManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * Universal suppressed-movement posture (dangerTravelResponse): any soldier
 * crossing ground the squad danger map rates "crawling hides" while under
 * suppression goes low and slows, no matter which goal drives the march -
 * hold marches, GO_TO/SEND ping relocations, the approach to claimed cover,
 * follow. Pinned soldiers crawl too: pinned only means seek cover, the
 * approach happens low. A live firing-prone plan keeps priority - this
 * controller never overrides the pose while one is active, and never stands a
 * prone-firing soldier up.
 */
public class TravelCrawlController {
    private static final int CHECK_INTERVAL_TICKS = 10;

    private final SoldierEntity soldier;
    private boolean crawlActive;
    private boolean mandatoryPoseActive;
    private int checkTimer;
    /** Last evaluation result, for the coverstate debug card. */
    private boolean crawlHidesHere;

    public TravelCrawlController(SoldierEntity soldier) {
        this.soldier = soldier;
        this.checkTimer = CHECK_INTERVAL_TICKS;
    }

    public void tick() {
        if (soldier.getMovementController().isMandatoryCrawl()) {
            mandatoryPoseActive = true;
            if (!soldier.isFiringProne()) soldier.setLowCrouching(true);
            crawlHidesHere = true;
            return;
        }
        if (mandatoryPoseActive) {
            mandatoryPoseActive = false;
            checkTimer = 1;
            if (!crawlActive && !soldier.isFiringProne()
                && soldier.getCoverBehaviorManager().getCurrentCover() == null) {
                soldier.setLowCrouching(false);
            }
        }
        if (!StevesArmyConfig.isDangerTravelResponseEnabled()) {
            disengage("config-off");
            return;
        }
        // Prone firing owns the shared pose; leave it alone entirely.
        if (soldier.isFiringProne()) return;

        // Posture/state only. SoldierSpeedController resolves the crawl cap at
        // movement consumption time, using the current attribute and raw goal request.

        if (--checkTimer > 0) return;
        checkTimer = CHECK_INTERVAL_TICKS;

        boolean suppressed = suppressionFreshAndPressured();
        boolean moving = !soldier.getNavigation().isDone();
        crawlHidesHere = dangerMapSaysCrawlHides();

        if (crawlActive) {
            if (!suppressed || !moving || !crawlHidesHere) {
                disengage(String.format("suppressed=%s moving=%s crawlHides=%s",
                    suppressed, moving, crawlHidesHere));
            }
        } else if (suppressed && moving && crawlHidesHere) {
            engage();
        }
    }

    /**
     * Pressured AND actively being fired at: the level alone decays slowly
     * (peak-suppression slowdown), so a soldier would otherwise stay low long
     * after the fire shifted. Requiring a recent suppression event bounds the
     * crawl to soldiers actually under fire.
     */
    private boolean suppressionFreshAndPressured() {
        return soldier.getCoverBehaviorManager().getSuppressionLevel()
                >= SuppressionTracker.PRESSURED_THRESHOLD
            && soldier.getCoverBehaviorManager().getSuppressionTracker()
                .wasRecentlySuppressed();
    }

    private void engage() {
        crawlActive = true;
        soldier.setLowCrouching(true);
        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[TravelResponse] Soldier {} going low - crawling hides here (suppression={})",
                soldier.getId(), String.format("%.2f", soldier.getCoverBehaviorManager().getSuppressionLevel()));
        }
    }

    private void disengage(String reason) {
        if (!crawlActive) return;
        crawlActive = false;
        if (!soldier.isFiringProne() && !soldier.getMovementController().isMandatoryCrawl()) {
            soldier.setLowCrouching(false);
        }
        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[TravelResponse] Soldier {} standing back up ({})",
                soldier.getId(), reason);
        }
    }

    /** Squad danger map verdict at the soldier's position; unknown ground reads false. */
    private boolean dangerMapSaysCrawlHides() {
        if (!(soldier.level() instanceof ServerLevel serverLevel)) return false;
        UUID squadId = soldier.getSquadId();
        if (squadId == null) return false;
        return SquadManager.get(serverLevel).getSquadById(squadId)
            .map(data -> data.getDangerMap().isCrawlHiddenAt(
                soldier.getX(), soldier.getZ(), serverLevel.getGameTime()))
            .orElse(false);
    }

    public boolean isCrawlActive() {
        return crawlActive || soldier.getMovementController().isMandatoryCrawl();
    }

    public boolean isCrawlHidingHere() {
        return crawlHidesHere;
    }
}

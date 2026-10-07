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
    private static final double CRAWL_TRAVEL_SPEED = 0.65D;
    private static final int CHECK_INTERVAL_TICKS = 10;

    private final SoldierEntity soldier;
    private boolean crawlActive;
    /** Navigation speed modifier observed when the crawl engaged; restored on exit. */
    private double restoredSpeedModifier = -1.0D;
    private int checkTimer;
    /** Last evaluation result, for the coverstate debug card. */
    private boolean crawlHidesHere;

    public TravelCrawlController(SoldierEntity soldier) {
        this.soldier = soldier;
        this.checkTimer = CHECK_INTERVAL_TICKS;
    }

    public void tick() {
        if (!StevesArmyConfig.isDangerTravelResponseEnabled()) {
            disengage("config-off");
            return;
        }
        // Prone firing owns the shared pose; leave it alone entirely.
        if (soldier.isFiringProne()) return;

        // Goals re-path with their own speed modifier (moveTo overwrites it);
        // re-assert the crawl speed every tick while engaged.
        if (crawlActive) {
            soldier.getNavigation().setSpeedModifier(CRAWL_TRAVEL_SPEED);
        }

        if (--checkTimer > 0) return;
        checkTimer = CHECK_INTERVAL_TICKS;

        boolean suppressed = soldier.getCoverBehaviorManager().getSuppressionLevel()
            >= SuppressionTracker.PRESSURED_THRESHOLD;
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

    private void engage() {
        crawlActive = true;
        restoredSpeedModifier = navSpeed();
        soldier.setLowCrouching(true);
        soldier.getNavigation().setSpeedModifier(CRAWL_TRAVEL_SPEED);
        if (DiagnosticLogManager.isCoverLoggingEnabled()) {
            StevesArmyMod.LOGGER.info("[TravelResponse] Soldier {} going low - crawling hides here (suppression={})",
                soldier.getId(), String.format("%.2f", soldier.getCoverBehaviorManager().getSuppressionLevel()));
        }
    }

    private void disengage(String reason) {
        if (!crawlActive) return;
        crawlActive = false;
        if (!soldier.isFiringProne()) {
            soldier.setLowCrouching(false);
        }
        // Restore the pre-crawl speed only if nothing else claimed the modifier
        // meanwhile (a goal's own moveTo already carries its intended speed).
        if (restoredSpeedModifier >= 0.0D
                && Math.abs(navSpeed() - CRAWL_TRAVEL_SPEED) < 1.0e-4D) {
            soldier.getNavigation().setSpeedModifier(restoredSpeedModifier);
        }
        restoredSpeedModifier = -1.0D;
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
        return crawlActive;
    }

    private double navSpeed() {
        return soldier.getNavigation() instanceof SoldierGroundNavigation nav
            ? nav.getNavSpeedModifier() : 1.0D;
    }

    public boolean isCrawlHidingHere() {
        return crawlHidesHere;
    }
}

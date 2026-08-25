package com.stevesarmy.entity.ai;

import com.stevesarmy.combat.cover.CoverFinder;
import com.stevesarmy.combat.cover.CoverPoint;
import com.stevesarmy.entity.SoldierEntity;

import java.util.List;
import java.util.Optional;

/**
 * Optional cover-selection override installed on CoverTacticalGoal. Given the
 * cover path's already-scored candidate list, returns a preferred cover or empty
 * to defer to the path's base selection. Implementations must never mutate the
 * candidate list; the path always falls back to its base selection when the
 * strategy returns empty, so a role-specific preference can never stall movement.
 */
public interface CoverSelectionStrategy {
    Optional<CoverPoint> select(SoldierEntity soldier, List<CoverFinder.ScoredCover> candidates);
}

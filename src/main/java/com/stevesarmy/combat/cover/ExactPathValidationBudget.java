package com.stevesarmy.combat.cover;

import com.stevesarmy.StevesArmyConfig;

/** Limits exact candidate path checks within one cover-selection search. */
public final class ExactPathValidationBudget {
    private final int limit;
    private int used;

    public ExactPathValidationBudget() {
        this.limit = StevesArmyConfig.getExactPathValidationLimit();
    }

    public boolean tryAcquire() {
        if (used >= limit) return false;
        used++;
        return true;
    }

    public boolean hasRemaining() {
        return used < limit;
    }

    public int getUsed() {
        return used;
    }

    public int getLimit() {
        return limit;
    }

    public boolean isExhausted() {
        return used >= limit;
    }
}

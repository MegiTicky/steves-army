package com.stevesarmy.squad;

import javax.annotation.Nullable;

/**
 * Explicit friend/foe stance an owner can mark on a player, team, or entity
 * category. Stored stances always override the built-in default rules; an
 * absent override means "use the defaults".
 */
public enum FofStance {
    FRIENDLY,
    NEUTRAL,
    HOSTILE;

    public String getDisplayName() {
        return switch (this) {
            case FRIENDLY -> "Friendly";
            case NEUTRAL -> "Neutral";
            case HOSTILE -> "Hostile";
        };
    }

    @Nullable
    public static FofStance fromOrdinal(int ordinal) {
        FofStance[] values = values();
        if (ordinal < 0 || ordinal >= values.length) return null;
        return values[ordinal];
    }

    @Nullable
    public static FofStance fromName(String name) {
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}

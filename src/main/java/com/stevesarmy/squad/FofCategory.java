package com.stevesarmy.squad;

import javax.annotation.Nullable;

/**
 * Entity categories that can carry a FoF stance. Mirrors the target buckets
 * in SoldierCombatGoal; a stance here replaces the matching config toggle
 * (no stance = config default).
 */
public enum FofCategory {
    MONSTERS("monsters", "Monsters"),
    TARGET_DUMMIES("target_dummies", "Target Dummies");

    private final String serializedName;
    private final String displayName;

    FofCategory(String serializedName, String displayName) {
        this.serializedName = serializedName;
        this.displayName = displayName;
    }

    public String getSerializedName() {
        return serializedName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Nullable
    public static FofCategory fromName(String name) {
        for (FofCategory category : values()) {
            if (category.serializedName.equalsIgnoreCase(name)) return category;
        }
        return null;
    }
}

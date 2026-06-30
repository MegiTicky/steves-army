package com.stevesarmy.combat.cover;

public enum CoverType {
    NONE(0.0f, "No cover"),
    CONCEALMENT(0.2f, "Visual only"),
    HALF(0.5f, "Can shoot over"),
    FULL(1.0f, "Cannot shoot over");

    private final float baseQuality;
    private final String description;

    CoverType(float baseQuality, String description) {
        this.baseQuality = baseQuality;
        this.description = description;
    }

    public float getBaseQuality() {
        return baseQuality;
    }

    public String getDescription() {
        return description;
    }
}
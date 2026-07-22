package com.stevesarmy.squad;

public enum SquadFormation {
    NONE(0xFFFFFF, "None"),
    CQB(0xFF4444, "CQB");

    private final int color;
    private final String displayName;

    SquadFormation(int color, String displayName) {
        this.color = color;
        this.displayName = displayName;
    }

    public int getColor() {
        return color;
    }

    public String getDisplayName() {
        return displayName;
    }
}
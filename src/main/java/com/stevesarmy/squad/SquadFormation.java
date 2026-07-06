package com.stevesarmy.squad;

public enum SquadFormation {
    NONE(0xFFFFFF, "None"),
    LINE(0x55FF55, "Line"),
    WEDGE(0x55AAFF, "Wedge"),
    COLUMN(0xFFAA55, "Column"),
    DIAMOND(0xFF55FF, "Diamond");

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
package com.stevesarmy.squad;

public enum FireTeam {
    ALL,
    ALPHA,
    BRAVO,
    CHARLIE,
    DELTA;

    public String getShortName() {
        return switch (this) {
            case ALL -> "ALL";
            case ALPHA -> "A";
            case BRAVO -> "B";
            case CHARLIE -> "C";
            case DELTA -> "D";
        };
    }
}
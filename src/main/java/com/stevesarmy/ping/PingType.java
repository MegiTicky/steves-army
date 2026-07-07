package com.stevesarmy.ping;

public enum PingType {
    SEND(0xFF55AAAA, "send"),
    GO_TO(0xFF55FF55, "go_to"),
    THREAT_DIRECTION(0xFFFF8800, "threat_direction"),
    LOCATION(0xFF5555FF, "location"),
    FOLLOW(0xFF55FFFF, "follow"),
    HOLD(0xFFFFAA00, "hold");
    
    private final int color;
    private final String translationKey;
    
    PingType(int color, String translationKey) {
        this.color = color;
        this.translationKey = translationKey;
    }
    
    public int getColor() {
        return color;
    }
    
    public String getTranslationKey() {
        return "ping.steves_army." + translationKey;
    }
}

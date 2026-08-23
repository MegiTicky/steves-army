package com.stevesarmy.squad;

import com.stevesarmy.ping.PingType;

public enum SquadActivityType {
    HOLD("HOLD"),
    GO_TO("GO TO"),
    ATTACK("ATTACK"),
    SEND("SEND"),
    SUPPRESS_AREA("SUPPRESS"),
    THREAT_DIRECTION("THREAT");

    private final String displayName;

    SquadActivityType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static SquadActivityType fromPingType(PingType type) {
        return switch (type) {
            case HOLD -> HOLD;
            case GO_TO -> GO_TO;
            case ATTACK -> ATTACK;
            case SEND -> SEND;
            case SUPPRESS_AREA -> SUPPRESS_AREA;
            case THREAT_DIRECTION -> THREAT_DIRECTION;
            case FOLLOW, LOCATION -> null;
        };
    }
}

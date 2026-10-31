package com.stevesarmy.transport;

import java.util.Locale;

/** Actions on the vehicle page of the ping wheel. */
public enum TransportOrder {
    MOUNT,
    DISMOUNT,
    MOUNT_CREW,
    DISMOUNT_CREW,
    DRIVE_GO_TO,
    DRIVE_ATTACK,
    DRIVE_FOLLOW,
    DRIVE_HOLD;

    public String getTranslationKey() {
        return "transport.steves_army." + name().toLowerCase(Locale.ROOT);
    }
}

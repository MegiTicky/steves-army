package com.stevesarmy.transport;

import java.util.Locale;

/** Vehicle wheel actions: seat soldiers on a vehicle, release them from one, or board vehicle crew. */
public enum TransportOrder {
    MOUNT,
    DISMOUNT,
    MOUNT_CREW;

    public String getTranslationKey() {
        return "transport.steves_army." + name().toLowerCase(Locale.ROOT);
    }
}

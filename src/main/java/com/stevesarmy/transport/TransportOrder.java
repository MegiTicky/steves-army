package com.stevesarmy.transport;

import java.util.Locale;

/** Vehicle wheel actions: seat soldiers on a vehicle or release them from one. */
public enum TransportOrder {
    MOUNT,
    DISMOUNT;

    public String getTranslationKey() {
        return "transport.steves_army." + name().toLowerCase(Locale.ROOT);
    }
}

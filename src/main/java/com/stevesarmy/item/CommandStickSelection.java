package com.stevesarmy.item;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class CommandStickSelection {

    private static final Set<Integer> SELECTED_IDS = new HashSet<>();

    private CommandStickSelection() {
    }

    public static void toggle(int entityId) {
        if (!SELECTED_IDS.remove(entityId)) {
            SELECTED_IDS.add(entityId);
        }
    }

    public static void clear() {
        SELECTED_IDS.clear();
    }

    public static Set<Integer> getSelectedIds() {
        return Collections.unmodifiableSet(SELECTED_IDS);
    }
}

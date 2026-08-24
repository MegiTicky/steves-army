package com.stevesarmy.entity;

import net.minecraft.network.chat.Component;

/** Soldier roles. The entity type is authoritative over weapons: a machine gunner
 *  carrying a rifle still behaves as a machine gunner. */
public enum SoldierRole {
    RIFLEMAN,
    MACHINE_GUNNER,
    GARRISON;

    public Component getDisplayName() {
        return Component.translatable("role.steves_army." + name().toLowerCase());
    }
}

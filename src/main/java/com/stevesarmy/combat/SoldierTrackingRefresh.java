package com.stevesarmy.combat;

import net.minecraft.server.level.ServerPlayer;
import java.util.List;

/** Implemented by the vanilla tracked-entity mixin. */
public interface SoldierTrackingRefresh {
    void stevesArmy$refreshSoldierTracking(List<ServerPlayer> players);
}

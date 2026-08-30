package com.stevesarmy.item;

import com.stevesarmy.entity.GarrisonEntity;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.player.Player;

public class SurvivalCommandStickItem extends CommandStickItem {

    public SurvivalCommandStickItem(Properties properties) {
        super(properties, SurvivalCommandStickItem::canTarget);
    }

    private static boolean canTarget(SoldierEntity soldier, Player player) {
        return soldier instanceof GarrisonEntity && soldier.isOwnedBy(player);
    }
}

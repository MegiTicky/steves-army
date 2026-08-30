package com.stevesarmy.item;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.player.Player;

public class CreativeCommandStickItem extends CommandStickItem {

    public CreativeCommandStickItem(Properties properties) {
        super(properties, CreativeCommandStickItem::canTarget);
    }

    private static boolean canTarget(SoldierEntity soldier, Player player) {
        return player.getAbilities().instabuild;
    }
}

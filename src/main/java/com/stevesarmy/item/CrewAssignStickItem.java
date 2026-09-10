package com.stevesarmy.item;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import net.minecraft.world.entity.player.Player;

/**
 * Command stick variant dedicated to vehicle crew: selects owned crew soldiers, and
 * shift-right-clicking a Create/VSAW seat assigns the selection to that seat and the
 * ship's other free seats (see CrewSeatInteractHandler + CommandStickAssignCrewPacket).
 */
public class CrewAssignStickItem extends CommandStickItem {

    public CrewAssignStickItem(Properties properties) {
        super(properties, CrewAssignStickItem::canTarget);
    }

    private static boolean canTarget(SoldierEntity soldier, Player player) {
        return soldier.getRole() == SoldierRole.VEHICLE_CREW && soldier.isOwnedBy(player);
    }
}

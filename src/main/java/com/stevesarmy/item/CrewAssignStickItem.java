package com.stevesarmy.item;

import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.network.CommandStickAssignCrewPacket;
import com.stevesarmy.network.NetworkHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Command stick variant dedicated to vehicle crew: selects owned crew soldiers, and
 * shift-right-clicking a Create/VSAW seat assigns the selection to that seat and the
 * ship's other free seats (CommandStickAssignCrewPacket).
 *
 * Create SeatEntities are not pickable, so the click never reaches the entity interact
 * event; both {@link #use} and {@link #useOn} therefore raytrace the view ray for a
 * seat before falling through to the inherited reposition action.
 */
public class CrewAssignStickItem extends CommandStickItem {

    private static final double SEAT_REACH = 6.0;

    public CrewAssignStickItem(Properties properties) {
        super(properties, CrewAssignStickItem::canTarget);
    }

    private static boolean canTarget(SoldierEntity soldier, Player player) {
        return soldier.getRole() == SoldierRole.VEHICLE_CREW && soldier.isOwnedBy(player);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && level.isClientSide && tryAssignToSeat(player)) {
            return InteractionResultHolder.success(stack);
        }
        return super.use(level, player, hand);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (player != null && player.isShiftKeyDown() && level.isClientSide && tryAssignToSeat(player)) {
            return InteractionResult.SUCCESS;
        }
        return super.useOn(context);
    }

    /**
     * Client-side: seat under the crosshair? Sends the assignment packet and consumes
     * the click. Returns false when no seat is in reach so the base reposition runs.
     */
    private static boolean tryAssignToSeat(Player player) {
        Entity seat = findSeatAlongLook(player);
        if (seat == null) {
            return false;
        }
        List<Integer> selected = new ArrayList<>(CommandStickSelection.getSelectedIds());
        if (selected.isEmpty()) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("No crew selected"), true);
            return true;
        }
        NetworkHandler.INSTANCE.sendToServer(new CommandStickAssignCrewPacket(seat.getId(), selected));
        return true;
    }

    /** First Create/VSAW seat entity the view ray passes through within build reach. */
    public static Entity findSeatAlongLook(Player player) {
        return VS2Compat.findSeatAlongLook(player, SEAT_REACH);
    }
}

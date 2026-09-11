package com.stevesarmy.item;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.compat.VS2Compat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.network.CommandStickAssignCrewPacket;
import com.stevesarmy.network.NetworkHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Command stick variant dedicated to vehicle crew: selects owned crew soldiers, and
 * shift-right-clicking the ship anchors the assignment (CommandStickAssignCrewPacket)
 * on the aimed block — the soldier-flow way. Ships hold SeatBlocks, not pickable seat
 * entities (Create discards an empty seat's entity), so the click raytraces blocks with
 * VS2's ship-aware clip and the server seats crew on free SeatBlocks near the anchor.
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
        if (level.isClientSide) {
            StevesArmyMod.LOGGER.info("[CrewStick] use: shift={} hand={}", player.isShiftKeyDown(), hand);
        }
        if (player.isShiftKeyDown() && level.isClientSide && tryAssign(player)) {
            return InteractionResultHolder.success(stack);
        }
        return super.use(level, player, hand);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (player != null && level.isClientSide) {
            StevesArmyMod.LOGGER.info("[CrewStick] useOn: shift={}", player.isShiftKeyDown());
        }
        if (player != null && player.isShiftKeyDown() && level.isClientSide && tryAssign(player)) {
            return InteractionResult.SUCCESS;
        }
        return super.useOn(context);
    }

    /**
     * Client-side: ship under the crosshair? Sends the assignment packet anchored at
     * the aimed block and consumes the click. Returns false when nothing is in reach
     * so the base reposition runs.
     */
    private static boolean tryAssign(Player player) {
        Vec3 anchor = findAimAnchor(player);
        List<Integer> selected = new ArrayList<>(CommandStickSelection.getSelectedIds());
        StevesArmyMod.LOGGER.info("[CrewStick] assign click: anchor={} selected={}",
            anchor == null ? "none" : formatVec3(anchor), selected.size());
        if (anchor == null) {
            return false;
        }
        if (selected.isEmpty()) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("No crew selected"), true);
            return true;
        }
        NetworkHandler.INSTANCE.sendToServer(new CommandStickAssignCrewPacket(anchor, selected));
        return true;
    }

    /** World-space anchor: ship-aware block hit within reach, or null. */
    @Nullable
    public static Vec3 findAimAnchor(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(SEAT_REACH));
        return VS2Compat.getShipAwareBlockHitLocation(player.level(), eye, end, player);
    }

    private static String formatVec3(Vec3 v) {
        return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}

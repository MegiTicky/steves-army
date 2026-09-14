package com.stevesarmy.item;

import com.stevesarmy.StevesArmyMod;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
        if (player.isShiftKeyDown() && level.isClientSide && tryAssign(player, true)) {
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
        if (player != null && player.isShiftKeyDown() && level.isClientSide && tryAssign(player, true)) {
            return InteractionResult.SUCCESS;
        }
        return super.useOn(context);
    }

    /**
     * Client-side: ship under the crosshair? Sends the assignment packet anchored at
     * the aimed block and consumes the click. Returns false when nothing is in reach
     * so the base reposition runs.
     */
    /**
     * Sends an assignment request for the selected vehicle crew. The dedicated Crew
     * Assign Stick requires ownership; Creative Command Stick callers pass false.
     */
    public static boolean tryAssign(Player player, boolean requireOwnership) {
        BlockHitResult hit = findAimHit(player);
        Vec3 anchor = hit == null ? null : hit.getLocation();
        List<Integer> selected = selectedVehicleCrewIds(player, requireOwnership);
        StevesArmyMod.LOGGER.info("[CrewStick] assign click: anchor={} block={} selected={}",
            anchor == null ? "none" : formatVec3(anchor),
            hit == null ? "none" : hit.getBlockPos(), selected.size());
        if (hit == null) {
            return false;
        }
        if (selected.isEmpty()) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("No crew selected"), true);
            return true;
        }
        NetworkHandler.INSTANCE.sendToServer(
            new CommandStickAssignCrewPacket(anchor, hit.getBlockPos(), selected));
        return true;
    }

    /** Selected vehicle crew that the caller is permitted to target on the client. */
    public static List<Integer> selectedVehicleCrewIds(Player player, boolean requireOwnership) {
        List<Integer> crewIds = new ArrayList<>();
        for (int id : CommandStickSelection.getSelectedIds()) {
            if (player.level().getEntity(id) instanceof SoldierEntity soldier
                && soldier.getRole() == SoldierRole.VEHICLE_CREW
                && (!requireOwnership || soldier.isOwnedBy(player))) {
                crewIds.add(id);
            }
        }
        return crewIds;
    }

    /**
     * Crosshair block hit, exactly the ray {@code /vs get-ship} uses: the vanilla clip,
     * which VS2 makes ship-aware in world space. VS2 resolves the ship from the hit
     * block directly — no seat picking, no coordinate transforms.
     */
    @Nullable
    public static BlockHitResult findAimHit(Player player) {
        HitResult hit = player.pick(SEAT_REACH, 1.0F, false);
        return hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit
            ? blockHit : null;
    }

    private static String formatVec3(Vec3 v) {
        return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}

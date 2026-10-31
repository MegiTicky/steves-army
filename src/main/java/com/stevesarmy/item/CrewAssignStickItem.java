package com.stevesarmy.item;

import com.stevesarmy.StevesArmyMod;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
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
     * Sends an assignment request for the selected vehicle crew. The dedicated Crew
     * Assign Stick requires ownership; Creative Command Stick callers pass false.
     * SBW vehicles use an entity ray; VS2 ships keep the aimed block anchor.
     */
    public static boolean tryAssign(Player player, boolean requireOwnership) {
        return tryAssign(player, requireOwnership, false);
    }

    /** Creative Command Stick may board any selected soldier, including hostile infantry. */
    public static boolean tryAssign(Player player, boolean requireOwnership, boolean includeNonCrew) {
        EntityHitResult entityHit = findAimedVehicle(player);
        BlockHitResult hit = findAimHit(player);
        int vehicleEntityId = entityHit == null ? 0 : entityHit.getEntity().getId();
        Vec3 anchor = vehicleEntityId > 0
            ? entityHit.getEntity().position()
            : hit == null ? null : hit.getLocation();
        List<Integer> selected = includeNonCrew
            ? selectedSoldierIds(player, requireOwnership)
            : selectedVehicleCrewIds(player, requireOwnership);
        StevesArmyMod.LOGGER.info("[CrewStick] assign click: anchor={} block={} vehicle={} selected={}",
            anchor == null ? "none" : formatVec3(anchor),
            hit == null ? "none" : hit.getBlockPos(), vehicleEntityId, selected.size());
        if (hit == null && vehicleEntityId <= 0) {
            return false;
        }
        if (selected.isEmpty()) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("No crew selected"), true);
            return true;
        }
        if (vehicleEntityId > 0) {
            return sendVehicleAssignment(player, entityHit.getEntity(), selected);
        }
        NetworkHandler.INSTANCE.sendToServer(
            new CommandStickAssignCrewPacket(anchor,
                hit == null ? net.minecraft.core.BlockPos.containing(anchor) : hit.getBlockPos(),
                vehicleEntityId, selected));
        return true;
    }

    /** Direct entity-interaction path; do not throw away Forge's actual target and raycast again. */
    public static boolean tryAssignVehicle(Player player, Entity vehicle, boolean requireOwnership,
                                           boolean includeNonCrew) {
        if (!isAssignableVehicle(vehicle)) return false;
        List<Integer> selected = includeNonCrew
            ? selectedSoldierIds(player, requireOwnership)
            : selectedVehicleCrewIds(player, requireOwnership);
        if (selected.isEmpty()) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("No crew selected"), true);
            return true;
        }
        return sendVehicleAssignment(player, vehicle, selected);
    }

    /** Uses the clipped view ray to select an SBW vehicle, even when vanilla pick misses it. */
    public static boolean tryAssignAimedVehicle(Player player, boolean requireOwnership,
                                                boolean includeNonCrew) {
        EntityHitResult hit = findAimedVehicle(player);
        return hit != null && tryAssignVehicle(player, hit.getEntity(), requireOwnership, includeNonCrew);
    }

    private static boolean sendVehicleAssignment(Player player, Entity vehicle, List<Integer> selected) {
        if (!isAssignableVehicle(vehicle)) return false;
        Vec3 anchor = vehicle.position();
        NetworkHandler.INSTANCE.sendToServer(new CommandStickAssignCrewPacket(
            anchor, vehicle.blockPosition(), vehicle.getId(), selected));
        return true;
    }

    /** Selected soldiers permitted by the invoking tool; role filtering is applied by callers as needed. */
    public static List<Integer> selectedSoldierIds(Player player, boolean requireOwnership) {
        List<Integer> soldierIds = new ArrayList<>();
        for (int id : CommandStickSelection.getSelectedIds()) {
            if (player.level().getEntity(id) instanceof SoldierEntity soldier
                && (!requireOwnership || soldier.isOwnedBy(player))) {
                soldierIds.add(id);
            }
        }
        return soldierIds;
    }

    /** Operational Superb Warfare vehicle under the view ray, or {@code null}. */
    @Nullable
    private static EntityHitResult findAimedVehicle(Player player) {
        if (!com.stevesarmy.compat.SbwCompat.isEnabled()) return null;
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 view = player.getViewVector(1.0F);
        Vec3 end = eye.add(view.scale(SEAT_REACH));
        BlockHitResult block = player.level().clip(new ClipContext(
            eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 clippedEnd = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        double maxDistanceSqr = eye.distanceToSqr(clippedEnd);
        if (maxDistanceSqr < 1.0e-6) return null;

        AABB searchBox = player.getBoundingBox()
            .expandTowards(clippedEnd.subtract(eye)).inflate(1.0);
        return ProjectileUtil.getEntityHitResult(player, eye, clippedEnd, searchBox,
            entity -> entity.isPickable() && !entity.isSpectator() && isAssignableVehicle(entity),
            maxDistanceSqr);
    }

    private static boolean isAssignableVehicle(Entity entity) {
        return com.stevesarmy.compat.SbwCompat.isOperational(entity)
            && !com.stevesarmy.compat.sbw.SbwVehicles.isDrone(entity);
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

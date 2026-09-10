package com.stevesarmy.api;

import com.stevesarmy.transport.CrewAssignment;
import com.stevesarmy.compat.VS2Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Public integration surface for other mods (notably VS Analog Warfare's vehicle
 * setup block) to auto-board vehicle crew onto a vehicle.
 *
 * Call styles, most to least preferred:
 * <ol>
 *   <li><b>Compile dependency</b> — add the steves_army jar to your libs, guard calls
 *       with {@code ModList.get().isLoaded("steves_army")}, and invoke directly.</li>
 *   <li><b>Reflection</b> — one reflective call to
 *       {@code com.stevesarmy.api.VehicleCrewApi#onVehicleSetupCompleted}.</li>
 *   <li><b>IMC</b> — send at any time:
 *       {@code InterModComms.sendTo("steves_army", "vehicleSetupCompleted",
 *       () -> new Object[]{serverPlayer, level, blockPos})}. Note IMC messages are
 *       drained when Steve's Army processes them (startup lifecycle), so for
 *       in-gameplay triggers prefer styles 1 or 2.</li> * </ol>
 * Semantics: crew owned by the given player who are on foot, near the anchor, and not
 * manning a station are seated on the ship (VSAW handle links first, then free Create
 * seat blocks). They then claim the vehicle's stations automatically. Crew already
 * manning a station are never pulled off. Returns/side effects are purely beneficial;
 * the call is a no-op when VS2 is absent or no ship is found.
 */
public final class VehicleCrewApi {
    private VehicleCrewApi() {}

    /**
     * A vehicle setup finished at {@code setupBlockPos} (e.g. VSAW's vehicle setup
     * block finished replaying its actions). Boards nearby station-less crew owned by
     * {@code player} onto the ship at that position.
     */
    public static void onVehicleSetupCompleted(@Nullable ServerPlayer player, Level level, BlockPos setupBlockPos) {
        if (player == null || !(level instanceof ServerLevel serverLevel) || !VS2Compat.isEnabled()) {
            return;
        }
        Object ship = VS2Compat.getShipAt(serverLevel, setupBlockPos);
        if (ship == null) {
            return;
        }
        CrewAssignment.autoAssignNear(player, serverLevel, ship, Vec3.atCenterOf(setupBlockPos));
    }

    /**
     * General entry point: boards up to the nearby station-less crew owned by
     * {@code player} onto the ship at {@code anchor}. Returns the number seated.
     */
    public static int assignCrewToShip(@Nullable ServerPlayer player, Level level, Vec3 anchor, int maxCrew) {
        if (player == null || !(level instanceof ServerLevel serverLevel) || !VS2Compat.isEnabled()
            || maxCrew <= 0) {
            return 0;
        }
        Object ship = VS2Compat.getShipAt(serverLevel, BlockPos.containing(anchor));
        if (ship == null) {
            return 0;
        }
        return CrewAssignment.autoAssignNear(player, serverLevel, ship, anchor);
    }
}

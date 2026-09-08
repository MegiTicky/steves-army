package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.TransportOrderMessage;
import com.stevesarmy.transport.TransportOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Hover/selection logic for the vehicle wheel page. This wheel is never opened by its own
 * key: it is reached by pressing the cycle button while the ping wheel is open (see
 * WheelCycleController), and the ping wheel's release path calls {@link #fireSelected(Minecraft)}.
 */
public class VehicleWheelHandler {
    private static TransportOrder currentHoveredAction;

    static TransportOrder getHoveredAction() {
        Minecraft mc = Minecraft.getInstance();
        currentHoveredAction = determineActionFromMouse();
        return currentHoveredAction;
    }

    static void fireSelected(Minecraft mc) {
        TransportOrder action = getHoveredAction();
        if (action == null || mc.player == null) {
            return;
        }
        Vec3 aimPosition = PingWheelHandler.findCrosshairPosition(mc);
        if (aimPosition == null) {
            return;
        }
        StevesArmyMod.LOGGER.info("Vehicle wheel fired: {} at {}", action, aimPosition);
        NetworkHandler.INSTANCE.sendToServer(
            new TransportOrderMessage(action, aimPosition, FireTeamScopeState.INSTANCE.getCurrentScope()));
    }

    /** Center dead zone cancels; otherwise MOUNT is the right half, DISMOUNT the left half. */
    private static TransportOrder determineActionFromMouse() {
        double deltaX = PingWheelHandler.getDeltaX();
        double deltaY = PingWheelHandler.getDeltaY();

        double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        if (distance < 30) {
            return null;
        }

        double angle = Math.toDegrees(Math.atan2(deltaY, deltaX));
        double adjustedDegrees = angle + 90;
        if (adjustedDegrees < 0) adjustedDegrees += 360;
        if (adjustedDegrees >= 360) adjustedDegrees -= 360;

        int sector = ((int) (adjustedDegrees / 180)) % TransportOrder.values().length;
        return TransportOrder.values()[sector];
    }
}

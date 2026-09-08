package com.stevesarmy.mixins;

import com.stevesarmy.client.KeyBindings;
import com.stevesarmy.client.WheelCycleController;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla re-grabs a released mouse the moment any button is pressed (MouseHandler.onPress:
 * no screen + not grabbed + press = grabMouse). The hold-to-open wheels release the mouse,
 * so a click mid-session would re-grab it: the camera unlocks and the cursor is yanked to
 * screen center, wrecking the wheel selection. Swallow every mouse press while a wheel
 * session is open, except the wheel's own key.
 */
@Mixin(MouseHandler.class)
public class MouseHandlerWheelMixin {

    @Inject(method = "m_91530_", at = @At("HEAD"), cancellable = true)
    private void stevesarmy$suppressPressDuringWheel(long window, int button, int action, int mods, CallbackInfo ci) {
        if (action != GLFW.GLFW_PRESS || !WheelCycleController.isAnyHoldWheelActive()) {
            return;
        }
        if (KeyBindings.PING_WHEEL.matchesMouse(button)) {
            return;
        }
        ci.cancel();
    }
}

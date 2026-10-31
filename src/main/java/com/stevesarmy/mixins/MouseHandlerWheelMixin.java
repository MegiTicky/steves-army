package com.stevesarmy.mixins;

import com.stevesarmy.client.WheelInteractionController;
import com.stevesarmy.client.WheelCycleController;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Consume wheel clicks before vanilla re-grabs the cursor or queues gameplay input. */
@Mixin(MouseHandler.class)
public class MouseHandlerWheelMixin {
    @Unique private final boolean[] stevesarmy$consumedButtons = new boolean[GLFW.GLFW_MOUSE_BUTTON_LAST + 1];

    @Inject(method = "m_91530_", at = @At("HEAD"), cancellable = true)
    private void stevesarmy$suppressPressDuringWheel(long window, int button, int action, int mods, CallbackInfo ci) {
        if (button < 0 || button >= stevesarmy$consumedButtons.length) return;
        boolean active = WheelCycleController.isAnyHoldWheelActive();
        boolean opener = WheelInteractionController.isOpenerMouse(button);
        if (action == GLFW.GLFW_RELEASE && stevesarmy$consumedButtons[button]) {
            stevesarmy$consumedButtons[button] = false;
            if (opener) WheelInteractionController.setOpenerMouseDown(button, false);
            ci.cancel();
            return;
        }
        if (action != GLFW.GLFW_PRESS) return;
        if (opener && (active || (WheelInteractionController.isClickMode() && WheelInteractionController.canOpen()))) {
            // Update only wheel bindings: vanilla would also recapture the cursor or pick a block.
            WheelInteractionController.queueMousePress(button);
            WheelInteractionController.setOpenerMouseDown(button, true);
            stevesarmy$consumedButtons[button] = true;
            ci.cancel();
            return;
        }
        if (!active) return;
        stevesarmy$consumedButtons[button] = true;
        if (WheelInteractionController.isClickMode() && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            WheelInteractionController.confirmClick();
        }
        WheelCycleController.onMouseButtonPressed(button);
        ci.cancel();
    }
}

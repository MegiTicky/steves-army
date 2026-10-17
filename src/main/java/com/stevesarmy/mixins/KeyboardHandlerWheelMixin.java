package com.stevesarmy.mixins;

import com.stevesarmy.client.WheelInteractionController;
import net.minecraft.client.KeyboardHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardHandlerWheelMixin {
    @Inject(method = "m_90893_", at = @At("HEAD"), cancellable = true)
    private void stevesarmy$cancelWheel(long window, int key, int scanCode, int action, int mods, CallbackInfo ci) {
        if (action == GLFW.GLFW_PRESS) WheelInteractionController.queueKeyboardPress(key, scanCode);
        if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS
            && WheelInteractionController.onEscape()) ci.cancel();
    }
}

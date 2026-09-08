package com.stevesarmy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Multi-page state for the hold-to-open wheels. The middle-mouse ping session has two pages:
 * the ping orders wheel (default) and the vehicle wheel. Pressing the configured cycle mouse
 * button (right-click by default) while the wheel is open flips between them; releasing the
 * wheel key fires the hovered action of the current page.
 */
public final class WheelCycleController {
    public enum Page {
        PING_ORDERS,
        VEHICLE
    }

    public enum CycleButton {
        LEFT(GLFW.GLFW_MOUSE_BUTTON_LEFT, "wheel.steves_army.switch_hint_lmb"),
        RIGHT(GLFW.GLFW_MOUSE_BUTTON_RIGHT, "wheel.steves_army.switch_hint_rmb");

        public final int glfwButton;
        public final String hintKey;

        CycleButton(int glfwButton, String hintKey) {
            this.glfwButton = glfwButton;
            this.hintKey = hintKey;
        }
    }

    private static boolean wasCycleButtonDown = false;
    private static Page page = Page.PING_ORDERS;

    private WheelCycleController() {}

    /** True while any hold-to-open wheel session has the mouse released. */
    public static boolean isAnyHoldWheelActive() {
        return PingWheelHandler.isWheelActive()
            || FormationWheelHandler.isWheelActive()
            || FireTeamWheelHandler.isWheelActive();
    }

    /** Called when the ping wheel session opens; the default page is the ping orders wheel. */
    public static void onSessionActivated() {
        page = Page.PING_ORDERS;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        int cycleButton = StevesArmyClientConfig.VEHICLE_WHEEL_CYCLE_BUTTON.get().glfwButton;
        boolean cycleButtonDown = GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), cycleButton) == GLFW.GLFW_PRESS;
        boolean pressed = cycleButtonDown && !wasCycleButtonDown;
        wasCycleButtonDown = cycleButtonDown;

        if (!PingWheelHandler.isWheelActive()) {
            page = Page.PING_ORDERS;
            return;
        }
        if (!StevesArmyClientConfig.ENABLE_VEHICLE_WHEEL.get()) {
            return;
        }
        if (pressed) {
            page = page == Page.PING_ORDERS ? Page.VEHICLE : Page.PING_ORDERS;
        }
    }

    public static boolean isVehiclePage() {
        return page == Page.VEHICLE;
    }

    public static Page getPage() {
        return page;
    }

    /**
     * Page title above the wheel plus the cycling hint, drawn by whichever page is
     * currently displayed so players can discover the vehicle page.
     */
    public static void drawPageHeader(GuiGraphics guiGraphics, Component pageTitle) {
        Minecraft mc = Minecraft.getInstance();
        int centerX = mc.getWindow().getGuiScaledWidth() / 2;
        int headerY = mc.getWindow().getGuiScaledHeight() / 2 - 96;
        guiGraphics.drawCenteredString(mc.font, pageTitle, centerX, headerY, 0xFFFFFFFF);
        if (StevesArmyClientConfig.ENABLE_VEHICLE_WHEEL.get()) {
            guiGraphics.drawCenteredString(mc.font,
                Component.translatable(StevesArmyClientConfig.VEHICLE_WHEEL_CYCLE_BUTTON.get().hintKey),
                centerX, headerY + mc.font.lineHeight, 0xFF888888);
        }
    }
}

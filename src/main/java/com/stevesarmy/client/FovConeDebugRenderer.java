package com.stevesarmy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.stevesarmy.combat.DetectionSystem;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Draws every soldier's focused detection arc — the {@link DetectionSystem#FOCUSED_ARC_DEGREES}
 * wedge the detection pipeline actually classifies targets with: same head-yaw
 * axis ({@code -yaw} convention), same range (basic/MG detection distance),
 * projected on the ground plane. The wedge rotates live with the head, so a
 * soldier swinging his aim drags the cone with the animation. Peripheral
 * (180°) bounds are drawn as a faint outline. Visible through walls on
 * purpose: for CQB observation the enemy cone inside the room is the point.
 */
public class FovConeDebugRenderer {

    private static boolean enabled = false;
    /** Arc segments per 90° wedge — coarse enough to stay cheap, smooth enough to read. */
    private static final int ARC_SEGMENTS = 20;
    private static final double CULL_DISTANCE_SQR = 128 * 128;

    public static void setEnabled(boolean e) {
        enabled = e;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void render(PoseStack poseStack, Camera camera) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 cameraPos = camera.getPosition();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        Matrix4f matrix = poseStack.last().pose();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof SoldierEntity soldier)) continue;
            double dx = soldier.getX() - cameraPos.x;
            double dz = soldier.getZ() - cameraPos.z;
            if (dx * dx + dz * dz > CULL_DISTANCE_SQR) continue;

            boolean enemy = soldier instanceof EnemySoldierEntity;
            int r = enemy ? 255 : 90;
            int g = enemy ? 80 : 255;
            int b = enemy ? 80 : 130;
            double groundY = soldier.getY() + 0.05 - cameraPos.y;
            double cx = soldier.getX() - cameraPos.x;
            double cz = soldier.getZ() - cameraPos.z;

            // Same axis the detection arc test uses: look = (sin(-yaw), cos(-yaw)).
            double yawRad = Math.toRadians(-soldier.getYHeadRot());
            double lx = Math.sin(yawRad);
            double lz = Math.cos(yawRad);

            double focusedRange = DetectionSystem.getFocusedRangeFor(soldier);
            if (soldier instanceof MachineGunnerEntity) {
                // Distinguish the longer MG cone with a brighter edge.
                b = 220;
            }

            // Focused wedge fill: triangle fan from the body center.
            BufferBuilder buffer = tesselator.getBuilder();
            buffer.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
            buffer.vertex(matrix, (float) cx, (float) groundY, (float) cz).color(r, g, b, 42).endVertex();
            double start = -Math.PI / 4;
            double span = Math.PI / 2;
            for (int i = 0; i <= ARC_SEGMENTS; i++) {
                double a = start + span * i / ARC_SEGMENTS;
                double px = cx + rotX(lx, lz, a) * focusedRange;
                double pz = cz + rotZ(lx, lz, a) * focusedRange;
                buffer.vertex(matrix, (float) px, (float) groundY, (float) pz).color(r, g, b, 42).endVertex();
            }
            tesselator.end();

            // Crisp edges: the two sides, the arc, and a short bright facing tick.
            buffer = tesselator.getBuilder();
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            double endLx = cx + rotX(lx, lz, start) * focusedRange;
            double endLz = cz + rotZ(lx, lz, start) * focusedRange;
            double endRx = cx + rotX(lx, lz, start + span) * focusedRange;
            double endRz = cz + rotZ(lx, lz, start + span) * focusedRange;
            line(buffer, matrix, cx, cz, groundY, endLx, endLz, r, g, b, 200);
            line(buffer, matrix, cx, cz, groundY, endRx, endRz, r, g, b, 200);
            double prevX = endLx;
            double prevZ = endLz;
            for (int i = 1; i <= ARC_SEGMENTS; i++) {
                double a = start + span * i / ARC_SEGMENTS;
                double ax = cx + rotX(lx, lz, a) * focusedRange;
                double az = cz + rotZ(lx, lz, a) * focusedRange;
                line(buffer, matrix, prevX, prevZ, groundY, ax, az, r, g, b, 200);
                prevX = ax;
                prevZ = az;
            }
            double tickX = cx + lx * 1.2;
            double tickZ = cz + lz * 1.2;
            line(buffer, matrix, cx, cz, groundY + 0.02, tickX, tickZ, 255, 255, 255, 220);
            tesselator.end();

            // Peripheral arc outline (180°): every other segment, faint.
            double peripheralRange = DetectionSystem.PERIPHERAL_RANGE;
            buffer = tesselator.getBuilder();
            buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            double pStart = -Math.PI / 2;
            double pSpan = Math.PI;
            prevX = cx + rotX(lx, lz, pStart) * peripheralRange;
            prevZ = cz + rotZ(lx, lz, pStart) * peripheralRange;
            for (int i = 2; i <= 8; i += 2) {
                double a = pStart + pSpan * i / 8;
                double ax = cx + rotX(lx, lz, a) * peripheralRange;
                double az = cz + rotZ(lx, lz, a) * peripheralRange;
                line(buffer, matrix, prevX, prevZ, groundY, ax, az, r, g, b, 55);
                prevX = ax;
                prevZ = az;
            }
            tesselator.end();
        }

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void line(BufferBuilder buffer, Matrix4f matrix,
                             double x1, double z1, double y,
                             double x2, double z2,
                             int r, int g, int b, int a) {
        buffer.vertex(matrix, (float) x1, (float) y, (float) z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, (float) x2, (float) y, (float) z2).color(r, g, b, a).endVertex();
    }

    /** Rotates the (x, z) look vector by angle a around the Y axis. */
    private static double rotX(double x, double z, double a) {
        return x * Math.cos(a) - z * Math.sin(a);
    }

    private static double rotZ(double x, double z, double a) {
        return x * Math.sin(a) + z * Math.cos(a);
    }
}

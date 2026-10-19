package com.stevesarmy.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;

/** Splits leg mesh quads at the knee and routes each half through its own pose. */
public final class KneeArmorVertexConsumer implements VertexConsumer {
    private static final float KNEE_Y = 6.0F / 16.0F;
    private final VertexConsumer delegate;
    private final PoseStack.Pose upperPose;
    private final PoseStack.Pose lowerPose;
    private final Vertex[] quad = new Vertex[4];
    private final int[] defaultColor = {255, 255, 255, 255};
    private int vertexCount;

    public KneeArmorVertexConsumer(VertexConsumer delegate, PoseStack.Pose upperPose, PoseStack.Pose lowerPose) {
        this.delegate = delegate;
        this.upperPose = upperPose;
        this.lowerPose = lowerPose;
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        if (vertexCount == quad.length) throw new IllegalStateException("Quad already has four vertices");
        quad[vertexCount++] = new Vertex((float) x, (float) y, (float) z, defaultColor[0], defaultColor[1],
            defaultColor[2], defaultColor[3], 0, 0, 0, 0, 0, 0, 0, 0, 0,
            false, false, false, false, false);
        return this;
    }

    @Override
    public VertexConsumer color(int r, int g, int b, int a) {
        Vertex v = current();
        v.r = r; v.g = g; v.b = b; v.a = a; v.hasColor = true;
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        Vertex vertex = current();
        vertex.u = u; vertex.v = v; vertex.hasUv = true;
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        Vertex vertex = current();
        vertex.overlayU = u; vertex.overlayV = v; vertex.hasOverlay = true;
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        Vertex vertex = current();
        vertex.lightU = u; vertex.lightV = v; vertex.hasLight = true;
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        Vertex vertex = current();
        vertex.nx = x; vertex.ny = y; vertex.nz = z; vertex.hasNormal = true;
        return this;
    }

    @Override
    public void endVertex() {
        if (vertexCount < 4) return;
        boolean allOnPlane = true;
        for (Vertex vertex : quad) allOnPlane &= vertex.y == KNEE_Y;
        if (allOnPlane) {
            emit(clip(false), upperPose);
        } else {
            emit(clip(false), upperPose);
            emit(clip(true), lowerPose);
        }
        vertexCount = 0;
    }

    @Override
    public void defaultColor(int r, int g, int b, int a) {
        defaultColor[0] = r; defaultColor[1] = g; defaultColor[2] = b; defaultColor[3] = a;
        delegate.defaultColor(r, g, b, a);
    }

    @Override
    public void unsetDefaultColor() {
        defaultColor[0] = defaultColor[1] = defaultColor[2] = defaultColor[3] = 255;
        delegate.unsetDefaultColor();
    }

    private Vertex current() {
        if (vertexCount == 0) throw new IllegalStateException("Vertex attributes must follow vertex()");
        return quad[vertexCount - 1];
    }

    private List<Vertex> clip(boolean lower) {
        List<Vertex> output = new ArrayList<>(6);
        Vertex previous = quad[3];
        boolean previousInside = inside(previous, lower);
        for (Vertex current : quad) {
            boolean currentInside = inside(current, lower);
            if (currentInside != previousInside) output.add(intersection(previous, current));
            if (currentInside) output.add(current);
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    private static boolean inside(Vertex vertex, boolean lower) {
        return lower ? vertex.y >= KNEE_Y : vertex.y <= KNEE_Y;
    }

    private static Vertex intersection(Vertex a, Vertex b) {
        float t = (KNEE_Y - a.y) / (b.y - a.y);
        return Vertex.interpolate(a, b, t, KNEE_Y);
    }

    private void emit(List<Vertex> polygon, PoseStack.Pose pose) {
        if (polygon.size() < 3) return;
        for (int i = 1; i + 1 < polygon.size(); i++) {
            Vertex a = polygon.get(0), b = polygon.get(i), c = polygon.get(i + 1);
            put(pose, a); put(pose, b); put(pose, c); put(pose, c);
        }
    }

    private void put(PoseStack.Pose pose, Vertex vertex) {
        Vector3f position = pose.pose().transformPosition(vertex.x, vertex.y, vertex.z, new Vector3f());
        Vector3f normal = pose.normal().transform(vertex.nx, vertex.ny, vertex.nz, new Vector3f());
        float lengthSquared = normal.lengthSquared();
        if (lengthSquared > 1.0E-12F && Float.isFinite(lengthSquared)) normal.mul((float) (1.0 / Math.sqrt(lengthSquared)));
        else normal.zero();

        delegate.vertex(position.x(), position.y(), position.z());
        if (vertex.hasColor) delegate.color(vertex.r, vertex.g, vertex.b, vertex.a);
        if (vertex.hasUv) delegate.uv(vertex.u, vertex.v);
        if (vertex.hasOverlay) delegate.overlayCoords(vertex.overlayU, vertex.overlayV);
        if (vertex.hasLight) delegate.uv2(vertex.lightU, vertex.lightV);
        if (vertex.hasNormal) delegate.normal(normal.x(), normal.y(), normal.z());
        delegate.endVertex();
    }

    private static final class Vertex {
        final float x, y, z;
        int r, g, b, a, overlayU, overlayV, lightU, lightV;
        float u, v, nx, ny, nz;
        boolean hasColor, hasUv, hasOverlay, hasLight, hasNormal;

        Vertex(float x, float y, float z, int r, int g, int b, int a, float u, float v,
               int overlayU, int overlayV, int lightU, int lightV, float nx, float ny, float nz,
               boolean hasColor, boolean hasUv, boolean hasOverlay, boolean hasLight, boolean hasNormal) {
            this.x = x; this.y = y; this.z = z;
            this.r = r; this.g = g; this.b = b; this.a = a;
            this.u = u; this.v = v; this.overlayU = overlayU; this.overlayV = overlayV;
            this.lightU = lightU; this.lightV = lightV; this.nx = nx; this.ny = ny; this.nz = nz;
            this.hasColor = hasColor; this.hasUv = hasUv; this.hasOverlay = hasOverlay;
            this.hasLight = hasLight; this.hasNormal = hasNormal;
        }

        static Vertex interpolate(Vertex a, Vertex b, float t, float y) {
            Vertex v = new Vertex(lerp(a.x, b.x, t), y, lerp(a.z, b.z, t),
                blend(a.r, b.r, t), blend(a.g, b.g, t), blend(a.b, b.b, t), blend(a.a, b.a, t),
                lerp(a.u, b.u, t), lerp(a.v, b.v, t), blend(a.overlayU, b.overlayU, t),
                blend(a.overlayV, b.overlayV, t), blend(a.lightU, b.lightU, t), blend(a.lightV, b.lightV, t),
                lerp(a.nx, b.nx, t), lerp(a.ny, b.ny, t), lerp(a.nz, b.nz, t),
                a.hasColor && b.hasColor, a.hasUv && b.hasUv, a.hasOverlay && b.hasOverlay,
                a.hasLight && b.hasLight, a.hasNormal && b.hasNormal);
            return v;
        }

        private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
        private static int blend(int a, int b, float t) { return Math.round(lerp(a, b, t)); }
    }
}

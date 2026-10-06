package com.stevesarmy.sim;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.LivingEntity;

/**
 * Physical cover-fidelity probe for the sim harness: casts block rays from a
 * threat origin to sample points across the soldier's actual server-side
 * bounding box and counts how many body points a shooter can see. This is the
 * ground truth behind "is the soldier actually IN cover" — the state machine
 * may claim IN_COVER while the pose, the docking offset, or the cover geometry
 * still leaves the hitbox exposed.
 *
 * Point set: 3 height bands (feet, mid, head) x (4 inset corners + center).
 * A healthy low-crouch (0.6-high box) behind a waist-high wall probes 0/15.
 */
public final class CoverFidelityProbe {
    private CoverFidelityProbe() {}

    public record Exposures(int probed, int exposed, int exposedHead) {}

    public static Exposures probe(ServerLevel level, LivingEntity soldier, Vec3 threatEye) {
        AABB bb = soldier.getBoundingBox();
        double height = bb.getYsize();
        double inset = 0.05;
        double[] ys = {bb.minY + 0.05, bb.minY + height * 0.5, bb.maxY - 0.05};
        double cx = bb.getCenter().x;
        double cz = bb.getCenter().z;
        double[] xs = {bb.minX + inset, cx, bb.maxX - inset};
        double[] zs = {bb.minZ + inset, cz, bb.maxZ - inset};

        int probed = 0;
        int exposed = 0;
        int exposedHead = 0;
        for (int band = 0; band < ys.length; band++) {
            double y = ys[band];
            for (int i = 0; i < xs.length; i++) {
                for (int j = 0; j < zs.length; j++) {
                    // center + 4 corners only (skip edge midpoints) = 5 points per band
                    boolean centerish = (i == 1) == (j == 1);
                    if (!centerish) continue;
                    probed++;
                    Vec3 point = new Vec3(xs[i], y, zs[j]);
                    HitResult hit = level.clip(new ClipContext(
                        threatEye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, soldier));
                    if (hit.getType() == HitResult.Type.MISS) {
                        exposed++;
                        if (band == ys.length - 1) exposedHead++;
                    }
                }
            }
        }
        return new Exposures(probed, exposed, exposedHead);
    }
}

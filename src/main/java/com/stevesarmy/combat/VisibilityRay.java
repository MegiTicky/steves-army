package com.stevesarmy.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared block-aware visibility traversal for soldier vision and firing checks. */
public final class VisibilityRay {
    private static final double EPSILON = 1.0e-7;
    private static final double MAX_CONCEALMENT = 1.0;
    private static final double SMOKE_SEARCH_INFLATE = 12.0;

    /** Lazily-resolved smoke emitter type from Create Big Cannons. Null if CBC is not loaded. */
    private static EntityType<?> smokeEmitterType;

    private VisibilityRay() {}

    private static EntityType<?> getSmokeEmitterType() {
        if (smokeEmitterType == null) {
            EntityType<?> resolved = BuiltInRegistries.ENTITY_TYPE.get(
                new ResourceLocation("createbigcannons", "smoke_emitter"));
            smokeEmitterType = resolved != BuiltInRegistries.ENTITY_TYPE.get(
                new ResourceLocation("air")) ? resolved : null;
        }
        return smokeEmitterType;
    }

    public record Result(boolean clear, double concealment, double blockedDistance) {
        public boolean hasContact() {
            return clear && concealment < MAX_CONCEALMENT;
        }

        public double spottingMultiplier() {
            // One wheat block is still visible, but it should not permit rapid
            // target acquisition. Two wheat blocks reach full concealment.
            return Math.max(0.05, 1.0 - 1.6 * concealment);
        }
    }

    public static Result trace(Level level, Vec3 from, Vec3 to, LivingEntity observer) {
        return trace(level, from, to, observer, Set.of());
    }

    public static Result trace(Level level, Vec3 from, Vec3 to, LivingEntity observer,
                               BlockPos... ignoredBlocks) {
        Set<BlockPos> ignored = new HashSet<>();
        for (BlockPos ignoredBlock : ignoredBlocks) {
            if (ignoredBlock != null) {
                ignored.add(ignoredBlock);
            }
        }
        return trace(level, from, to, observer, ignored);
    }

    private static Result trace(Level level, Vec3 from, Vec3 to, LivingEntity observer,
                                Set<BlockPos> ignoredBlocks) {
        if (from.distanceToSqr(to) < EPSILON) {
            return new Result(true, 0.0, Double.POSITIVE_INFINITY);
        }

        Vec3 direction = to.subtract(from);
        double length = direction.length();
        Vec3 unit = direction.scale(1.0 / length);
        Set<BlockPos> visited = new HashSet<>();
        double concealment = 0.0;

        int x = floor(from.x);
        int y = floor(from.y);
        int z = floor(from.z);
        int endX = floor(to.x);
        int endY = floor(to.y);
        int endZ = floor(to.z);

        double t = 0.0;
        while (t <= length + EPSILON) {
            BlockPos pos = new BlockPos(x, y, z);
            if (visited.add(pos)) {
                BlockState state = level.getBlockState(pos);
                if (ignoredBlocks.contains(pos) || isTransparent(state)) {
                    // Cover-peek callers explicitly ignore the cover block. Glass is
                    // transparent both to the AI and to TaCZ through its block tag.
                } else if (isConcealment(state)) {
                    if (outlineIntersectsRay(level, state, pos, from, to)) {
                        concealment = Math.min(MAX_CONCEALMENT, concealment + concealmentWeight(state));
                    }
                } else if (intersectsBlock(level, state, pos, from, to)) {
                    BlockHitResult hit = blockHit(level, state, pos, from, to);
                    double blockedDistance = hit == null
                        ? from.distanceTo(to)
                        : from.distanceTo(hit.getLocation());
                    return new Result(false, concealment, blockedDistance);
                }
            }

            if (x == endX && y == endY && z == endZ) {
                break;
            }

            double nextX = nextBoundary(from.x, unit.x, x);
            double nextY = nextBoundary(from.y, unit.y, y);
            double nextZ = nextBoundary(from.z, unit.z, z);
            double next = Math.min(nextX, Math.min(nextY, nextZ));
            if (next == Double.POSITIVE_INFINITY) {
                break;
            }

            if (nextX <= next + EPSILON) x += step(unit.x);
            if (nextY <= next + EPSILON) y += step(unit.y);
            if (nextZ <= next + EPSILON) z += step(unit.z);
            t = next;
        }

        // Check for smoke clouds from Create Big Cannons (or Small Arms smoke grenades).
        // Smoke is visually opaque, so any intersection fully blocks vision.
        EntityType<?> smokeType = getSmokeEmitterType();
        if (smokeType != null) {
            AABB rayBounds = new AABB(from, to).inflate(SMOKE_SEARCH_INFLATE);
            List<? extends Entity> smokeClouds = level.getEntities(
                (Entity) null, rayBounds, e -> e.getType() == smokeType && e.isAlive());
            for (Entity cloud : smokeClouds) {
                if (cloud.getBoundingBox().intersects(from, to)) {
                    double blocked = from.distanceTo(cloud.position());
                    return new Result(false, MAX_CONCEALMENT, blocked);
                }
            }
        }

        return new Result(true, concealment, Double.POSITIVE_INFINITY);
    }

    private static boolean intersectsBlock(Level level, BlockState state, BlockPos pos,
                                           Vec3 from, Vec3 to) {
        return blockHit(level, state, pos, from, to) != null;
    }

    private static BlockHitResult blockHit(Level level, BlockState state, BlockPos pos,
                                           Vec3 from, Vec3 to) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return null;
        }

        BlockHitResult hit = shape.clip(from, to, pos);
        return hit != null && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private static boolean outlineIntersectsRay(Level level, BlockState state, BlockPos pos,
                                                Vec3 from, Vec3 to) {
        // Crops have an empty collision shape but an age-dependent outline shape.
        // This makes concealment depend on whether the actual plant reaches the ray.
        VoxelShape shape = state.getShape(level, pos);
        return !shape.isEmpty() && shape.clip(from, to, pos) != null;
    }

    private static boolean isTransparent(BlockState state) {
        return state.is(ModBlockTags.TRANSPARENT_PENETRABLE);
    }

    private static boolean isConcealment(BlockState state) {
        return state.is(ModBlockTags.VISION_CONCEALMENT);
    }

    private static double concealmentWeight(BlockState state) {
        if (state.is(ModBlockTags.VISION_CONCEALMENT_MEDIUM)) return 0.50;
        // Direct additions to the aggregate tag are intentionally conservative.
        return 0.35;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static int step(double component) {
        return component > 0.0 ? 1 : -1;
    }

    private static double nextBoundary(double coordinate, double direction, int block) {
        if (Math.abs(direction) < EPSILON) return Double.POSITIVE_INFINITY;
        double boundary = direction > 0.0 ? block + 1.0 : block;
        return (boundary - coordinate) / direction;
    }
}

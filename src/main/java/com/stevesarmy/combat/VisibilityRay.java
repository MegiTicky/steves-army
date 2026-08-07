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
import java.util.Optional;
import java.util.Set;

public final class VisibilityRay {
    private static final double EPSILON = 1.0e-7;
    private static final double MAX_CONCEALMENT = 1.0;

    private static boolean smokeTypeResolved;
    private static EntityType<?> smokeEmitterType;

    private VisibilityRay() {}

    public enum SmokePolicy { BLOCK, IGNORE }

    public record Result(boolean clear, double concealment, double blockedDistance) {
        public boolean hasContact() {
            return clear && concealment < MAX_CONCEALMENT;
        }

        public double spottingMultiplier() {
            return Math.max(0.05, 1.0 - 1.6 * concealment);
        }
    }

    public static Result trace(Level level, Vec3 from, Vec3 to, LivingEntity observer) {
        return trace(level, from, to, observer, SmokePolicy.BLOCK, Set.of());
    }

    public static Result trace(Level level, Vec3 from, Vec3 to, LivingEntity observer,
                               BlockPos... ignoredBlocks) {
        Set<BlockPos> ignored = new HashSet<>();
        for (BlockPos ignoredBlock : ignoredBlocks) {
            if (ignoredBlock != null) {
                ignored.add(ignoredBlock);
            }
        }
        return trace(level, from, to, observer, SmokePolicy.BLOCK, ignored);
    }

    public static Result traceIgnoringSmoke(Level level, Vec3 from, Vec3 to, LivingEntity observer) {
        return trace(level, from, to, observer, SmokePolicy.IGNORE, Set.of());
    }

    private static Result trace(Level level, Vec3 from, Vec3 to, LivingEntity observer,
                                SmokePolicy smokePolicy, Set<BlockPos> ignoredBlocks) {
        if (from.distanceToSqr(to) < EPSILON) {
            return new Result(true, 0.0, Double.POSITIVE_INFINITY);
        }

        Vec3 direction = to.subtract(from);
        double length = direction.length();
        Vec3 unit = direction.scale(1.0 / length);
        Set<BlockPos> visited = new HashSet<>();
        double concealment = 0.0;
        double nearestObstruction = Double.POSITIVE_INFINITY;

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
                    double blocked = hit == null ? from.distanceTo(to) : from.distanceTo(hit.getLocation());
                    nearestObstruction = Math.min(nearestObstruction, blocked);
                    // If smoke is blocking, we still need to find the nearest obstruction.
                    // If smoke is ignored, we return immediately on solid block hit.
                    if (smokePolicy == SmokePolicy.IGNORE) {
                        return new Result(false, concealment, blocked);
                    }
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

        // Check for smoke clouds when smoke is not ignored.
        if (smokePolicy == SmokePolicy.BLOCK) {
            double smokeEntry = findSmokeIntersection(level, from, to);
            if (smokeEntry >= 0) {
                // If smoke is closer than nearestObstruction, the smoke wins.
                if (smokeEntry < nearestObstruction) {
                    return new Result(false, MAX_CONCEALMENT, smokeEntry);
                }
                // Otherwise the solid block is closer, so report that.
                return new Result(false, concealment, nearestObstruction);
            }
        }

        if (nearestObstruction < Double.POSITIVE_INFINITY) {
            return new Result(false, concealment, nearestObstruction);
        }

        return new Result(true, concealment, Double.POSITIVE_INFINITY);
    }

    private static double findSmokeIntersection(Level level, Vec3 from, Vec3 to) {
        EntityType<?> type = getSmokeEmitterType();
        if (type == null) {
            return -1;
        }
        // Use a tight search box: the ray's AABB inflated by a tiny margin.
        AABB rayBounds = new AABB(from, to).inflate(EPSILON);
        List<? extends Entity> clouds = level.getEntities(
            (Entity) null, rayBounds, e -> e.getType() == type && e.isAlive());
        double nearest = Double.POSITIVE_INFINITY;
        for (Entity cloud : clouds) {
            Optional<Vec3> hit = cloud.getBoundingBox().clip(from, to);
            if (hit.isPresent()) {
                double entryDist = from.distanceTo(hit.get());
                if (entryDist < nearest) {
                    nearest = entryDist;
                }
            }
        }
        return nearest < Double.POSITIVE_INFINITY ? nearest : -1;
    }

    private static EntityType<?> getSmokeEmitterType() {
        if (!smokeTypeResolved) {
            smokeTypeResolved = true;
            EntityType<?> resolved = BuiltInRegistries.ENTITY_TYPE.get(
                new ResourceLocation("createbigcannons", "smoke_emitter"));
            smokeEmitterType = resolved != BuiltInRegistries.ENTITY_TYPE.get(
                new ResourceLocation("air")) ? resolved : null;
        }
        return smokeEmitterType;
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
        if (state.is(ModBlockTags.VISION_CONCEALMENT_MEDIUM)) return 0.30;
        return 0.20;
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

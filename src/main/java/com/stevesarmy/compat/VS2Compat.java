package com.stevesarmy.compat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.combat.StationGunnerAI;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SoldierRole;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Optional VS2 integration. Ships are transport, never terrain for soldier AI. */
public final class VS2Compat {
    private static final String VS2_MOD_ID = "valkyrienskies";
    private static final String VS_UTILS_CLASS = "org.valkyrienskies.mod.common.VSGameUtilsKt";
    private static final String CONTRAPTION_ENTITY_CLASS =
        "com.simibubi.create.content.contraptions.AbstractContraptionEntity";
    private static final int[] ESCAPE_RADII = {2, 4, 6, 8, 12, 16, 24, 32};
    private static final int FULL_SEAT_RETRY_TICKS = 100;
    /** Ticks the owner must be off-ship before a FOLLOW soldier is released (debounce). */
    private static final int RELEASE_DEBOUNCE_TICKS = 40;
    /** Ticks between station duty scans for a seated crew soldier without a station. */
    private static final int CREW_DUTY_SCAN_INTERVAL_TICKS = 20;

    private static volatile boolean initialized;
    private static volatile boolean available;
    private static boolean reflectionFailureLogged;
    // VSGameUtilsKt methods: use MethodHandle to avoid ClientLevel class loading on dedicated server
    private static MethodHandle getShipMountedTo;
    private static MethodHandle getShipMountedToData;
    private static MethodHandle toWorldCoordinates;
    private static MethodHandle getShipsIntersecting;
    private static MethodHandle getShipObjectManagingPos;
    private static MethodHandle getShipObjectManagingPosDouble;
    private static MethodHandle clipIncludeShips;
    private static MethodHandle clipIncludeShipsOnly;
    private static MethodHandle vanillaClip;
    // Non-VSGameUtilsKt reflection (safe to use ordinary Method)
    private static Method getMountPosInShip;
    private static Method createSeatSitDown;
    private static Class<?> createSeatBlockClass;
    private static Class<?> createSeatEntityClass;
    private static Class<?> contraptionEntityClass;
    private static Class<?> shipMountedDataProviderClass;
    private static MethodHandle shipGetShipToWorld;
    private static MethodHandle shipGetWorldToShip;
    private static MethodHandle shipGetAABB;
    private static Method aabbMinX;
    private static Method aabbMinY;
    private static Method aabbMinZ;
    private static Method aabbMaxX;
    private static Method aabbMaxY;
    private static Method aabbMaxZ;
    private static Method getContraption;
    private static Method getSeats;
    private static Method getSeatMapping;
    private static Method addSittingPassenger;
    private static Method getShipId;
    private static Object createInteractiveUtil;
    private static Method getContraptionEntityForShip;
    private static final Map<UUID, SoldierState> states = new HashMap<>();
    private static final Map<UUID, UUID> authorizedMounts = new HashMap<>();
    private static final Map<UUID, BlockPos> authorizedStaticSeats = new HashMap<>();

    private VS2Compat() {}

    public static boolean isEnabled() {
        initialize();
        return available && StevesArmyConfig.VS2_COMPAT_ENABLED.get();
    }

    public static boolean prepareSoldierAi(SoldierEntity soldier) {
        if (soldier.level().isClientSide || !isEnabled()) {
            return false;
        }

        SoldierState state = states.computeIfAbsent(soldier.getUUID(), ignored -> new SoldierState());
        if (state.transportAnchorId != null) {
            if (state.reboardBlockTicks > 0) {
                state.reboardBlockTicks--;
            }
            updateTransport(soldier, state);
            // Transported soldiers freeze completely - crew included. A crew
            // soldier's claimed station is driven by StationGunnerAI (station
            // side, strict world space), so the soldier itself does no
            // computation while riding a VS object.
            return isTransported(soldier, state);
        }

        if (state.reboardBlockTicks > 0) {
            state.reboardBlockTicks--;
        }
        if (state.handleDismountGraceTicks > 0) {
            state.handleDismountGraceTicks--;
        }

        if (soldier.isPassenger()) {
            soldier.stopRiding();
        }

        if (tryStartTransport(soldier, state)) {
            return true;
        }

        if (isInsideShip(soldier) || isBeingDraggedByShip(soldier)) {
            // A soldier just let off at a mount handle may stand aboard briefly
            // (e.g. the loading hatch) before ship-extraction moves it to safe ground.
            if (state.handleDismountGraceTicks <= 0) {
                extractToSafeWorldPosition(soldier, state);
                return true;
            }
            return false;
        }

        rememberSafeWorldPosition(soldier, state);
        return false;
    }

    public static void onSoldierRemoved(SoldierEntity soldier) {
        states.remove(soldier.getUUID());
        authorizedMounts.remove(soldier.getUUID());
        authorizedStaticSeats.remove(soldier.getUUID());
        AnalogWarfareCompat.forget(soldier.getUUID());
    }

    /** True when the soldier is seated on a crew-claimed station seat (role keeps ticking AI). */
    public static boolean isCrewSeated(SoldierEntity soldier) {
        SoldierState state = states.get(soldier.getUUID());
        return state != null && state.crewSeated;
    }

    /** True while the post-dismount re-boarding cooldown is running. */
    public static boolean isAutoTransportBlocked(SoldierEntity soldier) {
        SoldierState state = states.get(soldier.getUUID());
        return state != null && state.reboardBlockTicks > 0;
    }

    /** The VS2 ship the entity is mounted to, or null. */
    @Nullable
    public static Object getMountedShip(Entity entity) {
        if (!isEnabled()) {
            return null;
        }
        try {
            return reflect(getShipMountedTo, entity);
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
            return null;
        }
    }

    /** Stable id of a VS2 ship object, or null. */
    @Nullable
    public static Long getShipIdOf(@Nullable Object ship) {
        if (ship == null) {
            return null;
        }
        try {
            return ((Number) getShipId.invoke(ship)).longValue();
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
            return null;
        }
    }

    /** Transforms a direction from ship-local space to world space. */
    public static Vec3 shipToWorldDirection(@Nullable Object ship, Vec3 direction) {
        if (ship == null || shipGetShipToWorld == null) {
            return direction;
        }
        try {
            Object matrix = shipGetShipToWorld.invoke(ship);
            if (matrix instanceof org.joml.Matrix4dc m) {
                org.joml.Vector3d v = new org.joml.Vector3d(direction.x, direction.y, direction.z);
                m.transformDirection(v);
                return new Vec3(v.x, v.y, v.z);
            }
        } catch (Throwable ignored) {
        }
        return direction;
    }

    /** Transforms a world position into ship-local space, or null when unavailable. */
    @Nullable
    public static Vec3 worldToShipLocal(@Nullable Object ship, Vec3 worldPos) {
        if (ship == null || shipGetWorldToShip == null) {
            return null;
        }
        try {
            Object matrix = shipGetWorldToShip.invoke(ship);
            if (matrix instanceof org.joml.Matrix4dc m) {
                org.joml.Vector3d v = new org.joml.Vector3d(worldPos.x, worldPos.y, worldPos.z);
                m.transformPosition(v);
                return new Vec3(v.x, v.y, v.z);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Transforms a ship-local (shipyard) position to world space, or null when
     * unavailable. Ship-mounted entities like tallyho cameras live at shipyard
     * coordinates server-side; VS2 transforms them only for client tracking.
     */
    @Nullable
    public static Vec3 shipToWorldPosition(@Nullable Object ship, Vec3 localPos) {
        if (ship == null || shipGetShipToWorld == null) {
            return null;
        }
        try {
            Object matrix = shipGetShipToWorld.invoke(ship);
            if (matrix instanceof org.joml.Matrix4dc m) {
                org.joml.Vector3d v = new org.joml.Vector3d(localPos.x, localPos.y, localPos.z);
                m.transformPosition(v);
                return new Vec3(v.x, v.y, v.z);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Positions beyond this magnitude are VS2 shipyard allocations, not world coordinates. */
    private static final double WORLD_COORD_LIMIT = 1.0e6;

    /**
     * World-space position of a tallyho station camera. Tallyho keeps these entities
     * in world coordinates (handleShoot spawns projectiles at the raw position), so a
     * plausible raw position is used directly. A shipyard-scale position means the
     * entity was spawned into or left in VS2's allocation region: the ship transform
     * is tried as a fallback and accepted only if it lands back in the world, since
     * aiming or scanning on guessed coordinates is worse than refusing to run.
     */
    @Nullable
    public static Vec3 stationCameraWorldPos(@Nullable Object ship, Entity station) {
        Vec3 raw = station.position();
        if (isWorldPlausible(raw)) {
            return raw;
        }
        Vec3 transformed = shipToWorldPosition(ship, raw);
        return isWorldPlausible(transformed) ? transformed : null;
    }

    private static boolean isWorldPlausible(@Nullable Vec3 pos) {
        return pos != null
            && Math.abs(pos.x) <= WORLD_COORD_LIMIT
            && Math.abs(pos.z) <= WORLD_COORD_LIMIT;
    }

    /**
     * Minimum corner of the ship's AABB in shipyard (block) space — the origin that
     * ship-local offsets are added to in order to reach real block positions.
     */
    @Nullable
    public static BlockPos getShipyardMin(@Nullable Object ship) {
        if (ship == null || shipGetAABB == null) {
            return null;
        }
        try {
            Object box = shipGetAABB.invoke(ship);
            if (box == null) {
                return null;
            }
            synchronized (VS2Compat.class) {
                if (aabbMinX == null || aabbMinX.getDeclaringClass() != box.getClass()) {
                    aabbMinX = box.getClass().getMethod("minX");
                    aabbMinY = box.getClass().getMethod("minY");
                    aabbMinZ = box.getClass().getMethod("minZ");
                }
            }
            return new BlockPos(
                ((Number) aabbMinX.invoke(box)).intValue(),
                ((Number) aabbMinY.invoke(box)).intValue(),
                ((Number) aabbMinZ.invoke(box)).intValue());
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Maximum corner of the ship's voxel AABB in shipyard (block) space; null when
     * VS2 has no AABB for the ship or reflection fails. Mirrors {@link #getShipyardMin}.
     */
    @Nullable
    public static BlockPos getShipyardMax(@Nullable Object ship) {
        if (ship == null || shipGetAABB == null) {
            return null;
        }
        try {
            Object box = shipGetAABB.invoke(ship);
            if (box == null) {
                return null;
            }
            synchronized (VS2Compat.class) {
                if (aabbMaxX == null || aabbMaxX.getDeclaringClass() != box.getClass()) {
                    aabbMaxX = box.getClass().getMethod("maxX");
                    aabbMaxY = box.getClass().getMethod("maxY");
                    aabbMaxZ = box.getClass().getMethod("maxZ");
                }
            }
            return new BlockPos(
                ((Number) aabbMaxX.invoke(box)).intValue(),
                ((Number) aabbMaxY.invoke(box)).intValue(),
                ((Number) aabbMaxZ.invoke(box)).intValue());
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** True when the entity is a Create SeatEntity (includes tallyho's FlexibleSeatEntity). */
    public static boolean isCreateSeatEntity(Entity entity) {
        initialize();
        return createSeatEntityClass != null && createSeatEntityClass.isInstance(entity);
    }

    /**
     * The {@code /vs get-ship} lookup: the ship managing the exact block a world-space
     * ray hit. VS2 exposes ship blocks to the world at their transformed positions, so
     * a vanilla clip's hit block resolves directly through VS2's chunk map.
     */
    @Nullable
    public static Object getShipObjectAtBlockPos(Level level, BlockPos pos) {
        return getShipObjectAtWorldPos(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** Id of the ship managing the given world/shipyard position, or null. */
    @Nullable
    public static Long getShipIdAt(Level level, double x, double y, double z) {
        Object ship = getShipObjectAtWorldPos(level, x, y, z);
        return ship == null ? null : getShipIdOf(ship);
    }

    /** Ship object managing the given world position, or null. */
    @Nullable
    public static Object getShipObjectAtWorldPos(Level level, double x, double y, double z) {
        initialize();
        if (!available) {
            return null;
        }
        try {
            return reflect(getShipObjectManagingPosDouble, level, x, y, z);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    /**
     * Resolves a ship from a clicked world-space anchor.
     *
     * <p>VS2's position-manager lookup is shipyard-chunk based, so it can return
     * an unrelated ship when the anchor is a world-space raycast hit. Reconcile
     * that result with VS2's world-space intersection query before falling back
     * to the legacy result.</p>
     */
    @Nullable
    public static Object resolveShipAtWorldAnchor(Level level, Vec3 anchor) {
        initialize();
        Object managingShip = getShipObjectAtWorldPos(level, anchor.x, anchor.y, anchor.z);
        if (!available || getShipsIntersecting == null) {
            StevesArmyMod.LOGGER.info(
                "[VS2] crew anchor resolver: world intersection unavailable, using managing shipId={}",
                getShipIdOf(managingShip));
            return managingShip;
        }

        try {
            Object ships = reflect(getShipsIntersecting, level, new AABB(anchor, anchor));
            List<Object> intersecting = new ArrayList<>();
            List<String> candidateIds = new ArrayList<>();
            if (ships instanceof Iterable<?> iterable) {
                for (Object candidate : iterable) {
                    if (candidate == null) {
                        continue;
                    }
                    intersecting.add(candidate);
                    Long candidateId = getShipIdOf(candidate);
                    candidateIds.add(candidateId == null ? "unknown" : candidateId.toString());
                }
            }

            // Geometric pick for multi-ship builds (tanks assembled as several stacked
            // ships): the ship the player actually clicked is the one whose own voxel
            // body contains the anchor, judged by the LOCAL block at the transformed
            // position — not just AABB overlap, which every stacked sub-ship satisfies.
            // A raycast hit lies on a block face, so BlockPos.containing can land in the
            // neighbouring air block; the six face neighbours are checked too.
            // Unloaded/no-transform candidates are "unavailable": invisible to the
            // geometric tiers but still reachable through the legacy fallbacks.
            List<String> geoVerdicts = new ArrayList<>();
            Object solidCandidate = null;
            Object airCandidate = null;
            Object managingInIntersecting = null;
            ServerLevel serverLevel = level instanceof ServerLevel ? (ServerLevel) level : null;
            for (Object candidate : intersecting) {
                Long candidateId = getShipIdOf(candidate);
                if (managingShip != null && managingInIntersecting == null
                    && getShipIdOf(managingShip) != null && getShipIdOf(managingShip).equals(candidateId)) {
                    managingInIntersecting = candidate;
                }
                String verdict;
                Vec3 local = worldToShipLocal(candidate, anchor);
                BlockPos converted = local == null ? null
                    : BlockPos.containing(local.x, local.y, local.z);
                BlockPos aabbMin = converted == null ? null : getShipyardMin(candidate);
                BlockPos aabbMax = converted == null ? null : getShipyardMax(candidate);
                boolean contained = converted != null && aabbMin != null && aabbMax != null
                    && converted.getX() >= aabbMin.getX() - 1 && converted.getX() <= aabbMax.getX() + 1
                    && converted.getY() >= aabbMin.getY() - 1 && converted.getY() <= aabbMax.getY() + 1
                    && converted.getZ() >= aabbMin.getZ() - 1 && converted.getZ() <= aabbMax.getZ() + 1;
                if (!contained) {
                    verdict = local == null ? "no-transform" : "outside";
                } else if (serverLevel == null || !isChunkLoaded(serverLevel, converted)) {
                    verdict = "unloaded";
                } else if (hasSolidBlockNear(serverLevel, converted)) {
                    verdict = "contains-anchor-solid";
                    if (solidCandidate == null) {
                        solidCandidate = candidate;
                    }
                } else {
                    verdict = "contains-anchor-air";
                    if (airCandidate == null) {
                        airCandidate = candidate;
                    }
                }
                geoVerdicts.add((candidateId == null ? "?" : candidateId) + ":" + verdict);
            }

            Long managingId = getShipIdOf(managingShip);
            Object selected;
            String decision;
            if (solidCandidate != null) {
                selected = solidCandidate;
                decision = "contains-anchor-solid";
            } else if (airCandidate != null) {
                selected = airCandidate;
                decision = "contains-anchor-air";
            } else if (managingInIntersecting != null) {
                selected = managingInIntersecting;
                decision = "managing-match";
            } else if (intersecting.size() == 1) {
                selected = intersecting.get(0);
                decision = "sole-intersection";
            } else if (intersecting.isEmpty()) {
                selected = managingShip;
                decision = "no-intersection-managing-fallback";
            } else {
                selected = null;
                decision = "ambiguous-intersection-near-player-fallback";
            }

            StevesArmyMod.LOGGER.info(
                "[VS2] crew anchor resolver: anchor={} managingShipId={} intersectingShipIds=[{}] geo=[{}] selectedShipId={} decision={}",
                formatVec3(anchor), managingId, String.join(", ", candidateIds),
                String.join(", ", geoVerdicts), getShipIdOf(selected), decision);
            return selected;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            StevesArmyMod.LOGGER.warn(
                "[VS2] crew anchor resolver failed; using managing shipId={}: {}",
                getShipIdOf(managingShip), exception.toString());
            return managingShip;
        }
    }

    /** Grants a soldier a grace period to stand at a handle after a handle dismount. */
    public static void markHandleDismount(SoldierEntity soldier) {
        getOrCreateState(soldier).handleDismountGraceTicks =
            StevesArmyConfig.VEHICLE_HANDLES_DISMOUNT_GRACE.get();
    }

    /**
     * True when the block at {@code pos} or any face neighbour is non-air. Raycast hits
     * lie on block faces, so {@code BlockPos.containing} at the hit point can land in
     * the adjacent air block; the neighbours decide whether the hit touched this ship.
     */
    private static boolean hasSolidBlockNear(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (!level.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    /** Remembers the handle owning a just-attached seat so later releases can exit at it. */
    private static void recordHandleLink(ServerLevel level, Object ship, SoldierEntity soldier, BlockPos seatPos) {
        if (StevesArmyConfig.VEHICLE_HANDLES_ENABLED.get()) {
            AnalogWarfareCompat.recordHandleForSeat(level, ship, soldier, seatPos);
        }
    }

    /**
     * World-space position of a seat entity. Create SeatEntities on ships live at
     * shipyard coordinates; VS2 data-provider seats (e.g. tallyho's FlexibleSeatEntity)
     * are already tracked at world coordinates.
     */
    public static Vec3 getSeatWorldPosition(Entity seat) {
        if (shipMountedDataProviderClass != null && shipMountedDataProviderClass.isInstance(seat)) {
            return seat.position();
        }
        if (seat.level() instanceof ServerLevel serverLevel) {
            try {
                Object ship = reflect(getShipObjectManagingPosDouble, serverLevel,
                    seat.getX(), seat.getY(), seat.getZ());
                if (ship != null) {
                    Object world = reflect(toWorldCoordinates, ship,
                        seat.getX(), seat.getY(), seat.getZ());
                    if (world instanceof org.joml.Vector3dc v) {
                        return new Vec3(v.x(), v.y(), v.z());
                    }
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return seat.position();
    }

    /**
     * Nearest unoccupied Create seat entity (includes tallyho's FlexibleSeatEntity,
     * which extends Create's SeatEntity) within the radius of the soldier.
     */
    @Nullable
    public static Entity findFreeSeatEntityNear(SoldierEntity soldier, double radius) {
        if (!(soldier.level() instanceof ServerLevel level)) {
            return null;
        }
        initialize();
        if (!available || createSeatEntityClass == null) {
            return null;
        }
        Entity best = null;
        double bestDistSqr = radius * radius;
        for (Entity entity : level.getAllEntities()) {
            if (!createSeatEntityClass.isInstance(entity) || entity.isRemoved()
                || !entity.getPassengers().isEmpty()) {
                continue;
            }
            Vec3 worldPos = getSeatWorldPosition(entity);
            double distSqr = worldPos.distanceToSqr(soldier.position());
            if (distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                best = entity;
            }
        }
        return best;
    }

    /**
     * Mounts the soldier onto a specific seat entity as command-driven transport,
     * mirroring {@link #seatSoldierDirect} but for existing seat entities (tallyho
     * FlexibleSeatEntity instances have no SeatBlock behind them). The soldier keeps
     * ticking its AI, so the vehicle crew goal can man stations while seated.
     */
    public static boolean seatSoldierOnSeatEntity(SoldierEntity soldier, Entity seat) {
        initialize();
        if (!available || soldier.isPassenger() || seat.isRemoved()) {
            return false;
        }
        authorizedMounts.put(soldier.getUUID(), seat.getUUID());
        try {
            soldier.startRiding(seat, true);
        } finally {
            authorizedMounts.remove(soldier.getUUID());
        }
        if (!soldier.isPassenger() || soldier.getVehicle() != seat) {
            return false;
        }

        SoldierState state = getOrCreateState(soldier);
        state.transportAnchorId = seat.getUUID();
        state.transportOwnerId = null;
        state.transportShipId = getShipIdOf(getMountedShip(seat));
        state.transportSeatPosition = null;
        state.seatRetryCooldownTicks = 0;
        state.reboardBlockTicks = 0;
        state.crewSeated = true;

        soldier.getVehicle().positionRider(soldier);
        syncTransportState(soldier, seat, true);
        StevesArmyMod.LOGGER.info("[VS2] seatSoldierOnSeatEntity: mounted soldier={} seat={} seatClass={} shipId={}",
            soldier.getId(), seat.getId(), seat.getClass().getSimpleName(), state.transportShipId);
        return true;
    }

    /** Allows only the immediate Create seat mount initiated by tryContraptionSeat or tryStaticSeat. */
    public static boolean isAuthorizedMount(SoldierEntity soldier, Entity vehicle) {
        if (vehicle == null) {
            return false;
        }
        // Ensure reflection classes are initialized before checking them.
        // On the client (render thread), initialize() may not have been called yet.
        initialize();
        // Direct authorization: soldier has a pending authorized mount UUID.
        UUID vehicleId = authorizedMounts.get(soldier.getUUID());
        if (vehicleId != null && vehicleId.equals(vehicle.getUUID())) {
            return true;
        }
        // Static-seat authorization: the SeatEntity's block position must match the pending candidate.
        BlockPos pendingPos = authorizedStaticSeats.get(soldier.getUUID());
        if (pendingPos != null && createSeatEntityClass != null && createSeatEntityClass.isInstance(vehicle)) {
            return vehicle.blockPosition().equals(pendingPos);
        }
        // On the client side, the server is authoritative. Accept any Create seat or contraption mount.
        // Use class name string comparison as a safe fallback since reflection classes may not be
        // loaded on a remote client's render thread before the mount event fires.
        if (soldier.level().isClientSide) {
            String vehicleClassName = vehicle.getClass().getName();
            if ("com.simibubi.create.content.contraptions.actors.seat.SeatEntity".equals(vehicleClassName)
                || (createSeatEntityClass != null && createSeatEntityClass.isInstance(vehicle))) {
                return true;
            }
            if ("com.simibubi.create.content.contraptions.AbstractContraptionEntity".equals(vehicleClassName)
                || (contraptionEntityClass != null && contraptionEntityClass.isInstance(vehicle))) {
                return true;
            }
            String side = "CLIENT";
            StevesArmyMod.LOGGER.info("[AuthorizeCl] {} isAuthorizedMount returning false for soldier={} vehicle={} vehicleClass={} createSeatClass={} contraptionClass={}",
                side, soldier.getId(), vehicle.getId(), vehicle.getClass().getName(),
                createSeatEntityClass == null ? "null" : createSeatEntityClass.getName(),
                contraptionEntityClass == null ? "null" : contraptionEntityClass.getName());
        }
        return false;
    }

    public static void clearAuthorizedMount(SoldierEntity soldier) {
        authorizedMounts.remove(soldier.getUUID());
        authorizedStaticSeats.remove(soldier.getUUID());
    }

    /**
     * Directly mounts a soldier to a static Create seat at the given shipyard block position.
     * Bypasses all FOLLOW/owner/transport logic. Returns true if the soldier is now a passenger.
     * After a successful mount, records the transport state so prepareSoldierAi does not
     * immediately dismount the soldier.
     */
    public static boolean seatSoldierDirect(SoldierEntity soldier, Level level, BlockPos seatBlockPos) {
        initialize();
        if (!available) return false;
        try {
            // Resolve the ship at the seat position for logging
            Object ship = null;
            Long shipId = null;
            org.joml.Vector3dc worldPos = null;
            try {
                ship = reflect(getShipObjectManagingPos, level, (net.minecraft.core.Vec3i) seatBlockPos);
                if (ship != null) {
                    shipId = ((Number) getShipId.invoke(ship)).longValue();
                    worldPos = (org.joml.Vector3dc) reflect(toWorldCoordinates, ship,
                        seatBlockPos.getX() + 0.5, seatBlockPos.getY(), seatBlockPos.getZ() + 0.5);
                }
            } catch (ReflectiveOperationException e) {
                StevesArmyMod.LOGGER.warn("[VS2] seatSoldierDirect: ship lookup failed for seat={}", seatBlockPos);
            }

            // Use SeatBlock.sitDown to create the seat at the shipyard block position.
            // This keeps the seat attached to the real seat block on the ship.
            // Mount the soldier after the seat entity is created.
            authorizedStaticSeats.put(soldier.getUUID(), seatBlockPos);
            try {
                createSeatSitDown.invoke(null, level, seatBlockPos, soldier);
            } finally {
                authorizedStaticSeats.remove(soldier.getUUID());
            }

            if (soldier.isPassenger() && soldier.getVehicle() != null) {
                Entity vehicle = soldier.getVehicle();

                // SeatEntity is at shipyard coordinates — VS2 handles world-space
                // tracking and rider position transforms (see MixinChunkMap$TrackedEntity,
                // MixinSeatBlock.wrapSitDownSetPos, MixinEntity.positionRider).
                // Do NOT move the seat to world-space.

                SoldierState state = getOrCreateState(soldier);
                state.transportAnchorId = vehicle.getUUID();
                state.transportOwnerId = null;
                state.transportShipId = shipId;
                state.transportSeatPosition = seatBlockPos;
                state.seatRetryCooldownTicks = 0;
                state.reboardBlockTicks = 0;
                state.crewSeated = soldier.getRole() == SoldierRole.VEHICLE_CREW;

                Vec3 prePos = soldier.position();
                soldier.getVehicle().positionRider(soldier);
                Vec3 postPos = soldier.position();

                // Synchronize the seat entity and passenger to all relevant players.
                syncTransportState(soldier, vehicle, true);

                StevesArmyMod.LOGGER.info("[VS2] seatSoldierDirect: mounted soldier={} vehicle={} vehicleClass={} seat={} shipId={} soldierPos={} -> {}",
                    soldier.getId(), vehicle.getId(), vehicle.getClass().getSimpleName(),
                    seatBlockPos, shipId,
                    formatVec3(prePos), formatVec3(postPos));

                if (worldPos != null) {
                    StevesArmyMod.LOGGER.info("[VS2] seatSoldierDirect: resolved seat worldPos=({}, {}, {})",
                        String.format("%.2f", worldPos.x()), String.format("%.2f", worldPos.y()), String.format("%.2f", worldPos.z()));
                }

                if (ship != null && level instanceof ServerLevel serverLevel) {
                    recordHandleLink(serverLevel, ship, soldier, seatBlockPos);
                }

                return true;
            }
            StevesArmyMod.LOGGER.warn("[VS2] seatSoldierDirect: mount did not persist soldier={} seat={}",
                soldier.getId(), seatBlockPos);
            return false;
        } catch (ReflectiveOperationException e) {
            StevesArmyMod.LOGGER.error("[VS2] seatSoldierDirect failed", e);
            return false;
        }
    }

    private static String formatVec3(Vec3 v) {
        return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    /** Public wrapper to clear VS2 dragging state after a manual release. */
    public static void clearShipDraggingStateDirect(SoldierEntity soldier) {
        clearShipDraggingState(soldier);
    }

    /** Clears the transport state for a soldier. Used by the release command. */
    public static void clearTransportState(SoldierEntity soldier) {
        SoldierState state = states.get(soldier.getUUID());
        if (state != null) {
            clearTransportState(state);
        }
    }

    /**
     * Manual release shared by the /stevesarmy transport release command and the vehicle wheel:
     * dismount, clear VS2 dragging + transport state, and briefly block automatic re-boarding.
     * Returns true if the soldier was mounted.
     */
    public static boolean releaseTransport(SoldierEntity soldier) {
        Entity vehicle = soldier.isPassenger() ? soldier.getVehicle() : null;
        if (soldier.isPassenger()) {
            soldier.stopRiding();
        }
        clearShipDraggingStateDirect(soldier);
        clearTransportState(soldier);
        blockAutoTransport(soldier);
        if (vehicle != null) {
            // Send empty passenger list so the anchor no longer reports the soldier.
            syncTransportState(soldier, vehicle, false);
        }
        return vehicle != null;
    }

    /** Blocks FOLLOW auto-transport for a cooldown, e.g. right after a manual dismount. */
    public static void blockAutoTransport(SoldierEntity soldier) {
        int delay = StevesArmyConfig.VS2_DISMOUNT_REBOARD_DELAY.get();
        if (delay <= 0) {
            return;
        }
        getOrCreateState(soldier).reboardBlockTicks = delay;
    }

    /** The LoadedShip managing the given block position, or null. */
    @Nullable
    public static Object getShipAt(Level level, BlockPos pos) {
        initialize();
        if (!available) {
            return null;
        }
        try {
            return reflect(getShipObjectManagingPos, level, (Vec3i) pos);
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
            return null;
        }
    }

    /**
     * Ship for a free-standing shipyard-space entity (tallyho stations, seats).
     * VS2's getShipMountedTo only resolves for entities riding a vehicle; station
     * cameras aren't riding anything, so fall back to the position-based lookup
     * at the entity's (shipyard) block position.
     */
    @Nullable
    public static Object getShipUnder(@Nullable Entity entity) {
        if (entity == null) {
            return null;
        }
        Object mounted = getMountedShip(entity);
        if (mounted != null) {
            return mounted;
        }
        return getShipAt(entity.level(), entity.blockPosition());
    }

    /**
     * Ship the vehicle-wheel MOUNT order targets when the crosshair is not on a vehicle:
     * the ship the player is mounted to, else the nearest ship within 64 blocks.
     */
    @Nullable
    public static Object resolveMountShipNearPlayer(ServerLevel level, ServerPlayer player) {
        initialize();
        if (!available) {
            return null;
        }
        try {
            Object mounted = reflect(getShipMountedTo, player);
            if (mounted != null) {
                return mounted;
            }
            for (double radius : new double[] {8.0, 16.0, 32.0, 64.0}) {
                Object ship = firstShipIntersecting(level, player.position(), radius);
                if (ship != null) {
                    return ship;
                }
            }
            return null;
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
            return null;
        }
    }

    @Nullable
    private static Object firstShipIntersecting(Level level, Vec3 center, double radius) {
        try {
            Object ships = reflect(getShipsIntersecting, level, new AABB(center, center).inflate(radius));
            if (ships instanceof Iterable<?> iterable) {
                for (Object ship : iterable) {
                    return ship;
                }
            }
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
        }
        return null;
    }

    /** Volume cap for the full-ship seat scan so huge hulls cannot stall the server thread. */
    private static final long MAX_STATIC_SCAN_VOLUME = 500_000L;

    /** Seat/occupancy counts from the most recent static seat scan (server thread only). */
    private static volatile String lastStaticScanSummary = "never-run";

    /** Summary of the most recent {@link #findFreeStaticSeats} call, for failure messages. */
    public static String getLastStaticScanSummary() {
        return lastStaticScanSummary;
    }

    /**
     * Unoccupied Create SeatBlocks belonging to the given ship, found by scanning the
     * ship's WHOLE voxel AABB in shipyard space (free seats can sit anywhere on a large
     * ship; a box around the clicked anchor misses them). Falls back to a box around
     * the converted anchor when VS2 exposes no voxel AABB. Columns in unloaded shipyard
     * chunks are skipped so the scan never forces chunk loads. Every click also logs a
     * per-ship block census and a block-type histogram of the target ship's blocks, so
     * a scan that finds no seats still identifies what the ship is built from.
     */
    public static List<BlockPos> findFreeStaticSeats(Level level, Object ship, Vec3 worldCenter, int maxSeats) {
        List<BlockPos> seats = new ArrayList<>();
        initialize();
        if (!available || ship == null || maxSeats <= 0 || !(level instanceof ServerLevel serverLevel)) {
            return seats;
        }
        try {
            long shipId = ((Number) getShipId.invoke(ship)).longValue();
            Vec3 shipLocalCenter = worldToShipLocal(ship, worldCenter);
            BlockPos anchor = BlockPos.containing(shipLocalCenter != null ? shipLocalCenter : worldCenter);

            BlockPos aabbMin = getShipyardMin(ship);
            BlockPos aabbMax = getShipyardMax(ship);
            BlockPos scanMin;
            BlockPos scanMax;
            String regionSource;
            if (aabbMin != null && aabbMax != null) {
                scanMin = aabbMin;
                scanMax = aabbMax;
                regionSource = "voxel-aabb";
            } else {
                scanMin = anchor.offset(-16, -8, -16);
                scanMax = anchor.offset(16, 8, 16);
                regionSource = "anchor-box(no-aabb)";
            }
            // Bound the work for huge hulls: clip to anchor ±48/±24 so the scan stays
            // near where the player is aiming even on ship-sized regions.
            long volume = (long) (scanMax.getX() - scanMin.getX() + 1)
                * (scanMax.getY() - scanMin.getY() + 1) * (scanMax.getZ() - scanMin.getZ() + 1);
            if (volume > MAX_STATIC_SCAN_VOLUME) {
                scanMin = new BlockPos(
                    Math.max(scanMin.getX(), anchor.getX() - 48),
                    Math.max(scanMin.getY(), anchor.getY() - 24),
                    Math.max(scanMin.getZ(), anchor.getZ() - 48));
                scanMax = new BlockPos(
                    Math.min(scanMax.getX(), anchor.getX() + 48),
                    Math.min(scanMax.getY(), anchor.getY() + 24),
                    Math.min(scanMax.getZ(), anchor.getZ() + 48));
                regionSource += "+clipped";
            }

            StevesArmyMod.LOGGER.info("[Crew] static seat scan: shipId={} anchorWorld={} anchorShipSpace={} region={} {}..{}",
                shipId, formatVec3(worldCenter), anchor, regionSource, scanMin, scanMax);

            // Ship attribution is chunk-granular in VS2, so resolve it once per chunk.
            Map<Long, Long> chunkShipIds = new HashMap<>();
            Map<Long, Integer> perShipBlocks = new HashMap<>();
            Map<String, Integer> shipBlockTypes = new HashMap<>();
            int seatBlocks = 0;
            int occupiedSeats = 0;
            int unloadedColumns = 0;
            int scannedBlocks = 0;

            for (int x = scanMin.getX(); x <= scanMax.getX(); x++) {
                for (int z = scanMin.getZ(); z <= scanMax.getZ(); z++) {
                    if (!isChunkLoaded(serverLevel, new BlockPos(x, scanMin.getY(), z))) {
                        unloadedColumns++;
                        continue;
                    }
                    for (int y = scanMin.getY(); y <= scanMax.getY(); y++) {
                        BlockPos candidate = new BlockPos(x, y, z);
                        BlockState blockState = level.getBlockState(candidate);
                        if (blockState.isAir()) {
                            continue;
                        }
                        scannedBlocks++;
                        Long chunkKey = ChunkPos.asLong(x >> 4, z >> 4);
                        Long cachedShipId = chunkShipIds.get(chunkKey);
                        if (cachedShipId == null) {
                            cachedShipId = resolveManagingShipId(level, x, scanMin.getY(), z);
                            chunkShipIds.put(chunkKey, cachedShipId);
                        }
                        long managingShipId = cachedShipId;
                        perShipBlocks.merge(managingShipId, 1, Integer::sum);
                        if (managingShipId != shipId) {
                            continue;
                        }
                        shipBlockTypes.merge(registryName(blockState), 1, Integer::sum);
                        if (!createSeatBlockClass.isInstance(blockState.getBlock())) {
                            continue;
                        }
                        seatBlocks++;
                        if (isCreateSeatOccupied(serverLevel, candidate)) {
                            occupiedSeats++;
                            continue;
                        }
                        seats.add(candidate);
                    }
                }
            }
            seats.sort(Comparator.comparingDouble(pos -> pos.distSqr(anchor)));

            int shipBlockTotal = 0;
            List<Map.Entry<String, Integer>> types = new ArrayList<>(shipBlockTypes.entrySet());
            types.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            List<String> topBlocks = new ArrayList<>();
            List<String> seatSuspects = new ArrayList<>();
            for (Map.Entry<String, Integer> type : types) {
                shipBlockTotal += type.getValue();
                if (topBlocks.size() < 8) {
                    topBlocks.add(type.getKey() + " x" + type.getValue());
                }
                String name = type.getKey();
                if ((name.contains("seat") || name.contains("chair") || name.contains("bench"))
                    && !name.equals("create:seat")) {
                    seatSuspects.add(name + " x" + type.getValue());
                }
            }

            List<String> shipCounts = new ArrayList<>();
            List<Map.Entry<Long, Integer>> ships = new ArrayList<>(perShipBlocks.entrySet());
            ships.sort(Map.Entry.<Long, Integer>comparingByValue().reversed());
            for (Map.Entry<Long, Integer> entry : ships) {
                if (shipCounts.size() >= 4) {
                    break;
                }
                shipCounts.add((entry.getKey() == -1L ? "world" : entry.getKey()) + ":" + entry.getValue());
            }

            StevesArmyMod.LOGGER.info(
                "[Crew] static seat scan result: shipId={} scannedBlocks={} unloadedColumns={} perShipBlocks=[{}] shipBlocks={} seatBlocks={} occupied={} free={} topBlocks=[{}] seatSuspects=[{}]",
                shipId, scannedBlocks, unloadedColumns, String.join(", ", shipCounts),
                shipBlockTotal, seatBlocks, occupiedSeats, seats.size(),
                String.join(", ", topBlocks), String.join(", ", seatSuspects));
            lastStaticScanSummary = "seatBlocks=" + seatBlocks + " occupied=" + occupiedSeats
                + " free=" + seats.size();
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
        }
        return seats;
    }

    /** Id of the ship managing the block at the given coords, or -1 (world/unmanaged). */
    private static long resolveManagingShipId(Level level, int x, int y, int z) {
        try {
            Object manager = reflect(getShipObjectManagingPos, level, new BlockPos(x, y, z));
            return manager == null ? -1L : ((Number) getShipId.invoke(manager)).longValue();
        } catch (ReflectiveOperationException exception) {
            return -1L;
        }
    }

    /** Compact "modid:path" name of the block held by the given state. */
    private static String registryName(BlockState state) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return key == null ? state.getBlock().toString() : key.toString();
    }

    /**
     * One-click inventory of every entity tied to the ship: entities VS2 reports as
     * mounted to it, plus any Create/tallyho seat entity whose raw (shipyard) position
     * falls inside the ship's voxel AABB. Identifies entity-based seats that a block
     * scan can never see.
     */
    public static void logShipEntityCensus(ServerLevel level, Object ship, Vec3 anchorWorld) {
        if (!available || ship == null) {
            return;
        }
        try {
            long shipId = ((Number) getShipId.invoke(ship)).longValue();
            BlockPos aabbMin = getShipyardMin(ship);
            BlockPos aabbMax = getShipyardMax(ship);
            List<String> details = new ArrayList<>();
            int matched = 0;
            for (Entity entity : level.getAllEntities()) {
                if (entity.isRemoved()) {
                    continue;
                }
                boolean mountedHere = false;
                Object mountedTo = reflect(getShipMountedTo, entity);
                if (mountedTo != null
                    && ((Number) getShipId.invoke(mountedTo)).longValue() == shipId) {
                    mountedHere = true;
                }
                boolean seatInAabb = false;
                if (!mountedHere && isCreateSeatEntity(entity) && aabbMin != null && aabbMax != null) {
                    BlockPos pos = entity.blockPosition();
                    seatInAabb = pos.getX() >= aabbMin.getX() - 2 && pos.getX() <= aabbMax.getX() + 2
                        && pos.getY() >= aabbMin.getY() - 2 && pos.getY() <= aabbMax.getY() + 2
                        && pos.getZ() >= aabbMin.getZ() - 2 && pos.getZ() <= aabbMax.getZ() + 2;
                }
                if (!mountedHere && !seatInAabb) {
                    continue;
                }
                matched++;
                if (details.size() < 10) {
                    List<String> riders = new ArrayList<>();
                    for (Entity passenger : entity.getPassengers()) {
                        riders.add(describeEntity(passenger));
                    }
                    details.add(describeEntity(entity)
                        + " pos=" + entity.blockPosition()
                        + " riders=[" + String.join(", ", riders) + "]"
                        + " vehicle=" + (entity.getVehicle() == null ? "none"
                            : describeEntity(entity.getVehicle())));
                }
            }
            StevesArmyMod.LOGGER.info("[Crew] ship entity census: shipId={} anchor={} matched={} details=[{}]",
                shipId, formatVec3(anchorWorld), matched, String.join(", ", details));
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
        }
    }

    /**
     * Short identity of an entity for the census: class, id, name, health when living,
     * and aliveness — enough to tell a seated crew member from a seat-riding-seat
     * stack or a stale tallyho seat.
     */
    private static String describeEntity(Entity entity) {
        StringBuilder description = new StringBuilder(entity.getClass().getSimpleName())
            .append('#').append(entity.getId());
        Component name = entity.getCustomName();
        description.append(" '").append(name == null ? entity.getName().getString() : name.getString()).append('\'');
        if (entity instanceof LivingEntity living) {
            description.append(" hp=").append(living.getHealth());
        }
        description.append(entity.isAlive() ? " alive" : " DEAD");
        return description.toString();
    }

    /**
     * Checks whether a single navigation node (block position) should be treated as
     * blocked because it intersects a VS2 ship. Uses the mob's collision footprint
     * for the AABB test.
     */
    public static boolean isNodeBlockedByShip(Level level, BlockPos node, Mob mob) {
        if (!isEnabled()) {
            return false;
        }
        double w = mob.getBbWidth() * 0.5 + 0.1;
        double h = mob.getBbHeight() * 0.5 + 0.1;
        AABB bounds = new AABB(node).inflate(w, h, w);
        return intersectsShip(level, bounds);
    }

    public static boolean shouldRejectNavigation(Level level, BlockPos position) {
        if (!isEnabled()) {
            return false;
        }
        AABB bounds = new AABB(position).inflate(0.35D, 0.1D, 0.35D);
        return intersectsShip(level, bounds);
    }

    public static boolean shouldRejectNavigation(Entity entity) {
        return isEnabled() && isOnShip(entity);
    }

    public static boolean isOnShip(Entity entity) {
        if (!isEnabled()) {
            return false;
        }
        try {
            return reflect(getShipMountedTo, entity) != null;
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
            return false;
        }
    }

    /**
     * One-shot diagnostic dump for crew mount failures: which ships VS thinks are at the
     * anchor (is the resolved ship the clicked one?), where the ship's voxel AABB sits in
     * ship space, the anchor's ship-space position with a shipToWorld round-trip check,
     * and whether the shipyard chunk there is loaded. Each field targets one candidate
     * root cause for "scan finds no seats".
     */
    public static void logCrewMountDiagnostics(ServerLevel level, @Nullable Object ship, Vec3 anchorWorld) {
        initialize();
        if (!available) {
            return;
        }
        try {
            StringBuilder shipsAtAnchor = new StringBuilder();
            Object ships = reflect(getShipsIntersecting, level, new AABB(anchorWorld, anchorWorld));
            if (ships instanceof Iterable<?> iterable) {
                for (Object s : iterable) {
                    shipsAtAnchor.append(getShipIdOf(s)).append(' ');
                }
            }
            Long shipId = getShipIdOf(ship);
            BlockPos voxelMin = ship == null ? null : getShipyardMin(ship);
            Vec3 converted = ship == null ? null : worldToShipLocal(ship, anchorWorld);
            String roundTrip = "n/a";
            if (converted != null) {
                Vec3 back = shipToWorldPosition(ship, converted);
                roundTrip = back == null ? "null" : String.format("%.2f", back.distanceTo(anchorWorld));
            }
            boolean chunkLoaded = converted == null || isChunkLoaded(level, BlockPos.containing(converted));
            StevesArmyMod.LOGGER.info(
                "[CrewDiag] anchor={} shipsAtAnchor=[{}] shipId={} voxelAABBmin={} anchorInShipSpace={} shipToWorldRoundTripErr={} originChunkLoaded={}",
                formatVec3(anchorWorld), shipsAtAnchor.toString().trim(), shipId, voxelMin,
                converted == null ? "null" : formatVec3(converted), roundTrip, chunkLoaded);
        } catch (Throwable t) {
            StevesArmyMod.LOGGER.warn("[CrewDiag] failed: {}", t.toString());
        }
    }

    /** True when the level chunk containing {@code pos} is currently loaded, best effort. */
    private static boolean isChunkLoaded(ServerLevel level, BlockPos pos) {
        try {
            Object chunkSource = level.getChunkSource();
            Method getChunkNow = chunkSource.getClass().getMethod("getChunkNow", int.class, int.class);
            return getChunkNow.invoke(chunkSource, pos.getX() >> 4, pos.getZ() >> 4) != null;
        } catch (ReflectiveOperationException ignored) {
            return true; // cannot tell — do not misreport
        }
    }

    /**
     * Seats a soldier on a free Create contraption seat of the given ship via the Create
     * Interactive ship mapping — Create's own seat bookkeeping (contraption-local seat
     * positions and its UUID→index occupancy map), so no shipyard block or coordinate
     * transform is involved. This is the path normal soldiers take on moving ships
     * (tryContraptionSeat). Returns the contraption vehicle when seated, or null.
     */
    @Nullable
    public static Entity seatSoldierOnShipContraption(ServerLevel level, long shipId, SoldierEntity soldier) {
        if (!isEnabled() || createInteractiveUtil == null) {
            return null;
        }
        try {
            Entity mappedVehicle = (Entity) getContraptionEntityForShip.invoke(createInteractiveUtil, shipId, false);
            if (mappedVehicle == null) {
                StevesArmyMod.LOGGER.info("[Crew] Create Interactive has no contraption for ship={}", shipId);
                return null;
            }
            StevesArmyMod.LOGGER.info("[Crew] Create Interactive mapped ship={} to contraption vehicle={}",
                shipId, mappedVehicle.getId());
            Object contraption = getContraption.invoke(mappedVehicle);
            if (contraption == null) {
                StevesArmyMod.LOGGER.info("[Crew] Contraption vehicle={} has no initialized contraption",
                    mappedVehicle.getId());
                return null;
            }
            @SuppressWarnings("unchecked")
            java.util.List<BlockPos> seats = (java.util.List<BlockPos>) getSeats.invoke(contraption);
            @SuppressWarnings("unchecked")
            java.util.Map<UUID, Integer> occupied = (java.util.Map<UUID, Integer>) getSeatMapping.invoke(contraption);
            StevesArmyMod.LOGGER.info("[Crew] Contraption vehicle={} seats={} occupied={}",
                mappedVehicle.getId(), seats.size(), occupied);
            for (int index = 0; index < seats.size(); index++) {
                if (occupied.containsValue(index)) {
                    continue;
                }
                authorizedMounts.put(soldier.getUUID(), mappedVehicle.getUUID());
                try {
                    addSittingPassenger.invoke(mappedVehicle, soldier, index);
                    if (soldier.isPassenger() && soldier.getVehicle() == mappedVehicle) {
                        SoldierState state = getOrCreateState(soldier);
                        state.transportAnchorId = mappedVehicle.getUUID();
                        state.transportOwnerId = null;
                        state.crewSeated = soldier.getRole() == SoldierRole.VEHICLE_CREW;
                        state.transportShipId = shipId;
                        state.transportSeatPosition = null;
                        state.seatRetryCooldownTicks = 0;
                        state.reboardBlockTicks = 0;
                        syncTransportState(soldier, mappedVehicle, true);
                        StevesArmyMod.LOGGER.info("[Crew] assigned soldier={} to contraption seat={} vehicle={}",
                            soldier.getId(), index, mappedVehicle.getId());
                        return mappedVehicle;
                    }
                    StevesArmyMod.LOGGER.warn("[Crew] contraption seat mount failed soldier={} seat={} vehicle={}",
                        soldier.getId(), index, mappedVehicle.getId());
                } finally {
                    authorizedMounts.remove(soldier.getUUID());
                }
            }
            StevesArmyMod.LOGGER.info("[Crew] no empty contraption seats for soldier={} vehicle={}",
                soldier.getId(), mappedVehicle.getId());
            return null;
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
            return null;
        }
    }

    /**
     * Returns the nearest block hit found by VS's ship-aware raycast. A vanilla
     * hit is harmless because VisibilityRay also checks vanilla blocks itself.
     */
    public static double getShipAwareBlockHitDistance(Level level, Vec3 from, Vec3 to,
                                                       @Nullable Entity source) {
        Vec3 hit = getShipAwareBlockHitLocation(level, from, to, source);
        return hit == null ? Double.POSITIVE_INFINITY : from.distanceTo(hit);
    }

    /**
     * World-space location of the nearest ship-aware block hit along the ray, or null.
     * Used by the crew stick/egg to anchor on the ship the player is aiming at — the
     * soldier-flow way (ship + anchor position, never via a seat entity: empty Create
     * seats discard their entity, so ships hold SeatBlocks, not pickable entities).
     */
    @Nullable
    public static Vec3 getShipAwareBlockHitLocation(Level level, Vec3 from, Vec3 to,
                                                    @Nullable Entity source) {
        if (!isEnabled()) {
            return null;
        }
        // Fail closed on absurd endpoints: a clip mixing world and shipyard
        // coordinates would march through unloaded chunks with blocking loads
        // and freeze the server tick.
        if (Math.abs(from.x) > 1.0E6D || Math.abs(from.z) > 1.0E6D
            || Math.abs(to.x) > 1.0E6D || Math.abs(to.z) > 1.0E6D) {
            return null;
        }

        try {
            ClipContext context = new ClipContext(
                from,
                to,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                source
            );
            BlockHitResult hit;
            if (clipIncludeShipsOnly != null) {
                hit = (BlockHitResult) reflect(clipIncludeShipsOnly, level, context, true, null, true);
            } else {
                hit = (BlockHitResult) reflect(clipIncludeShips, level, context);
                BlockHitResult vanillaHit = (BlockHitResult) reflect(vanillaClip, level, context);
                if (hit.getType() != HitResult.Type.BLOCK
                    || (vanillaHit.getType() == HitResult.Type.BLOCK
                        && hit.getLocation().distanceToSqr(from)
                            >= vanillaHit.getLocation().distanceToSqr(from))) {
                    return null;
                }
            }
            if (hit.getType() != HitResult.Type.BLOCK) {
                return null;
            }

            return hit.getLocation();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logReflectionFailure(exception);
            return null;
        }
    }

    private static void syncTransportState(SoldierEntity soldier, Entity anchor, boolean mounted) {
        if (!(soldier.level() instanceof ServerLevel serverLevel)) return;

        java.util.Set<ServerPlayer> recipients = new java.util.HashSet<>();

        // Use the soldier's world-space position for recipient selection.
        // The anchor is at shipyard coordinates, not world coordinates.
        Vec3 refPos = soldier.position();

        for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
            if (player.distanceToSqr(refPos) < 16384.0) {
                recipients.add(player);
            }
        }

        if (recipients.isEmpty()) return;

        if (mounted && anchor != null) {
            // Shipyard entities are not tracked by vanilla chunk-based tracking, so we must
            // explicitly send the add-entity packet before the passenger packet so the client
            // has the anchor entity registered.
            net.minecraft.network.protocol.Packet<?> spawnPacket = anchor.getAddEntityPacket();
            ClientboundSetPassengersPacket passengersPacket = new ClientboundSetPassengersPacket(anchor);
            for (ServerPlayer player : recipients) {
                player.connection.send(spawnPacket);
                player.connection.send(passengersPacket);
            }
            StevesArmyMod.LOGGER.info("[SyncTransport] Mounted: sent spawn+passengers for anchor={} soldier={} to {} players",
                anchor.getId(), soldier.getId(), recipients.size());
        } else if (anchor != null) {
            // Soldier has already been dismounted; anchor now has empty passenger list.
            ClientboundSetPassengersPacket passengersPacket = new ClientboundSetPassengersPacket(anchor);
            for (ServerPlayer player : recipients) {
                player.connection.send(passengersPacket);
            }
            StevesArmyMod.LOGGER.info("[SyncTransport] Unmounted: sent empty passengers for anchor={} soldier={}",
                anchor.getId(), soldier.getId());
        }
    }

    private static boolean isInsideShip(Entity entity) {
        return intersectsShip(entity.level(), entity.getBoundingBox());
    }

    private static boolean intersectsShip(Level level, AABB bounds) {
        try {
            Object ships = reflect(getShipsIntersecting, level, bounds);
            return ships instanceof Iterable<?> iterable && iterable.iterator().hasNext();
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
            return false;
        }
    }

    private static boolean isBeingDraggedByShip(Entity entity) {
        try {
            Method getter = entity.getClass().getMethod("getDraggingInformation");
            Object draggingInfo = getter.invoke(entity);
            return draggingInfo != null && (boolean) draggingInfo.getClass()
                .getMethod("isEntityBeingDraggedByAShip").invoke(draggingInfo);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static void rememberSafeWorldPosition(SoldierEntity soldier, SoldierState state) {
        if (soldier.onGround() && isSafeWorldPosition(soldier, soldier.blockPosition())) {
            state.lastSafeWorldPosition = soldier.blockPosition();
        }
    }

    private static boolean tryStartTransport(SoldierEntity soldier, SoldierState state) {
        if (!StevesArmyConfig.VS2_AUTO_TRANSPORT.get() || soldier.getSquadMode() != SquadMode.FOLLOW) {
            return false;
        }
        // Vehicle crew board ships only through the explicit crew paths (egg on seat,
        // crew assign stick, wheel mount-crew, API). Auto-transport would make them
        // owner-linked passengers that get released the moment the player leaves.
        if (soldier.getRole() == SoldierRole.VEHICLE_CREW) {
            return false;
        }
        // A recent manual dismount suppresses automatic re-boarding for a short while.
        if (state.reboardBlockTicks > 0) {
            return false;
        }
        LivingEntity owner = soldier.getOwner();
        if (owner == null || !owner.isAlive() || owner.getVehicle() == null || !isOnShip(owner)) {
            state.seatRetryCooldownTicks = 0;
            return false;
        }
        if (state.seatRetryCooldownTicks > 0) {
            state.seatRetryCooldownTicks--;
            return false;
        }
        logSeatAttempt(soldier, state, owner);
        if (countTransportedFor(owner.getUUID()) >= StevesArmyConfig.VS2_MAX_TRANSPORTED_SOLDIERS.get()) {
            StevesArmyMod.LOGGER.info("[VS2] Seat attempt skipped: transport limit reached for owner={}", owner.getId());
            return false;
        }

        if (tryContraptionSeat(soldier, owner, state) || tryStaticSeat(soldier, owner, state)) {
            stopMovement(soldier);
            return true;
        }
        return false;
    }

    private static boolean tryContraptionSeat(SoldierEntity soldier, LivingEntity owner, SoldierState state) {
        try {
            Object ship = reflect(getShipMountedTo, owner);
            if (ship != null && createInteractiveUtil != null) {
                long shipId = ((Number) getShipId.invoke(ship)).longValue();
                Entity mappedVehicle = (Entity) getContraptionEntityForShip.invoke(createInteractiveUtil, shipId, false);
                if (mappedVehicle != null) {
                    StevesArmyMod.LOGGER.info("[VS2] Create Interactive mapped ship={} to contraption vehicle={}",
                        shipId, mappedVehicle.getId());
                    if (tryAssignContraptionSeat(soldier, owner, state, mappedVehicle)) {
                        return true;
                    }
                } else {
                    StevesArmyMod.LOGGER.info("[VS2] Create Interactive has no contraption for ship={}", shipId);
                }
            }
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
        }

        Entity vehicle = owner.getVehicle();
        while (vehicle != null) {
            if (contraptionEntityClass.isInstance(vehicle)) {
                try {
                    Object contraption = getContraption.invoke(vehicle);
                    if (contraption == null) {
                        StevesArmyMod.LOGGER.info("[VS2] Contraption vehicle={} has no initialized contraption", vehicle.getId());
                        return false;
                    }
                    @SuppressWarnings("unchecked")
                    java.util.List<BlockPos> seats = (java.util.List<BlockPos>) getSeats.invoke(contraption);
                    @SuppressWarnings("unchecked")
                    Map<UUID, Integer> occupied = (Map<UUID, Integer>) getSeatMapping.invoke(contraption);
                    StevesArmyMod.LOGGER.info("[VS2] Contraption vehicle={} seats={} occupied={}",
                        vehicle.getId(), seats.size(), occupied);
                    for (int index = 0; index < seats.size(); index++) {
                        if (occupied.containsValue(index)) {
                            continue;
                        }
                        authorizedMounts.put(soldier.getUUID(), vehicle.getUUID());
                        try {
                            StevesArmyMod.LOGGER.info("[VS2] Trying contraption seat={} soldier={} vehicle={}",
                                index, soldier.getId(), vehicle.getId());
                            addSittingPassenger.invoke(vehicle, soldier, index);
                            if (soldier.isPassenger() && soldier.getVehicle() == vehicle) {
                                state.transportAnchorId = vehicle.getUUID();
                                state.transportOwnerId = owner.getUUID();
                                state.crewSeated = soldier.getRole() == SoldierRole.VEHICLE_CREW;
                                state.transportShipId = getShipId.invoke(reflect(getShipMountedTo, owner)) instanceof Number id
                                    ? id.longValue() : null;
                                state.seatRetryCooldownTicks = 0;
                                state.reboardBlockTicks = 0;

                                syncTransportState(soldier, vehicle, true);

                                StevesArmyMod.LOGGER.info("[VS2] Assigned soldier={} to Create contraption seat={} vehicle={}",
                                    soldier.getId(), index, vehicle.getId());
                                return true;
                            }
                            StevesArmyMod.LOGGER.warn("[VS2] Create seat mount failed soldier={} seat={} vehicle={} passenger={} actualVehicle={}",
                                soldier.getId(), index, vehicle.getId(), soldier.isPassenger(),
                                soldier.getVehicle() == null ? "none" : soldier.getVehicle().getId());
                        } finally {
                            authorizedMounts.remove(soldier.getUUID());
                        }
                    }
                    StevesArmyMod.LOGGER.info("[VS2] No empty contraption seats for soldier={} vehicle={}",
                        soldier.getId(), vehicle.getId());
                    return false;
                } catch (ReflectiveOperationException exception) {
                    logReflectionFailure(exception);
                    return false;
                }
            }
            StevesArmyMod.LOGGER.info("[VS2] Seat chain vehicle={} class={}",
                vehicle.getId(), vehicle.getClass().getName());
            vehicle = vehicle.getVehicle();
        }
        StevesArmyMod.LOGGER.info("[VS2] No AbstractContraptionEntity found in owner vehicle chain owner={}", owner.getId());
        return false;
    }

    private static boolean tryAssignContraptionSeat(SoldierEntity soldier, LivingEntity owner,
        SoldierState state, Entity vehicle) {
        try {
            Object contraption = getContraption.invoke(vehicle);
            if (contraption == null) {
                return false;
            }
            @SuppressWarnings("unchecked")
            java.util.List<BlockPos> seats = (java.util.List<BlockPos>) getSeats.invoke(contraption);
            @SuppressWarnings("unchecked")
            Map<UUID, Integer> occupied = (Map<UUID, Integer>) getSeatMapping.invoke(contraption);
            StevesArmyMod.LOGGER.info("[VS2] Contraption vehicle={} seats={} occupied={}",
                vehicle.getId(), seats.size(), occupied);
            for (int index = 0; index < seats.size(); index++) {
                if (occupied.containsValue(index)) {
                    continue;
                }
                authorizedMounts.put(soldier.getUUID(), vehicle.getUUID());
                try {
                    StevesArmyMod.LOGGER.info("[VS2] Trying contraption seat={} soldier={} vehicle={}",
                        index, soldier.getId(), vehicle.getId());
                    addSittingPassenger.invoke(vehicle, soldier, index);
                    if (soldier.isPassenger() && soldier.getVehicle() == vehicle) {
                        state.transportAnchorId = vehicle.getUUID();
                        state.transportOwnerId = owner.getUUID();
                        state.transportShipId = getShipId.invoke(reflect(getShipMountedTo, owner)) instanceof Number id
                            ? id.longValue() : null;
                        state.seatRetryCooldownTicks = 0;

                        syncTransportState(soldier, vehicle, true);

                        StevesArmyMod.LOGGER.info("[VS2] Assigned soldier={} to Create contraption seat={} vehicle={}",
                            soldier.getId(), index, vehicle.getId());
                        return true;
                    }
                    StevesArmyMod.LOGGER.warn("[VS2] Create seat mount failed soldier={} seat={} vehicle={} passenger={} actualVehicle={}",
                        soldier.getId(), index, vehicle.getId(), soldier.isPassenger(),
                        soldier.getVehicle() == null ? "none" : soldier.getVehicle().getId());
                } finally {
                    authorizedMounts.remove(soldier.getUUID());
                }
            }
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
        }
        return false;
    }

    private static boolean tryStaticSeat(SoldierEntity soldier, LivingEntity owner, SoldierState state) {
        // Crew soldiers board shipyard seats exactly like riflemen; once seated,
        // crewSeated keeps their crew AI ticking so they still man their station.
        try {
            Object ownerShip = reflect(getShipMountedTo, owner);
            if (ownerShip == null) {
                StevesArmyMod.LOGGER.info("[VS2] Static seat fallback: owner={} has no mounted ship", owner.getId());
                return false;
            }
            long ownerShipId = ((Number) getShipId.invoke(ownerShip)).longValue();
            Object mountedData = reflect(getShipMountedToData, owner, (Object) null);
            Object mountPosition = mountedData == null ? null : getMountPosInShip.invoke(mountedData);
            if (!(mountPosition instanceof org.joml.Vector3dc localPosition)) {
                StevesArmyMod.LOGGER.info("[VS2] Static seat fallback has no ship-local owner position owner={}", owner.getId());
                return false;
            }
            BlockPos origin = BlockPos.containing(localPosition.x(), localPosition.y(), localPosition.z());
            StevesArmyMod.LOGGER.info("[VS2] Static seat scan owner={} shipOrigin={} worldVehiclePos={}",
                owner.getId(), origin, owner.getVehicle().position());
            int seatBlocks = 0;
            int rejectedShip = 0;
            int occupiedSeats = 0;
            for (int radius = 0; radius <= 10; radius++) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                            continue;
                        }
                        for (int y = -2; y <= 2; y++) {
                            BlockPos candidate = origin.offset(x, y, z);
                            BlockState blockState = owner.level().getBlockState(candidate);
                            if (!createSeatBlockClass.isInstance(blockState.getBlock())) {
                                continue;
                            }
                            seatBlocks++;
                            Object seatShip = reflect(getShipObjectManagingPos, owner.level(), candidate);
                            if (seatShip == null || ((Number) getShipId.invoke(seatShip)).longValue() != ownerShipId) {
                                rejectedShip++;
                                continue;
                            }
                            if (isCreateSeatOccupied(owner.level(), candidate)) {
                                occupiedSeats++;
                                continue;
                            }
                            StevesArmyMod.LOGGER.info("[VS2] Static seat candidate soldier={} pos={} shipId={}",
                                soldier.getId(), candidate, ownerShipId);
                            authorizedStaticSeats.put(soldier.getUUID(), candidate);
                            try {
                                // SeatBlock.sitDown creates the SeatEntity at the shipyard block position
                                // and mounts the soldier without requiring the soldier to be moved there first.
                                // VS2's own rider/rendering mixins handle the world-space transform.
                                createSeatSitDown.invoke(null, owner.level(), candidate, soldier);
                            } finally {
                                authorizedStaticSeats.remove(soldier.getUUID());
                            }
                            if (soldier.isPassenger() && soldier.getVehicle() != null) {
                                Entity vehicle = soldier.getVehicle();

                                // SeatEntity is at shipyard coordinates — VS2 handles world-space
                                // tracking and rider position transforms.
                                // Do NOT move the seat to world-space.

                                state.transportAnchorId = vehicle.getUUID();
                                state.transportOwnerId = owner.getUUID();
                                state.crewSeated = soldier.getRole() == SoldierRole.VEHICLE_CREW;
                                state.transportShipId = ownerShipId;
                                state.transportSeatPosition = candidate;
                                state.seatRetryCooldownTicks = 0;
                                state.reboardBlockTicks = 0;

                                syncTransportState(soldier, soldier.getVehicle(), true);

                                StevesArmyMod.LOGGER.info("[VS2] Static seat attached soldier={} seat={} vehicle={} soldierPos={} vehiclePos={}",
                                    soldier.getId(), candidate, soldier.getVehicle().getId(), soldier.position(),
                                    soldier.getVehicle().position());
                                if (owner.level() instanceof ServerLevel serverLevel) {
                                    recordHandleLink(serverLevel, ownerShip, soldier, candidate);
                                }
                                return true;
                            }
                            StevesArmyMod.LOGGER.warn("[VS2] Static seat mount did not persist soldier={} seat={} passenger={} vehicle={}",
                                soldier.getId(), candidate, soldier.isPassenger(),
                                soldier.getVehicle() == null ? "none" : soldier.getVehicle().getId());
                        }
                    }
                }
            }
            if (seatBlocks > 0 && occupiedSeats == seatBlocks) {
                state.seatRetryCooldownTicks = FULL_SEAT_RETRY_TICKS;
            }
            StevesArmyMod.LOGGER.info("[VS2] Static seat fallback found no usable seat soldier={} owner={} blocks={} shipRejected={} occupied={}",
                soldier.getId(), owner.getId(), seatBlocks, rejectedShip, occupiedSeats);
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
        }
        return false;
    }

    /**
     * Occupied only when a seat entity at {@code position} actually carries an alive
     * rider: Create's empty SeatEntities self-discard, but tallyho FlexibleSeatEntities
     * persist forever when empty, and a bare entity at the block coords must not block
     * a seat block. Seat entities riding each other (leftover from raw-mount tests)
     * count as unoccupied too — the census logs the rider stack so false negatives
     * stay diagnosable.
     */
    private static boolean isCreateSeatOccupied(Level level, BlockPos position) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        for (Entity seat : serverLevel.getAllEntities()) {
            if (!createSeatEntityClass.isInstance(seat) || !seat.blockPosition().equals(position)) {
                continue;
            }
            for (Entity passenger : seat.getPassengers()) {
                if (passenger != null && passenger.isAlive()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when {@code pos} holds a free Create SeatBlock on the given ship — the
     * precondition for {@link #seatSoldierDirect} to mount there (the same checks
     * {@link #findFreeStaticSeats} applies, for a single position).
     */
    public static boolean isValidStaticSeat(ServerLevel level, @Nullable Object ship, BlockPos pos) {
        initialize();
        if (!available || ship == null || createSeatBlockClass == null) {
            return false;
        }
        try {
            if (!createSeatBlockClass.isInstance(level.getBlockState(pos).getBlock())) {
                return false;
            }
            Long shipId = getShipIdOf(ship);
            if (shipId == null) {
                return false;
            }
            Object seatShip = reflect(getShipObjectManagingPos, level, (Vec3i) pos);
            Long seatShipId = seatShip == null ? null : getShipIdOf(seatShip);
            if (!shipId.equals(seatShipId)) {
                return false;
            }
            return !isCreateSeatOccupied(level, pos);
        } catch (ReflectiveOperationException exception) {
            logReflectionFailure(exception);
            return false;
        }
    }

    /**
     * Seated crew soldiers freeze (their goals never tick), so they can never run
     * the unmounted duty scan in VehicleCrewGoal. Throttled hook that hands the
     * soldier to StationGunnerAI.assignSeatedSoldier, which finds it a station on
     * the same ship and activates the station-side gun AI.
     */
    private static void tickSeatedCrewDuty(SoldierEntity soldier, SoldierState state) {
        if (!state.crewSeated) {
            return;
        }
        if (state.crewDutyScanCooldown > 0) {
            state.crewDutyScanCooldown--;
            return;
        }
        state.crewDutyScanCooldown = CREW_DUTY_SCAN_INTERVAL_TICKS;
        StationGunnerAI.assignSeatedSoldier(soldier, state.transportShipId);
    }

    private static void updateTransport(SoldierEntity soldier, SoldierState state) {
        // Command-driven mounts (transportOwnerId == null) are kept regardless of owner state.
        if (state.transportOwnerId == null) {
            if (soldier.isPassenger() && state.transportAnchorId != null
                && state.transportAnchorId.equals(soldier.getVehicle().getUUID())) {
                soldier.getVehicle().positionRider(soldier);
                stopMovement(soldier);
                tickSeatedCrewDuty(soldier, state);
                return;
            }
            StevesArmyMod.LOGGER.info("[VS2] Command-driven transport lost soldier={} passenger={} expectedAnchor={}",
                soldier.getId(), soldier.isPassenger(), state.transportAnchorId);
            Entity oldSeat = state.transportAnchorId != null && soldier.level() instanceof ServerLevel sl
                ? sl.getEntity(state.transportAnchorId) : null;
            clearShipDraggingState(soldier);
            if (soldier.isPassenger()) {
                soldier.stopRiding();
            }
            clearShipDraggingState(soldier);
            Vec3 postReleasePos = soldier.position();
            // Send empty passenger list after dismount.
            if (oldSeat != null) {
                syncTransportState(soldier, oldSeat, false);
            }
            StevesArmyMod.LOGGER.info("[VS2] Command transport release soldier={} postPos={}",
                soldier.getId(), formatVec3(postReleasePos));
            clearTransportState(state);
            return;
        }

        Entity anchor = getEntity(soldier, state.transportAnchorId);
        if (anchor == null || !soldier.isPassenger() || soldier.getVehicle() != anchor) {
            StevesArmyMod.LOGGER.info("[VS2] Releasing transport soldier={} anchor={} passenger={}",
                soldier.getId(), anchor == null ? "missing" : anchor.getId(), soldier.isPassenger());
            releaseAndClear(soldier, state);
            return;
        }

        // Keep the rider's stored position on its seat. Seats on VS objects live in
        // shipyard space, and a soldier whose stored position stays at its stale
        // world spot (where it stood when seated) makes every AI query straddle the
        // two spaces - VS2 rejects the mixed box and the physics thread starves.
        // Non-player entities are allowed to live at shipyard coords (VS2 only
        // intercepts setPosRaw for players). Mirrors the command-driven branch.
        anchor.positionRider(soldier);
        tickSeatedCrewDuty(soldier, state);

        // HOLD soldiers stay seated regardless of owner state.
        // FOLLOW soldiers release when the owner is no longer on the ship.
        if (soldier.getSquadMode() == SquadMode.HOLD) {
            stopMovement(soldier);
            return;
        }

        // Vehicle crew seats are command-driven: they stay aboard regardless of where
        // the owner is, even if a legacy owner-linked state somehow exists.
        if (soldier.getRole() == SoldierRole.VEHICLE_CREW) {
            stopMovement(soldier);
            return;
        }

        LivingEntity owner = getOwner(soldier, state.transportOwnerId);
        boolean ownerOnShip = owner != null && owner.isAlive() && isTransportOwnerOnShip(owner, state);
        if (ownerOnShip) {
            state.ownerOffShipCount = 0;
            stopMovement(soldier);
            return;
        }

        // The owner-on-ship check flickers false for a couple of ticks while VS2
        // oscillates the rider's seat position between shipyard and world during its
        // physics pass. Releasing on the first false would drop the soldier off the
        // ship to the sea floor, so hold through short dips and only release once the
        // owner has been confirmed off-ship for a sustained window.
        state.ownerOffShipCount++;
        if (state.ownerOffShipCount < RELEASE_DEBOUNCE_TICKS) {
            stopMovement(soldier);
            return;
        }

        StevesArmyMod.LOGGER.info("[VS2] Releasing transport soldier={} anchor={} passenger={} owner={} ownerOnShip={} offShipStreak={}",
            soldier.getId(), anchor.getId(), soldier.isPassenger(),
            owner == null ? "missing" : owner.getId(), ownerOnShip, state.ownerOffShipCount);
        releaseAndClear(soldier, state);
    }

    private static void releaseAndClear(SoldierEntity soldier, SoldierState state) {
        Vec3 preDismountPos = soldier.position();
        Entity vehicle = soldier.isPassenger() ? soldier.getVehicle() : null;
        clearShipDraggingState(soldier);
        if (soldier.isPassenger()) {
            soldier.stopRiding();
        }
        clearShipDraggingState(soldier);
        Vec3 postReleasePos = soldier.position();
        // Send empty passenger list after dismount so the anchor now reports no passengers.
        if (vehicle != null) {
            syncTransportState(soldier, vehicle, false);
        }
        StevesArmyMod.LOGGER.info("[VS2] Transport release soldier={} prePos={} postPos={} dist={}",
            soldier.getId(), formatVec3(preDismountPos), formatVec3(postReleasePos),
            String.format("%.2f", preDismountPos.distanceTo(postReleasePos)));
        clearTransportState(state);
        // Automatic releases (owner left the ship, lost anchor) also exit at the hatch
        // when the vehicle is parked nearby.
        if (StevesArmyConfig.VEHICLE_HANDLES_ENABLED.get()) {
            AnalogWarfareCompat.exitAtRememberedHandle(soldier);
        }
    }

    private static void clearTransportState(SoldierState state) {
        state.transportAnchorId = null;
        state.transportOwnerId = null;
        state.transportShipId = null;
        state.transportSeatPosition = null;
        state.crewSeated = false;
    }

    private static boolean isTransported(SoldierEntity soldier, SoldierState state) {
        return state.transportAnchorId != null && soldier.isPassenger()
            && state.transportAnchorId.equals(soldier.getVehicle().getUUID());
    }

    private static boolean isTransportOwnerOnShip(LivingEntity owner, SoldierState state) {
        // Seated owner: the seat's position resolves to the transport ship.
        Entity vehicle = owner.getVehicle();
        while (vehicle != null) {
            if (createSeatEntityClass.isInstance(vehicle)) {
                try {
                    Vec3 seatWorldPos = vehicle.position();
                    Object ship = reflect(getShipObjectManagingPosDouble, owner.level(), seatWorldPos.x, seatWorldPos.y, seatWorldPos.z);
                    boolean found = ship != null && state.transportShipId != null
                        && ((Number) getShipId.invoke(ship)).longValue() == state.transportShipId;
                    // Throttle diagnostic logging to once per 100 ticks
                    if (found && owner.tickCount % 100 == 0) {
                        StevesArmyMod.LOGGER.info("[VS2] ownerOnShip owner={} seatPos={} shipFound={} expectedShipId={}",
                            owner.getId(), seatWorldPos, ship != null, state.transportShipId);
                    }
                    if (found) return true;
                } catch (ReflectiveOperationException exception) {
                    logReflectionFailure(exception);
                }
            }
            vehicle = vehicle.getVehicle();
        }
        // Walking owner: seat-chain checks can't see someone standing on the deck.
        // A world-space bounding-box intersect is the only reliable test then;
        // without it the transport releases soldiers the moment the owner stands up.
        if (state.transportShipId != null) {
            Object ship = firstShipIntersecting(owner.level(), owner.position(), 3.0D);
            return ship != null && state.transportShipId.equals(getShipIdOf(ship));
        }
        return false;
    }

    private static boolean isMountedToStaticCreateSeat(Entity entity) {
        Entity vehicle = entity.getVehicle();
        while (vehicle != null) {
            if (createSeatEntityClass.isInstance(vehicle)) {
                try {
                    Vec3 seatWorldPos = vehicle.position();
                    return reflect(getShipObjectManagingPosDouble, entity.level(), seatWorldPos.x, seatWorldPos.y, seatWorldPos.z) != null;
                } catch (ReflectiveOperationException exception) {
                    logReflectionFailure(exception);
                    return false;
                }
            }
            vehicle = vehicle.getVehicle();
        }
        return false;
    }

    private static int countTransportedFor(UUID ownerId) {
        int count = 0;
        for (SoldierState state : states.values()) {
            if (ownerId.equals(state.transportOwnerId) && state.transportAnchorId != null) {
                count++;
            }
        }
        return count;
    }

    private static void extractToSafeWorldPosition(SoldierEntity soldier, SoldierState state) {
        stopMovement(soldier);
        if (soldier.isPassenger()) {
            soldier.stopRiding();
        }
        BlockPos destination = findSafePositionOutsideShip(soldier);
        if (destination == null && state.lastSafeWorldPosition != null
            && isSafeWorldPosition(soldier, state.lastSafeWorldPosition)) {
            destination = state.lastSafeWorldPosition;
        }
        if (destination != null) {
            moveToWorldPosition(soldier, destination);
            state.lastSafeWorldPosition = destination;
        }
    }

    @Nullable
    private static BlockPos findSafePositionOutsideShip(SoldierEntity soldier) {
        BlockPos origin = soldier.blockPosition();
        for (int radius : ESCAPE_RADII) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                        continue;
                    }
                    int worldX = origin.getX() + x;
                    int worldZ = origin.getZ() + z;
                    BlockPos surface = new BlockPos(worldX,
                        soldier.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ), worldZ);
                    if (isSafeWorldPosition(soldier, surface)) {
                        return surface;
                    }
                    for (int y = -3; y <= 3; y++) {
                        BlockPos candidate = origin.offset(x, y, z);
                        if (isSafeWorldPosition(soldier, candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos findSafePositionNear(SoldierEntity soldier, BlockPos center) {
        for (int[] offset : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
            for (int y = -1; y <= 1; y++) {
                BlockPos candidate = center.offset(offset[0], y, offset[1]);
                if (isSafeWorldPosition(soldier, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isSafeWorldPosition(SoldierEntity soldier, BlockPos position) {
        BlockState floor = soldier.level().getBlockState(position.below());
        if (floor.getCollisionShape(soldier.level(), position.below()).isEmpty()) {
            return false;
        }
        Vec3 location = Vec3.atBottomCenterOf(position);
        AABB bounds = soldier.getDimensions(Pose.STANDING).makeBoundingBox(location);
        return !intersectsShip(soldier.level(), bounds) && soldier.level().noCollision(soldier, bounds);
    }

    private static void moveToWorldPosition(SoldierEntity soldier, BlockPos position) {
        soldier.moveTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5, soldier.getYRot(), soldier.getXRot());
        soldier.setDeltaMovement(Vec3.ZERO);
    }

    private static void stopMovement(SoldierEntity soldier) {
        soldier.getNavigation().stop();
        soldier.cancelCoverMovement();
        soldier.setDeltaMovement(Vec3.ZERO);
    }

    /** Clears VS2's per-entity dragging state so the soldier is not dragged by the ship after dismount. */
    private static void clearShipDraggingState(SoldierEntity soldier) {
        try {
            Method getter = soldier.getClass().getMethod("getDraggingInformation");
            Object draggingInfo = getter.invoke(soldier);
            if (draggingInfo != null) {
                Class<?> dc = draggingInfo.getClass();
                try {
                    dc.getMethod("setLastShipStoodOn", Long.class).invoke(draggingInfo, (Long) null);
                } catch (NoSuchMethodException e1) {
                    dc.getMethod("setLastShipStoodOn", Object.class).invoke(draggingInfo, (Object) null);
                }
                dc.getMethod("setAddedMovementLastTick", org.joml.Vector3dc.class)
                    .invoke(draggingInfo, new org.joml.Vector3d());
                try {
                    dc.getMethod("setAddedYawRotLastTick", double.class).invoke(draggingInfo, 0.0);
                } catch (NoSuchMethodException e2) {
                    dc.getMethod("setAddedYawRotLastTick", Double.class).invoke(draggingInfo, 0.0);
                }
            }
        } catch (ReflectiveOperationException e) {
            StevesArmyMod.LOGGER.warn("[VS2] Failed to clear dragging state for soldier {}: {}", soldier.getId(), e.getMessage());
        }
    }

    @Nullable
    private static Entity getEntity(SoldierEntity soldier, @Nullable UUID id) {
        return id == null || !(soldier.level() instanceof ServerLevel level) ? null : level.getEntity(id);
    }

    @Nullable
    private static LivingEntity getOwner(SoldierEntity soldier, @Nullable UUID id) {
        Entity entity = getEntity(soldier, id);
        return entity instanceof LivingEntity living ? living : null;
    }

    private static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (!ModList.get().isLoaded(VS2_MOD_ID)) {
            return;
        }
        try {
            Class<?> utils = Class.forName(VS_UTILS_CLASS);
            Class<?> loadedShipClass = Class.forName("org.valkyrienskies.core.api.ships.LoadedShip");
            Class<?> mountedDataClass = Class.forName("org.valkyrienskies.mod.common.entity.ShipMountedToData");
            Class<?> shipClass = Class.forName("org.valkyrienskies.core.api.ships.Ship");
            Class<?> vector3dClass = Class.forName("org.joml.Vector3d");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            // Use MethodHandles to avoid JVM enumerating the entire Kotlin facade method table,
            // which would trigger ClientLevel class loading on dedicated server.
            getShipMountedTo = lookup.findStatic(utils, "getShipMountedTo",
                MethodType.methodType(loadedShipClass, Entity.class));
            getShipsIntersecting = lookup.findStatic(utils, "getShipsIntersecting",
                MethodType.methodType(Iterable.class, Level.class, AABB.class));
            getShipObjectManagingPos = lookup.findStatic(utils, "getShipObjectManagingPos",
                MethodType.methodType(loadedShipClass, Level.class, Vec3i.class));
            getShipObjectManagingPosDouble = lookup.findStatic(utils, "getShipObjectManagingPos",
                MethodType.methodType(loadedShipClass, Level.class, double.class, double.class, double.class));
            getShipMountedToData = lookup.findStatic(utils, "getShipMountedToData",
                MethodType.methodType(mountedDataClass, Entity.class, Float.class));
            toWorldCoordinates = lookup.findStatic(utils, "toWorldCoordinates",
                MethodType.methodType(vector3dClass, shipClass, double.class, double.class, double.class));
            Class<?> raycastUtils = Class.forName(
                "org.valkyrienskies.mod.common.world.RaycastUtilsKt");
            clipIncludeShips = lookup.findStatic(raycastUtils, "clipIncludeShips",
                MethodType.methodType(BlockHitResult.class, Level.class, ClipContext.class));
            try {
                // VS 2.4 added skipWorld, which lets us avoid classifying the
                // vanilla result when looking for a ship-only obstruction.
                clipIncludeShipsOnly = lookup.findStatic(raycastUtils, "clipIncludeShips",
                    MethodType.methodType(BlockHitResult.class, Level.class, ClipContext.class,
                        boolean.class, Long.class, boolean.class));
            } catch (NoSuchMethodException ignored) {
                // VS 2.3 has no skipWorld overload; the fallback below uses the
                // vanilla-only result to identify a ship hit.
                vanillaClip = lookup.findStatic(raycastUtils, "vanillaClip",
                    MethodType.methodType(BlockHitResult.class, BlockGetter.class, ClipContext.class));
            }

            createSeatBlockClass = Class.forName("com.simibubi.create.content.contraptions.actors.seat.SeatBlock");
            createSeatEntityClass = Class.forName("com.simibubi.create.content.contraptions.actors.seat.SeatEntity");
            try {
                createSeatSitDown = createSeatBlockClass.getMethod("sitDown",
                    Level.class, BlockPos.class, Entity.class);
            } catch (NoSuchMethodException ignored) {
                // MCP name fallback
                createSeatSitDown = createSeatBlockClass.getDeclaredMethod("m_7600_",
                    Level.class, BlockPos.class, Entity.class);
            }
            getMountPosInShip = mountedDataClass.getMethod("getMountPosInShip");
            try {
                shipMountedDataProviderClass = Class.forName(
                    "org.valkyrienskies.mod.common.entity.ShipMountedToDataProvider");
                shipGetShipToWorld = lookup.findVirtual(shipClass, "getShipToWorld",
                    MethodType.methodType(org.joml.Matrix4dc.class));
                shipGetWorldToShip = lookup.findVirtual(shipClass, "getWorldToShip",
                    MethodType.methodType(org.joml.Matrix4dc.class));
                // VS2 replaced the old ShipAABB type with JOML's AABBic voxel box;
                // the minX/minY/minZ accessor lookups in getShipyardMin are generic by class.
                Class<?> shipAabbClass = Class.forName("org.joml.primitives.AABBic");
                shipGetAABB = lookup.findVirtual(shipClass, "getShipVoxelAABB",
                    MethodType.methodType(shipAabbClass));
            } catch (ReflectiveOperationException | LinkageError crewHelpers) {
                // Non-fatal: ship transform helpers fall back to raw positions.
                StevesArmyMod.LOGGER.warn("[VS2] Ship-transform helper unavailable: {}",
                    crewHelpers.toString());
            }
            contraptionEntityClass = Class.forName(CONTRAPTION_ENTITY_CLASS);
            getContraption = contraptionEntityClass.getMethod("getContraption");
            Class<?> contraptionClass = Class.forName("com.simibubi.create.content.contraptions.Contraption");
            getSeats = contraptionClass.getMethod("getSeats");
            getSeatMapping = contraptionClass.getMethod("getSeatMapping");
            addSittingPassenger = contraptionEntityClass.getMethod("addSittingPassenger", Entity.class, int.class);
            getShipId = shipClass.getMethod("getId");
            if (ModList.get().isLoaded("create_interactive")) {
                Class<?> interactive = Class.forName("org.valkyrienskies.create_interactive.CreateInteractiveUtil");
                createInteractiveUtil = interactive.getField("INSTANCE").get(null);
                getContraptionEntityForShip = interactive.getMethod("getContraptionEntityForShip", long.class, boolean.class);
                StevesArmyMod.LOGGER.info("[VS2] Create Interactive ship-to-contraption lookup enabled");
            }
            available = true;
            StevesArmyMod.LOGGER.info("[VS2] Enabled soldier ship avoidance and transport compatibility");
        } catch (ReflectiveOperationException | LinkageError exception) {
            logReflectionFailure(exception);
        }
    }

    private static Object reflect(MethodHandle handle, Object... args) throws ReflectiveOperationException {
        try {
            return handle.invokeWithArguments(args);
        } catch (ReflectiveOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            // MethodHandle may wrap the real cause
            Throwable cause = e.getCause();
            if (cause instanceof ReflectiveOperationException roe) {
                throw roe;
            }
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static void logReflectionFailure(Throwable exception) {
        available = false;
        if (!reflectionFailureLogged) {
            reflectionFailureLogged = true;
            StevesArmyMod.LOGGER.warn("[VS2] Soldier compatibility is unavailable: {}", exception.toString());
        }
    }

    private static void logSeatAttempt(SoldierEntity soldier, SoldierState state, LivingEntity owner) {
        long now = System.currentTimeMillis();
        if (now - state.lastSeatAttemptLog < 5000L) {
            return;
        }
        state.lastSeatAttemptLog = now;
        StevesArmyMod.LOGGER.info("[VS2] Seat attempt soldier={} owner={} ownerVehicle={} ownerVehicleId={}",
            soldier.getId(), owner.getId(), owner.getVehicle().getClass().getSimpleName(), owner.getVehicle().getId());
    }

    private static SoldierState getOrCreateState(SoldierEntity soldier) {
        return states.computeIfAbsent(soldier.getUUID(), ignored -> new SoldierState());
    }

    private static final class SoldierState {
        @Nullable private BlockPos lastSafeWorldPosition;
        @Nullable private UUID transportAnchorId;
        @Nullable private UUID transportOwnerId;
        @Nullable private Long transportShipId;
        @Nullable private BlockPos transportSeatPosition;
        private int seatRetryCooldownTicks;
        private int reboardBlockTicks;
        private long lastSeatAttemptLog;
        /** Crew soldiers seated on a station seat keep ticking their AI. */
        private boolean crewSeated;
        /** Ticks the has been off-ship consecutively (release debounce). */
        private int ownerOffShipCount;
        /** Ticks a soldier may stand inside the ship after a mount-handle dismount. */
        private int handleDismountGraceTicks;
        /** Throttle for the seated-crew station duty scan. */
        private int crewDutyScanCooldown;
    }
}

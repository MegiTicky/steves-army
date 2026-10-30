package com.stevesarmy.entity.ai;

import com.stevesarmy.GameClock;
import com.stevesarmy.compat.PlayerReviveCompat;
import com.stevesarmy.combat.GunIntegration;
import com.stevesarmy.combat.cover.CoverBehaviorManager;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SupplyPouchProjectile;
import com.stevesarmy.entity.SupportEntity;
import com.stevesarmy.entity.SupportDeliveryQueue;
import com.stevesarmy.entity.ai.GrenadeTacticalController.SmokeRequestResult;
import com.stevesarmy.inventory.PouchContainer;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SyncSoldierInventoryPacket;
import com.stevesarmy.registry.HealingItems;
import com.stevesarmy.squad.OwnedSoldierRegistry;
import com.stevesarmy.squad.ResupplyConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side support work used only by {@link SupportCoverGoal}.
 *
 * Supports two task types:
 * <ul>
 *   <li>REVIVING – navigates to a downed PlayerRevive player and revives them.</li>
 *   <li>RESUPPLY – throws a homing pouch once within throw range of a friendly
 *       soldier or player whose ammo or healing items are below the squad-wide
 *       threshold. Targets beyond throw range require a trip, which only starts
 *       when the route is safe and stages short of the recipient.</li>
 * </ul>
 */
public final class SupportDutyController {
    private static final double TASK_RADIUS = 32.0;
    private static final double THROW_RANGE = 16.0;
    private static final double THROW_ARRIVE_DISTANCE = 3.0;
    private static final double MOVE_SPEED = 1.0;
    /** Trips stop and throw from this fraction of throw range, short of the recipient. */
    private static final double STAGING_RANGE_FACTOR = 0.8;
    /** Routes whose heading is this close to the primary threat direction are refused. */
    private static final double THREAT_CONE_DOT = 0.34;
    private static final int RESUPPLY_COOLDOWN_TICKS = 80;
    private static final int REVIVE_COOLDOWN_TICKS = 200;
    private static final int THROW_WINDUP_TICKS = 10;
    private static final long CLAIM_TIMEOUT_MS = 15000L;
    private static final int MAX_DUTY_TICKS = 600;
    private static final int SEARCH_RETRY_TICKS = 20;
    private static final int MAX_HOLD_TICKS = 100;
    private static final int NO_PROGRESS_ABORT_TICKS = 40;
    private static final long TARGET_FAILURE_COOLDOWN_MS = 30000L;

    private final SupportEntity soldier;
    private SupportTask currentTask = SupportTask.IDLE;
    private LivingEntity currentTarget;
    private int taskCooldownTicks;
    private int searchCooldownTicks;
    private int pathRecalcTicks;
    private int reviveProgressTicks;
    private int dutyTicks;
    private int throwWindupTicks;
    private int holdTicks;
    private int noProgressTicks;
    private ResupplyConfig currentConfig;
    private boolean rescueSmokePending;
    private int rescueSmokeWaitTicks;
    private boolean reviveProne;
    private String debugLastDecision = "not yet evaluated";
    private String debugLastPouch = "none";
    private String debugRescueSmoke = "none";

    private record ClaimEntry(UUID claimerId, long timestamp) {}
    private record TaskSelection(SupportTask task, LivingEntity target) {}

    private static final Map<UUID, ClaimEntry> CLAIMS = new HashMap<>();
    private static final Map<UUID, Long> TARGET_FAILURES = new HashMap<>();
    private static final Map<UUID, Float> SIM_REVIVE_PROGRESS = new HashMap<>();

    private enum SupportTask {
        IDLE,
        REVIVING,
        RESUPPLY
    }

    public enum DutyResult {
        RUNNING,
        COMPLETE,
        ABORTED
    }

    public SupportDutyController(SupportEntity soldier) {
        this.soldier = soldier;
    }

    public boolean tryStartDuty() {
        if (isActive() || !canWork()) {
            return false;
        }
        // Departures need suppression fully recovered, not merely below the
        // firing line — a pressured medic walking into the open gets shot.
        if (!soldier.getCoverBehaviorManager().getSuppressionTracker().isRecovered()) {
            return false;
        }
        if (taskCooldownTicks > 0) {
            taskCooldownTicks--;
            return false;
        }
        if (searchCooldownTicks > 0) {
            searchCooldownTicks--;
            return false;
        }

        TaskSelection selection = findTask();
        if (selection == null || selection.target() == null) {
            searchCooldownTicks = SEARCH_RETRY_TICKS;
            return false;
        }

        currentTask = selection.task();
        currentTarget = selection.target();
        currentConfig = loadConfig();
        claim(currentTarget);
        return true;
    }

    public boolean isActive() {
        return currentTask != SupportTask.IDLE && currentTarget != null;
    }

    /** Read-only state for simulator diagnostics. */
    public String debugTask() { return currentTask.name(); }
    public UUID debugTarget() { return currentTarget == null ? null : currentTarget.getUUID(); }
    public int debugDutyTicks() { return dutyTicks; }
    public int debugHoldTicks() { return holdTicks; }

    /** Read-only explanation for a specific bleeding player, including gates before selection. */
    public String debugRescueEligibility(ServerPlayer player) {
        ClaimEntry entry = CLAIMS.get(player.getUUID());
        boolean claimed = entry != null && GameClock.nowMs() - entry.timestamp() <= CLAIM_TIMEOUT_MS;
        boolean withinSearch = soldier.getBoundingBox().inflate(TASK_RADIUS).contains(player.position());
        String owner = claimed ? entry.claimerId().toString() : "none";
        return "player=" + player.getName().getString() + " playerId=" + player.getUUID()
            + " distance=" + String.format(java.util.Locale.ROOT, "%.1f", soldier.distanceTo(player))
            + " withinSearch=" + withinSearch + " friendly=" + soldier.isFriendlyTo(player)
            + " task=" + currentTask + " taskTarget=" + debugTarget()
            + " claimOwner=" + owner + " canWork=" + canWork()
            + " recovered=" + soldier.getCoverBehaviorManager().getSuppressionTracker().isRecovered()
            + " suppressed=" + soldier.getCoverBehaviorManager().isSuppressed()
            + " pinned=" + soldier.getCoverBehaviorManager().isPinned()
            + " activeThreat=" + soldier.getThreatAwareness().hasActiveThreat()
            + " taskCooldownChecks=" + taskCooldownTicks + " searchCooldownChecks=" + searchCooldownTicks
            + " smoke=" + debugRescueSmoke;
    }

    /** Snapshot only: never starts a path, changes claims, or consumes cooldowns. */
    public List<String> debugLines(String phase, boolean coverGate) {
        ResupplyConfig config = loadConfig();
        LivingEntity target = currentTarget;
        if (target == null && soldier.level() instanceof ServerLevel level) {
            AABB box = soldier.getBoundingBox().inflate(TASK_RADIUS);
            List<LivingEntity> candidates = new ArrayList<>(level.getEntitiesOfClass(SoldierEntity.class, box,
                t -> t.isAlive() && !(t instanceof SupportEntity) && soldier.isFriendlyTo(t)));
            for (ServerPlayer player : level.players()) {
                if (player.isAlive() && box.contains(player.position()) && soldier.isFriendlyTo(player)) candidates.add(player);
            }
            target = candidates.stream().min(Comparator.comparingDouble(soldier::distanceTo)).orElse(null);
        }
        List<String> lines = new ArrayList<>();
        lines.add("SUPPORT " + phase + " / " + currentTask + (reviveProne ? " prone" : "") + " | last: " + debugLastDecision);
        String work = soldier.isRecalling() ? "recalling" : soldier.isHealing() ? "healing"
            : soldier.isUsingItem() ? "using item" : soldier.isPreparingOrReloading() ? "reloading" : canWork() ? "ready" : "unavailable";
        lines.add("work " + work + " | recovered "
            + soldier.getCoverBehaviorManager().getSuppressionTracker().isRecovered()
            + " | cover gate " + coverGate + " | cooldown checks " + taskCooldownTicks + "/" + searchCooldownTicks);
        if (target == null) {
            lines.add("recipient: none in 32-block search box");
        } else {
            int ammo = countTotalAmmo(target);
            int heals = countHealingItems(target);
            ClaimEntry entry = CLAIMS.get(target.getUUID());
            long now = GameClock.nowMs();
            boolean claimed = entry != null && now - entry.timestamp() <= CLAIM_TIMEOUT_MS;
            Long failed = TARGET_FAILURES.get(target.getUUID());
            long deferMs = failed == null ? 0 : Math.max(0, TARGET_FAILURE_COOLDOWN_MS - (now - failed));
            lines.add((currentTarget == null ? "nearest " : "target ") + target.getName().getString()
                + " | distance " + String.format(java.util.Locale.ROOT, "%.1f", soldier.distanceTo(target))
                + " | claimed " + claimed + " | pending " + (soldier.level() instanceof ServerLevel level && SupportDeliveryQueue.get(level).hasTarget(target.getUUID()))
                + " | defer " + ((deferMs + 999) / 1000) + "s");
            lines.add("ammo " + ammo + "/<" + config.ammoThreshold() + " to " + config.resupplyToAmmo()
                + " | heals " + heals + "/<" + config.healingThreshold() + " to " + config.resupplyToHeals());
            lines.add("supply+space ammo " + supportHasCompatibleAmmoFor(target)
                + " heals " + supportHasHealingItems(target) + " | throw lane " + (hasClearThrow(target) ? "clear" : "blocked"));
        }
        lines.add(currentTask == SupportTask.REVIVING || !"none".equals(debugRescueSmoke)
            ? "rescue smoke: " + debugRescueSmoke : "pouch: " + debugLastPouch);
        if (soldier.level() instanceof ServerLevel level) {
            level.players().stream().filter(p -> p.isAlive() && isBleeding(p)
                && soldier.getBoundingBox().inflate(TASK_RADIUS).contains(p.position()))
                .min(Comparator.comparingDouble(soldier::distanceTo))
                .ifPresent(p -> lines.add("downed " + p.getName().getString()
                    + " | friendly " + soldier.isFriendlyTo(p)
                    + " | threat " + soldier.getThreatAwareness().hasActiveThreat()
                    + " | suppressed " + soldier.getCoverBehaviorManager().isSuppressed()
                    + " | claim " + (CLAIMS.containsKey(p.getUUID()) ? "present" : "none")));
        }
        return lines;
    }

    public void debugPouchOutcome(String outcome) { debugLastPouch = outcome; }
    public void debugRescueSmoke(String outcome) { debugRescueSmoke = outcome; }

    /** Remove only a completed fixture's own bookkeeping, without affecting live soldiers. */
    public static void clearSimState(java.util.Set<UUID> actors) {
        CLAIMS.entrySet().removeIf(e -> actors.contains(e.getKey()) || actors.contains(e.getValue().claimerId()));
        TARGET_FAILURES.keySet().removeIf(actors::contains);
        SIM_REVIVE_PROGRESS.keySet().removeIf(actors::contains);
    }

    /** Fake-player-only simulator substitute for the optional PlayerRevive capability. */
    public static void setSimBleeding(net.minecraftforge.common.util.FakePlayer player) {
        if (!com.stevesarmy.sim.SimManager.running()) throw new IllegalStateException("simulator is not running");
        SIM_REVIVE_PROGRESS.put(player.getUUID(), 0f);
    }

    public static float simReviveProgress(UUID id) { return SIM_REVIVE_PROGRESS.getOrDefault(id, -1f); }

    public void start() {
        pathRecalcTicks = 0;
        reviveProgressTicks = 0;
        dutyTicks = 0;
        throwWindupTicks = 0;
        holdTicks = 0;
        noProgressTicks = 0;
        rescueSmokePending = false;
        rescueSmokeWaitTicks = 0;
        reviveProne = false;
        debugRescueSmoke = "not needed";
        // Kill any stale cover path before duty movement; a throw-from-cover
        // duty must not keep walking the old route.
        soldier.getNavigation().stop();
        if (currentTask == SupportTask.REVIVING && currentTarget instanceof ServerPlayer player
            && soldier.getThreatAwareness().hasActiveThreat()) {
            Vec3 smokePoint = rescueSmokePoint(player);
            SmokeRequestResult result = soldier.getGrenadeTacticalController().requestRescueSmoke(player, smokePoint);
            debugLastDecision = result.accepted() ? "rescue smoke preparing" : "rescue smoke skipped: " + result.reason();
            rescueSmokePending = result.accepted();
        }
        double startRange = currentTask == SupportTask.REVIVING ? THROW_ARRIVE_DISTANCE : THROW_RANGE;
        if (!rescueSmokePending && soldier.distanceToSqr(currentTarget) > startRange * startRange) {
            navigateToTarget();
        }
    }

    public DutyResult tick() {
        if (!isActive()) {
            return DutyResult.ABORTED;
        }
        if (!soldier.isAlive() || currentTarget == null || !currentTarget.isAlive()
            || soldier.isRecalling() || soldier.isHealing() || soldier.isUsingItem()) {
            finish(0);
            return DutyResult.ABORTED;
        }
        if (++dutyTicks > MAX_DUTY_TICKS) {
            finish(RESUPPLY_COOLDOWN_TICKS);
            return DutyResult.ABORTED;
        }

        if (rescueSmokePending) {
            rescueSmokeWaitTicks++;
            if (!soldier.getGrenadeTacticalController().isActive()
                || rescueSmokeWaitTicks > 40) {
                rescueSmokePending = false;
                if (rescueSmokeWaitTicks > 40 && soldier.getGrenadeTacticalController().isSmokeRequestBlocked()) {
                    soldier.getGrenadeTacticalController().cancel();
                }
                debugLastDecision = "rescue smoke attempt finished; approaching";
            } else {
                soldier.getNavigation().stop();
                soldier.setLowCrouching(true);
                return DutyResult.RUNNING;
            }
        }
        if (dutyTicks % 20 == 0) {
            ClaimEntry claim = CLAIMS.get(currentTarget.getUUID());
            if (claim != null && claim.claimerId().equals(soldier.getUUID())) claim(currentTarget);
        }

        // Under fire mid-duty: duck in place and wait for a lull instead of
        // aborting — an abort would ping-pong the medic between the target and
        // cover. Only pinned or a long hold gives up the trip.
        CoverBehaviorManager manager = soldier.getCoverBehaviorManager();
        if (manager.isSuppressed()) {
            holdTicks++;
            soldier.getNavigation().stop();
            soldier.setLowCrouching(true);
            if (manager.isPinned() || holdTicks > MAX_HOLD_TICKS) {
                finish(RESUPPLY_COOLDOWN_TICKS);
                return DutyResult.ABORTED;
            }
            return DutyResult.RUNNING;
        }
        if (holdTicks > 0) {
            holdTicks = 0;
            soldier.setLowCrouching(false);
        }

        return switch (currentTask) {
            case REVIVING -> tickRevive();
            case RESUPPLY -> tickResupply();
            default -> DutyResult.ABORTED;
        };
    }

    public void stop() {
        if (rescueSmokePending && soldier.getGrenadeTacticalController().isSmokeRequestBlocked()) {
            soldier.getGrenadeTacticalController().cancel();
        }
        if (currentTarget != null) {
            releaseClaim(currentTarget);
        }
        currentTask = SupportTask.IDLE;
        currentTarget = null;
        currentConfig = null;
        reviveProgressTicks = 0;
        rescueSmokePending = false;
        rescueSmokeWaitTicks = 0;
        reviveProne = false;
        throwWindupTicks = 0;
        holdTicks = 0;
        noProgressTicks = 0;
        dutyTicks = 0;
        soldier.setLowCrouching(false);
    }

    private boolean canWork() {
        return soldier.isAlive()
            && !soldier.level().isClientSide
            && !soldier.isHealing()
            && !soldier.isUsingItem()
            && !soldier.isPreparingOrReloading()
            && !soldier.isRecalling();
    }

    private ResupplyConfig loadConfig() {
        if (soldier.getServer() == null) return ResupplyConfig.DEFAULT;
        UUID ownerId = soldier.getOwnerUUID().orElse(null);
        if (ownerId == null) return ResupplyConfig.DEFAULT;
        return OwnedSoldierRegistry.get(soldier.getServer()).getResupplyConfig(ownerId);
    }

    // ── Task finding ─────────────────────────────────────────────────────

    private TaskSelection findTask() {
        ResupplyConfig config = loadConfig();

        TaskSelection revive = findReviveTask();
        if (revive != null) {
            debugLastDecision = "revive selected " + revive.target().getName().getString();
            return revive;
        }

        TaskSelection resupply = findResupplyTask(config);
        if (resupply != null) return resupply;

        return null;
    }

    private TaskSelection findReviveTask() {
        if (!PlayerReviveCompat.isLoaded() && SIM_REVIVE_PROGRESS.isEmpty()) return null;
        for (ServerPlayer player : findBleedingPlayers()) {
            if (!isClaimed(player)) {
                return new TaskSelection(SupportTask.REVIVING, player);
            }
        }
        return null;
    }

    private TaskSelection findResupplyTask(ResupplyConfig config) {
        List<LivingEntity> candidates = findTargetsNeedingResupply(config);
        debugLastDecision = candidates.isEmpty() ? "no recipient below threshold" : "no serviceable recipient";
        for (LivingEntity target : candidates) {
            if (isClaimed(target)) { debugLastDecision = "recipient claimed"; continue; }
            if (soldier.level() instanceof ServerLevel level && SupportDeliveryQueue.get(level).hasTarget(target.getUUID())) {
                debugLastDecision = "delivery already pending"; continue;
            }
            if (isOnFailureCooldown(target)) { debugLastDecision = "recipient deferred"; continue; }
            if (!canServe(target, config)) { debugLastDecision = "no compatible supply or capacity"; continue; }
            if (!isDutyApproachSafe(target)) { debugLastDecision = "blocked, unsafe, or unreachable"; continue; }
            debugLastDecision = "selected " + target.getName().getString();
            return new TaskSelection(SupportTask.RESUPPLY, target);
        }
        return null;
    }

    // ── Target searching ─────────────────────────────────────────────────

    private List<ServerPlayer> findBleedingPlayers() {
        if (!(soldier.level() instanceof ServerLevel level)) return List.of();
        AABB box = soldier.getBoundingBox().inflate(TASK_RADIUS);
        List<ServerPlayer> result = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isAlive() && isBleeding(player)
                && box.contains(player.position()) && soldier.isFriendlyTo(player)) {
                result.add(player);
            }
        }
        result.sort(Comparator.comparingDouble(soldier::distanceTo));
        return result;
    }

    private List<LivingEntity> findTargetsNeedingResupply(ResupplyConfig config) {
        if (!(soldier.level() instanceof ServerLevel level)) return List.of();
        AABB box = soldier.getBoundingBox().inflate(TASK_RADIUS);
        List<LivingEntity> result = new ArrayList<>();

        for (SoldierEntity target : level.getEntitiesOfClass(SoldierEntity.class, box,
            t -> t.isAlive() && !(t instanceof SupportEntity) && soldier.isFriendlyTo(t))) {
            if (needsResupply(target, config)) {
                result.add(target);
            }
        }
        for (ServerPlayer player : level.players()) {
            if (player.isAlive() && soldier.isFriendlyTo(player)
                && box.contains(player.position())
                && needsResupply(player, config)) {
                result.add(player);
            }
        }

        result.sort(Comparator.comparingDouble(soldier::distanceTo));
        return result;
    }

    // ── Resupply need checks ────────────────────────────────────────────

    private boolean needsResupply(LivingEntity target, ResupplyConfig config) {
        if (config.ammoThreshold() > 0 && countTotalAmmo(target) < config.ammoThreshold()) return true;
        if (config.healingThreshold() > 0 && countHealingItems(target) < config.healingThreshold()) return true;
        return false;
    }

    /** True when the medic carries at least one item this target's deficit needs. */
    private boolean canServe(LivingEntity target, ResupplyConfig config) {
        if (config.ammoThreshold() > 0
            && countTotalAmmo(target) < config.ammoThreshold()
            && supportHasCompatibleAmmoFor(target)) {
            return true;
        }
        return config.healingThreshold() > 0
            && countHealingItems(target) < config.healingThreshold()
            && supportHasHealingItems(target);
    }

    /**
     * Conservative departure check. An in-range throw needs a clear lane; a
     * trip must also avoid the primary threat direction and be reachable.
     */
    private boolean isDutyApproachSafe(LivingEntity target) {
        if (soldier.distanceToSqr(target) <= THROW_RANGE * THROW_RANGE) {
            return hasClearThrow(target);
        }
        if (soldier.getThreatAwareness().hasActiveThreat()) {
            Vec3 toTarget = target.position().subtract(soldier.position());
            Vec3 threatDir = soldier.getThreatAwareness().getPrimaryDirection(soldier.position());
            if (toTarget.lengthSqr() > 1.0E-4 && threatDir.lengthSqr() > 1.0E-4
                && toTarget.normalize().dot(threatDir.normalize()) > THREAT_CONE_DOT) {
                return false;
            }
        }
        return isTargetReachable(target);
    }

    private boolean isTargetReachable(LivingEntity target) {
        Path path = soldier.getNavigation().createPath(target.blockPosition(), 0);
        if (path == null || path.getEndNode() == null) return false;
        Vec3 end = Vec3.atCenterOf(path.getEndNode().asBlockPos());
        return end.distanceToSqr(target.position()) <= THROW_RANGE * THROW_RANGE;
    }

    // ── Ammo / healing counts ───────────────────────────────────────────

    private int countTotalAmmo(LivingEntity target) {
        return countAmmo(getTargetGunStacks(target), getCarriedStacks(target));
    }

    /** Magazine rounds plus compatible carried rounds for the selected weapon. */
    private static int countAmmo(List<ItemStack> guns, List<ItemStack> carried) {
        if (guns.isEmpty()) return 0;
        int total = 0;
        for (ItemStack gun : guns) {
            total += GunIntegration.getCurrentAmmo(gun);
        }
        for (ItemStack stack : carried) {
            if (!stack.isEmpty()) {
                for (ItemStack gun : guns) {
                    int rounds = GunIntegration.getAmmoCountForGun(gun, stack);
                    if (rounds > 0) {
                        total = (int) Math.min(Integer.MAX_VALUE, (long) total + rounds);
                        break;
                    }
                }
            }
        }
        return total;
    }

    private List<ItemStack> getCarriedStacks(LivingEntity target) {
        List<ItemStack> stacks = new ArrayList<>();
        if (target instanceof SoldierEntity soldier) {
            SoldierInventory inv = soldier.getSoldierInventory();
            for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
                stacks.add(inv.getItem(slot));
            }
        } else if (target instanceof Player player) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                stacks.add(player.getInventory().getItem(i));
            }
        }
        return stacks;
    }

    /** Resupply the active weapon first; other guns cannot mask its shortage. */
    private List<ItemStack> getTargetGunStacks(LivingEntity target) {
        List<ItemStack> guns = new ArrayList<>();
        if (target instanceof SoldierEntity soldier) {
            SoldierInventory inv = soldier.getSoldierInventory();
            if (GunIntegration.isGun(inv.getItem(SoldierInventory.SLOT_MAIN_HAND))) {
                guns.add(inv.getItem(SoldierInventory.SLOT_MAIN_HAND));
                return guns;
            }
            if (GunIntegration.isGun(inv.getItem(SoldierInventory.SLOT_SIDEARM))) {
                guns.add(inv.getItem(SoldierInventory.SLOT_SIDEARM));
                return guns;
            }
            for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
                ItemStack stack = inv.getItem(slot);
                if (!stack.isEmpty() && GunIntegration.isGun(stack)) {
                    guns.add(stack);
                    return guns;
                }
            }
        } else if (target instanceof Player player) {
            if (GunIntegration.isGun(player.getMainHandItem())) {
                guns.add(player.getMainHandItem());
                return guns;
            }
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (!stack.isEmpty() && GunIntegration.isGun(stack)) {
                    guns.add(stack);
                    return guns;
                }
            }
        }
        return guns;
    }

    private static boolean isAmmoForAnyGun(List<ItemStack> guns, ItemStack stack) {
        for (ItemStack gun : guns) {
            if (GunIntegration.getAmmoCountForGun(gun, stack) > 0) return true;
        }
        return false;
    }

    private int countHealingItems(LivingEntity target) {
        if (target instanceof SoldierEntity soldier) {
            SoldierInventory inv = soldier.getSoldierInventory();
            int count = 0;
            for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
                if (HealingItems.isHealingItem(inv.getItem(slot))) {
                    count += inv.getItem(slot).getCount();
                }
            }
            return count;
        }
        if (target instanceof Player player) {
            return countHealingItemsPlayer(player);
        }
        return 0;
    }

    private int countHealingItemsPlayer(Player player) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (HealingItems.isHealingItem(player.getInventory().getItem(i))) {
                count += player.getInventory().getItem(i).getCount();
            }
        }
        return count;
    }

    // ── Support inventory checks ────────────────────────────────────────

    private boolean supportHasCompatibleAmmoFor(LivingEntity target) {
        List<ItemStack> guns = getTargetGunStacks(target);
        if (guns.isEmpty()) return false;
        SoldierInventory inv = soldier.getSoldierInventory();
        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty() && isAmmoForAnyGun(guns, stack) && canAccept(target, stack)) return true;
        }
        return false;
    }

    private boolean supportHasHealingItems(LivingEntity target) {
        SoldierInventory inv = soldier.getSoldierInventory();
        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
            if (HealingItems.isHealingItem(inv.getItem(slot)) && canAccept(target, inv.getItem(slot))) return true;
        }
        return false;
    }

    private boolean canAccept(LivingEntity target, ItemStack item) {
        if (target instanceof SoldierEntity recipient) {
            SoldierInventory inv = recipient.getSoldierInventory();
            for (int i = SoldierInventory.SLOT_GENERAL_START; i < SoldierInventory.INVENTORY_SIZE; i++) {
                if (hasSpace(inv.getItem(i), item)) return true;
            }
        } else if (target instanceof Player player) {
            for (ItemStack stack : player.getInventory().items) {
                if (hasSpace(stack, item)) return true;
            }
        }
        return false;
    }

    private static boolean hasSpace(ItemStack slot, ItemStack item) {
        return slot.isEmpty() || (ItemStack.isSameItemSameTags(slot, item)
            && slot.getCount() < slot.getMaxStackSize());
    }

    private boolean hasClearThrow(LivingEntity target) {
        return soldier.level().clip(new net.minecraft.world.level.ClipContext(
            soldier.getEyePosition(), target.getEyePosition(),
            net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE, soldier)).getType()
            == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    // ── Revive logic ────────────────────────────────────────────────────

    private DutyResult tickRevive() {
        double distance = soldier.distanceTo(currentTarget);
        if (distance > THROW_ARRIVE_DISTANCE) {
            if (reviveProne) {
                reviveProne = false;
                soldier.setLowCrouching(false);
            }
            if (soldier.getNavigation().isDone()) noProgressTicks++;
            else noProgressTicks = 0;
            if (noProgressTicks >= NO_PROGRESS_ABORT_TICKS) {
                debugLastDecision = "aborted: no path progress";
                finish(REVIVE_COOLDOWN_TICKS);
                return DutyResult.ABORTED;
            }
            if (++pathRecalcTicks >= 20) {
                pathRecalcTicks = 0;
                navigateToTarget();
            }
            return DutyResult.RUNNING;
        }

        soldier.getNavigation().stop();
        noProgressTicks = 0;
        reviveProne = true;
        soldier.setLowCrouching(true);
        soldier.getLookControl().setLookAt(
            currentTarget.getX(), currentTarget.getEyeY(), currentTarget.getZ(),
            30.0F, 30.0F);

        if (!(currentTarget instanceof ServerPlayer player) || !isBleeding(player)) {
            finish(0);
            return DutyResult.COMPLETE;
        }

        if (++reviveProgressTicks % 5 == 0 && !addReviveProgress(player)) {
            finish(REVIVE_COOLDOWN_TICKS);
            return DutyResult.ABORTED;
        }
        return DutyResult.RUNNING;
    }

    private Vec3 rescueSmokePoint(ServerPlayer player) {
        Vec3 target = player.position();
        Vec3 offset = target.subtract(soldier.position()).multiply(1.0, 0.0, 1.0);
        if (offset.lengthSqr() < 9.0) {
            offset = new Vec3(1.0, 0.0, 0.0);
            target = soldier.position().add(offset.normalize().scale(3.5));
        }
        return target;
    }

    private static boolean isBleeding(ServerPlayer player) {
        Float simulated = SIM_REVIVE_PROGRESS.get(player.getUUID());
        if (simulated != null && player instanceof net.minecraftforge.common.util.FakePlayer) return simulated < 100f;
        return PlayerReviveCompat.isPlayerBleeding(player);
    }

    private static boolean addReviveProgress(ServerPlayer player) {
        Float simulated = SIM_REVIVE_PROGRESS.get(player.getUUID());
        if (simulated != null && player instanceof net.minecraftforge.common.util.FakePlayer) {
            SIM_REVIVE_PROGRESS.put(player.getUUID(), Math.min(100f, simulated + 5f));
            return true;
        }
        return PlayerReviveCompat.addReviveProgress(player, PlayerReviveCompat.getProgressPerPlayer() * 5);
    }

    // ── Resupply throw logic ────────────────────────────────────────────

    private DutyResult tickResupply() {
        double distanceSq = soldier.distanceToSqr(currentTarget);

        if (distanceSq > THROW_RANGE * THROW_RANGE) {
            if (soldier.getNavigation().isDone()) {
                noProgressTicks++;
            } else {
                noProgressTicks = 0;
            }
            if (noProgressTicks >= NO_PROGRESS_ABORT_TICKS) {
                debugLastDecision = "aborted: no resupply path progress";
                recordTargetFailure(currentTarget);
                finish(RESUPPLY_COOLDOWN_TICKS);
                return DutyResult.ABORTED;
            }
            if (++pathRecalcTicks >= 20) {
                pathRecalcTicks = 0;
                navigateToTarget();
            }
            return DutyResult.RUNNING;
        }

        noProgressTicks = 0;
        soldier.getNavigation().stop();
        soldier.setLowCrouching(true);

        if (soldier.isPreparingOrReloading()) {
            return DutyResult.RUNNING;
        }

        ResupplyConfig config = currentConfig != null ? currentConfig : loadConfig();
        if (!needsResupply(currentTarget, config)) {
            debugLastDecision = "recipient no longer needs supplies";
            finish(0);
            return DutyResult.COMPLETE;
        }

        if (!canServe(currentTarget, config) || !hasClearThrow(currentTarget)) {
            debugLastDecision = "aborted: supply, capacity, or throw lane";
            recordTargetFailure(currentTarget);
            finish(RESUPPLY_COOLDOWN_TICKS);
            return DutyResult.ABORTED;
        }

        soldier.getLookControl().setLookAt(
            currentTarget.getX(), currentTarget.getEyeY(), currentTarget.getZ(),
            30.0F, 30.0F);

        if (++throwWindupTicks < THROW_WINDUP_TICKS) {
            return DutyResult.RUNNING;
        }

        List<ItemStack> payload = buildPayload(currentTarget, config);
        if (payload.isEmpty()) {
            debugLastDecision = "aborted: empty payload";
            finish(RESUPPLY_COOLDOWN_TICKS);
            return DutyResult.ABORTED;
        }

        spawnPouch(currentTarget, payload);
        debugLastDecision = "pouch thrown to " + currentTarget.getName().getString();
        debugLastPouch = "in flight to " + currentTarget.getName().getString();
        syncInventory();
        finish(RESUPPLY_COOLDOWN_TICKS);
        return DutyResult.COMPLETE;
    }

    // ── Payload building ────────────────────────────────────────────────

    private List<ItemStack> buildPayload(LivingEntity target, ResupplyConfig config) {
        List<ItemStack> payload = new ArrayList<>();
        List<ItemStack> space = inventorySpace(target);

        if (config.resupplyToAmmo() > 0 && config.ammoThreshold() > 0) {
            int currentAmmo = countTotalAmmo(target);
            int ammoDeficit = Math.max(0, config.resupplyToAmmo() - currentAmmo);
            if (ammoDeficit > 0) {
                takeCompatibleAmmo(getTargetGunStacks(target), ammoDeficit, payload, space);
            }
        }

        if (config.resupplyToHeals() > 0 && config.healingThreshold() > 0) {
            int currentHeals = countHealingItems(target);
            int healDeficit = Math.max(0, config.resupplyToHeals() - currentHeals);
            if (healDeficit > 0) {
                takeHealingItems(healDeficit, payload, space);
            }
        }

        return payload;
    }

    private static List<ItemStack> inventorySpace(LivingEntity target) {
        List<ItemStack> space = new ArrayList<>();
        if (target instanceof SoldierEntity recipient) {
            SoldierInventory inv = recipient.getSoldierInventory();
            for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE; slot++) {
                space.add(inv.getItem(slot).copy());
            }
        } else if (target instanceof Player player) {
            for (ItemStack stack : player.getInventory().items) space.add(stack.copy());
        }
        return space;
    }

    /** Recheck capacity when a support pouch arrives, since inventory can change in flight. */
    public static boolean canAcceptPayload(LivingEntity target, List<ItemStack> payload) {
        List<ItemStack> space = inventorySpace(target);
        for (ItemStack item : payload) {
            if (reserveCapacity(space, item, item.getCount()) != item.getCount()) return false;
        }
        return !payload.isEmpty();
    }

    private void takeCompatibleAmmo(List<ItemStack> gunStacks, int want, List<ItemStack> payload, List<ItemStack> space) {
        SoldierInventory inv = soldier.getSoldierInventory();
        int remaining = want;

        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE && remaining > 0; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty() || !isAmmoForAnyGun(gunStacks, stack)) continue;

            int roundsPerItem = 0;
            ItemStack one = stack.copyWithCount(1);
            for (ItemStack gun : gunStacks) {
                roundsPerItem = GunIntegration.getAmmoCountForGun(gun, one);
                if (roundsPerItem > 0) break;
            }
            if (roundsPerItem <= 0) continue;
            int take = (int) Math.min(stack.getCount(),
                ((long) remaining + roundsPerItem - 1) / roundsPerItem);
            take = reserveCapacity(space, stack, take);
            if (take == 0) continue;
            ItemStack split = stack.split(take);
            mergeInto(payload, split);
            remaining = (int) Math.max(0, (long) remaining - (long) take * roundsPerItem);
        }
    }

    private void takeHealingItems(int want, List<ItemStack> payload, List<ItemStack> space) {
        SoldierInventory inv = soldier.getSoldierInventory();
        int remaining = want;

        for (int slot = SoldierInventory.SLOT_GENERAL_START; slot < SoldierInventory.INVENTORY_SIZE && remaining > 0; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty() || !HealingItems.isHealingItem(stack)) continue;

            int take = Math.min(stack.getCount(), remaining);
            take = reserveCapacity(space, stack, take);
            if (take == 0) continue;
            ItemStack split = stack.split(take);
            mergeInto(payload, split);
            remaining -= take;
        }
    }

    private static int reserveCapacity(List<ItemStack> space, ItemStack item, int requested) {
        int remaining = requested;
        for (ItemStack slot : space) {
            if (slot.isEmpty() || !ItemStack.isSameItemSameTags(slot, item)) continue;
            int insert = Math.min(remaining, slot.getMaxStackSize() - slot.getCount());
            slot.grow(insert);
            remaining -= insert;
            if (remaining == 0) return requested;
        }
        for (int i = 0; i < space.size() && remaining > 0; i++) {
            if (!space.get(i).isEmpty()) continue;
            int insert = Math.min(remaining, item.getMaxStackSize());
            space.set(i, item.copyWithCount(insert));
            remaining -= insert;
        }
        return requested - remaining;
    }

    private void mergeInto(List<ItemStack> payload, ItemStack toAdd) {
        if (toAdd.isEmpty()) return;
        for (ItemStack existing : payload) {
            if (ItemStack.isSameItemSameTags(existing, toAdd)) {
                int space = existing.getMaxStackSize() - existing.getCount();
                int add = Math.min(toAdd.getCount(), space);
                existing.grow(add);
                toAdd.shrink(add);
                if (toAdd.isEmpty()) return;
            }
        }
        if (!toAdd.isEmpty()) {
            payload.add(toAdd);
        }
    }

    // ── Pouch spawning ──────────────────────────────────────────────────

    private void spawnPouch(LivingEntity target, List<ItemStack> payload) {
        if (!(soldier.level() instanceof ServerLevel level)) return;

        Vec3 origin = soldier.getEyePosition();
        ItemStack pouchStack = PouchContainer.packContents(payload);
        SupportDeliveryQueue.get(level).enqueue(soldier, target, pouchStack);
        SupplyPouchProjectile pouch = SupplyPouchProjectile.forTarget(level, soldier, target);

        Vec3 targetPos = target.getEyePosition();
        Vec3 dir = targetPos.subtract(origin).normalize();
        double dist = origin.distanceTo(targetPos);
        double speed = Math.min(1.6, 0.4 + dist * 0.05);
        pouch.setDeltaMovement(dir.scale(speed).add(0, 0.15, 0));

        level.addFreshEntity(pouch);
    }

    // ── Inventory sync ──────────────────────────────────────────────────

    private void syncInventory() {
        NetworkHandler.sendToTracking(soldier,
            new SyncSoldierInventoryPacket(soldier.getId(), soldier.getSoldierInventory().save()));
    }

    // ── Navigation ──────────────────────────────────────────────────────

    private void navigateToTarget() {
        if (currentTarget == null) {
            return;
        }
        if (currentTask == SupportTask.REVIVING) {
            soldier.getNavigation().moveTo(currentTarget, MOVE_SPEED);
            return;
        }
        // Stage short of the recipient: the pouch homes the rest of the way,
        // so the medic never needs to enter the open ground around the target.
        Vec3 offset = soldier.position().subtract(currentTarget.position());
        Vec3 horizontal = new Vec3(offset.x, 0.0, offset.z);
        double dist = horizontal.length();
        Vec3 staging;
        if (dist < 0.01) {
            staging = currentTarget.position();
        } else {
            double stage = Math.min(dist, THROW_RANGE * STAGING_RANGE_FACTOR);
            staging = currentTarget.position().add(horizontal.scale(stage / dist));
        }
        if (!soldier.getNavigation().moveTo(staging.x, staging.y, staging.z, MOVE_SPEED)) {
            soldier.getNavigation().moveTo(
                currentTarget.getX(), currentTarget.getY(), currentTarget.getZ(), MOVE_SPEED);
        }
    }

    // ── Claim / failure bookkeeping ─────────────────────────────────────

    private boolean isClaimed(LivingEntity target) {
        ClaimEntry entry = CLAIMS.get(target.getUUID());
        if (entry == null) return false;
        if (entry.claimerId().equals(soldier.getUUID())) return true;
        if (GameClock.nowMs() - entry.timestamp() > CLAIM_TIMEOUT_MS) {
            CLAIMS.remove(target.getUUID());
            return false;
        }
        return true;
    }

    private void claim(LivingEntity target) {
        CLAIMS.put(target.getUUID(), new ClaimEntry(soldier.getUUID(), GameClock.nowMs()));
    }

    private void releaseClaim(LivingEntity target) {
        ClaimEntry entry = CLAIMS.get(target.getUUID());
        if (entry != null && entry.claimerId().equals(soldier.getUUID())) {
            CLAIMS.remove(target.getUUID());
        }
    }

    private static boolean isOnFailureCooldown(LivingEntity target) {
        Long failedAt = TARGET_FAILURES.get(target.getUUID());
        if (failedAt == null) return false;
        if (GameClock.nowMs() - failedAt > TARGET_FAILURE_COOLDOWN_MS) {
            TARGET_FAILURES.remove(target.getUUID());
            return false;
        }
        return true;
    }

    private static void recordTargetFailure(LivingEntity target) {
        TARGET_FAILURES.put(target.getUUID(), GameClock.nowMs());
    }

    /** A thrown pouch missed its intended recipient; defer another attempt. */
    public static void noteFailedPouch(UUID targetId) {
        TARGET_FAILURES.put(targetId, GameClock.nowMs());
    }

    private void finish(int cooldown) {
        if (rescueSmokePending && soldier.getGrenadeTacticalController().isSmokeRequestBlocked()) {
            soldier.getGrenadeTacticalController().cancel();
        }
        rescueSmokePending = false;
        reviveProne = false;
        if (currentTarget != null) {
            releaseClaim(currentTarget);
        }
        currentTask = SupportTask.IDLE;
        currentTarget = null;
        currentConfig = null;
        taskCooldownTicks = cooldown;
        reviveProgressTicks = 0;
        throwWindupTicks = 0;
        holdTicks = 0;
        noProgressTicks = 0;
        dutyTicks = 0;
        soldier.setLowCrouching(false);
    }
}

package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.ai.CombatGoalController;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SidearmDebugPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side subscriptions and snapshot building for the sidearm-fallback
 * debug overlay (/stevesarmy_debug sidearm). Per nearby soldier: the fallback
 * state machine, both guns with their ammo, cover/suppression/engagement
 * state, and exactly why the primary has or has not been restored.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class SidearmDebugManager {
    private static final double SOLDIER_SCAN_RADIUS = 128.0;
    private static final int SEND_INTERVAL_TICKS = 10;

    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();
    private static int sendCountdown = SEND_INTERVAL_TICKS;

    private SidearmDebugManager() {}

    public static boolean isEnabled(UUID playerId) {
        return SUBSCRIBERS.contains(playerId);
    }

    public static boolean setEnabled(ServerPlayer player, boolean enabled) {
        if (enabled) {
            SUBSCRIBERS.add(player.getUUID());
        } else {
            SUBSCRIBERS.remove(player.getUUID());
        }
        sendSnapshot(player);
        return enabled;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SUBSCRIBERS.isEmpty()) {
            return;
        }
        if (++sendCountdown < SEND_INTERVAL_TICKS) {
            return;
        }
        sendCountdown = 0;
        MinecraftServer server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (SUBSCRIBERS.contains(player.getUUID())) {
                sendSnapshot(player);
            }
        }
    }

    public static void sendSnapshot(ServerPlayer player) {
        if (!SUBSCRIBERS.contains(player.getUUID())) {
            NetworkHandler.sendTo(player, new SidearmDebugPacket(false, List.of()));
            return;
        }
        ServerLevel level = player.serverLevel();
        List<SidearmDebugPacket.Soldier> soldiers = new ArrayList<>();
        for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class,
                player.getBoundingBox().inflate(SOLDIER_SCAN_RADIUS), SoldierEntity::isAlive)) {
            soldiers.add(snapshot(soldier));
        }
        NetworkHandler.sendTo(player, new SidearmDebugPacket(true, soldiers));
    }

    private static SidearmDebugPacket.Soldier snapshot(SoldierEntity soldier) {
        ItemStack held = soldier.getMainHandItem();
        ItemStack sidearmStack = soldier.getSoldierInventory() != null
            ? soldier.getSoldierInventory().getItem(SoldierInventory.SLOT_SIDEARM)
            : ItemStack.EMPTY;
        CombatGoalController goal = soldier.getCombatGoal();

        boolean suppressed = soldier.getCoverBehaviorManager().isSuppressed();
        boolean pinned = soldier.getCoverBehaviorManager().isPinned();
        String suppState = pinned ? "PINNED" : suppressed ? "PRESSURED" : "CLEAR";

        return new SidearmDebugPacket.Soldier(
            soldier.getUUID(), soldier.getEyePosition(),
            goal != null && goal.isSidearmFallbackActive(),
            goal == null ? 0 : goal.getSidearmRestoreCooldownTicks(),
            goal == null ? "no-goal" : goal.getSidearmFallbackDebugStatus(),
            GunIntegration.getGunId(held),
            GunIntegration.getCurrentAmmo(soldier),
            GunIntegration.getMagazineSize(soldier),
            GunIntegration.isReloading(soldier),
            sidearmStack.isEmpty() ? "" : GunIntegration.getGunId(sidearmStack),
            sidearmStack.isEmpty() ? 0 : GunIntegration.getCurrentAmmo(sidearmStack),
            goal == null ? "" : goal.getSidearmFallbackOriginalGunId(),
            soldier.getCoverBehaviorManager().getState().name(),
            suppState,
            goal != null && goal.hasActiveEngagement());
    }
}

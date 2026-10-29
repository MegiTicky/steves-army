package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SupportEntity;
import com.stevesarmy.entity.ai.SupportCoverGoal;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SupportDebugPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class SupportDebugManager {
    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();
    private static int ticks;
    private SupportDebugManager() {}

    public static void setEnabled(ServerPlayer player, boolean enabled) {
        if (enabled) SUBSCRIBERS.add(player.getUUID());
        else SUBSCRIBERS.remove(player.getUUID());
        send(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SUBSCRIBERS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SUBSCRIBERS.isEmpty() || ++ticks < 10) return;
        ticks = 0;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (SUBSCRIBERS.contains(player.getUUID())) send(player);
        }
    }

    private static void send(ServerPlayer player) {
        if (!SUBSCRIBERS.contains(player.getUUID())) {
            NetworkHandler.sendTo(player, new SupportDebugPacket(false, List.of()));
            return;
        }
        List<SupportDebugPacket.Support> supports = new ArrayList<>();
        for (SupportEntity support : player.serverLevel().getEntitiesOfClass(SupportEntity.class,
            player.getBoundingBox().inflate(48), SupportEntity::isAlive)) {
            if (!(support.getCoverTacticalGoal() instanceof SupportCoverGoal goal)) continue;
            supports.add(new SupportDebugPacket.Support(support.getUUID(), support.getEyePosition(),
                goal.debugDuty().debugLines(goal.debugPhase(), goal.debugCoverGate())));
            if (supports.size() >= 32) break;
        }
        NetworkHandler.sendTo(player, new SupportDebugPacket(true, supports));
    }
}

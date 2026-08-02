package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.EnemyContactSyncPacket;
import com.stevesarmy.network.NetworkHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class EnemyContactTracker {
    private static final int CONTACT_UPDATE_INTERVAL_TICKS = 5;
    private static final int LAST_SEEN_TICKS = 20 * 10;
    private static final int FALLBACK_TEAM_COLOR = 0xFFFF5555;

    private static final Map<UUID, Map<UUID, Contact>> contactsByOwner = new HashMap<>();

    private EnemyContactTracker() {
    }

    public static void reportContact(SoldierEntity observer, LivingEntity threat) {
        if (!(observer.level() instanceof ServerLevel level)) {
            return;
        }
        if (!(observer.getOwner() instanceof ServerPlayer owner) || owner.serverLevel() != level) {
            return;
        }

        long gameTime = level.getGameTime();
        Map<UUID, Contact> contacts = contactsByOwner.computeIfAbsent(owner.getUUID(), ignored -> new HashMap<>());
        Contact contact = contacts.computeIfAbsent(threat.getUUID(), ignored -> new Contact(threat.getUUID(), level));
        contact.level = level;
        contact.headPosition = threat.position().add(0.0, threat.getBbHeight(), 0.0);
        contact.teamColor = getTeamColor(threat);
        contact.lastSeenGameTime = gameTime;
        contact.lostSent = false;

        if (gameTime - contact.lastSentGameTime >= CONTACT_UPDATE_INTERVAL_TICKS) {
            send(owner, contact, true);
            contact.lastSentGameTime = gameTime;
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Iterator<Map.Entry<UUID, Map<UUID, Contact>>> owners = contactsByOwner.entrySet().iterator();
        while (owners.hasNext()) {
            Map.Entry<UUID, Map<UUID, Contact>> ownerEntry = owners.next();
            ServerPlayer owner = event.getServer().getPlayerList().getPlayer(ownerEntry.getKey());
            Iterator<Contact> contacts = ownerEntry.getValue().values().iterator();
            while (contacts.hasNext()) {
                Contact contact = contacts.next();
                if (owner == null || owner.serverLevel() != contact.level) {
                    contacts.remove();
                    continue;
                }

                Entity entity = contact.level.getEntity(contact.threatId);
                if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                    NetworkHandler.sendTo(owner, EnemyContactSyncPacket.remove(contact.threatId));
                    contacts.remove();
                    continue;
                }

                long gameTime = contact.level.getGameTime();
                if (!contact.lostSent && gameTime > contact.lastSeenGameTime) {
                    send(owner, contact, false);
                    contact.lostSent = true;
                }
                if (gameTime - contact.lastSeenGameTime > LAST_SEEN_TICKS) {
                    contacts.remove();
                }
            }

            if (ownerEntry.getValue().isEmpty()) {
                owners.remove();
            }
        }
    }

    public static void removeThreat(ServerLevel level, UUID threatId) {
        Iterator<Map.Entry<UUID, Map<UUID, Contact>>> owners = contactsByOwner.entrySet().iterator();
        while (owners.hasNext()) {
            Map.Entry<UUID, Map<UUID, Contact>> ownerEntry = owners.next();
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerEntry.getKey());
            Iterator<Contact> contacts = ownerEntry.getValue().values().iterator();
            while (contacts.hasNext()) {
                Contact contact = contacts.next();
                if (contact.level == level && contact.threatId.equals(threatId)) {
                    if (owner != null && owner.serverLevel() == level) {
                        NetworkHandler.sendTo(owner, EnemyContactSyncPacket.remove(threatId));
                    }
                    contacts.remove();
                }
            }
            if (ownerEntry.getValue().isEmpty()) {
                owners.remove();
            }
        }
    }

    private static void send(ServerPlayer owner, Contact contact, boolean visible) {
        NetworkHandler.sendTo(owner, EnemyContactSyncPacket.upsert(
            contact.threatId, contact.headPosition, contact.teamColor, visible));
    }

    private static int getTeamColor(LivingEntity threat) {
        if (threat.getTeam() == null) {
            return FALLBACK_TEAM_COLOR;
        }
        Integer color = threat.getTeam().getColor().getColor();
        return color != null ? 0xFF000000 | color : FALLBACK_TEAM_COLOR;
    }

    private static final class Contact {
        private final UUID threatId;
        private ServerLevel level;
        private Vec3 headPosition = Vec3.ZERO;
        private int teamColor = FALLBACK_TEAM_COLOR;
        private long lastSeenGameTime;
        private long lastSentGameTime = Long.MIN_VALUE / 2;
        private boolean lostSent;

        private Contact(UUID threatId, ServerLevel level) {
            this.threatId = threatId;
            this.level = level;
        }
    }
}

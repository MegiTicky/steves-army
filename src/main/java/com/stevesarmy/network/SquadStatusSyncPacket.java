package com.stevesarmy.network;

import com.stevesarmy.client.ClientSquadData;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.squad.FireDiscipline;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.FireTeamAssignment;
import com.stevesarmy.squad.OwnedSoldierRegistry;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public class SquadStatusSyncPacket {
    private final List<SoldierStatusEntry> entries;

    public SquadStatusSyncPacket(List<SoldierStatusEntry> entries) {
        this.entries = entries;
    }

    public List<SoldierStatusEntry> getEntries() {
        return entries;
    }

    public static SquadStatusSyncPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<SoldierStatusEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID entityId = buf.readUUID();
            int entityIntId = buf.readInt();
            String name = buf.readUtf(64);
            float health = buf.readFloat();
            float maxHealth = buf.readFloat();
            int totalAmmo = buf.readVarInt();
            ItemStack gunStack = buf.readItem();
            int squadModeOrdinal = buf.readVarInt();
            int fireDisciplineOrdinal = buf.readVarInt();
            int fireTeamOrdinal = buf.readVarInt();
            int coverState = buf.readVarInt();
            double distance = buf.readDouble();
            int recallTicks = buf.readVarInt();
            boolean loaded = buf.readBoolean();
            entries.add(new SoldierStatusEntry(entityId, entityIntId, name, health, maxHealth, totalAmmo, gunStack,
                squadModeOrdinal, fireDisciplineOrdinal, fireTeamOrdinal, coverState, distance, recallTicks, loaded));
        }
        return new SquadStatusSyncPacket(entries);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (SoldierStatusEntry entry : entries) {
            buf.writeUUID(entry.entityId);
            buf.writeInt(entry.entityIntId);
            buf.writeUtf(entry.name, 64);
            buf.writeFloat(entry.health);
            buf.writeFloat(entry.maxHealth);
            buf.writeVarInt(entry.totalAmmo);
            buf.writeItem(entry.gunStack);
            buf.writeVarInt(entry.squadModeOrdinal);
            buf.writeVarInt(entry.fireDisciplineOrdinal);
            buf.writeVarInt(entry.fireTeamOrdinal);
            buf.writeVarInt(entry.coverState);
            buf.writeDouble(entry.distance);
            buf.writeVarInt(entry.recallTicks);
            buf.writeBoolean(entry.loaded);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientSquadData.INSTANCE.update(entries);
        });
        ctx.get().setPacketHandled(true);
    }

    public static SquadStatusSyncPacket createForPlayer(ServerPlayer player) {
        List<SoldierStatusEntry> entries = new ArrayList<>();
        if (player.getServer() != null) {
            OwnedSoldierRegistry registry = OwnedSoldierRegistry.get(player.getServer());
            Map<UUID, SoldierEntity> loadedSoldiers = new java.util.HashMap<>();
            for (ServerLevel level : player.getServer().getAllLevels()) {
                for (var entity : level.getEntities().getAll()) {
                    if (entity instanceof SoldierEntity soldier && soldier.isOwnedBy(player)) {
                        if (soldier.isAlive()) {
                            loadedSoldiers.put(soldier.getUUID(), soldier);
                            registry.refresh(soldier, level);
                        } else {
                            registry.remove(soldier.getUUID());
                        }
                    }
                }
            }
            registry.pruneDeadEntries();
            FireTeamAssignment fireTeamAssignment = FireTeamAssignment.get(player.getServer().overworld(), player.getUUID());
            for (OwnedSoldierRegistry.Entry snapshot : registry.getOwned(player.getUUID())) {
                SoldierEntity soldier = loadedSoldiers.get(snapshot.soldierId());
                boolean loaded = soldier != null && soldier.isAlive();
                int teamOrdinal = loaded ? fireTeamAssignment.getTeamFor(soldier.getUUID()).ordinal() : snapshot.fireTeam();
                entries.add(new SoldierStatusEntry(
                    snapshot.soldierId(),
                    loaded ? soldier.getId() : -1,
                    loaded ? soldier.getName().getString() : snapshot.name(),
                    loaded ? soldier.getHealth() : snapshot.health(),
                    loaded ? soldier.getMaxHealth() : snapshot.maxHealth(),
                    loaded && soldier.getCombatGoal() != null ? soldier.getCombatGoal().getTotalAmmo() : snapshot.totalAmmo(),
                    loaded ? soldier.getSoldierInventory().getItem(SoldierInventory.SLOT_MAIN_HAND).copy() : snapshot.gunStack(),
                    loaded ? soldier.getSquadMode().ordinal() : snapshot.squadMode(),
                    loaded ? soldier.getFireDiscipline().ordinal() : snapshot.fireDiscipline(),
                    teamOrdinal,
                    loaded ? soldier.getSyncedCoverState() : snapshot.coverState(),
                    loaded ? soldier.distanceTo(player) : -1.0D,
                    loaded ? soldier.getRecallTicks() : snapshot.recallTicks(),
                    loaded));
            }
        }
        return new SquadStatusSyncPacket(entries);
    }

    public static class SoldierStatusEntry {
        public final UUID entityId;
        public final int entityIntId;
        public final String name;
        public final float health;
        public final float maxHealth;
        public final int totalAmmo;
        public final ItemStack gunStack;
        public final int squadModeOrdinal;
        public final int fireDisciplineOrdinal;
        public final int fireTeamOrdinal;
        public final int coverState;
        public final double distance;
        public final int recallTicks;
        public final boolean loaded;

        public SoldierStatusEntry(UUID entityId, int entityIntId, String name, float health, float maxHealth,
                                    int totalAmmo, ItemStack gunStack, int squadModeOrdinal,
                                   int fireDisciplineOrdinal, int fireTeamOrdinal,
                                   int coverState, double distance, int recallTicks, boolean loaded) {
            this.entityId = entityId;
            this.entityIntId = entityIntId;
            this.name = name;
            this.health = health;
            this.maxHealth = maxHealth;
            this.totalAmmo = totalAmmo;
            this.gunStack = gunStack;
            this.squadModeOrdinal = squadModeOrdinal;
            this.fireDisciplineOrdinal = fireDisciplineOrdinal;
            this.fireTeamOrdinal = fireTeamOrdinal;
            this.coverState = coverState;
            this.distance = distance;
            this.recallTicks = recallTicks;
            this.loaded = loaded;
        }

        public SquadMode getSquadMode() { return SquadMode.values()[squadModeOrdinal % SquadMode.values().length]; }
        public FireDiscipline getFireDiscipline() { return FireDiscipline.values()[fireDisciplineOrdinal % FireDiscipline.values().length]; }
        public FireTeam getFireTeam() { return FireTeam.values()[fireTeamOrdinal % FireTeam.values().length]; }
    }
}

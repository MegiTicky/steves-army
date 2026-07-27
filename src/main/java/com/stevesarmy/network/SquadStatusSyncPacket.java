package com.stevesarmy.network;

import com.stevesarmy.client.ClientSquadData;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.squad.FireDiscipline;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.FireTeamAssignment;
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
            entries.add(new SoldierStatusEntry(entityId, entityIntId, name, health, maxHealth, totalAmmo, gunStack,
                squadModeOrdinal, fireDisciplineOrdinal, fireTeamOrdinal, coverState, distance, recallTicks));
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
        if (player.level() instanceof ServerLevel) {
            ServerLevel serverLevel = (ServerLevel) player.level();
            List<SoldierEntity> soldiers = new ArrayList<>();
            for (var entity : serverLevel.getEntities().getAll()) {
                if (entity instanceof SoldierEntity s && s.isOwnedBy(player)) {
                    soldiers.add(s);
                }
            }
            FireTeamAssignment fireTeamAssignment = FireTeamAssignment.get(serverLevel, player.getUUID());
            soldiers.sort(Comparator
                .comparingInt((SoldierEntity soldier) -> fireTeamAssignment.getTeamFor(soldier.getUUID()).ordinal())
                .thenComparing(soldier -> soldier.getName().getString(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SoldierEntity::getUUID));
            for (SoldierEntity soldier : soldiers) {

                ItemStack gunStack = ItemStack.EMPTY;
                int totalAmmo = 0;
                SoldierInventory inv = soldier.getSoldierInventory();
                if (inv != null) {
                    ItemStack mainHand = inv.getItem(SoldierInventory.SLOT_MAIN_HAND);
                    if (!mainHand.isEmpty()) {
                        gunStack = mainHand.copy();
                    }
                }
                if (soldier.getCombatGoal() != null) {
                    totalAmmo = soldier.getCombatGoal().getTotalAmmo();
                }

                entries.add(new SoldierStatusEntry(
                    soldier.getUUID(),
                    soldier.getId(),
                    soldier.getName().getString(),
                    soldier.getHealth(),
                    soldier.getMaxHealth(),
                    totalAmmo,
                    gunStack,
                    soldier.getSquadMode().ordinal(),
                    soldier.getFireDiscipline().ordinal(),
                    fireTeamAssignment.getTeamFor(soldier.getUUID()).ordinal(),
                    soldier.getSyncedCoverState(),
                    soldier.distanceTo(player),
                    soldier.getRecallTicks()
                ));
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

        public SoldierStatusEntry(UUID entityId, int entityIntId, String name, float health, float maxHealth,
                                    int totalAmmo, ItemStack gunStack, int squadModeOrdinal,
                                   int fireDisciplineOrdinal, int fireTeamOrdinal,
                                   int coverState, double distance, int recallTicks) {
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
        }

        public SquadMode getSquadMode() { return SquadMode.values()[squadModeOrdinal % SquadMode.values().length]; }
        public FireDiscipline getFireDiscipline() { return FireDiscipline.values()[fireDisciplineOrdinal % FireDiscipline.values().length]; }
        public FireTeam getFireTeam() { return FireTeam.values()[fireTeamOrdinal % FireTeam.values().length]; }
    }
}

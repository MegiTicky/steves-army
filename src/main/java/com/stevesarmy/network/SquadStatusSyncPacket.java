package com.stevesarmy.network;

import com.stevesarmy.client.ClientSquadData;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.squad.FireDiscipline;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
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
            String gunName = buf.readUtf(64);
            int squadModeOrdinal = buf.readVarInt();
            int fireDisciplineOrdinal = buf.readVarInt();
            int coverState = buf.readVarInt();
            entries.add(new SoldierStatusEntry(entityId, entityIntId, name, health, maxHealth, totalAmmo, gunName,
                squadModeOrdinal, fireDisciplineOrdinal, coverState));
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
            buf.writeUtf(entry.gunName, 64);
            buf.writeVarInt(entry.squadModeOrdinal);
            buf.writeVarInt(entry.fireDisciplineOrdinal);
            buf.writeVarInt(entry.coverState);
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
            for (SoldierEntity soldier : soldiers) {

                String gunName = "";
                int totalAmmo = 0;
                SoldierInventory inv = soldier.getSoldierInventory();
                if (inv != null) {
                    ItemStack mainHand = inv.getItem(SoldierInventory.SLOT_MAIN_HAND);
                    if (!mainHand.isEmpty()) {
                        gunName = mainHand.getHoverName().getString();
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
                    gunName,
                    soldier.getSquadMode().ordinal(),
                    soldier.getFireDiscipline().ordinal(),
                    soldier.getSyncedCoverState()
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
        public final String gunName;
        public final int squadModeOrdinal;
        public final int fireDisciplineOrdinal;
        public final int coverState;

        public SoldierStatusEntry(UUID entityId, int entityIntId, String name, float health, float maxHealth,
                                   int totalAmmo, String gunName, int squadModeOrdinal,
                                   int fireDisciplineOrdinal,
                                   int coverState) {
            this.entityId = entityId;
            this.entityIntId = entityIntId;
            this.name = name;
            this.health = health;
            this.maxHealth = maxHealth;
            this.totalAmmo = totalAmmo;
            this.gunName = gunName;
            this.squadModeOrdinal = squadModeOrdinal;
            this.fireDisciplineOrdinal = fireDisciplineOrdinal;
            this.coverState = coverState;
        }

        public SquadMode getSquadMode() { return SquadMode.values()[squadModeOrdinal % SquadMode.values().length]; }
        public FireDiscipline getFireDiscipline() { return FireDiscipline.values()[fireDisciplineOrdinal % FireDiscipline.values().length]; }
    }
}
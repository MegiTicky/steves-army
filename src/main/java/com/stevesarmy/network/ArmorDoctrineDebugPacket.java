package com.stevesarmy.network;

import com.stevesarmy.client.ClientArmorDoctrineDebugData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Owner-only debug snapshots for the armor doctrine: hard-target contacts
 * known to nearby squads, and the per-soldier role/gate state that drives
 * the hide, displace, suppress, and hunter-engage reactions.
 */
public final class ArmorDoctrineDebugPacket {
    private static final int MAX_CONTACTS = 16;
    private static final int MAX_SOLDIERS = 48;

    public record Contact(UUID threatId, Vec3 aimPoint, Vec3 hullCenter, Vec3 velocity,
                          boolean suppressed, long ageTicks, double accuracy) {}

    public record Soldier(UUID soldierId, Vec3 pos, boolean hunter, boolean squadHasAt,
                          boolean exposed, boolean displace, boolean ducked,
                          Vec3 coverPos, UUID suppressionTargetId) {}

    private final int mode;
    private final List<Contact> contacts;
    private final List<Soldier> soldiers;

    public ArmorDoctrineDebugPacket(int mode, List<Contact> contacts, List<Soldier> soldiers) {
        this.mode = mode;
        this.contacts = List.copyOf(contacts);
        this.soldiers = List.copyOf(soldiers);
    }

    public ArmorDoctrineDebugPacket(FriendlyByteBuf buf) {
        mode = buf.readVarInt();
        int contactCount = Math.min(buf.readVarInt(), MAX_CONTACTS);
        List<Contact> decodedContacts = new ArrayList<>(contactCount);
        for (int i = 0; i < contactCount; i++) {
            decodedContacts.add(new Contact(buf.readUUID(), readVec(buf), readVec(buf),
                readNullableVec(buf), buf.readBoolean(), buf.readVarInt(), buf.readFloat()));
        }
        int soldierCount = Math.min(buf.readVarInt(), MAX_SOLDIERS);
        List<Soldier> decodedSoldiers = new ArrayList<>(soldierCount);
        for (int i = 0; i < soldierCount; i++) {
            decodedSoldiers.add(new Soldier(buf.readUUID(), readVec(buf), buf.readBoolean(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                readNullableVec(buf), readNullableUuid(buf)));
        }
        contacts = List.copyOf(decodedContacts);
        soldiers = List.copyOf(decodedSoldiers);
    }

    public static void encode(ArmorDoctrineDebugPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.mode);
        buf.writeVarInt(Math.min(packet.contacts.size(), MAX_CONTACTS));
        for (int i = 0; i < packet.contacts.size() && i < MAX_CONTACTS; i++) {
            Contact contact = packet.contacts.get(i);
            buf.writeUUID(contact.threatId());
            writeVec(buf, contact.aimPoint());
            writeVec(buf, contact.hullCenter());
            writeNullableVec(buf, contact.velocity());
            buf.writeBoolean(contact.suppressed());
            buf.writeVarInt((int) Math.min(contact.ageTicks(), Integer.MAX_VALUE));
            buf.writeFloat((float) contact.accuracy());
        }
        buf.writeVarInt(Math.min(packet.soldiers.size(), MAX_SOLDIERS));
        for (int i = 0; i < packet.soldiers.size() && i < MAX_SOLDIERS; i++) {
            Soldier soldier = packet.soldiers.get(i);
            buf.writeUUID(soldier.soldierId());
            writeVec(buf, soldier.pos());
            buf.writeBoolean(soldier.hunter());
            buf.writeBoolean(soldier.squadHasAt());
            buf.writeBoolean(soldier.exposed());
            buf.writeBoolean(soldier.displace());
            buf.writeBoolean(soldier.ducked());
            writeNullableVec(buf, soldier.coverPos());
            writeNullableUuid(buf, soldier.suppressionTargetId());
        }
    }

    public static void handle(ArmorDoctrineDebugPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> ClientArmorDoctrineDebugData.INSTANCE.receive(packet)));
        ctx.get().setPacketHandled(true);
    }

    public int mode() { return mode; }
    public List<Contact> contacts() { return contacts; }
    public List<Soldier> soldiers() { return soldiers; }

    private static void writeVec(FriendlyByteBuf buf, Vec3 value) {
        buf.writeDouble(value.x);
        buf.writeDouble(value.y);
        buf.writeDouble(value.z);
    }

    private static Vec3 readVec(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    private static void writeNullableVec(FriendlyByteBuf buf, Vec3 value) {
        buf.writeBoolean(value != null);
        if (value != null) writeVec(buf, value);
    }

    private static Vec3 readNullableVec(FriendlyByteBuf buf) {
        return buf.readBoolean() ? readVec(buf) : null;
    }

    private static void writeNullableUuid(FriendlyByteBuf buf, UUID value) {
        buf.writeBoolean(value != null);
        if (value != null) buf.writeUUID(value);
    }

    private static UUID readNullableUuid(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUUID() : null;
    }
}

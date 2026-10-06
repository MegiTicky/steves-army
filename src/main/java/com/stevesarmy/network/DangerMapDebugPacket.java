package com.stevesarmy.network;

import com.stevesarmy.client.ClientDangerMapDebugData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Debug snapshots for the danger-map overlay (/stevesarmy_debug dangermap):
 * baked cells around the viewer (packed position + score + per-height bits)
 * and each squad's active threat view.
 */
public final class DangerMapDebugPacket {
    private static final int MAX_CELLS = 2048;
    private static final int MAX_THREATS = 24;

    /** Cell center at the standing surface: (cx*2+1, surfaceY, cz*2+1) packed via BlockPos.asLong. */
    public record Cell(long packedPos, int score, boolean eyeExposed, boolean legExposed) {}

    public record Threat(Vec3 pos, float weight, boolean hardTarget) {}

    private final boolean renderEnabled;
    private final List<Cell> cells;
    private final List<Threat> threats;

    public DangerMapDebugPacket(boolean renderEnabled, List<Cell> cells, List<Threat> threats) {
        this.renderEnabled = renderEnabled;
        this.cells = List.copyOf(cells);
        this.threats = List.copyOf(threats);
    }

    public DangerMapDebugPacket(FriendlyByteBuf buf) {
        renderEnabled = buf.readBoolean();
        int cellCount = Math.min(buf.readVarInt(), MAX_CELLS);
        List<Cell> decodedCells = new ArrayList<>(cellCount);
        for (int i = 0; i < cellCount; i++) {
            decodedCells.add(new Cell(buf.readLong(), buf.readUnsignedByte(),
                buf.readBoolean(), buf.readBoolean()));
        }
        cells = List.copyOf(decodedCells);

        int threatCount = Math.min(buf.readVarInt(), MAX_THREATS);
        List<Threat> decodedThreats = new ArrayList<>(threatCount);
        for (int i = 0; i < threatCount; i++) {
            decodedThreats.add(new Threat(readVec(buf), buf.readFloat(), buf.readBoolean()));
        }
        threats = List.copyOf(decodedThreats);
    }

    public static void encode(DangerMapDebugPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.renderEnabled);
        buf.writeVarInt(Math.min(packet.cells.size(), MAX_CELLS));
        for (int i = 0; i < packet.cells.size() && i < MAX_CELLS; i++) {
            Cell cell = packet.cells.get(i);
            buf.writeLong(cell.packedPos());
            buf.writeByte(Math.max(0, Math.min(100, cell.score())));
            buf.writeBoolean(cell.eyeExposed());
            buf.writeBoolean(cell.legExposed());
        }
        buf.writeVarInt(Math.min(packet.threats.size(), MAX_THREATS));
        for (int i = 0; i < packet.threats.size() && i < MAX_THREATS; i++) {
            Threat threat = packet.threats.get(i);
            writeVec(buf, threat.pos());
            buf.writeFloat(threat.weight());
            buf.writeBoolean(threat.hardTarget());
        }
    }

    public static void handle(DangerMapDebugPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> ClientDangerMapDebugData.INSTANCE.receive(packet)));
        ctx.get().setPacketHandled(true);
    }

    public boolean renderEnabled() { return renderEnabled; }
    public List<Cell> cells() { return cells; }
    public List<Threat> threats() { return threats; }

    private static void writeVec(FriendlyByteBuf buf, Vec3 value) {
        buf.writeDouble(value.x);
        buf.writeDouble(value.y);
        buf.writeDouble(value.z);
    }

    private static Vec3 readVec(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}

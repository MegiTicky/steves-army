package com.stevesarmy.network;

import com.stevesarmy.client.ClientDangerMapDebugData;
import net.minecraft.core.BlockPos;
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
    private static final int MAX_CORRIDORS = 4;
    private static final int MAX_CORRIDOR_CELLS = 256;

    /** Cell center at the standing surface: (cx*2+1, surfaceY, cz*2+1) packed via BlockPos.asLong. */
    public record Cell(long packedPos, int score, boolean eyeExposed, boolean legExposed) {}

    public record Threat(Vec3 pos, float weight, boolean hardTarget) {}

    /**
     * One soldier's last captured pathfinding corridor: sampled cells (same
     * packing as {@link Cell}, {@code score} = danger multiplier 0-100) plus
     * the route target and the role weight applied to it in A*.
     */
    public record Corridor(BlockPos target, float weight, List<Cell> cells) {}

    /**
     * Per-subscriber bake stats for a quick MSPT sanity check.
     * {@code cells} = cells near the viewer (sent); {@code bakedCells} = total
     * actually baked across nearby squads; {@code surfaceMiss} = bakes
     * abandoned to an unloaded chunk probe (rays spent, no cell stored).
     */
    public record Stats(int cells, int bakedCells, int threats, int raysLastTick,
                        int rayBudget, int surfaceMiss) {}

    private final boolean renderEnabled;
    private final List<Cell> cells;
    private final List<Threat> threats;
    private final List<Corridor> corridors;
    private final Stats stats;

    public DangerMapDebugPacket(boolean renderEnabled, List<Cell> cells, List<Threat> threats,
                                List<Corridor> corridors, Stats stats) {
        this.renderEnabled = renderEnabled;
        this.cells = List.copyOf(cells);
        this.threats = List.copyOf(threats);
        this.corridors = List.copyOf(corridors);
        this.stats = stats;
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

        int corridorCount = Math.min(buf.readVarInt(), MAX_CORRIDORS);
        List<Corridor> decodedCorridors = new ArrayList<>(corridorCount);
        for (int i = 0; i < corridorCount; i++) {
            BlockPos target = buf.readBlockPos();
            float weight = buf.readFloat();
            int corridorCellCount = Math.min(buf.readVarInt(), MAX_CORRIDOR_CELLS);
            List<Cell> corridorCells = new ArrayList<>(corridorCellCount);
            for (int j = 0; j < corridorCellCount; j++) {
                corridorCells.add(new Cell(buf.readLong(), buf.readUnsignedByte(), false, false));
            }
            decodedCorridors.add(new Corridor(target, weight, List.copyOf(corridorCells)));
        }
        corridors = List.copyOf(decodedCorridors);
        stats = new Stats(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
            buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public static void encode(DangerMapDebugPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.renderEnabled);
        buf.writeVarInt(Math.min(packet.cells.size(), MAX_CELLS));
        for (Cell cell : packet.cells) {
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
        buf.writeVarInt(Math.min(packet.corridors.size(), MAX_CORRIDORS));
        for (int i = 0; i < packet.corridors.size() && i < MAX_CORRIDORS; i++) {
            Corridor corridor = packet.corridors.get(i);
            buf.writeBlockPos(corridor.target());
            buf.writeFloat(corridor.weight());
            buf.writeVarInt(Math.min(corridor.cells().size(), MAX_CORRIDOR_CELLS));
            for (int j = 0; j < corridor.cells().size() && j < MAX_CORRIDOR_CELLS; j++) {
                Cell cell = corridor.cells().get(j);
                buf.writeLong(cell.packedPos());
                buf.writeByte(Math.max(0, Math.min(100, cell.score())));
            }
        }
        buf.writeVarInt(packet.stats.cells());
        buf.writeVarInt(packet.stats.bakedCells());
        buf.writeVarInt(packet.stats.threats());
        buf.writeVarInt(packet.stats.raysLastTick());
        buf.writeVarInt(packet.stats.rayBudget());
        buf.writeVarInt(packet.stats.surfaceMiss());
    }

    public static void handle(DangerMapDebugPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> ClientDangerMapDebugData.INSTANCE.receive(packet)));
        ctx.get().setPacketHandled(true);
    }

    public boolean renderEnabled() { return renderEnabled; }
    public List<Cell> cells() { return cells; }
    public List<Threat> threats() { return threats; }
    public List<Corridor> corridors() { return corridors; }
    public Stats stats() { return stats; }

    private static void writeVec(FriendlyByteBuf buf, Vec3 value) {
        buf.writeDouble(value.x);
        buf.writeDouble(value.y);
        buf.writeDouble(value.z);
    }

    private static Vec3 readVec(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}

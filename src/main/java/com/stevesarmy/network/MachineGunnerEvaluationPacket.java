package com.stevesarmy.network;

import com.stevesarmy.client.CoverDebugRenderer;
import com.stevesarmy.combat.cover.CoverDebugManager;
import com.stevesarmy.combat.cover.FiringPositionFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class MachineGunnerEvaluationPacket {
    private static final int MAX_TARGETS = 24;
    private static final int MAX_CANDIDATES = FiringPositionFinder.MAX_PATH_CHECK_CANDIDATES;

    private final int entityId;
    private final BlockPos center;
    private final BlockPos anchor;
    private final int targetCount;
    private final int coverTargetCount;
    private final boolean gridFallback;
    private final int coverChecked;
    private final int proneChecked;
    private final int rejectedAccess;
    private final int activeTargetCount;
    private final int lastSeenCount;
    private final int peekTargetCount;
    private final List<Target> targets;
    private final List<Candidate> candidates;
    private final String failure;

    public record Candidate(BlockPos position, int rank, int posture, float access,
                            float protection, float score, boolean pathChecked,
                            boolean pathExists, boolean canReach) {}

    public record Target(Vec3 position, int category, float weight, float freshness) {}

    public MachineGunnerEvaluationPacket(int entityId, BlockPos center, BlockPos anchor,
                                         int targetCount, int coverTargetCount, boolean gridFallback,
                                         int coverChecked, int proneChecked, int rejectedAccess,
                                         int activeTargetCount, int lastSeenCount, int peekTargetCount,
                                         List<Target> targets, List<Candidate> candidates, String failure) {
        this.entityId = entityId;
        this.center = center;
        this.anchor = anchor;
        this.targetCount = targetCount;
        this.coverTargetCount = coverTargetCount;
        this.gridFallback = gridFallback;
        this.coverChecked = coverChecked;
        this.proneChecked = proneChecked;
        this.rejectedAccess = rejectedAccess;
        this.activeTargetCount = activeTargetCount;
        this.lastSeenCount = lastSeenCount;
        this.peekTargetCount = peekTargetCount;
        this.targets = targets != null ? List.copyOf(targets) : List.of();
        this.candidates = candidates != null ? List.copyOf(candidates) : List.of();
        this.failure = failure != null ? failure : "unknown";
    }

    public MachineGunnerEvaluationPacket(FriendlyByteBuf buf) {
        entityId = buf.readVarInt();
        center = readNullableBlockPos(buf);
        anchor = readNullableBlockPos(buf);
        targetCount = buf.readVarInt();
        coverTargetCount = buf.readVarInt();
        gridFallback = buf.readBoolean();
        coverChecked = buf.readVarInt();
        proneChecked = buf.readVarInt();
        rejectedAccess = buf.readVarInt();
        activeTargetCount = buf.readVarInt();
        lastSeenCount = buf.readVarInt();
        peekTargetCount = buf.readVarInt();
        int targetSize = buf.readVarInt();
        targets = new ArrayList<>();
        for (int i = 0; i < targetSize; i++) {
            targets.add(new Target(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                buf.readVarInt(), buf.readFloat(), buf.readFloat()));
        }
        int candidateSize = buf.readVarInt();
        candidates = new ArrayList<>();
        for (int i = 0; i < candidateSize; i++) {
            candidates.add(new Candidate(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readBoolean(),
                buf.readBoolean(), buf.readBoolean()));
        }
        failure = buf.readUtf(128);
    }

    public static MachineGunnerEvaluationPacket from(int entityId, BlockPos center, BlockPos anchor,
                                                      FiringPositionFinder.EvaluationReport report,
                                                      String failure) {
        List<Candidate> candidates = new ArrayList<>();
        int count = Math.min(MAX_CANDIDATES, report.candidates().size());
        for (int i = 0; i < count; i++) {
            var position = report.candidates().get(i);
            int rank = i + 1;
            var path = report.pathChecks().stream()
                .filter(check -> check.rank() == rank)
                .findFirst()
                .orElse(null);
            candidates.add(new Candidate(position.destination(), rank, position.posture().ordinal() + 1,
                position.firingAccess(), position.protection(), position.score(), path != null,
                path != null && path.pathExists(), path != null && path.canReach()));
        }
        List<Target> targets = report.suppressionTargets().stream()
            .limit(MAX_TARGETS)
            .map(target -> new Target(target.position(), target.category().ordinal(),
                target.weight(), target.freshness()))
            .toList();
        return new MachineGunnerEvaluationPacket(entityId, center, anchor,
            report.suppressionTargetCount(), report.coverTargetCount(), report.usedGridFallback(),
            report.coverPositionsChecked(), report.pronePositionsChecked(), report.rejectedForAccess(),
            report.activeTargetCount(), report.lastSeenCount(), report.peekTargetCount(), targets,
            candidates, failure);
    }

    public static void encode(MachineGunnerEvaluationPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.entityId);
        writeNullableBlockPos(buf, packet.center);
        writeNullableBlockPos(buf, packet.anchor);
        buf.writeVarInt(packet.targetCount);
        buf.writeVarInt(packet.coverTargetCount);
        buf.writeBoolean(packet.gridFallback);
        buf.writeVarInt(packet.coverChecked);
        buf.writeVarInt(packet.proneChecked);
        buf.writeVarInt(packet.rejectedAccess);
        buf.writeVarInt(packet.activeTargetCount);
        buf.writeVarInt(packet.lastSeenCount);
        buf.writeVarInt(packet.peekTargetCount);
        buf.writeVarInt(packet.targets.size());
        for (Target target : packet.targets) {
            buf.writeDouble(target.position().x);
            buf.writeDouble(target.position().y);
            buf.writeDouble(target.position().z);
            buf.writeVarInt(target.category());
            buf.writeFloat(target.weight());
            buf.writeFloat(target.freshness());
        }
        buf.writeVarInt(packet.candidates.size());
        for (Candidate candidate : packet.candidates) {
            buf.writeBlockPos(candidate.position());
            buf.writeVarInt(candidate.rank());
            buf.writeVarInt(candidate.posture());
            buf.writeFloat(candidate.access());
            buf.writeFloat(candidate.protection());
            buf.writeFloat(candidate.score());
            buf.writeBoolean(candidate.pathChecked());
            buf.writeBoolean(candidate.pathExists());
            buf.writeBoolean(candidate.canReach());
        }
        buf.writeUtf(packet.failure, 128);
    }

    public static void handle(MachineGunnerEvaluationPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
            CoverDebugManager.setMachineGunnerEvaluation(new CoverDebugManager.MachineGunnerEvaluationDebugData(
                packet.entityId, packet.center, packet.anchor, packet.targetCount, packet.coverTargetCount,
                packet.gridFallback, packet.coverChecked, packet.proneChecked, packet.rejectedAccess,
                packet.activeTargetCount, packet.lastSeenCount, packet.peekTargetCount,
                packet.targets.stream().map(Target::position).toList(), packet.candidates.stream().map(candidate ->
                    new CoverDebugManager.FiringPositionDebugEntry(candidate.position(), candidate.rank(),
                        candidate.posture(), candidate.access(), candidate.protection(), candidate.score(),
                        candidate.pathChecked(), candidate.pathExists(), candidate.canReach())).toList(),
                packet.failure))
        ));
        ctx.get().setPacketHandled(true);
    }

    private static void writeNullableBlockPos(FriendlyByteBuf buf, BlockPos pos) {
        buf.writeBoolean(pos != null);
        if (pos != null) buf.writeBlockPos(pos);
    }

    private static BlockPos readNullableBlockPos(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readBlockPos() : null;
    }
}

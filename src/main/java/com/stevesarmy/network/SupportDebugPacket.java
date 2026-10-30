package com.stevesarmy.network;

import com.stevesarmy.client.SupportDebugRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class SupportDebugPacket {
    private static final int MAX_SUPPORTS = 32;
    private static final int MAX_LINES = 7;
    public record Support(UUID id, Vec3 position, List<String> lines) {}
    private final boolean enabled;
    private final List<Support> supports;

    public SupportDebugPacket(boolean enabled, List<Support> supports) {
        this.enabled = enabled;
        this.supports = List.copyOf(supports);
    }

    public SupportDebugPacket(FriendlyByteBuf buf) {
        enabled = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_SUPPORTS) throw new IllegalArgumentException("invalid support count");
        List<Support> decoded = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = buf.readUUID();
            Vec3 position = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
            int lineCount = buf.readVarInt();
            if (lineCount < 0 || lineCount > MAX_LINES) throw new IllegalArgumentException("invalid support line count");
            List<String> lines = new ArrayList<>(lineCount);
            for (int j = 0; j < lineCount; j++) lines.add(buf.readUtf(256));
            decoded.add(new Support(id, position, List.copyOf(lines)));
        }
        supports = List.copyOf(decoded);
    }

    public static void encode(SupportDebugPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.enabled);
        buf.writeVarInt(Math.min(packet.supports.size(), MAX_SUPPORTS));
        for (Support support : packet.supports.subList(0, Math.min(packet.supports.size(), MAX_SUPPORTS))) {
            buf.writeUUID(support.id());
            buf.writeDouble(support.position().x);
            buf.writeDouble(support.position().y);
            buf.writeDouble(support.position().z);
            buf.writeVarInt(Math.min(support.lines().size(), MAX_LINES));
            for (String line : support.lines().subList(0, Math.min(support.lines().size(), MAX_LINES))) {
                buf.writeUtf(line.substring(0, Math.min(line.length(), 256)), 256);
            }
        }
    }

    public static void handle(SupportDebugPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> SupportDebugRenderer.receive(packet.enabled, packet.supports)));
        context.get().setPacketHandled(true);
    }
}

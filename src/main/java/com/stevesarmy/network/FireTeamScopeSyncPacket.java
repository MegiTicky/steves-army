package com.stevesarmy.network;

import com.stevesarmy.client.FireTeamScopeState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class FireTeamScopeSyncPacket {
    private final int teamCount;

    public FireTeamScopeSyncPacket(int teamCount) {
        this.teamCount = teamCount;
    }

    public static void encode(FireTeamScopeSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.teamCount);
    }

    public static FireTeamScopeSyncPacket decode(FriendlyByteBuf buf) {
        return new FireTeamScopeSyncPacket(buf.readVarInt());
    }

    public static void handle(FireTeamScopeSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> FireTeamScopeState.INSTANCE.setTeamCount(msg.teamCount, "server sync")));
        ctx.get().setPacketHandled(true);
    }
}

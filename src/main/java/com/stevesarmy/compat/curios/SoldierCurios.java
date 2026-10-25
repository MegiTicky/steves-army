package com.stevesarmy.compat.curios;

import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.fml.ModList;
import java.util.List;

/** Keeps optional Curios classes out of ordinary mod class loading. */
public final class SoldierCurios {
    public record Descriptor(String identifier, int index, boolean cosmetic) {}
    public static boolean available() { return ModList.get().isLoaded("curios"); }
    public static void init() { if (available()) CuriosIntegration.init(); }
    public static List<Descriptor> describe(SoldierEntity soldier) {
        return available() ? CuriosIntegration.describe(soldier) : List.of();
    }
    public static Slot slot(SoldierEntity soldier, Descriptor descriptor, int x, int y) {
        return CuriosIntegration.slot(soldier, descriptor, x, y);
    }
    public static void transfer(SoldierEntity source, SoldierEntity target) {
        if (available()) CuriosIntegration.transfer(source, target);
    }
    public static void write(FriendlyByteBuf buf, List<Descriptor> descriptors) {
        buf.writeVarInt(descriptors.size());
        for (Descriptor d : descriptors) {
            buf.writeUtf(d.identifier()); buf.writeVarInt(d.index()); buf.writeBoolean(d.cosmetic());
        }
    }
    public static List<Descriptor> read(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > 4096) throw new IllegalArgumentException("Invalid accessory count");
        var result = new java.util.ArrayList<Descriptor>(count);
        for (int i = 0; i < count; i++) result.add(new Descriptor(buf.readUtf(256), buf.readVarInt(), buf.readBoolean()));
        return List.copyOf(result);
    }
    private SoldierCurios() {}
}

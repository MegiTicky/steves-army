package com.stevesarmy.compat.curios;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.fml.ModList;
import java.util.List;

/** Optional Curios boundary; failures disable only the Curios feature. */
public final class SoldierCurios {
    public record Descriptor(String identifier, int index, boolean cosmetic) {}
    public enum State { MISSING, OK, UNTESTED, BROKEN }
    private static volatile State state = State.UNTESTED;
    private static boolean logged;
    private static String reason = "";
    private static String version() { return ModList.get().getModContainerById("curios").map(m -> m.getModInfo().getVersion().toString()).orElse("missing"); }
    private static final java.util.Map<java.util.UUID, String> notifications = new java.util.concurrent.ConcurrentHashMap<>();
    private static void warn(net.minecraftforge.event.TickEvent.PlayerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || event.player.tickCount % 40 != 0) return;
        String message = state == State.BROKEN ? reason : state == State.UNTESTED ? "untested" : "";
        if (!message.isEmpty() && !message.equals(notifications.put(java.util.UUID.nameUUIDFromBytes((event.player.getUUID().toString() + event.player.level().isClientSide).getBytes(java.nio.charset.StandardCharsets.UTF_8)), message)))
            event.player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(state == State.BROKEN ? "steves_army.curios.disabled" : "steves_army.curios.untested", version(), "5.14.1+1.20.1", message));
    }
    public static State state() { return state; }
    public static boolean available() { return state == State.OK || (state == State.UNTESTED && ModList.get().isLoaded("curios")); }
    public static void init() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(SoldierCurios::warn);
        if (!ModList.get().isLoaded("curios")) { state = State.MISSING; return; }
        try { CuriosIntegration.probe(); CuriosIntegration.init(); state = version().equals("5.14.1+1.20.1") ? State.OK : State.UNTESTED; }
        catch (RuntimeException | LinkageError error) { fail("initialization", error); }
    }
    static void fail(String feature, Throwable error) {
        if (error instanceof VirtualMachineError || error instanceof ThreadDeath) throw (Error) error;
        if (state == State.BROKEN) return;
        reason = feature + ": " + error.getClass().getSimpleName();
        state = State.BROKEN;
        if (!logged) { logged = true; StevesArmyMod.LOGGER.warn("Curios disabled during {} (installed={}, tested=5.14.1): {}", feature, version(), error.toString()); }
    }
    public static List<Descriptor> describe(SoldierEntity soldier) { if (!available()) return List.of(); try { return CuriosIntegration.describe(soldier); } catch (RuntimeException | LinkageError e) { fail("slot discovery", e); return List.of(); } }
    public static <T> T guarded(String operation, java.util.function.Supplier<T> action, T fallback) {
        if (!available()) return fallback;
        try { return action.get(); } catch (RuntimeException | LinkageError e) { fail(operation, e); return fallback; }
    }
    public static void guarded(String operation, Runnable action) { guarded(operation, () -> { action.run(); return true; }, false); }
    public static Slot slot(SoldierEntity soldier, Descriptor descriptor, int x, int y) { try { Slot backing = CuriosIntegration.slot(soldier, descriptor, x, y);
        return new Slot(new net.minecraft.world.SimpleContainer(1), 0, x, y) {
            private net.minecraft.world.item.ItemStack snapshot = net.minecraft.world.item.ItemStack.EMPTY;
            @Override public net.minecraft.world.item.ItemStack getItem() {
                if (!available()) return snapshot;
                return guarded("slot read", () -> { snapshot = backing.getItem().copy(); return backing.getItem(); }, snapshot);
            }
            @Override public boolean isActive() { return available(); }
            @Override public void set(net.minecraft.world.item.ItemStack stack) { guarded("slot write", () -> backing.set(stack)); }
            @Override public void setChanged() { guarded("slot changed", backing::setChanged); }
            @Override public net.minecraft.world.item.ItemStack remove(int amount) { return guarded("slot remove", () -> backing.remove(amount), net.minecraft.world.item.ItemStack.EMPTY); }
            @Override public boolean mayPlace(net.minecraft.world.item.ItemStack stack) { return guarded("slot insert check", () -> backing.mayPlace(stack), false); }
            @Override public boolean mayPickup(net.minecraft.world.entity.player.Player player) { return guarded("slot extract check", () -> backing.mayPickup(player), false); }
            @Override public int getMaxStackSize() { return 1; }
            @Override public int getMaxStackSize(net.minecraft.world.item.ItemStack stack) { return 1; }
        }; } catch (RuntimeException | LinkageError e) { fail("slot creation", e); return new Slot(new net.minecraft.world.SimpleContainer(1), 0, x, y) {
            @Override public boolean isActive() { return false; }
            @Override public boolean mayPlace(net.minecraft.world.item.ItemStack stack) { return false; }
            @Override public boolean mayPickup(net.minecraft.world.entity.player.Player player) { return false; }
        }; } }
    public static com.mojang.datafixers.util.Pair<net.minecraft.resources.ResourceLocation, net.minecraft.resources.ResourceLocation> icon(Descriptor descriptor, net.minecraft.world.level.Level level) { if (!available()) return null; try { return CuriosIntegration.icon(descriptor, level); } catch (RuntimeException | LinkageError e) { fail("slot icon", e); return null; } }
    public static boolean transfer(SoldierEntity source, SoldierEntity target) { if (!available()) return state == State.MISSING || !ModList.get().isLoaded("curios"); try { return CuriosIntegration.transfer(source, target); } catch (RuntimeException | LinkageError e) { fail("role transfer", e); return false; } }
    public static void write(FriendlyByteBuf buf, List<Descriptor> descriptors) { buf.writeVarInt(descriptors.size()); for (Descriptor d : descriptors) { buf.writeUtf(d.identifier()); buf.writeVarInt(d.index()); buf.writeBoolean(d.cosmetic()); } }
    public static List<Descriptor> read(FriendlyByteBuf buf) { int count = buf.readVarInt(); if (count < 0 || count > 4096) throw new IllegalArgumentException("Invalid accessory count"); var result = new java.util.ArrayList<Descriptor>(count); for (int i=0;i<count;i++) result.add(new Descriptor(buf.readUtf(256),buf.readVarInt(),buf.readBoolean())); return List.copyOf(result); }
    private SoldierCurios() {}
}

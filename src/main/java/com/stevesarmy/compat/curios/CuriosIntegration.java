package com.stevesarmy.compat.curios;

import com.google.common.collect.HashMultimap;
import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.items.SlotItemHandler;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.event.CurioDropsEvent;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import java.util.*;

final class CuriosIntegration {
    private static final UUID RING_BONUS = UUID.fromString("cc4bd07b-90bf-49cf-9331-b282ebbd0441");
    static void probe() {
        try {
            CuriosApi.class.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class);
            CuriosApi.class.getMethod("getSlot", String.class, net.minecraft.world.level.Level.class);
            CuriosApi.class.getMethod("getCurio", ItemStack.class);
            ICurioStacksHandler.class.getMethod("getStacks");
            ICurioStacksHandler.class.getMethod("getCosmeticStacks");
            ICurioStacksHandler.class.getMethod("getRenders");
            top.theillusivec4.curios.api.type.capability.ICuriosItemHandler.class.getMethod("writeTag");
            top.theillusivec4.curios.api.type.capability.ICuriosItemHandler.class.getMethod("readTag", net.minecraft.nbt.Tag.class);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Required Curios equipment API is unavailable", e); }
    }
    static void init() {
        MinecraftForge.EVENT_BUS.addListener(CuriosIntegration::tick);
        MinecraftForge.EVENT_BUS.addListener(CuriosIntegration::drops);
    }
    private static void tick(LivingEvent.LivingTickEvent event) {
        if (!SoldierCurios.available()) return;
        try {
            if (event.getEntity() instanceof SoldierEntity soldier && !soldier.level().isClientSide) ensureDefaults(soldier);
        } catch (RuntimeException | LinkageError error) { SoldierCurios.fail("soldier tick", error); }
    }
    private static void ensureDefaults(SoldierEntity soldier) {
        CuriosApi.getCuriosInventory(soldier).ifPresent(handler -> {
            var ring = handler.getCurios().get("ring");
            // A stable transient bonus belongs to the soldier, never the player.
            if (ring != null && !ring.getModifiers().containsKey(RING_BONUS)) {
                var modifiers = HashMultimap.<String, AttributeModifier>create();
                modifiers.put("ring", new AttributeModifier(RING_BONUS, "Soldier extra ring", 1, AttributeModifier.Operation.ADDITION));
                handler.addTransientSlotModifiers(modifiers);
            }
        });
    }
    static List<SoldierCurios.Descriptor> describe(SoldierEntity soldier) {
        ensureDefaults(soldier);
        var result = new ArrayList<SoldierCurios.Descriptor>();
        CuriosApi.getCuriosInventory(soldier).ifPresent(handler -> handler.getCurios().entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler>>comparingInt(entry ->
                CuriosApi.getSlot(entry.getKey(), soldier.level()).map(type -> type.getOrder()).orElse(Integer.MAX_VALUE))
                .thenComparing(Map.Entry::getKey)).forEach(entry -> {
                var stacks = entry.getValue();
                for (int i = 0; i < stacks.getStacks().getSlots(); i++) result.add(new SoldierCurios.Descriptor(entry.getKey(), i, false));
                if (stacks.hasCosmetic()) for (int i = 0; i < stacks.getCosmeticStacks().getSlots(); i++) result.add(new SoldierCurios.Descriptor(entry.getKey(), i, true));
            }));
        return List.copyOf(result);
    }
    static Slot slot(SoldierEntity soldier, SoldierCurios.Descriptor d, int x, int y) {
        var handler = CuriosApi.getCuriosInventory(soldier).resolve().orElseThrow();
        var group = handler.getCurios().get(d.identifier());
        IDynamicStackHandler stacks = d.cosmetic() ? group.getCosmeticStacks() : group.getStacks();
        return new SlotItemHandler(stacks, d.index(), x, y) {
            @Override public boolean mayPickup(Player player) {
                return !hasItem() || !stacks.extractItem(d.index(), 1, true).isEmpty();
            }
            @Override public boolean allowModification(Player player) { return true; }
            @Override public boolean mayPlace(ItemStack stack) { return stacks.isItemValid(d.index(), stack); }
            @Override public void set(ItemStack stack) {
                ItemStack previous = getItem().copy();
                super.set(stack);
                if (!stack.isEmpty() && !ItemStack.matches(previous, stack)) {
                    var context = new top.theillusivec4.curios.api.SlotContext(d.identifier(), soldier, d.index(), d.cosmetic(),
                        d.cosmetic() || group.getRenders().get(d.index()));
                    CuriosApi.getCurio(stack).ifPresent(curio -> curio.onEquipFromUse(context));
                }
            }
            @Override public int getMaxStackSize() { return 1; }
            @Override public int getMaxStackSize(ItemStack stack) { return 1; }
        };
    }
    static com.mojang.datafixers.util.Pair<net.minecraft.resources.ResourceLocation, net.minecraft.resources.ResourceLocation> icon(SoldierCurios.Descriptor descriptor, net.minecraft.world.level.Level level) {
        return CuriosApi.getSlot(descriptor.identifier(), level)
            .map(type -> com.mojang.datafixers.util.Pair.of(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS, type.getIcon())).orElse(null);
    }
    static boolean transfer(SoldierEntity source, SoldierEntity target) {
        var from = CuriosApi.getCuriosInventory(source).resolve().orElseThrow();
        var to = CuriosApi.getCuriosInventory(target).resolve().orElseThrow();
        var original = from.writeTag().copy();
        to.readTag(original.copy());
        if (!original.equals(to.writeTag())) throw new IllegalStateException("Accessory transfer did not preserve serialized data");
        return true;
    }
    private static void drops(CurioDropsEvent event) {
        if (!SoldierCurios.available()) return;
        try {
            if (event.getEntity() instanceof SoldierEntity soldier) {
                boolean enemy = soldier instanceof com.stevesarmy.entity.EnemySoldierEntity;
                boolean enabled = (enemy ? StevesArmyConfig.ENEMY_SOLDIER_DEATH_DROPS : StevesArmyConfig.SOLDIER_DEATH_DROPS).get();
                boolean curios = (enemy ? StevesArmyConfig.ENEMY_SOLDIER_DROP_CURIOS : StevesArmyConfig.SOLDIER_DROP_CURIOS).get();
                if (!enabled || !curios) event.setCanceled(true);
            }
        } catch (RuntimeException | LinkageError error) { SoldierCurios.fail("death drops", error); }
    }
}

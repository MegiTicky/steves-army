package com.stevesarmy.squad;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Soldier death loot, spawned through a per-tick budget so a massed death
 * (artillery, grenades) cannot dump hundreds of item entities in one tick.
 * Stacks are merged per soldier before entering the queue; the queue is
 * flushed in full on server stop so nothing is lost to shutdown.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class SoldierDeathDropQueue {
    private static final ArrayDeque<PendingDrop> QUEUE = new ArrayDeque<>();
    private static final int PICKUP_DELAY_TICKS = 10;

    private SoldierDeathDropQueue() {}

    private static final class PendingDrop {
        final ServerLevel level;
        final double x;
        final double y;
        final double z;
        final ArrayDeque<ItemStack> stacks;

        PendingDrop(ServerLevel level, double x, double y, double z, List<ItemStack> stacks) {
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
            this.stacks = new ArrayDeque<>(stacks);
        }
    }

    public static void enqueue(SoldierEntity soldier, List<ItemStack> stacks) {
        if (stacks.isEmpty() || !(soldier.level() instanceof ServerLevel level)) {
            return;
        }
        List<ItemStack> merged = mergeStacks(stacks);
        if (merged.isEmpty()) {
            return;
        }
        QUEUE.add(new PendingDrop(level, soldier.getX(), soldier.getY(), soldier.getZ(), merged));
        if (QUEUE.size() > 256) {
            // Safety valve against unbounded growth; drop oldest backlog in full.
            flushOne();
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || QUEUE.isEmpty()) {
            return;
        }
        int budget = StevesArmyConfig.getSoldierDeathDropsPerTick();
        if (budget <= 0) {
            budget = Integer.MAX_VALUE;
        }
        while (budget-- > 0 && !QUEUE.isEmpty()) {
            if (!spawnNext()) {
                break;
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        while (!QUEUE.isEmpty()) {
            spawnNext();
        }
    }

    /** Spawns the head drop's next stack; removes the head when it empties. */
    private static boolean spawnNext() {
        PendingDrop drop = QUEUE.peek();
        if (drop == null) {
            return false;
        }
        ItemStack stack = drop.stacks.poll();
        if (stack != null && !stack.isEmpty()) {
            ItemEntity entity = new ItemEntity(drop.level, drop.x, drop.y, drop.z, stack);
            entity.setPickUpDelay(PICKUP_DELAY_TICKS);
            drop.level.addFreshEntity(entity);
        }
        if (drop.stacks.isEmpty()) {
            QUEUE.poll();
        }
        return true;
    }

    private static void flushOne() {
        PendingDrop drop = QUEUE.poll();
        if (drop == null) {
            return;
        }
        ItemStack stack;
        while ((stack = drop.stacks.poll()) != null) {
            if (!stack.isEmpty()) {
                ItemEntity entity = new ItemEntity(drop.level, drop.x, drop.y, drop.z, stack);
                entity.setPickUpDelay(PICKUP_DELAY_TICKS);
                drop.level.addFreshEntity(entity);
            }
        }
    }

    /** Combines identical item+tag stacks up to max size so ammo piles collapse. */
    private static List<ItemStack> mergeStacks(List<ItemStack> stacks) {
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            boolean fullyAbsorbed = false;
            if (stack.getMaxStackSize() > 1) {
                for (ItemStack existing : merged) {
                    if (existing.getCount() < existing.getMaxStackSize()
                        && ItemStack.isSameItemSameTags(existing, stack)) {
                        int toMove = Math.min(existing.getMaxStackSize() - existing.getCount(), stack.getCount());
                        existing.grow(toMove);
                        stack.shrink(toMove);
                        if (stack.isEmpty()) {
                            fullyAbsorbed = true;
                            break;
                        }
                    }
                }
            }
            if (!fullyAbsorbed) {
                merged.add(stack);
            }
        }
        return merged;
    }
}

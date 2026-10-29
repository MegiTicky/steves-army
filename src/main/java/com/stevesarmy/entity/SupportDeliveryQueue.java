package com.stevesarmy.entity;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.ai.SupportCoverGoal;
import com.stevesarmy.entity.ai.SupportDutyController;
import com.stevesarmy.inventory.PouchContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Server-owned payloads; cosmetic projectiles cannot intercept or destroy them. */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class SupportDeliveryQueue extends SavedData {
    public static final int DELAY_TICKS = 12;
    private static final String DATA_NAME = "steves_army_support_deliveries";
    private final List<Delivery> pending = new ArrayList<>();
    private record Delivery(UUID source, UUID target, ItemStack pouch, Vec3 origin, long due) {}

    public static SupportDeliveryQueue get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(SupportDeliveryQueue::load, SupportDeliveryQueue::new, DATA_NAME);
    }

    public void enqueue(SupportEntity source, LivingEntity target, ItemStack pouch) {
        pending.add(new Delivery(source.getUUID(), target.getUUID(), pouch.copy(), source.position(),
            source.level().getGameTime() + DELAY_TICKS));
        setDirty();
    }

    public boolean hasTarget(UUID target) {
        return pending.stream().anyMatch(d -> d.target().equals(target));
    }

    public List<ItemStack> pendingForSource(UUID source) {
        return pending.stream().filter(d -> d.source().equals(source)).map(d -> d.pouch().copy()).toList();
    }

    public void clearSimSources(java.util.Set<UUID> sources) {
        if (pending.removeIf(d -> sources.contains(d.source()))) setDirty();
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level) get(level).tick(level);
    }

    private void tick(ServerLevel level) {
        Iterator<Delivery> iterator = pending.iterator();
        while (iterator.hasNext()) {
            Delivery delivery = iterator.next();
            if (level.getGameTime() < delivery.due()) continue;
            Entity entity = level.getEntity(delivery.target());
            LivingEntity target = entity instanceof LivingEntity living && living.isAlive() ? living : null;
            boolean deliverable = target != null && SupportDutyController.canAcceptPayload(target, PouchContainer.unpackContents(delivery.pouch()));
            if (!deliverable) {
                Vec3 drop = target == null ? delivery.origin() : target.position();
                if (!level.hasChunkAt(BlockPos.containing(drop))) continue;
                ItemEntity item = new ItemEntity(level, drop.x, drop.y + 0.5, drop.z, delivery.pouch().copy());
                item.setPickUpDelay(10);
                if (!level.addFreshEntity(item)) continue;
                SupportDutyController.noteFailedPouch(delivery.target());
                report(level, delivery.source(), "dropped: recipient unavailable or full");
            } else {
                SupplyPouchProjectile.deliverContents(target, delivery.pouch());
                report(level, delivery.source(), "delivered");
            }
            iterator.remove();
            setDirty();
        }
    }

    private static void report(ServerLevel level, UUID source, String outcome) {
        if (level.getEntity(source) instanceof SupportEntity support
            && support.getCoverTacticalGoal() instanceof SupportCoverGoal goal) goal.debugDuty().debugPouchOutcome(outcome);
    }

    public static SupportDeliveryQueue load(CompoundTag tag) {
        SupportDeliveryQueue queue = new SupportDeliveryQueue();
        ListTag list = tag.getList("Deliveries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("Source") || !entry.hasUUID("Target")) continue;
            ItemStack pouch = ItemStack.of(entry.getCompound("Pouch"));
            if (pouch.isEmpty()) continue;
            queue.pending.add(new Delivery(entry.getUUID("Source"), entry.getUUID("Target"), pouch,
                new Vec3(entry.getDouble("X"), entry.getDouble("Y"), entry.getDouble("Z")), entry.getLong("Due")));
        }
        return queue;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Delivery delivery : pending) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Source", delivery.source());
            entry.putUUID("Target", delivery.target());
            entry.put("Pouch", delivery.pouch().save(new CompoundTag()));
            entry.putDouble("X", delivery.origin().x);
            entry.putDouble("Y", delivery.origin().y);
            entry.putDouble("Z", delivery.origin().z);
            entry.putLong("Due", delivery.due());
            list.add(entry);
        }
        tag.put("Deliveries", list);
        return tag;
    }
}

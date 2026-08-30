package com.stevesarmy.item;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.CommandStickMovePacket;
import com.stevesarmy.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

public class CommandStickItem extends Item {

    private static final double TARGET_RANGE = 64.0;
    private static final double CONE_COS = Math.cos(Math.toRadians(10));

    private final BiPredicate<SoldierEntity, Player> targetFilter;

    public CommandStickItem(Properties properties, BiPredicate<SoldierEntity, Player> targetFilter) {
        super(properties);
        this.targetFilter = targetFilter;
    }

    public boolean isTargetable(SoldierEntity soldier, Player player) {
        return targetFilter.test(soldier, player);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            return InteractionResultHolder.pass(stack);
        }

        if (player.isShiftKeyDown()) {
            handleReposition(player);
        } else {
            SoldierEntity target = findTargetableSoldier(player, targetFilter);
            if (target != null) {
                CommandStickSelection.toggle(target.getId());
                com.stevesarmy.client.CommandStickState.get().onSelectionChanged();
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (player == null) return InteractionResult.PASS;

        if (player.isShiftKeyDown()) {
            if (level.isClientSide) {
                handleReposition(player);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (level.isClientSide) {
            SoldierEntity target = findTargetableSoldier(player, targetFilter);
            if (target != null) {
                CommandStickSelection.toggle(target.getId());
                com.stevesarmy.client.CommandStickState.get().onSelectionChanged();
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  net.minecraft.world.entity.LivingEntity target,
                                                  InteractionHand hand) {
        if (player.level().isClientSide
                && target instanceof SoldierEntity soldier
                && isTargetable(soldier, player)) {
            CommandStickSelection.toggle(target.getId());
            com.stevesarmy.client.CommandStickState.get().onSelectionChanged();
            return InteractionResult.sidedSuccess(player.level().isClientSide);
        }
        return super.interactLivingEntity(stack, player, target, hand);
    }

    private static void handleReposition(Player player) {
        List<Integer> selected = new ArrayList<>(CommandStickSelection.getSelectedIds());
        if (selected.isEmpty()) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("No soldiers selected"),
                true);
            return;
        }

        Vec3 hitPos = raytraceTargetPosition(player);
        BlockPos targetPos = BlockPos.containing(hitPos);
        NetworkHandler.INSTANCE.sendToServer(
            new CommandStickMovePacket(targetPos, selected));
    }

    private static Vec3 raytraceTargetPosition(Player player) {
        Minecraft mc = Minecraft.getInstance();
        int renderDistance = mc.options.renderDistance().get();
        double maxDistance = renderDistance * 16.0;

        Vec3 eyePos = player.getEyePosition(1.0f);
        Vec3 lookVec = player.getViewVector(1.0f);
        Vec3 endPos = eyePos.add(lookVec.scale(maxDistance));

        Vec3 rayStart = eyePos;
        for (int i = 0; i < 64; i++) {
            BlockHitResult hit = player.level().clip(new ClipContext(
                rayStart, endPos,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player));
            if (hit.getType() == HitResult.Type.MISS) {
                return endPos;
            }
            if (!isTransparent(player, hit)) {
                return hit.getLocation();
            }
            rayStart = hit.getLocation().add(lookVec.scale(0.001));
        }
        return endPos;
    }

    private static boolean isTransparent(Player player, BlockHitResult hit) {
        var state = player.level().getBlockState(hit.getBlockPos());
        return state.getCollisionShape(player.level(), hit.getBlockPos()).isEmpty();
    }

    @Nullable
    public static SoldierEntity findTargetableSoldier(Player player, BiPredicate<SoldierEntity, Player> filter) {
        Level level = player.level();
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();

        AABB searchBox = player.getBoundingBox().inflate(TARGET_RANGE);
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, searchBox);

        SoldierEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity entity : entities) {
            if (!(entity instanceof SoldierEntity soldier)) continue;
            if (!filter.test(soldier, player)) continue;

            Vec3 toEntity = soldier.position().add(0, soldier.getBbHeight() * 0.5, 0).subtract(eyePos);
            double dist = toEntity.length();
            if (dist > TARGET_RANGE || dist < 0.01) continue;

            double dot = lookVec.normalize().dot(toEntity.normalize());
            if (dot < CONE_COS) continue;

            double score = -dot + dist * 0.0001;
            if (score < bestScore) {
                bestScore = score;
                best = soldier;
            }
        }

        return best;
    }

    public static boolean isCommandStick(ItemStack stack) {
        return stack.getItem() instanceof CommandStickItem;
    }
}

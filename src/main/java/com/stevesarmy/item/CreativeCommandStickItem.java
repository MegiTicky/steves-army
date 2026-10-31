package com.stevesarmy.item;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.compat.SbwCompat;
import com.stevesarmy.compat.sbw.SbwVehicles;
import com.stevesarmy.compat.VS2Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

public class CreativeCommandStickItem extends CommandStickItem {

    public CreativeCommandStickItem(Properties properties) {
        super(properties, CreativeCommandStickItem::canTarget);
    }

    private static boolean canTarget(SoldierEntity soldier, Player player) {
        return player.getAbilities().instabuild;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player.isShiftKeyDown() && level.isClientSide && tryAssignCrew(player)) {
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        return super.use(level, player, hand);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player != null && player.isShiftKeyDown() && context.getLevel().isClientSide
            && tryAssignCrew(player)) {
            return InteractionResult.SUCCESS;
        }
        return super.useOn(context);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity target, InteractionHand hand) {
        if (player.isShiftKeyDown() && player.level().isClientSide
            && SbwCompat.isOperational(target) && !SbwVehicles.isDrone(target)
            && tryAssignCrew(player)) {
            return InteractionResult.SUCCESS;
        }
        return super.interactLivingEntity(stack, player, target, hand);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("item.steves_army.creative_command_stick.select_any")
            .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.steves_army.creative_command_stick.board")
            .withStyle(ChatFormatting.GRAY));
    }

    /**
     * Creative only: a vehicle or ship click assigns every selected soldier,
     * including enemy infantry. Other shift-clicks retain normal repositioning.
     */
    private static boolean tryAssignCrew(Player player) {
        if (!player.getAbilities().instabuild) return false;
        if (CrewAssignStickItem.tryAssignAimedVehicle(player, false, true)) return true;
        HitResult hit = player.pick(6.0, 1.0F, false);
        if (hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult entityHit
            && SbwCompat.isOperational(entityHit.getEntity())
            && !SbwVehicles.isDrone(entityHit.getEntity())) {
            return CrewAssignStickItem.tryAssign(player, false, true);
        }
        if (hit instanceof BlockHitResult blockHit
            && VS2Compat.getShipObjectAtBlockPos(player.level(), blockHit.getBlockPos()) != null) {
            return CrewAssignStickItem.tryAssign(player, false, true);
        }
        return false;
    }
}

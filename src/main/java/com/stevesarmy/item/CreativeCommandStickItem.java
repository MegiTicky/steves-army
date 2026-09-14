package com.stevesarmy.item;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.compat.VS2Compat;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

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

    /**
     * Creative only: a ship click mounts selected friendly or hostile vehicle crew.
     * Other shift-clicks retain this stick's normal reposition behavior.
     */
    private static boolean tryAssignCrew(Player player) {
        List<Integer> crew = CrewAssignStickItem.selectedVehicleCrewIds(player, false);
        if (crew.isEmpty()) {
            return false;
        }
        BlockHitResult hit = CrewAssignStickItem.findAimHit(player);
        return hit != null && VS2Compat.getShipObjectAtBlockPos(player.level(), hit.getBlockPos()) != null
            && CrewAssignStickItem.tryAssign(player, false);
    }
}

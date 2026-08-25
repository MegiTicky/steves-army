package com.stevesarmy.item;

import com.stevesarmy.compat.ysm.YsmCompat;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Right-clicking a soldier with this item opens the YSM model picker for that soldier.
 * The owner may restyle their own soldiers; creative players may restyle any soldier.
 */
public class SurgicalKnifeItem extends Item {

    public SurgicalKnifeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity entity, InteractionHand hand) {
        if (entity instanceof SoldierEntity soldier && YsmCompat.isLoaded() && YsmCompat.canEditModel(player, soldier)) {
            if (player.level().isClientSide) {
                if (player.isShiftKeyDown()) {
                    YsmCompat.disableModel(soldier);
                    YsmCompat.requestDisableModel(soldier);
                } else {
                    YsmCompat.openModelScreen(soldier);
                }
            }
            return InteractionResult.sidedSuccess(player.level().isClientSide);
        }
        return InteractionResult.PASS;
    }
}

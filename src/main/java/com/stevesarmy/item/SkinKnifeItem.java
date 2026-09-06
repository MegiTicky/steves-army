package com.stevesarmy.item;

import com.stevesarmy.skin.SoldierSkinManager;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Right-clicking a soldier with this item cycles through the custom skins
 * found in {@code <game dir>/stevesarmy/skins}; shift-click resets the soldier
 * to the default skin. Same access rule as the YSM surgical knife: the owner
 * may restyle their own soldiers, creative players may restyle any soldier.
 */
public class SkinKnifeItem extends Item {

    public SkinKnifeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity entity, InteractionHand hand) {
        if (!(entity instanceof SoldierEntity soldier)) {
            return InteractionResult.PASS;
        }
        if (player.level().isClientSide) {
            return canEdit(player, soldier) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (!canEdit(player, soldier)) {
            return InteractionResult.PASS;
        }

        List<String> skins = SoldierSkinManager.getSkinNames();
        if (skins.isEmpty()) {
            player.displayClientMessage(Component.literal(
                "No skins found in " + SoldierSkinManager.getSkinFolder() + " (drop 64x64 player PNGs there)"), true);
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            soldier.setSkin("");
            player.displayClientMessage(Component.literal("Skin reset to default"), true);
        } else {
            // -1 (default or unknown skin) maps to the first skin in the list.
            int index = skins.indexOf(soldier.getSkin());
            String next = skins.get(Math.floorMod(index + 1, skins.size()));
            soldier.setSkin(next);
            player.displayClientMessage(Component.literal("Skin: " + next), true);
        }
        return InteractionResult.SUCCESS;
    }

    private static boolean canEdit(Player player, SoldierEntity soldier) {
        return soldier.isOwnedBy(player) || player.getAbilities().instabuild;
    }
}

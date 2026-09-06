package com.stevesarmy.item;

import com.stevesarmy.client.screen.SoldierSkinScreen;
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
 * Right-clicking a soldier with this item opens the skin picker menu;
 * shift-click cycles through the skins found in
 * {@code <game dir>/stevesarmy/skins} and shift-click resets happen through
 * the menu. Same access rule as the YSM surgical knife: the owner may restyle
 * their own soldiers, creative players may restyle any soldier.
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
        if (!canEdit(player, soldier)) {
            return InteractionResult.PASS;
        }
        if (player.level().isClientSide) {
            if (!player.isShiftKeyDown()) {
                SoldierSkinScreen.open(soldier);
            }
            return InteractionResult.SUCCESS;
        }
        // Sneak-click: server-authoritative quick cycle.
        if (player.isShiftKeyDown()) {
            List<String> skins = SoldierSkinManager.getSkinNames();
            if (skins.isEmpty()) {
                player.displayClientMessage(Component.literal(
                    "No skins found in " + SoldierSkinManager.getSkinFolder() + " (drop 64x64 player PNGs there)"), true);
                return InteractionResult.SUCCESS;
            }
            // -1 (default or unknown skin) maps to the first skin in the list.
            int index = skins.indexOf(soldier.getSkin());
            String next = skins.get(Math.floorMod(index + 1, skins.size()));
            soldier.setSkin(next);
            player.displayClientMessage(Component.literal("Skin: " + next), true);
        }
        // Non-shift right-click: the client opens the menu, which applies via SetSkinPacket.
        return InteractionResult.SUCCESS;
    }

    private static boolean canEdit(Player player, SoldierEntity soldier) {
        return soldier.isOwnedBy(player) || player.getAbilities().instabuild;
    }
}

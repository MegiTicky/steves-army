package com.stevesarmy.item;

import com.stevesarmy.inventory.PouchContainer;
import com.stevesarmy.entity.SupplyPouchProjectile;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Supply pouch: a 27-slot container carried as a single item. Shift + use
 * opens the pouch; plain use throws it like a snowball — a direct entity hit
 * delivers the whole contents, a block hit drops the pouch on the ground for
 * anyone to pick up. Dead soldiers leave their kit inside one of these.
 */
public class SupplyPouchItem extends Item {

    public SupplyPouchItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return handleUse(player, level, hand);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        return handleUse(player, context.getLevel(), context.getHand()).getResult();
    }

    private InteractionResultHolder<ItemStack> handleUse(Player player, Level level, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown()) {
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                openPouch(serverPlayer, stack, hand);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        if (!level.isClientSide) {
            SupplyPouchProjectile pouch = new SupplyPouchProjectile(level, player);
            pouch.setItem(stack.copy());
            pouch.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 1.5F, 1.0F);
            level.addFreshEntity(pouch);
        }
        player.awardStat(Stats.ITEM_USED.get(this));
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private static void openPouch(ServerPlayer player, ItemStack stack, InteractionHand hand) {
        CompoundTag tag = stack.getOrCreateTag();
        if (!tag.hasUUID(PouchContainer.POUCH_ID_TAG)) {
            tag.putUUID(PouchContainer.POUCH_ID_TAG, UUID.randomUUID());
        }
        UUID pouchId = tag.getUUID(PouchContainer.POUCH_ID_TAG);
        PouchContainer container = new PouchContainer(player, pouchId);
        player.openMenu(new SimpleMenuProvider(
            (windowId, inventory, p) -> new ChestMenu(
                net.minecraft.world.inventory.MenuType.GENERIC_9x3, windowId, inventory, container, 3),
            Component.translatable("gui.steves_army.supply_pouch")));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        int count = PouchContainer.countContents(stack);
        if (count > 0) {
            tooltip.add(Component.translatable("tooltip.steves_army.supply_pouch.contains", count)
                .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.steves_army.supply_pouch.empty")
                .withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.steves_army.supply_pouch.usage")
            .withStyle(ChatFormatting.DARK_GRAY));
    }
}

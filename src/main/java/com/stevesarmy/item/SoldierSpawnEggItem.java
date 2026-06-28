package com.stevesarmy.item;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeSpawnEggItem;

import java.util.function.Supplier;

public class SoldierSpawnEggItem extends ForgeSpawnEggItem {
    
    public SoldierSpawnEggItem(Supplier<? extends EntityType<? extends SoldierEntity>> type, int primaryColor, int secondaryColor, Properties props) {
        super(type, primaryColor, secondaryColor, props);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        
        BlockPos pos = context.getClickedPos();
        ItemStack stack = context.getItemInHand();
        
        Entity entity = this.getType(stack.getTag()).spawn((ServerLevel) level, stack, context.getPlayer(), pos, net.minecraft.world.entity.MobSpawnType.SPAWN_EGG, true, true);
        
        if (entity instanceof SoldierEntity soldier) {
            soldier.setOwnerUUID(context.getPlayer().getUUID());
        }
        
        return InteractionResult.CONSUME;
    }
}
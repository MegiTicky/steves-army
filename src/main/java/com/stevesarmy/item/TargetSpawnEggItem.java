package com.stevesarmy.item;

import com.stevesarmy.entity.TargetEntity;
import com.stevesarmy.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

public class TargetSpawnEggItem extends Item {
    
    public TargetSpawnEggItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        ItemStack stack = context.getItemInHand();
        
        ServerLevel serverLevel = (ServerLevel) level;
        Entity entity = ModEntities.TARGET.get().spawn(serverLevel, stack, context.getPlayer(), pos, net.minecraft.world.entity.MobSpawnType.SPAWN_EGG, true, false);
        
        if (entity != null) {
            level.gameEvent(context.getPlayer(), GameEvent.ENTITY_PLACE, pos);
            stack.shrink(1);
        }
        
        return InteractionResult.CONSUME;
    }
}
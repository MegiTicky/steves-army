package com.stevesarmy.item;

import com.stevesarmy.entity.MachineGunnerEntity;
import com.stevesarmy.entity.SoldierSpawner;
import com.stevesarmy.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeSpawnEggItem;

import java.util.function.Supplier;

public class MachineGunnerSpawnEggItem extends ForgeSpawnEggItem {

    public MachineGunnerSpawnEggItem(Supplier<? extends EntityType<? extends MachineGunnerEntity>> type,
                                     int primaryColor, int secondaryColor, Properties props) {
        super(type, primaryColor, secondaryColor, props);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();

        MachineGunnerEntity gunner = ModEntities.MACHINE_GUNNER.get().create((ServerLevel) level);
        if (gunner == null) {
            return InteractionResult.FAIL;
        }
        gunner.moveTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
            player != null ? player.getYRot() : 0.0F, 0.0F);

        SoldierSpawner.SpawnResult result = SoldierSpawner.finishSpawn((ServerLevel) level, gunner, player, false);
        if (!result.success()) {
            return InteractionResult.FAIL;
        }

        if (player != null && !player.isCreative()) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.SUCCESS;
    }
}

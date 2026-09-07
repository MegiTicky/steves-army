package com.stevesarmy.item;

import com.stevesarmy.entity.SoldierSpawner;
import com.stevesarmy.entity.VehicleCrewEntity;
import com.stevesarmy.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeSpawnEggItem;

import java.util.function.Supplier;

public class VehicleCrewSpawnEggItem extends ForgeSpawnEggItem {

    public VehicleCrewSpawnEggItem(Supplier<? extends EntityType<? extends VehicleCrewEntity>> type,
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

        VehicleCrewEntity crew = ModEntities.VEHICLE_CREW.get().create((ServerLevel) level);
        if (crew == null) {
            return InteractionResult.FAIL;
        }
        crew.moveTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
            player != null ? player.getYRot() : 0.0F, 0.0F);
        crew.maybeRandomizeSkin();

        CompoundTag entityTag = getEntityTag(context.getItemInHand());
        if (entityTag != null) {
            crew.fillFromPickBlockData(entityTag);
        }

        SoldierSpawner.SpawnResult result = SoldierSpawner.finishSpawn((ServerLevel) level, crew, player, false);
        if (!result.success()) {
            return InteractionResult.FAIL;
        }

        if (player != null && !player.isCreative()) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.SUCCESS;
    }

    private CompoundTag getEntityTag(net.minecraft.world.item.ItemStack stack) {
        if (stack.getTag() != null && stack.getTag().contains("EntityTag")) {
            return stack.getTag().getCompound("EntityTag");
        }
        return null;
    }
}

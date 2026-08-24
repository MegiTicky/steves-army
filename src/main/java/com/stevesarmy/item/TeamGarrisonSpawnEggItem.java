package com.stevesarmy.item;

import com.stevesarmy.entity.SoldierSpawner;
import com.stevesarmy.entity.TeamGarrisonEntity;
import com.stevesarmy.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeSpawnEggItem;

import java.util.function.Supplier;

public class TeamGarrisonSpawnEggItem extends ForgeSpawnEggItem {

    public TeamGarrisonSpawnEggItem(Supplier<? extends EntityType<? extends TeamGarrisonEntity>> type,
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

        TeamGarrisonEntity garrison = ModEntities.TEAM_GARRISON.get().create((ServerLevel) level);
        if (garrison == null) {
            return InteractionResult.FAIL;
        }
        garrison.moveTo(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
            player != null ? player.getYRot() : 0.0F, 0.0F);

        // Persisted team from a creative-copied egg, if any.
        ItemStack stack = context.getItemInHand();
        CompoundTag entityTag = stack.getTag() != null && stack.getTag().contains("EntityTag")
            ? stack.getTag().getCompound("EntityTag") : null;
        if (entityTag != null) {
            if (entityTag.contains("TeamName")) {
                garrison.setTeamName(entityTag.getString("TeamName"));
            }
            garrison.fillFromPickBlockData(entityTag);
        }
        if (garrison.getTeamName() == null && player != null && player.getTeam() != null) {
            garrison.setTeamName(player.getTeam().getName());
        }

        // Team garrisons are never owned: no player ownership, squad, or recall.
        SoldierSpawner.SpawnResult result = SoldierSpawner.finishSpawn((ServerLevel) level, garrison, null, false);
        if (!result.success()) {
            return InteractionResult.FAIL;
        }

        if (player != null && !player.isCreative()) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.SUCCESS;
    }
}

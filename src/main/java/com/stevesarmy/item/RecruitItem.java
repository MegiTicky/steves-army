package com.stevesarmy.item;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.squad.SquadData;
import com.stevesarmy.squad.SquadManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class RecruitItem extends Item {
    public RecruitItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        BlockPos pos = context.getClickedPos();
        
        if (level.isClientSide || player == null) {
            return InteractionResult.SUCCESS;
        }
        
        ServerLevel serverLevel = (ServerLevel) level;
        
        SquadManager squadManager = SquadManager.get(serverLevel);
        SquadData squad = squadManager.getSquadByLeader(player.getUUID())
            .orElseGet(() -> squadManager.createSquad(player.getUUID()));
        
        SoldierEntity soldier = ModEntities.SOLDIER.get().create(serverLevel);
        if (soldier == null) {
            return InteractionResult.FAIL;
        }
        
        Vec3 spawnPos = pos.getCenter().add(0, 0.5, 0);
        soldier.moveTo(spawnPos.x, spawnPos.y, spawnPos.z, player.getYRot(), 0.0F);
        soldier.setOwnerUUID(player.getUUID());
        soldier.setSquadId(squad.getSquadId());
        
        serverLevel.addFreshEntity(soldier);
        squadManager.addMemberToSquad(squad.getSquadId(), soldier.getUUID());
        
        context.getItemInHand().shrink(1);
        
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "Recruited soldier (" + squad.getMemberCount() + " total)"
        ));
        
        return InteractionResult.SUCCESS;
    }
}
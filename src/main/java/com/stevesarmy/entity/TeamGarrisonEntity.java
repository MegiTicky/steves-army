package com.stevesarmy.entity;

import com.stevesarmy.registry.ModItems;
import com.stevesarmy.squad.FireTeam;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

/**
 * Team-owned garrison. Belongs to a scoreboard team instead of an individual
 * player: it holds a position, ignores pings, and is friendly to every member
 * of its team. It has no owner UUID, so it is not part of any player's squad,
 * recall pipeline, or squad command screen, and it never runs out of ammo.
 */
public class TeamGarrisonEntity extends GarrisonEntity {

    private String teamName = null;

    public TeamGarrisonEntity(EntityType<? extends SoldierEntity> type, Level level) {
        super(type, level);
        this.setSquadMode(SquadMode.HOLD);
        this.setFireTeam(FireTeam.ALL);
    }

    @Override
    public boolean isFriendlyTo(LivingEntity other) {
        if (other == this) return false;
        return this.getTeam() != null && other.getTeam() != null
            && this.getTeam().isAlliedTo(other.getTeam());
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (isFriendlyTo(target)) return false;
        return super.canAttack(target);
    }

    @Override
    public boolean isAlliedTo(Entity other) {
        if (other.getTeam() != null && this.getTeam() != null
            && this.getTeam().isAlliedTo(other.getTeam())) {
            return true;
        }
        return super.isAlliedTo(other);
    }

    @Override
    public boolean hasInfiniteReserveAmmo() {
        return InfiniteReserveAmmo.hasInfiniteReserveAmmo(this);
    }

    @Override
    public void configureInfiniteReserveAmmo() {
        InfiniteReserveAmmo.ensureInfiniteReserveAmmo(this);
    }

    @Override
    public boolean ensureInfiniteReserveAmmo() {
        return InfiniteReserveAmmo.ensureInfiniteReserveAmmo(this);
    }

    public String getTeamName() {
        return teamName;
    }

    public void setTeamName(String teamName) {
        this.teamName = teamName;
    }

    @Override
    protected Item getPickBlockEggItem() {
        return ModItems.TEAM_GARRISON_SPAWN_EGG.get();
    }

    @Override
    public ItemStack getPickedResult(HitResult target) {
        ItemStack stack = super.getPickedResult(target);
        if (teamName != null) {
            stack.getOrCreateTag().getCompound("EntityTag").putString("TeamName", teamName);
        }
        return stack;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (teamName != null) {
            tag.putString("TeamName", teamName);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("TeamName")) {
            teamName = tag.getString("TeamName");
        }
    }
}

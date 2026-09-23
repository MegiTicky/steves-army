package com.stevesarmy.combat;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Recruits-style reverse targeting: vanilla monsters proactively hunt soldiers
 * instead of only retaliating after being attacked. Soldiers are custom
 * PathfinderMobs, so nothing in vanilla AI ever sees them as prey on its own.
 * Covers every soldier type — friendly, enemy, garrison, vehicle crew — since
 * they all extend SoldierEntity.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class MonsterTargetingHandler {
    private MonsterTargetingHandler() {}

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!StevesArmyConfig.shouldMonstersAttackSoldiers()) return;
        if (!(event.getEntity() instanceof Monster monster)) return;
        // Creepers have no target-selector-driven AI; endermen anger via the stare mechanic.
        if (monster instanceof Creeper || monster instanceof EnderMan) return;
        if (hasSoldierTargetGoal(monster)) return;
        monster.targetSelector.addGoal(3, new TargetSoldiersGoal(monster));
    }

    /** Named subclass so dimension re-joins can be deduped instead of stacking goals. */
    private static final class TargetSoldiersGoal extends NearestAttackableTargetGoal<SoldierEntity> {
        TargetSoldiersGoal(Monster monster) {
            super(monster, SoldierEntity.class, true);
        }
    }

    private static boolean hasSoldierTargetGoal(Monster monster) {
        for (net.minecraft.world.entity.ai.goal.WrappedGoal wrapped : monster.targetSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof TargetSoldiersGoal) return true;
        }
        return false;
    }
}

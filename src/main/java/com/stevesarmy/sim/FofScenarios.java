package com.stevesarmy.sim;

import com.stevesarmy.StevesArmyConfig;
import com.stevesarmy.combat.FofCombat;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.FofRelations;
import com.stevesarmy.squad.FofStance;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.nbt.CompoundTag;

import java.util.Set;
import java.util.UUID;

/** Focused diplomacy state and live entity policy checks. */
public final class FofScenarios {
    private FofScenarios() {}

    public static SimScenario relationships() {
        boolean[] checks = new boolean[11];
        return SimScenario.builder("fof_relationships")
            .description("Symmetric player precedence, event snapshots, peace lock, directional retaliation and save/load.")
            .duration(20).terrain(t -> t.floorOnly())
            .setup(ctx -> {
                java.util.Arrays.fill(checks, false);
                UUID a = UUID.randomUUID(), b = UUID.randomUUID();
                FofRelations data = new FofRelations();
                data.setTeamRelation("red", "blue", FofStance.FRIENDLY);
                data.forcePlayerRelation(a, b, FofStance.HOSTILE);
                checks[0] = data.resolve(a, "red", b, "blue", 10).stance() == FofStance.HOSTILE
                    && data.resolve(b, "blue", a, "red", 10).stance() == FofStance.HOSTILE;
                checks[1] = data.startEvent("war", Set.of(a), Set.of(b), Set.of("red"), Set.of("blue"), FofStance.HOSTILE, 100);
                data.forcePlayerRelation(a, b, FofStance.FRIENDLY);
                checks[2] = data.resolve(a, "new_team", b, "blue", 20).source().equals("event:war")
                    && data.resolve(null, "red", null, "blue", 20).source().equals("event:war");
                FofRelations loaded = FofRelations.load(data.save(new CompoundTag()));
                checks[3] = loaded.resolve(a, "red", b, "blue", 20).source().equals("event:war");
                checks[4] = loaded.resolve(a, "red", b, "blue", 101).stance() == FofStance.FRIENDLY;
                data.endEvent("war");
                data.forcePlayerRelation(a, b, FofStance.HOSTILE);
                data.recordHostileAction(a, b, 200);
                checks[5] = data.mayRetaliate(b, a, 201) && !data.mayRetaliate(a, b, 201);
                checks[6] = data.requestPlayerChange(a, b, FofStance.FRIENDLY, 201) == FofRelations.ChangeResult.LOCKED;
                checks[7] = data.acceptPlayerChange(b, a, 202) == FofRelations.ChangeResult.LOCKED
                    && data.resolve(a, "red", b, "blue", 202).stance() == FofStance.HOSTILE;
                checks[8] = data.resolve(a, "red", b, "blue", 801).stance() == FofStance.FRIENDLY;
                checks[9] = !data.mayRetaliate(b, a, 801);
                checks[10] = data.resolve(a, "red", a, "red", 801).stance() == FofStance.FRIENDLY;
            })
            .assertThat("player hostility overrides friendly team symmetrically", v -> checks[0])
            .assertThat("event starts and applies to snapshot players and garrisons", v -> checks[1] && checks[2])
            .assertThat("event persists and expiry restores underlying diplomacy", v -> checks[3] && checks[4])
            .assertThat("neutral retaliation is directional and expires", v -> checks[5] && checks[9])
            .assertThat("peace waits for acceptance and combat cooldown", v -> checks[6] && checks[7] && checks[8])
            .assertThat("owner identity remains friendly", v -> checks[10])
            .build();
    }

    public static SimScenario pvePolicy() {
        boolean[] checks = new boolean[10];
        return SimScenario.builder("fof_pve_policy")
            .description("PvE blocks engagement between hostile owned soldiers while retaining enemy NPC combat.")
            .duration(20).terrain(t -> t.floorOnly())
            .setup(ctx -> {
                java.util.Arrays.fill(checks, false);
                UUID a = UUID.randomUUID(), b = UUID.randomUUID();
                SoldierEntity first = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "red", 10, 0, 20, SquadMode.HOLD);
                SoldierEntity second = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "blue", 20, 0, 20, SquadMode.HOLD);
                first.setOwnerUUID(a); second.setOwnerUUID(b);
                first.setNoAi(true); second.setNoAi(true);
                var enemy = ctx.spawnEnemy(30, 0, 20);
                enemy.setNoAi(true);
                var data = FofRelations.get(ctx.level.getServer());
                data.forcePlayerRelation(a, b, FofStance.HOSTILE);
                StevesArmyConfig.CombatPolicy previous = StevesArmyConfig.getCombatPolicy();
                boolean previousProtection = StevesArmyConfig.getSquadFriendlyFire();
                try {
                    StevesArmyConfig.COMBAT_POLICY.set(StevesArmyConfig.CombatPolicy.OPEN_PVP);
                    checks[0] = FofCombat.mayEngage(first, second);
                    StevesArmyConfig.SQUAD_FRIENDLY_FIRE.set(true);
                    float health = second.getHealth();
                    second.hurt(ctx.level.damageSources().mobAttack(first), 1.0f);
                    checks[6] = second.getHealth() < health;
                    StevesArmyConfig.COMBAT_POLICY.set(StevesArmyConfig.CombatPolicy.PVE_ONLY);
                    checks[1] = !FofCombat.policyAllows(first, second) && !FofCombat.mayEngage(first, second);
                    checks[2] = FofCombat.mayEngage(first, enemy);
                    second.invulnerableTime = 0;
                    health = second.getHealth();
                    second.hurt(ctx.level.damageSources().mobAttack(first), 1.0f);
                    checks[7] = second.getHealth() == health;
                    var cover = second.getCoverBehaviorManager();
                    float before = cover.getSuppressionTracker().getSuppressionLevel();
                    cover.onNearMiss(second.position(), second, 1.0f, first);
                    cover.onCbcNearMiss(second.position(), second, first);
                    checks[3] = cover.getSuppressionTracker().getSuppressionLevel() == before;
                    StevesArmyConfig.COMBAT_POLICY.set(StevesArmyConfig.CombatPolicy.OPEN_PVP);
                    data.forcePlayerRelation(a, b, FofStance.FRIENDLY);
                    second.invulnerableTime = 0;
                    health = second.getHealth();
                    second.hurt(ctx.level.damageSources().mobAttack(first), 1.0f);
                    checks[8] = second.getHealth() == health;
                    StevesArmyConfig.SQUAD_FRIENDLY_FIRE.set(false);
                    second.invulnerableTime = 0;
                    second.hurt(ctx.level.damageSources().mobAttack(first), 1.0f);
                    checks[9] = second.getHealth() < health;
                    cover.onNearMiss(second.position(), second, 1.0f, first);
                    cover.onCbcNearMiss(second.position(), second, first);
                    checks[4] = cover.getSuppressionTracker().getSuppressionLevel() == before;
                    data.forcePlayerRelation(a, b, FofStance.HOSTILE);
                    cover.onCbcNearMiss(second.position(), second, first);
                    checks[5] = cover.getSuppressionTracker().getSuppressionLevel() > before;
                } finally {
                    StevesArmyConfig.COMBAT_POLICY.set(previous);
                    StevesArmyConfig.SQUAD_FRIENDLY_FIRE.set(previousProtection);
                    data.forcePlayerRelation(a, b, null);
                }
            })
            .assertThat("hostile player units can engage under open PvP", v -> checks[0])
            .assertThat("PvE blocks hostile player units", v -> checks[1])
            .assertThat("PvE permits combat with enemy NPCs", v -> checks[2])
            .assertThat("PvE and friendly relationships block bullet and CBC suppression", v -> checks[3] && checks[4])
            .assertThat("hostile CBC fire still suppresses when PvP is allowed", v -> checks[5])
            .assertThat("real hostile damage applies in open PvP and is blocked in PvE", v -> checks[6] && checks[7])
            .assertThat("friendly damage follows the protection setting", v -> checks[8] && checks[9])
            .build();
    }
}

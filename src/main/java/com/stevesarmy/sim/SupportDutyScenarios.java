package com.stevesarmy.sim;

import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.entity.SupplyPouchProjectile;
import com.stevesarmy.entity.ai.SupportCoverGoal;
import com.stevesarmy.inventory.PouchContainer;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.registry.ModEntities;
import com.stevesarmy.registry.ModItems;
import com.stevesarmy.sim.scenario.SimContext;
import com.stevesarmy.sim.scenario.SimScenario;
import com.stevesarmy.squad.ResupplyConfig;
import com.stevesarmy.squad.OwnedSoldierRegistry;
import com.stevesarmy.squad.SquadMode;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/** Real support goal, real inventory and real pouch physics; no duty is injected. */
public final class SupportDutyScenarios {
    private SupportDutyScenarios() {}

    public static SimScenario coordination(boolean twoSupports) {
        boolean[] flags = new boolean[4]; // first duty, second duty, simultaneous claim, second support ready
        int[] deliveries = new int[2];
        String[] token = new String[1];
        SimScenario.Builder builder = SimScenario.builder(twoSupports ? "support_duty_two_supports" : "support_duty_two_recipients")
            .description("Real support goals coordinate healing-item deliveries within one squad.")
            .duration(1000)
            .terrain(t -> { t.platform(); t.coverRow(16, 21, 18, 21, 1); t.coverRow(16, 27, 18, 27, 1); })
            .setup(ctx -> {
                java.util.Arrays.fill(flags, false);
                java.util.Arrays.fill(deliveries, 0);
                token[0] = java.util.UUID.randomUUID().toString();
                ctx.spawnAnchor(4, 0, 24);
                SoldierEntity a = ctx.spawnFriendly(ModEntities.SUPPORT.get(), "support", 12, 0, 24, SquadMode.FOLLOW);
                a.getSoldierInventory().setItem(SoldierInventory.SLOT_GENERAL_START, fixtureApples(8, token[0]));
                if (twoSupports) {
                    SoldierEntity b = ctx.spawnFriendly(ModEntities.SUPPORT.get(), "support2", 12, 0, 28, SquadMode.FOLLOW);
                    b.getSoldierInventory().setItem(SoldierInventory.SLOT_GENERAL_START, fixtureApples(8, token[0]));
                    ctx.injectSuppression("support2", 8);
                }
                SoldierEntity recipient = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "recipient", 15, 0, 16, SquadMode.FOLLOW);
                recipient.setNoAi(true);
                ctx.disarmSoldier("recipient");
                if (!twoSupports) {
                    SoldierEntity second = ctx.spawnFriendly(ModEntities.SOLDIER.get(), "recipient2", 12, 0, 13, SquadMode.FOLLOW);
                    second.setNoAi(true);
                    ctx.disarmSoldier("recipient2");
                }
                java.util.UUID owner = ctx.anchor.getUUID();
                OwnedSoldierRegistry.get(ctx.level.getServer()).setResupplyConfig(owner, new ResupplyConfig(0, 1, 0, 2));
                ctx.spawnEnemy(38, 0, 24).setNoAi(true);
                ctx.injectSuppression("support", 8);
                ctx.cleanupActions.add(() -> {
                    java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
                    ctx.friendlies.forEach(s -> ids.add(s.getUUID()));
                    com.stevesarmy.entity.ai.SupportDutyController.clearSimState(ids);
                    OwnedSoldierRegistry.get(ctx.level.getServer()).clearSimResupplyConfig(owner);
                    ctx.level.getEntitiesOfClass(SupplyPouchProjectile.class, fixtureArea(ctx)).stream()
                        .filter(p -> p.getOwner() != null && ids.contains(p.getOwner().getUUID()))
                        .forEach(SupplyPouchProjectile::discard);
                    ctx.level.getEntitiesOfClass(ItemEntity.class, fixtureArea(ctx)).stream()
                        .filter(item -> fixtureItemCount(item.getItem(), token[0]) > 0)
                        .forEach(ItemEntity::discard);
                });
            });
        for (int tick = 10; tick <= 1000; tick += 2) {
            final int at = tick;
            builder.action(at, "sample support coordination", ctx -> {
                var first = (SupportCoverGoal) ctx.soldier("support").getCoverTacticalGoal();
                var second = twoSupports ? (SupportCoverGoal) ctx.soldier("support2").getCoverTacticalGoal() : null;
                java.util.UUID firstTarget = first.debugDuty().debugTarget();
                java.util.UUID secondTarget = second == null ? null : second.debugDuty().debugTarget();
                if (ctx.soldier("recipient").getUUID().equals(firstTarget) || ctx.soldier("recipient").getUUID().equals(secondTarget)) flags[0] = true;
                if (!twoSupports && ctx.soldier("recipient2").getUUID().equals(firstTarget)) flags[1] = true;
                if (firstTarget != null && firstTarget.equals(secondTarget)) flags[2] = true;
                deliveries[0] = apples(ctx.soldier("recipient"), token[0]);
                deliveries[1] = twoSupports ? 0 : apples(ctx.soldier("recipient2"), token[0]);
                if (twoSupports && deliveries[0] == 0
                    && ctx.soldier("support2").getCoverBehaviorManager().isInCover()) flags[3] = true;
                if (at % 100 == 0) ctx.diagnostics.add("t" + at + " target1=" + firstTarget + " target2=" + secondTarget
                    + " recipient1=" + deliveries[0] + " recipient2=" + deliveries[1]);
            });
        }
        return builder.assertThat("first recipient is selected and receives two items", v -> flags[0] && deliveries[0] == 2)
            .assertThat("two supports never claim the same recipient simultaneously", v -> !flags[2])
            .assertThat("second support reaches cover while the recipient still needs supplies", v -> !twoSupports || flags[3])
            .assertThat("both recipients are selected and resupplied", v -> twoSupports || (flags[1] && deliveries[1] == 2))
            .build();
    }

    public static SimScenario create(String name, int recipientX, int recipientZ, boolean noSupply, boolean blocked, boolean unsafe) {
        boolean[] observed = new boolean[4]; // cover, duty, delivery, return to cover
        int[] counts = new int[4]; // support, recipient, projectile, ground pouch
        String[] token = new String[1];
        boolean thresholdMet = name.equals("threshold_met");
        boolean fullInventory = name.equals("full_inventory");
        boolean playerRecipient = name.equals("player");
        boolean interrupt = name.equals("interrupted");
        boolean coverless = name.equals("coverless");
        boolean quiet = name.equals("quiet") || coverless;
        boolean[] interruption = new boolean[3]; // injected, duty exited, duty restarted
        net.minecraftforge.common.util.FakePlayer[] player = new net.minecraftforge.common.util.FakePlayer[1];
        SimScenario.Builder builder = SimScenario.builder("support_duty_" + name)
            .description("Support healing-item resupply with real goal and pouch physics; "
                + "TaCZ and PlayerRevive are not simulated by this fixture.")
            .duration(800)
            .terrain(t -> {
                if (coverless) {
                    t.floorOnly();
                    return;
                }
                t.platform();
                t.coverRow(16, 21, 18, 21, 1);
                t.coverRow(16, 27, 18, 27, 1);
                t.coverBlock(22, 24, 2);
                if (blocked) t.coverRow(16, 14, 16, 20, 3);
            })
            .setup(ctx -> {
                java.util.Arrays.fill(observed, false);
                java.util.Arrays.fill(counts, 0);
                java.util.Arrays.fill(interruption, false);
                ctx.spawnAnchor(4, 0, 24);
                AABB area = fixtureArea(ctx);
                token[0] = java.util.UUID.randomUUID().toString();
                SoldierEntity support = ctx.spawnFriendly(ModEntities.SUPPORT.get(), "support", 12, 0, 24, SquadMode.FOLLOW);
                SoldierEntity recipient = playerRecipient ? null : ctx.spawnFriendly(ModEntities.SOLDIER.get(), "recipient", recipientX, 0, recipientZ, SquadMode.FOLLOW);
                if (recipient != null) {
                    recipient.setNoAi(true);
                    ctx.disarmSoldier("recipient");
                }
                if (playerRecipient) {
                    java.util.UUID id = java.util.UUID.randomUUID();
                    player[0] = new net.minecraftforge.common.util.FakePlayer(ctx.level,
                        new com.mojang.authlib.GameProfile(id, "SimSupport"));
                    var pos = ctx.terrain.at(recipientX, 0, recipientZ);
                    player[0].moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
                    if (!ctx.level.addFreshEntity(player[0])) throw new IllegalStateException("failed to add sim player");
                    support.setOwnerUUID(id);
                } else player[0] = null;
                if (thresholdMet) recipient.getSoldierInventory().setItem(SoldierInventory.SLOT_GENERAL_START,
                    fixtureApples(2, token[0]));
                if (fullInventory) {
                    for (int i = SoldierInventory.SLOT_GENERAL_START; i < SoldierInventory.INVENTORY_SIZE; i++) {
                        recipient.getSoldierInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
                    }
                }
                if (!noSupply) support.getSoldierInventory().setItem(SoldierInventory.SLOT_GENERAL_START,
                    fixtureApples(8, token[0]));
                java.util.UUID ownerId = playerRecipient ? player[0].getUUID() : ctx.anchor.getUUID();
                OwnedSoldierRegistry.get(ctx.level.getServer()).setResupplyConfig(ownerId,
                    new ResupplyConfig(0, 1, 0, 2));
                ctx.cleanupActions.add(() -> {
                    java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
                    ctx.friendlies.forEach(s -> ids.add(s.getUUID()));
                    if (player[0] != null) ids.add(player[0].getUUID());
                    com.stevesarmy.entity.ai.SupportDutyController.clearSimState(ids);
                    OwnedSoldierRegistry.get(ctx.level.getServer()).clearSimResupplyConfig(ownerId);
                    ctx.level.getEntitiesOfClass(SupplyPouchProjectile.class, area).stream()
                        .filter(p -> p.getOwner() == support).forEach(SupplyPouchProjectile::discard);
                    ctx.level.getEntitiesOfClass(ItemEntity.class, area).stream()
                        .filter(item -> fixtureItemCount(item.getItem(), token[0]) > 0)
                        .forEach(ItemEntity::discard);
                    if (player[0] != null) player[0].discard();
                });
                if (!quiet) {
                    ctx.spawnEnemy(38, 0, 24).setNoAi(true);
                    ctx.injectSuppression("support", 8);
                }
                ctx.diagnostics.add("PlayerRevive integration: " + (com.stevesarmy.compat.PlayerReviveCompat.isLoaded()
                    ? "loaded; no bleeding-player fixture in this scenario" : "UNAVAILABLE in this run"));
            });
        for (int tick = 10; tick <= 800; tick += 10) {
            final int at = tick;
            builder.action(at, "sample support duty", ctx -> sample(ctx, at, observed, counts, token[0], player[0]));
            if (interrupt) builder.action(at, "interrupt active support duty once", ctx -> {
                SupportCoverGoal goal = (SupportCoverGoal) ctx.soldier("support").getCoverTacticalGoal();
                boolean active = "DUTY".equals(goal.debugPhase());
                if (!interruption[0] && active) {
                    ctx.injectSuppression("support", 8);
                    interruption[0] = true;
                    ctx.diagnostics.add("t" + at + " injected suppression during duty");
                } else if (interruption[0] && !active) interruption[1] = true;
                else if (interruption[1] && active) interruption[2] = true;
            });
        }
        return builder
            .assertThat("support reaches cover before a duty", view -> quiet || observed[0])
            .assertThat(noSupply || unsafe || thresholdMet ? "no duty starts when no eligible recipient can be served" : "support selects the recipient for resupply",
                view -> noSupply || unsafe || thresholdMet ? !observed[1] : observed[1])
            .assertThat(noSupply || unsafe || thresholdMet ? "no unexpected delivery" : blocked ? "blocked delivery remains observable" : fullInventory
                    ? "full recipient inventory drops undelivered items" : "recipient receives two healing items",
                view -> thresholdMet ? counts[1] == 2 && counts[0] == 8 : noSupply || unsafe ? !observed[2] : blocked ? true
                    : fullInventory ? counts[1] == 0 && counts[3] >= 2 : observed[2])
            .assertThat("blocked or full-recipient deliveries do not repeatedly expend supplies",
                view -> !(blocked || fullInventory) || counts[3] <= 2)
            .assertThat("healing items conserved in inventories, flight, or ground pouch",
                view -> counts[0] + counts[1] + counts[2] + counts[3] == (noSupply ? 0 : thresholdMet ? 10 : 8))
            .assertThat("support returns to cover after completed delivery", view -> !observed[2] || observed[3])
            .assertThat("suppressed duty aborts and can restart after recovery", view -> !interrupt
                || (interruption[0] && interruption[1] && interruption[2]))
            .build();
    }

    private static void sample(SimContext ctx, int tick, boolean[] observed, int[] counts,
                               String token, net.minecraft.world.entity.player.Player player) {
        SoldierEntity support = ctx.soldier("support");
        SoldierEntity recipient = ctx.soldier("recipient");
        if (!(support.getCoverTacticalGoal() instanceof SupportCoverGoal goal)) return;
        boolean covered = support.getCoverBehaviorManager().isInCover();
        if (!observed[1] && covered) observed[0] = true;
        if ("RESUPPLY".equals(goal.debugDuty().debugTask())) observed[1] = true;
        counts[0] = apples(support, token);
        counts[1] = player == null ? apples(recipient, token) : player.getInventory().items.stream()
            .mapToInt(stack -> fixtureItemCount(stack, token)).sum();
        if (counts[1] >= 2) observed[2] = true;
        if (observed[2] && covered) observed[3] = true;
        AABB area = fixtureArea(ctx);
        counts[2] = ctx.level.getEntitiesOfClass(SupplyPouchProjectile.class, area).stream()
            .filter(p -> p.getOwner() == support).mapToInt(p -> fixtureItemCount(p.getItem(), token)).sum();
        counts[3] = ctx.level.getEntitiesOfClass(ItemEntity.class, area).stream()
            .mapToInt(item -> fixtureItemCount(item.getItem(), token)).sum();
        if (tick % 50 == 0) {
            ctx.diagnostics.add(String.format("t%d phase=%s task=%s target=%s dutyTicks=%d holdTicks=%d cover=%s sup=%.2f pos=(%.1f,%.1f) apples=support:%d recipient:%d flight:%d ground:%d",
                tick, goal.debugPhase(), goal.debugDuty().debugTask(), goal.debugDuty().debugTarget(),
                goal.debugDuty().debugDutyTicks(), goal.debugDuty().debugHoldTicks(), covered,
                support.getCoverBehaviorManager().getSuppressionTracker().getSuppressionLevel(),
                support.getX(), support.getZ(), counts[0], counts[1], counts[2], counts[3]));
        }
    }

    private static AABB fixtureArea(SimContext ctx) {
        return new AABB(ctx.terrain.origin).inflate(70, 8, 70);
    }

    private static int apples(SoldierEntity soldier, String token) {
        int total = 0;
        SoldierInventory inv = soldier.getSoldierInventory();
        for (int i = 0; i < SoldierInventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            total += fixtureItemCount(stack, token);
        }
        return total;
    }

    private static ItemStack fixtureApples(int count, String token) {
        ItemStack stack = new ItemStack(Items.GOLDEN_APPLE, count);
        stack.getOrCreateTag().putString("SteveArmySimFixture", token);
        return stack;
    }

    private static int fixtureItemCount(ItemStack stack, String token) {
        if (stack.is(Items.GOLDEN_APPLE)) {
            return stack.hasTag() && token.equals(stack.getTag().getString("SteveArmySimFixture")) ? stack.getCount() : 0;
        }
        if (!stack.is(ModItems.SUPPLY_POUCH.get())) return 0;
        return PouchContainer.unpackContents(stack).stream().mapToInt(s -> fixtureItemCount(s, token)).sum();
    }
}

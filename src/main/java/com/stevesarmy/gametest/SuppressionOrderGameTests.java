package com.stevesarmy.gametest;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.ai.SuppressionOrderController;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;

/** Lifecycle tests that do not require a loaded weapon or terrain fixture. */
@GameTestHolder(StevesArmyMod.MODID)
public final class SuppressionOrderGameTests {
    private SuppressionOrderGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 220)
    public static void orderFailsWithoutFirstShot(GameTestHelper helper) {
        SuppressionOrderController order = new SuppressionOrderController();
        order.start(BlockPos.ZERO, 0);
        helper.runAtTickTime(201, () -> {
            order.tick(201);
            helper.assertTrue(order.getPhase() == SuppressionOrderController.Phase.FAILED,
                "order must fail after 200 ticks without a shot");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void preparationDoesNotSpendFiringBudget(GameTestHelper helper) {
        SuppressionOrderController order = new SuppressionOrderController();
        order.start(BlockPos.ZERO, 0);
        order.setPreparing(SuppressionOrderController.BlockReason.GEOMETRY);
        helper.assertTrue(order.getFiringTicksRemaining() == SuppressionOrderController.FIRING_BUDGET_TICKS,
            "preparation must not consume firing time");
        order.recordShot();
        order.consumeFiringTick();
        helper.assertTrue(order.getFiringTicksRemaining() == SuppressionOrderController.FIRING_BUDGET_TICKS - 1,
            "only active firing consumes the post-shot budget");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void repeatedOrderUsesNewGeneration(GameTestHelper helper) {
        SuppressionOrderController order = new SuppressionOrderController();
        order.start(BlockPos.ZERO, 0);
        int firstGeneration = order.getGeneration();
        order.start(BlockPos.ZERO.above(), 5);
        helper.assertTrue(order.getGeneration() > firstGeneration,
            "a repeated ping must invalidate stale relocation work");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void orderKeepsFixedRelocationOrigin(GameTestHelper helper) {
        SuppressionOrderController order = new SuppressionOrderController();
        BlockPos orderOrigin = new BlockPos(12, 64, -8);
        order.start(BlockPos.ZERO, 0);
        order.setOrigin(orderOrigin);
        order.setOrigin(orderOrigin.offset(20, 0, 20));
        helper.assertTrue(orderOrigin.equals(order.getOrigin()),
            "suppression relocation must stay centered on the order-start position");
        helper.succeed();
    }
}

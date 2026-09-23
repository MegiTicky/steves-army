package com.stevesarmy.client;

import com.stevesarmy.client.screen.FofQuickMarkScreen;
import com.stevesarmy.client.screen.SquadCommandScreen;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.DebugMessage;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.squad.FireTeam;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

@Mod.EventBusSubscriber(modid = "steves_army", value = Dist.CLIENT)
public class KeyInputHandler {

    private static boolean ctrlLastTick = false;
    private static final double MARK_TARGET_RANGE = 32.0;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (KeyBindings.DEBUG.consumeClick()) {
            NetworkHandler.INSTANCE.sendToServer(new DebugMessage());
        }

        if (KeyBindings.SQUAD_COMMAND.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof SquadCommandScreen) {
                mc.setScreen(null);
            } else {
                mc.setScreen(new SquadCommandScreen());
            }
        }

        if (KeyBindings.MARK_TARGET.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            LivingEntity target = findLookTarget(mc);
            if (target == null) {
                mc.player.displayClientMessage(Component.literal("No markable target in reach"), true);
            } else {
                mc.setScreen(new FofQuickMarkScreen(target));
            }
        }

        if (KeyBindings.CYCLE_FIRE_TEAM.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            long window = mc.getWindow().getWindow();
            boolean ctrlHeld = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;

            if (ctrlHeld) {
                FireTeamScopeState.INSTANCE.setCurrentScope(FireTeam.ALL, "cycle key with Ctrl");
                mc.player.displayClientMessage(Component.literal("[ALL]"), true);
            } else {
                FireTeam current = FireTeamScopeState.INSTANCE.getCurrentScope();
                int teamCount = FireTeamScopeState.INSTANCE.getTeamCount();
                FireTeam next;
                if (current == FireTeam.ALL || current.ordinal() >= teamCount) {
                    next = FireTeam.values()[1];
                } else {
                    next = FireTeam.values()[current.ordinal() + 1];
                }
                FireTeamScopeState.INSTANCE.setCurrentScope(next, "cycle key");
                mc.player.displayClientMessage(Component.literal("[" + next.getShortName() + "]"), true);
            }
        }
    }

    /**
     * Long-range entity pick along the view vector. Deliberately not
     * mc.hitResult — that is clipped to the ~3-block interaction reach and
     * never sees distant targets.
     */
    @Nullable
    private static LivingEntity findLookTarget(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 view = mc.player.getViewVector(1.0F);
        Vec3 end = eye.add(view.scale(MARK_TARGET_RANGE));
        AABB searchBox = mc.player.getBoundingBox().expandTowards(view.scale(MARK_TARGET_RANGE)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(mc.level, mc.player, eye, end, searchBox,
            entity -> entity instanceof LivingEntity living
                && entity.isPickable()
                && !entity.isSpectator()
                && entity != mc.player
                && isMarkable(mc.player, living));
        return hit != null && hit.getEntity() instanceof LivingEntity living ? living : null;
    }

    /** Only players and other owners' soldiers carry a FoF squad key. */
    private static boolean isMarkable(Player self, LivingEntity living) {
        if (living instanceof SoldierEntity soldier) {
            return soldier.getOwnerUUID().filter(ownerId -> !ownerId.equals(self.getUUID())).isPresent();
        }
        return living instanceof Player;
    }
}

package com.stevesarmy.mixins;

import com.stevesarmy.client.CommandStickState;
import com.stevesarmy.client.FireTeamScopeState;
import com.stevesarmy.client.StevesArmyClientConfig;
import com.stevesarmy.entity.EnemySoldierEntity;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.item.CommandStickItem;
import com.stevesarmy.squad.FireTeam;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftGlowMixin {

    @Inject(method = "m_91314_", at = @At("HEAD"), cancellable = true)
    private void forceGlowForCommandStick(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof SoldierEntity soldier)) return;

        CommandStickState state = CommandStickState.get();
        if (!state.isActive()) return;

        if (!state.isTargetable(soldier)) return;

        cir.setReturnValue(true);
    }

    @Inject(method = "m_91314_", at = @At("RETURN"), cancellable = true)
    private void onShouldEntityAppearGlowing(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof SoldierEntity soldier)) return;
        if (!cir.getReturnValueZ()) return;

        if (entity instanceof EnemySoldierEntity) {
            return;
        }

        Minecraft mc = (Minecraft) (Object) this;
        var player = mc.player;
        if (player == null) {
            cir.setReturnValue(false);
            return;
        }

        if (!soldier.isOwnedBy(player)) {
            cir.setReturnValue(false);
            return;
        }

        if (!StevesArmyClientConfig.SHOW_OWNED_SOLDIER_GLOW.get()) {
            cir.setReturnValue(false);
            return;
        }

        FireTeam scope = FireTeamScopeState.INSTANCE.getCurrentScope();
        cir.setReturnValue(scope == FireTeam.ALL || soldier.getFireTeam() == scope);
    }
}

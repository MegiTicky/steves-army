package com.stevesarmy.compat.ysm;

import com.elfmcys.yesstevemodel.client.entity.PlayerPreviewEntity;
import com.elfmcys.yesstevemodel.client.gui.button.ModelButton;
import com.elfmcys.yesstevemodel.client.model.ModelAssembly;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.NetworkHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Model list button that restyles the selected soldier instead of the local player. */
@OnlyIn(Dist.CLIENT)
public class SoldierModelButton extends ModelButton {

    private final SoldierEntity soldier;

    public SoldierModelButton(int x, int y, boolean isAuthLocked, PlayerPreviewEntity previewEntity, ModelAssembly modelAssembly, SoldierEntity soldier) {
        super(x, y, isAuthLocked, previewEntity, modelAssembly);
        this.soldier = soldier;
    }

    @Override
    public void onPress() {
        if (this.isStarred) {
            return;
        }
        String modelId = this.modelIdHolder.getModelId();
        String textureId = this.modelIdHolder.getCurrentTextureName();
        SoldierModelCapability capability = SoldierModelCapabilityProvider.get(this.soldier);
        if (capability != null) {
            capability.setYsmModel(modelId, textureId);
        }
        NetworkHandler.INSTANCE.sendToServer(new C2SRequestSoldierModelPacket(this.soldier.getId(), modelId, textureId));
    }
}

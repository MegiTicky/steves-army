package com.stevesarmy.compat.ysm;

import com.elfmcys.yesstevemodel.client.entity.PlayerPreviewEntity;
import com.elfmcys.yesstevemodel.client.gui.button.TextureButton;
import com.elfmcys.yesstevemodel.client.model.ModelAssembly;
import com.stevesarmy.entity.SoldierEntity;
import com.stevesarmy.network.NetworkHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Texture button that restyles the selected soldier. */
@OnlyIn(Dist.CLIENT)
public class SoldierTextureButton extends TextureButton {

    private final SoldierEntity soldier;
    private final int entityId;
    private final String modelId;
    private final String textureName;

    public SoldierTextureButton(int x, int y, PlayerPreviewEntity previewEntity, SoldierEntity soldier, int textureIndex, ModelAssembly modelAssembly) {
        super(x, y, previewEntity, modelAssembly);
        this.soldier = soldier;
        this.entityId = soldier.getId();
        SoldierModelCapability capability = SoldierModelCapabilityProvider.get(soldier);
        this.modelId = capability != null && capability.getModelId() != null ? capability.getModelId() : "";
        this.textureName = modelAssembly.getAnimationBundle().getTextures().getKeyAt(textureIndex);
        previewEntity.initModelWithTexture(this.modelId, this.textureName);
    }

    @Override
    public void onPress() {
        SoldierModelCapability capability = SoldierModelCapabilityProvider.get(this.soldier);
        if (capability != null) {
            capability.setYsmModel(this.modelId, this.textureName);
        }
        NetworkHandler.INSTANCE.sendToServer(new C2SRequestSoldierModelPacket(this.entityId, this.modelId, this.textureName));
    }
}

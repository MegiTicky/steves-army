package com.stevesarmy.compat.ysm;

import com.elfmcys.yesstevemodel.capability.AuthModelsCapabilityProvider;
import com.elfmcys.yesstevemodel.model.ServerModelManager;
import com.elfmcys.yesstevemodel.model.format.ServerModelData;
import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.entity.SoldierEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server-bound request to set a soldier's YSM model and texture. The server validates the
 * entity, ownership, model availability and auth requirement before applying the change.
 */
public class C2SRequestSoldierModelPacket {

    private final int entityId;
    private final String modelId;
    private final String textureId;

    public C2SRequestSoldierModelPacket(int entityId, String modelId, String textureId) {
        this.entityId = entityId;
        this.modelId = modelId;
        this.textureId = textureId;
    }

    public static void encode(C2SRequestSoldierModelPacket message, FriendlyByteBuf buf) {
        buf.writeInt(message.entityId);
        buf.writeUtf(message.modelId);
        buf.writeUtf(message.textureId);
    }

    public static C2SRequestSoldierModelPacket decode(FriendlyByteBuf buf) {
        return new C2SRequestSoldierModelPacket(buf.readInt(), buf.readUtf(), buf.readUtf());
    }

    public static void handle(C2SRequestSoldierModelPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer sender = context.getSender();
                if (sender != null) {
                    handleRequest(message, sender);
                }
            });
        }
        context.setPacketHandled(true);
    }

    private static void handleRequest(C2SRequestSoldierModelPacket message, ServerPlayer sender) {
        if (!YsmCompat.isLoaded()) {
            return;
        }
        ServerLevel level = sender.serverLevel();
        Entity entity = level.getEntity(message.entityId);
        if (!(entity instanceof SoldierEntity soldier)) {
            return;
        }
        if (!soldier.isOwnedBy(sender) && !sender.getAbilities().instabuild) {
            StevesArmyMod.LOGGER.debug("[YSM] Reject soldier model {} for {}: not owned and not creative", message.modelId, sender.getName().getString());
            return;
        }
        // The client is authoritative for the cosmetic model choice; it may have models the
        // server cannot load (e.g. OpenYSM hash-check failures). Validate texture and auth only
        // when the server actually has the model loaded, otherwise accept and persist.
        ServerModelData data = ServerModelManager.getServerModelInfo().get(message.modelId);
        if (data != null) {
            if (!data.getModelInfo().getTextures().contains(message.textureId)) {
                StevesArmyMod.LOGGER.debug("[YSM] Reject soldier model {} for {}: unknown texture {}", message.modelId, sender.getName().getString(), message.textureId);
                return;
            }
            if (ServerModelManager.getAuthModels().contains(message.modelId)) {
                boolean authorized = sender.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP)
                    .map(cap -> cap.containsModel(message.modelId))
                    .orElseGet(() -> false);
                if (!authorized) {
                    StevesArmyMod.LOGGER.debug("[YSM] Reject soldier model {} for {}: not authorized", message.modelId, sender.getName().getString());
                    return;
                }
            }
        } else {
            StevesArmyMod.LOGGER.debug("[YSM] Apply soldier model {} for {} without server-side model data", message.modelId, sender.getName().getString());
        }
        soldier.setYsmModelId(message.modelId);
        soldier.setYsmTextureId(message.textureId);
    }
}

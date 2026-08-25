package com.stevesarmy.compat.ysm;

import com.elfmcys.yesstevemodel.client.animation.StopAnimationPredicate;
import com.elfmcys.yesstevemodel.client.animation.predicate.ArmorPredicate;
import com.elfmcys.yesstevemodel.client.animation.predicate.InteractionHandAnimationPredicate;
import com.elfmcys.yesstevemodel.client.animation.predicate.ItemHoldAnimationPredicate;
import com.elfmcys.yesstevemodel.client.animation.predicate.MainHandHoldPredicate;
import com.elfmcys.yesstevemodel.client.animation.predicate.OffHandHoldPredicate;
import com.elfmcys.yesstevemodel.client.animation.predicate.OffhandAttackAnimationPredicate;
import com.elfmcys.yesstevemodel.client.compat.gun.common.ItemUseAnimationPredicate;
import com.elfmcys.yesstevemodel.client.entity.GeoEntity;
import com.elfmcys.yesstevemodel.client.entity.LivingAnimatable;
import com.elfmcys.yesstevemodel.client.model.AnimationDataProvider;
import com.elfmcys.yesstevemodel.client.model.ModelAssembly;
import com.elfmcys.yesstevemodel.client.model.ModelResourceBundle;
import com.elfmcys.yesstevemodel.client.model.processor.ControllerSlotBinder;
import com.elfmcys.yesstevemodel.client.model.processor.ParallelProcessor;
import com.elfmcys.yesstevemodel.geckolib3.core.builder.Animation;
import com.elfmcys.yesstevemodel.geckolib3.core.builder.AnimationController;
import com.elfmcys.yesstevemodel.geckolib3.core.controller.CompositeAnimationController;
import com.elfmcys.yesstevemodel.client.animation.predicate.NamedAnimationPredicate;
import com.stevesarmy.entity.SoldierEntity;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.apache.commons.lang3.function.TriFunction;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * YSM animatable capability for soldiers. Uses the same controllers as the vanilla YSM
 * player except the main predicate, which is LivingEntity-safe.
 */
@OnlyIn(Dist.CLIENT)
public class SoldierModelCapability extends LivingAnimatable<SoldierEntity> {

    private static final AnimationDataProvider<ModelAssembly> ANIMATION_DATA_PROVIDER = new AnimationDataProvider<>() {
        @Override
        public Object2ReferenceMap<String, AnimationController> getAnimationEntries(ModelAssembly assembly, ModelResourceBundle resourceBundle) {
            return assembly.getAnimationBundle().getAnimationEntries();
        }

        @Override
        public Object2ReferenceMap<String, Animation> getAnimations(ModelAssembly assembly, ModelResourceBundle resourceBundle) {
            return assembly.getAnimationBundle().getMainAnimations();
        }

        @Override
        public com.elfmcys.yesstevemodel.client.animation.condition.ConditionArmor getConditionArmor(ModelAssembly assembly, ModelResourceBundle resourceBundle) {
            return assembly.getAnimationBundle().getConditionManager().getArmor();
        }
    };

    public SoldierModelCapability(SoldierEntity soldier, boolean isActive) {
        super(soldier, isActive);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public void registerAnimationControllers() {
        // OpenYSM discovers these controllers from the loaded model. Fixed controller
        // names leave model-specific visibility animations, such as Wine Fox's Mask,
        // at their default state.
        registerParallelController("pre_parallel", (name, entity, animationName) ->
            new CompositeAnimationController(entity, name, 0.0f,
                animationName != null ? new NamedAnimationPredicate(animationName) : StopAnimationPredicate.INSTANCE));
        addAnimationController(new CompositeAnimationController(this, "player.main", 0.1f, new SoldierMainAnimationPredicate()));
        registerSlotController("pre_main", (name, entity) ->
            new CompositeAnimationController(entity, name, 0.0f, StopAnimationPredicate.INSTANCE));
        registerSlotController("post_main", (name, entity) ->
            new CompositeAnimationController(entity, name, 0.0f, StopAnimationPredicate.INSTANCE));
        registerSlotController("pre_hold", (name, entity) ->
            new CompositeAnimationController(entity, name, 0.0f, StopAnimationPredicate.INSTANCE));
        addAnimationController(new CompositeAnimationController(this, "player.hold_offhand", 0.1f, new OffHandHoldPredicate()));
        addAnimationController(new CompositeAnimationController(this, "player.hold_mainhand", 0.1f, new MainHandHoldPredicate()));
        registerSlotController("post_hold", (name, entity) ->
            new CompositeAnimationController(entity, name, 0.0f, StopAnimationPredicate.INSTANCE));
        if (ItemUseAnimationPredicate.isLoaded()) {
            addAnimationController(new CompositeAnimationController(this, "player.fire", 0.0f, new ItemUseAnimationPredicate()));
        }
        registerSlotController("pre_swing", (name, entity) ->
            new CompositeAnimationController(entity, name, 0.0f, StopAnimationPredicate.INSTANCE));
        addAnimationController(new CompositeAnimationController(this, "player.swing", 0.0f, new ItemHoldAnimationPredicate()));
        registerSlotController("post_swing", (name, entity) ->
            new CompositeAnimationController(entity, name, 0.0f, StopAnimationPredicate.INSTANCE));
        registerSlotController("pre_use", (name, entity) ->
            new CompositeAnimationController(entity, name, 0.0f, StopAnimationPredicate.INSTANCE));
        addAnimationController(new CompositeAnimationController(this, "player.use", 0.1f, new InteractionHandAnimationPredicate()));
        registerSlotController("post_use", (name, entity) ->
            new CompositeAnimationController(entity, name, 0.0f, StopAnimationPredicate.INSTANCE));
        addAnimationController(new CompositeAnimationController(this, "player.passenger", 0.1f, new OffhandAttackAnimationPredicate()));
        registerParallelController("parallel", (name, entity, animationName) ->
            new CompositeAnimationController(entity, name, 0.0f,
                animationName != null ? new NamedAnimationPredicate(animationName) : StopAnimationPredicate.INSTANCE, true));
        registerArmorControllers();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerParallelController(String slotName,
                                             TriFunction<String, SoldierModelCapability, String, CompositeAnimationController> factory) {
        new ParallelProcessor<SoldierModelCapability, ModelAssembly>("player", slotName, true, ANIMATION_DATA_PROVIDER,
            (name, entity, animationName) -> factory.apply(name, entity, animationName))
            .process(getModelAssembly(), getModelAssembly().getExpressionCache())
            .create(this, controller -> addAnimationController(controller));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerSlotController(String slotName,
                                         BiFunction<String, SoldierModelCapability, CompositeAnimationController> factory) {
        new ControllerSlotBinder<SoldierModelCapability, ModelAssembly>("player", slotName, ANIMATION_DATA_PROVIDER,
            (name, entity) -> factory.apply(name, entity))
            .process(getModelAssembly(), getModelAssembly().getExpressionCache())
            .create(this, controller -> addAnimationController(controller));
    }

    /** Registers per-slot armor controllers for slots the loaded model actually animates. */
    private void registerArmorControllers() {
        if (getModelAssembly() == null) {
            return;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.ARMOR) {
                continue;
            }
            String slotKey = "player.armor_" + slot.getName();
            if (getAnimationEntries(slotKey) != null || getAnimation(slot.getName() + ":default") != null) {
                addAnimationController(new CompositeAnimationController(this, slotKey, 0.0f, new ArmorPredicate(slot)));
            }
        }
    }

    @Override
    @NotNull
    public GeoEntity.ModelWrapper buildRenderShape(ModelAssembly modelAssembly, boolean isActive) {
        return new TexturedModelWrapper(modelAssembly, isActive, true, true, 600);
    }

    /**
     * Applies the model id/texture broadcast on the entity data to this capability.
     * Called from the render path before the geo model is used.
     */
    public void syncModelFromEntityData() {
        String modelId = entity.getYsmModelId();
        String textureId = entity.getYsmTextureId();
        if (modelId.isEmpty()) {
            if (isModelInitialized()) {
                resetModel();
            }
            return;
        }
        if (!modelId.equals(getModelId()) || !Objects.equals(textureId, currentTextureName) || !isModelInitialized()) {
            initModelWithTexture(modelId, textureId);
        }
    }

    /** Immediate client-side restyle; the C2S packet mirrors it for server persistence. */
    public void setYsmModel(String modelId, String textureId) {
        initModelWithTexture(modelId, textureId);
    }
}

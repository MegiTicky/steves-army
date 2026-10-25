package com.stevesarmy.compat.curios;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.stevesarmy.client.model.SoldierModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.client.ICurioRenderer;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client-only, loaded behind the Curios presence check. Never changes global item renderers. */
public final class SoldierCuriosLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
    private final RenderLayerParent<T, M> parent;

    /**
     * A fixed renderer draws geometry in vanilla model space rather than copying humanoid poses.
     * Its native sneak helpers are cancelled before applying the soldier attachment. Head profiles
     * receive zero look angles because the soldier head already contains the entire look rotation.
     * Unknown renderers receive only the unit root; slot names alone cannot identify renderer behavior.
     */
    public record FixedProfile(String attachment, boolean sneakTranslation, boolean sneakRotation,
                               boolean headLook) {}
    private static final Map<String, FixedProfile> FIXED = new ConcurrentHashMap<>();
    static {
        // CombatGear 3.4.0: standalone EntityModels, not HumanoidModels. These renderers call
        // translateIfSneaking, body ones additionally call rotateIfSneaking. Head models animate
        // their bone from the supplied look angles; body models have empty setupAnim methods.
        for (String name : new String[]{"T10parachuteclosed", "Radiobackpack", "Flamebacktank"}) {
            registerFixedRenderer("net.combatgear.client.renderer." + name + "Renderer",
                new FixedProfile("back", true, true, false));
        }
        for (String name : new String[]{"Gasmask", "Balaclava", "Gogglesdown", "Gpnvgdown", "Gpnvgup", "Sandmask", "Toque"}) {
            registerFixedRenderer("net.combatgear.client.renderer." + name + "Renderer",
                new FixedProfile("head", true, false, true));
        }
    }

    /** Per-renderer opt-in for other fixed vanilla-space accessories; "slot" uses slot/index mapping. */
    public static void registerFixedRenderer(String rendererClass, FixedProfile profile) {
        FIXED.put(java.util.Objects.requireNonNull(rendererClass), java.util.Objects.requireNonNull(profile));
    }

    public SoldierCuriosLayer(RenderLayerParent<T, M> parent) {
        super(parent);
        this.parent = parent;
    }

    @Override
    public void render(PoseStack stack, MultiBufferSource buffers, int light, T entity,
                       float limbSwing, float limbSwingAmount, float partialTicks,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        if (!(parent.getModel() instanceof SoldierModel<?> model)) return;
        CuriosApi.getCuriosInventory(entity).ifPresent(handler -> handler.getCurios().forEach((id, stacks) -> {
            for (int i = 0; i < stacks.getStacks().getSlots(); i++) {
                ItemStack item = stacks.getCosmeticStacks().getStackInSlot(i);
                boolean cosmetic = !item.isEmpty();
                boolean renderable = stacks.getRenders().size() > i && stacks.getRenders().get(i);
                if (!cosmetic && renderable) item = stacks.getStacks().getStackInSlot(i);
                if (item.isEmpty()) continue;
                ICurioRenderer renderer = CuriosRendererRegistry.getRenderer(item.getItem()).orElse(null);
                if (renderer == null) continue;
                SlotContext context = new SlotContext(id, entity, i, cosmetic, renderable);
                FixedProfile profile = FIXED.get(renderer.getClass().getName());
                stack.pushPose();
                try {
                    model.translateBodyRoot(stack);
                    if (profile != null) {
                        String attachment = profile.attachment().equals("slot") ? id : profile.attachment();
                        applyAttachment(stack, model, attachment, i);
                        // Renderer subsequently multiplies T_sneak * R_sneak. Cancel in reverse order.
                        if (entity.isCrouching()) {
                            if (profile.sneakRotation()) stack.mulPose(Axis.XP.rotation(-model.body.xRot));
                            if (profile.sneakTranslation()) stack.translate(0, -.1875, 0);
                        }
                    }
                    // Pose-copying renderers already have independently posed head/body/arms/legs.
                    // Root only here prevents applying any of those part rotations a second time.
                    if (renderer instanceof ICurioRenderer.HumanoidRender humanoid) {
                        renderHumanoid(humanoid, item, context, stack, buffers, light, model,
                            limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch);
                    } else {
                        renderer.render(item, context, stack, parent, buffers, light, limbSwing, limbSwingAmount,
                            partialTicks, ageInTicks, profile != null && profile.headLook() ? 0 : netHeadYaw,
                            profile != null && profile.headLook() ? 0 : headPitch);
                    }
                } finally {
                    stack.popPose();
                }
            }
        }));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void renderHumanoid(ICurioRenderer.HumanoidRender renderer, ItemStack item, SlotContext context,
                                PoseStack stack, MultiBufferSource buffers, int light, SoldierModel<?> source,
                                float swing, float amount, float partial, float age, float yaw, float pitch) {
        // Curios' default prepareModel calls vanilla setupAnim AFTER copying the parent. Reapply
        // soldier poses after preparation so vanilla walking cannot overwrite independent limb poses.
        RenderLayerParent bridge = parent;
        renderer.prepareModel(item, context, stack, bridge, swing, amount, partial, age, yaw, pitch);
        net.minecraft.client.model.HumanoidModel target = renderer.getModel(item, context);
        source.copyPropertiesTo(target);
        renderer.renderModel(item, context, stack, bridge, buffers, light);
    }

    /** Maps vanilla-space geometry into a single attachment, preserving its vanilla bind pivot. */
    static void applyAttachment(PoseStack stack, SoldierModel<?> model, String slot, int index) {
        ModelPart part;
        float x = 0, y = 0;
        boolean left = (index & 1) != 0;
        switch (slot.toLowerCase(Locale.ROOT)) {
            case "head", "face", "hat", "mask", "eyes" -> part = model.head;
            case "hands", "hand", "arms", "arm", "wrist", "bracelet", "ring" -> {
                part = left ? model.leftArm : model.rightArm;
                x = left ? 5 : -5; y = 2;
            }
            case "left_hand", "left_arm", "left_ring" -> { part = model.leftArm; x = 5; y = 2; }
            case "right_hand", "right_arm", "right_ring" -> { part = model.rightArm; x = -5; y = 2; }
            case "feet", "foot", "boots", "anklet" -> {
                part = left ? model.leftLeg : model.rightLeg;
                x = left ? 1.9F : -1.9F; y = 12;
                part.translateAndRotate(stack);
                model.translateCurioShin(stack, left);
                stack.translate(-x / 16, -(y + 6) / 16, 0);
                return;
            }
            case "legs", "leg", "left_leg", "right_leg" -> {
                left = slot.equalsIgnoreCase("left_leg") || (!slot.equalsIgnoreCase("right_leg") && left);
                part = left ? model.leftLeg : model.rightLeg;
                x = left ? 1.9F : -1.9F; y = 12;
            }
            // Necklaces sit at the collar, and conventionally follow the torso rather than look yaw.
            case "neck", "necklace", "back", "body", "chest", "shoulders", "cape", "belt", "waist" -> part = model.body;
            default -> { return; }
        }
        part.translateAndRotate(stack);
        stack.translate(-x / 16, -y / 16, 0);
    }
}

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.stevesarmy.client.model.SoldierModel;
import com.stevesarmy.client.animation.SoldierAnimations;
import com.stevesarmy.client.renderer.ArticulatedArmorModel;
import com.stevesarmy.client.renderer.SoldierHeldItemLayer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.animation.AnimationDefinition;
import java.lang.reflect.*;

/** Exercises the actual rig, keyframes, armor pose copying and reversed transitions. */
public class SoldierAnimationChecks {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static SoldierModel model;
    static ModelPart root;
    static Method apply, crouch, crawl;
    static void reset() {
        root.getAllParts().forEach(ModelPart::resetPose);
        root.y = 6; root.z = -1; root.xRot = .0873F;
    }
    static Bounds bounds(String... parts) {
        Bounds result = new Bounds();
        PoseStack stack = new PoseStack();
        root.translateAndRotate(stack);
        for (String name : parts) root.getChild(name).render(stack, result, 0, 0);
        return result;
    }
    public static void main(String[] args) throws Exception {
        root = SoldierModel.createBodyLayer().bakeRoot(); model = new SoldierModel(root);
        model.young = false;
        apply = SoldierModel.class.getDeclaredMethod("apply", AnimationDefinition.class, float.class, float.class);
        crouch = SoldierModel.class.getDeclaredMethod("applyCrouch", float.class, float.class, float.class);
        crawl = SoldierModel.class.getDeclaredMethod("applyCrawl", float.class, float.class, float.class, float.class, boolean.class);
        apply.setAccessible(true); crouch.setAccessible(true); crawl.setAccessible(true);
        for (AnimationDefinition clip : new AnimationDefinition[]{SoldierAnimations.UNIT_IDLE, SoldierAnimations.UNIT_WALK,
            SoldierAnimations.UNIT_HURT, SoldierAnimations.UNIT_HURT_1, SoldierAnimations.UNIT_DEATH, SoldierAnimations.UNIT_DEATH_BACK}) {
            for (int i = 0; i <= 100; i++) {
                reset(); apply.invoke(model, clip, clip.lengthInSeconds() * i / 100, 1f);
                root.getAllParts().forEach(p -> check(Float.isFinite(p.x + p.y + p.z + p.xRot + p.yRot + p.zRot
                    + p.xScale + p.yScale + p.zScale), "non-finite animation"));
            }
        }
        reset();
        Bounds standing = bounds("head", "body", "right_leg", "left_leg");
        crouch.invoke(model, 1f, 0f, 0f);
        Bounds kneel = bounds("head", "body", "right_leg", "left_leg");
        System.out.println("standing " + standing + " kneel " + kneel);
        check(kneel.maxY < standing.maxY - .2, "kneel did not lower silhouette");
        check(kneel.maxY <= 1.52, "kneeling head exceeds sneak hitbox");
        check(kneel.minY >= -.15, "kneeling leg sunk too far into floor");
        SoldierModel armor = new SoldierModel(SoldierModel.createBodyLayer(new CubeDeformation(.5f)).bakeRoot());
        model.copyPropertiesTo(armor);
        Field rootField = SoldierModel.class.getDeclaredField("unit"); rootField.setAccessible(true);
        ModelPart armorRoot = (ModelPart) rootField.get(armor);
        check(armorRoot.y == root.y && armorRoot.xRot == root.xRot, "armor lost root transform");
        check(armorRoot.getChild("right_leg").getChild("lower_leg").xRot == root.getChild("right_leg").getChild("lower_leg").xRot,
            "armor lost knee transform");
        checkForeignArmor();
        PoseStack palm = new PoseStack();
        model.translateWeaponToHand(palm);
        org.joml.Vector3f beforeGun = palm.last().pose().transformPosition(new org.joml.Vector3f());
        SoldierHeldItemLayer.applyGunDisplayTransform(palm, false);
        org.joml.Vector3f afterGun = palm.last().pose().transformPosition(new org.joml.Vector3f());
        check(beforeGun.distance(afterGun) < 1e-6, "gun display offset moved the authored grip anchor away from the palm");
        reset(); crawl.invoke(model, 1f, 0f, 0f, 0f, true);
        Bounds prone = bounds("head", "body", "right_leg", "left_leg");
        System.out.println("crawl " + prone);
        check(prone.maxY <= .7 && prone.minY >= -.2, "crawl does not fit ground envelope");
        // Motion state must not advance on repeated renders and must reverse continuously.
        Class<?> motionClass = Class.forName("com.stevesarmy.client.model.SoldierModel$Motion");
        Constructor<?> ctor = motionClass.getDeclaredConstructor(); ctor.setAccessible(true);
        Object motion = ctor.newInstance();
        Method update = motionClass.getDeclaredMethod("update", float.class, float.class, float.class, float.class); update.setAccessible(true);
        Field c = motionClass.getDeclaredField("crouch"); c.setAccessible(true);
        update.invoke(motion, 0f, 0f, 0f, 0f); update.invoke(motion, 1f, 1f, 1f, 1f);
        float before = c.getFloat(motion); update.invoke(motion, 1f, 0f, 0f, 0f);
        check(before == c.getFloat(motion), "same-frame render changes pose");
        update.invoke(motion, 2f, 0f, 0f, 0f);
        check(c.getFloat(motion) > 0 && c.getFloat(motion) < before, "transition reversal snapped");
        System.out.println("PASS: six clips x 101 samples; posture bounds; fallback AND replacement armor root/knees; gun anchor; transition reversal.");
    }
    static void checkForeignArmor() throws Exception {
        // Equipment mods replace our supplied model through Forge's armor hook.
        // A conventional rigid 12px armor leg must bend with the 6+6px skin rig.
        HumanoidModel custom = new HumanoidModel(LayerDefinition.create(
            HumanoidModel.createMesh(CubeDeformation.NONE, 0), 64, 32).bakeRoot());
        for (float amount : new float[]{0, .25f, .5f, .75f, 1}) {
            reset(); crouch.invoke(model, amount, 0f, 0f);
            model.copyPropertiesTo(custom);
            for (String part : new String[]{"head", "body", "right_leg", "left_leg"}) {
                custom.setAllVisible(false);
                ModelPart selected = switch (part) {
                    case "head" -> custom.head; case "body" -> custom.body;
                    case "right_leg" -> custom.rightLeg; default -> custom.leftLeg;
                };
                selected.visible = true;
                Bounds expected = bounds(part), actual = new Bounds();
                new ArticulatedArmorModel(custom, model).renderToBuffer(new PoseStack(), actual, 0, 0, 1, 1, 1, 1);
                check(Math.abs(expected.minY - actual.minY) < .03 && Math.abs(expected.maxY - actual.maxY) < .03,
                    "replacement armor detached from " + part + " at crouch " + amount + ": " + actual + " vs " + expected);
                check(selected.visible && custom.hat.visible == false, "borrowed armor visibility not restored");
            }
        }
    }
    static class Bounds implements VertexConsumer {
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        public VertexConsumer vertex(double x, double y, double z) { minY=Math.min(minY,1.5-y); maxY=Math.max(maxY,1.5-y); return this; }
        public VertexConsumer color(int r,int g,int b,int a) { return this; }
        public VertexConsumer uv(float u,float v) { return this; }
        public VertexConsumer overlayCoords(int u,int v) { return this; }
        public VertexConsumer uv2(int u,int v) { return this; }
        public VertexConsumer normal(float x,float y,float z) { return this; }
        public void endVertex() { }
        public void defaultColor(int r,int g,int b,int a) { }
        public void unsetDefaultColor() { }
        public String toString() { return "[" + minY + "," + maxY + "]"; }
    }
}

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
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exercises the actual rig, keyframes, armor pose copying and reversed transitions. */
public class SoldierAnimationChecks {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static SoldierModel model;
    static ModelPart root;
    static Method apply, crouch, crawl;
    static void reset() {
        root.getAllParts().forEach(ModelPart::resetPose);
        root.y = 6; root.z = -1; root.xRot = 5 * net.minecraft.util.Mth.DEG_TO_RAD;
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
        Method locomotion = SoldierModel.class.getDeclaredMethod("applyLocomotion", float.class, float.class, float.class);
        locomotion.setAccessible(true);
        for (int i = 0; i <= 100; i++) {
            reset(); locomotion.invoke(model, i * .1f, .8f, 1f);
            check(root.y == 6, "running introduces vertical root bob");
            check(Math.abs(model.rightLeg.xRot + model.leftLeg.xRot) < 1e-6, "running legs are not opposed");
        }
        reset(); locomotion.invoke(model, 0f, .8f, 1f);
        check(model.rightLeg.xRot > 1, "running stride is too short");
        reset();
        Method reviewedPosture = SoldierModel.class.getDeclaredMethod("applyReviewedPosture", float.class, float.class,
            float.class, float.class, float.class, boolean.class, float.class, float.class);
        reviewedPosture.setAccessible(true);
        reviewedPosture.invoke(model, 0f, 0f, 1f, 0f, 0f, false, 0f, 0f);
        locomotion.invoke(model, 0f, .8f, 1f);
        check(model.rightLeg.xRot > 1 && model.leftLeg.xRot < -1,
            "standing reviewed posture overwrote the running stride");
        checkReviewedEndpoints();
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
        System.out.println("PASS: six clips x 101 samples; reviewed Blockbench endpoint vertices; posture bounds; armor root/knees; gun orientation and attachment; transition reversal.");
    }
    static void checkReviewedEndpoints() throws Exception {
        Method weapon = SoldierModel.class.getDeclaredMethod("applyWeaponPose", String.class, String.class, float.class);
        Method reviewed = SoldierModel.class.getDeclaredMethod("applyReviewedPosture", float.class, float.class,
            float.class, float.class, float.class, boolean.class, float.class, float.class);
        weapon.setAccessible(true); reviewed.setAccessible(true);
        JsonObject states;
        try (var reader = new InputStreamReader(SoldierModel.class.getResourceAsStream(
                "/assets/steves_army/pose_data/reviewed_poses.json"), StandardCharsets.UTF_8)) {
            states = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("states");
        }
        for (String state : new String[]{"standing_ready", "standing_lowered", "crouch", "cover"}) {
            boolean crouched = state.equals("crouch") || state.equals("cover");
            float ready = state.equals("standing_lowered") || state.equals("crouch") ? 0 : 1;
            reset();
            weapon.invoke(model, "tacz:rifle", "rifle", ready);
            reviewed.invoke(model, crouched ? 1f : 0f, state.equals("cover") ? 1f : 0f, ready, 0f, 0f, true, 0f, 0f);
            JsonObject expected = states.getAsJsonObject(state).getAsJsonObject("expected");
            double largestError = 0;
            for (var entry : expected.entrySet()) {
                String name = entry.getKey(); boolean shin = name.endsWith("_shin");
                ModelPart parent = root.getChild(shin ? name.replace("_shin", "") : name);
                ModelPart part = shin ? parent.getChild("lower_leg") : parent;
                List<ModelPart> descendants = part.getAllParts().filter(p -> p != part).toList();
                List<Boolean> visible = descendants.stream().map(p -> p.visible).toList();
                descendants.forEach(p -> p.visible = false);
                Bounds vertices = new Bounds(); PoseStack stack = new PoseStack(); root.translateAndRotate(stack);
                if (shin) parent.translateAndRotate(stack);
                try { part.render(stack, vertices, 0, 0); }
                finally { for (int i = 0; i < descendants.size(); i++) descendants.get(i).visible = visible.get(i); }
                List<double[]> wanted = new ArrayList<>();
                for (JsonElement corner : entry.getValue().getAsJsonArray()) {
                    JsonArray a = corner.getAsJsonArray();
                    wanted.add(new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()});
                }
                double error = Math.max(nearestError(vertices.points, wanted), nearestError(wanted, vertices.points));
                largestError = Math.max(largestError, error);
                check(error < .00002, state + " differs from approved Blockbench " + name + " by " + error + " blocks");
            }
            System.out.println("Reviewed " + state + " max vertex error " + largestError);
        }
        // The calibrated standing ADS mount must retain the pre-port TaCZ
        // orientation; blend samples must not turn the gun through a half turn.
        reset(); weapon.invoke(model, "tacz:rifle", "rifle", 1f);
        PoseStack original = new PoseStack(); model.translateToHand(net.minecraft.world.entity.HumanoidArm.RIGHT, original);
        org.joml.Quaternionf oldMount = original.last().pose().getUnnormalizedRotation(new org.joml.Quaternionf());
        reviewed.invoke(model, 0f, 0f, 1f, 0f, 0f, true, 0f, 0f);
        PoseStack updated = new PoseStack(); model.translateWeaponToHand(updated);
        check(angleBetween(oldMount, updated.last().pose().getUnnormalizedRotation(new org.joml.Quaternionf())) < .001,
            "standing TaCZ mount changed basis");
        for (int phase = 0; phase < 2; phase++) {
            org.joml.Quaternionf previous = null;
            for (int i = 0; i <= 100; i++) {
                float t = i / 100f; reset(); weapon.invoke(model, "tacz:rifle", "rifle", phase == 0 ? 0f : t);
                reviewed.invoke(model, phase == 0 ? t : 1f, phase == 0 ? 0f : t,
                    phase == 0 ? 0f : t, 0f, 0f, true, 0f, 0f);
                PoseStack hand = new PoseStack(); model.translateWeaponToHand(hand);
                org.joml.Quaternionf q = hand.last().pose().getUnnormalizedRotation(new org.joml.Quaternionf());
                if (previous != null) check(angleBetween(previous, q) < .1, "weapon flips during reviewed transition");
                previous = q;
                root.getAllParts().forEach(p -> check(p.xScale == 1 && p.yScale == 1 && p.zScale == 1, "reviewed pose shortened a limb"));
            }
        }
    }
    static String pointSummary(List<double[]> points) {
        return points.stream().limit(8).map(p -> String.format(java.util.Locale.ROOT, "(%.6f,%.6f,%.6f)", p[0], p[1], p[2])).toList().toString();
    }
    static double nearestError(List<double[]> points, List<double[]> candidates) {
        double max = 0;
        for (double[] p : points) {
            double min = Double.POSITIVE_INFINITY;
            for (double[] q : candidates) min = Math.min(min, Math.sqrt(Math.pow(p[0]-q[0],2)+Math.pow(p[1]-q[1],2)+Math.pow(p[2]-q[2],2)));
            max = Math.max(max, min);
        }
        return max;
    }
    static double angleBetween(org.joml.Quaternionf a, org.joml.Quaternionf b) {
        return 2 * Math.acos(Math.min(1, Math.abs(a.dot(b))));
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
        final List<double[]> points = new ArrayList<>();
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        public VertexConsumer vertex(double x, double y, double z) { minY=Math.min(minY,1.5-y); maxY=Math.max(maxY,1.5-y); points.add(new double[]{x,1.5-y,z}); return this; }
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

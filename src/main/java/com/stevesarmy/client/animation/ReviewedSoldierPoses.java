package com.stevesarmy.client.animation;

import com.google.gson.*;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native transforms exported from the human-reviewed Blockbench endpoints. */
public final class ReviewedSoldierPoses {
    public record Part(Vector3f position, Quaternionf rotation, Vector3f centre) { }
    public record Pose(Map<String, Part> parts, Quaternionf weaponRotation) { }
    private static final Map<String, Pose> POSES = load();
    // Calibrate the upright proxy to the existing TaCZ hand/display convention.
    // TaCZ's own thirdperson_hand attachment still handles each gun's mesh origin.
    private static final Quaternionf WEAPON_BASIS = new Quaternionf(get("standing_ready").weaponRotation)
        .invert().mul(get("standing_ready").parts.get("right_arm").rotation);

    private ReviewedSoldierPoses() { }
    public static Pose get(String name) { return Objects.requireNonNull(POSES.get(name), name); }
    public static Quaternionf weaponMount(Pose pose) {
        return new Quaternionf(pose.weaponRotation).mul(WEAPON_BASIS);
    }
    public static Quaternionf rotation(ModelPart part) {
        return new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot);
    }
    public static Part worldPart(ModelPart root, ModelPart part, Vector3f centre) {
        Quaternionf r = rotation(root);
        Vector3f p = new Vector3f(part.x, part.y, part.z).rotate(r).add(root.x, root.y, root.z);
        return new Part(p, r.mul(rotation(part)), centre);
    }
    public static Part blend(Part a, Part b, float weight, boolean centrePivot) {
        Quaternionf rotation = new Quaternionf(a.rotation).slerp(b.rotation, weight);
        Vector3f position;
        if (centrePivot) {
            // The reviewed rigid arms animate around their centres. Reconstruct
            // the original skin/armor pivot after interpolation, never scale it.
            Vector3f ca = new Vector3f(a.centre).rotate(a.rotation).add(a.position);
            Vector3f cb = new Vector3f(b.centre).rotate(b.rotation).add(b.position);
            position = ca.lerp(cb, weight).sub(new Vector3f(a.centre).rotate(rotation));
        } else position = new Vector3f(a.position).lerp(b.position, weight);
        return new Part(position, rotation, a.centre);
    }
    public static Pose blend(Pose a, Pose b, float weight) {
        Map<String, Part> parts = new LinkedHashMap<>();
        a.parts.forEach((name, part) -> parts.put(name, blend(part, b.parts.get(name), weight, name.endsWith("_arm"))));
        return new Pose(parts, new Quaternionf(a.weaponRotation).slerp(b.weaponRotation, weight));
    }
    public static void writeLocal(ModelPart root, ModelPart part, Part world) {
        Quaternionf inverse = rotation(root).invert();
        Vector3f p = new Vector3f(world.position).sub(root.x, root.y, root.z).rotate(inverse);
        part.setPos(p.x, p.y, p.z);
        writeRotation(part, inverse.mul(world.rotation));
    }
    public static void writeRotation(ModelPart part, Quaternionf rotation) {
        float x = rotation.x, y = rotation.y, z = rotation.z, w = rotation.w;
        part.xRot = (float) Math.atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y));
        part.yRot = (float) Math.asin(Math.max(-1, Math.min(1, 2 * (w * y - z * x))));
        part.zRot = (float) Math.atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z));
    }
    private static Map<String, Pose> load() {
        var stream = ReviewedSoldierPoses.class.getResourceAsStream("/assets/steves_army/pose_data/reviewed_poses.json");
        if (stream == null) throw new IllegalStateException("Missing reviewed soldier poses");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject states = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("states");
            Map<String, Pose> result = new LinkedHashMap<>();
            for (var entry : states.entrySet()) {
                JsonObject data = entry.getValue().getAsJsonObject();
                Map<String, Part> parts = new LinkedHashMap<>();
                for (var p : data.getAsJsonObject("parts").entrySet()) {
                    JsonObject part = p.getValue().getAsJsonObject();
                    parts.put(p.getKey(), new Part(vector(part.getAsJsonArray("position")),
                        quaternion(part.getAsJsonArray("rotation")), vector(part.getAsJsonArray("centre"))));
                }
                result.put(entry.getKey(), new Pose(Collections.unmodifiableMap(parts), quaternion(data.getAsJsonArray("weaponRotation"))));
            }
            return Collections.unmodifiableMap(result);
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read reviewed soldier poses", e); }
    }
    private static Vector3f vector(JsonArray a) { return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()); }
    private static Quaternionf quaternion(JsonArray a) { return new Quaternionf(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat()).normalize(); }
}

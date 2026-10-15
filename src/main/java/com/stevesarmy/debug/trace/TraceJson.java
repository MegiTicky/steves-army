package com.stevesarmy.debug.trace;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Explicit adapters keep Minecraft internals and Java module reflection out of trace files. */
public final class TraceJson {
    private TraceJson() {}
    public static final Gson GSON = new GsonBuilder().serializeNulls()
        .registerTypeAdapter(Vec3.class, new VectorAdapter())
        .registerTypeAdapter(BlockPos.class, new PositionAdapter())
        .registerTypeAdapter(UUID.class, (JsonSerializer<UUID>) (v, t, c) -> new JsonPrimitive(v.toString()))
        .create();

    public static JsonArray vector(Vec3 v) {
        JsonArray a = new JsonArray();
        a.add(v.x); a.add(v.y); a.add(v.z);
        return a;
    }

    public static Vec3 vector(JsonArray a) {
        return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }
    private static final class VectorAdapter implements JsonSerializer<Vec3>, JsonDeserializer<Vec3> {
        public JsonElement serialize(Vec3 v, java.lang.reflect.Type t, JsonSerializationContext c) { return vector(v); }
        public Vec3 deserialize(JsonElement v, java.lang.reflect.Type t, JsonDeserializationContext c) { return vector(v.getAsJsonArray()); }
    }
    private static final class PositionAdapter implements JsonSerializer<BlockPos>, JsonDeserializer<BlockPos> {
        public JsonElement serialize(BlockPos v, java.lang.reflect.Type t, JsonSerializationContext c) { return vector(Vec3.atLowerCornerOf(v)); }
        public BlockPos deserialize(JsonElement v, java.lang.reflect.Type t, JsonDeserializationContext c) { return BlockPos.containing(vector(v.getAsJsonArray())); }
    }
}

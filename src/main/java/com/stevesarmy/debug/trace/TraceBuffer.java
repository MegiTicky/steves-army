package com.stevesarmy.debug.trace;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded serialized payloads; no live entities, worlds or paths are retained. Server thread only. */
public final class TraceBuffer {
    public record Row(long timeMs, String kind, UUID actor, String dimension, byte[] payload) {
        public JsonObject json() { return JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject(); }
        long cost() { return payload.length + 256L; }
    }
    private final Deque<Row> rows = new ArrayDeque<>();
    private final long maxBytes;
    private final long windowMs;
    private long bytes;
    private long dropped;
    private long lastBudgetEviction = -1;

    public TraceBuffer(long windowMs, long maxBytes) {
        this.windowMs = windowMs;
        this.maxBytes = maxBytes;
    }
    public void add(long timeMs, String kind, UUID actor, String dimension, JsonObject value) {
        byte[] payload = TraceJson.GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        Row row = new Row(timeMs, kind, actor, dimension, payload);
        if (row.cost() > maxBytes) { dropped++; lastBudgetEviction = timeMs; return; }
        rows.addLast(row); bytes += row.cost();
        prune(timeMs);
    }
    public void prune(long now) {
        while (!rows.isEmpty() && rows.peekFirst().timeMs < now - windowMs) removeFirst();
        while (bytes > maxBytes && !rows.isEmpty()) {
            lastBudgetEviction = now; dropped++; removeFirst();
        }
    }
    private void removeFirst() { bytes -= rows.removeFirst().cost(); }
    public List<Row> rows() { return List.copyOf(rows); }
    public long bytes() { return bytes; }
    public int size() { return rows.size(); }
    public long dropped() { return dropped; }
    public boolean truncated(long now) { return lastBudgetEviction >= 0 && lastBudgetEviction >= now - windowMs; }
    public long firstTime() { return rows.isEmpty() ? 0 : rows.peekFirst().timeMs; }
    public Set<UUID> actorIds() {
        Set<UUID> ids = new HashSet<>();
        for (Row row : rows) ids.add(row.actor);
        return ids;
    }
}

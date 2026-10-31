package com.stevesarmy.squad;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-authoritative, symmetric diplomacy for players and their owned units.
 * This deliberately does not replace the legacy category filters in
 * {@link FofSettings}; callers use {@link #resolve} for player/team identity.
 */
public final class FofRelations extends net.minecraft.world.level.saveddata.SavedData {
    private static final String DATA_NAME = "steves_army_fof_relations";
    private static final long COMBAT_LOCK_TICKS = 600L;

    private final Map<String, FofStance> players = new HashMap<>();
    private final Map<String, FofStance> teams = new HashMap<>();
    private final Map<String, PendingChange> pending = new HashMap<>();
    private final Map<String, Long> combat = new HashMap<>();
    private final Map<String, Long> retaliation = new HashMap<>();
    private final Map<String, EventRecord> events = new HashMap<>();
    private long lastCleanupTick = Long.MIN_VALUE;

    public static FofRelations get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            FofRelations::load, FofRelations::new, DATA_NAME);
    }

    public enum ChangeResult { APPLIED, PENDING, LOCKED, INVALID }

    public record Resolution(FofStance stance, String source) {}

    public record PendingChange(UUID requester, UUID other, @Nullable FofStance desired,
                                boolean acceptedByOther) {
        PendingChange accepted() {
            return new PendingChange(requester, other, desired, true);
        }
    }

    public record EventView(String id, Set<UUID> left, Set<UUID> right,
                            Set<String> leftTeams, Set<String> rightTeams,
                            FofStance stance, long expiresAt) {}

    private record EventRecord(String id, Set<UUID> left, Set<UUID> right,
                               Set<String> leftTeams, Set<String> rightTeams,
                               FofStance stance, long expiresAt) {
        EventView view() {
            return new EventView(id, Set.copyOf(left), Set.copyOf(right), Set.copyOf(leftTeams), Set.copyOf(rightTeams), stance, expiresAt);
        }
    }

    /** Resolve active relationship in strict priority order. */
    public Resolution resolve(@Nullable UUID a, @Nullable String teamA,
                              @Nullable UUID b, @Nullable String teamB, long tick) {
        cleanup(tick);
        if (a != null && a.equals(b)) return new Resolution(FofStance.FRIENDLY, "owner");
        EventRecord event = eventFor(a, teamA, b, teamB);
        if (event != null) return new Resolution(event.stance(), "event:" + event.id());
        if (a != null && b != null) {
            applyApprovedIfReady(a, b, tick);
            FofStance player = players.get(playerKey(a, b));
            if (player != null) return new Resolution(player, "player-pair");
        }
        if (teamA != null && teamB != null) {
            FofStance team = teams.get(teamKey(teamA, teamB));
            if (team != null) return new Resolution(team, "team-pair");
            if (teamA.equals(teamB)) return new Resolution(FofStance.FRIENDLY, "same-team");
        }
        return new Resolution(FofStance.NEUTRAL, "default");
    }

    public ChangeResult requestPlayerChange(UUID requester, UUID other,
                                             @Nullable FofStance desired,
                                             long tick) {
        return requestPlayerChange(requester, other, desired, null, null, tick);
    }

    public ChangeResult requestPlayerChange(UUID requester, UUID other,
                                             @Nullable FofStance desired,
                                             @Nullable String requesterTeam, @Nullable String otherTeam,
                                             long tick) {
        if (requester == null || other == null || requester.equals(other)) return ChangeResult.INVALID;
        String key = playerKey(requester, other);
        FofStance current = resolve(requester, requesterTeam, other, otherTeam, tick).stance();
        if (current == FofStance.HOSTILE && desired != FofStance.HOSTILE) {
            pending.put(key, new PendingChange(requester, other, desired, false));
            setDirty();
            return isCombatQuiet(key, tick) ? ChangeResult.PENDING : ChangeResult.LOCKED;
        }
        if (desired == null) players.remove(key); else players.put(key, desired);
        pending.remove(key);
        setDirty();
        return ChangeResult.APPLIED;
    }

    /** The other player accepts a pending de-escalation request. */
    public ChangeResult acceptPlayerChange(UUID accepter, UUID requester, long tick) {
        if (accepter == null || requester == null || accepter.equals(requester)) return ChangeResult.INVALID;
        cleanup(tick);
        String key = playerKey(accepter, requester);
        PendingChange change = pending.get(key);
        if (change == null || !change.other().equals(accepter) || !change.requester().equals(requester)) {
            return ChangeResult.INVALID;
        }
        if (!isCombatQuiet(key, tick) || eventFor(accepter, requester) != null) {
            pending.put(key, change.accepted());
            setDirty();
            return ChangeResult.LOCKED;
        }
        applyPending(key, change);
        return ChangeResult.APPLIED;
    }

    public boolean cancelPlayerChange(UUID actor, UUID other) {
        if (pending.remove(playerKey(actor, other)) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public Map<UUID, FofStance> playerRelations(UUID owner) {
        Map<UUID, FofStance> result = new HashMap<>();
        players.forEach((key, stance) -> {
            String[] parts = key.split("/", 2);
            UUID a = UUID.fromString(parts[0]);
            UUID b = UUID.fromString(parts[1]);
            if (a.equals(owner)) result.put(b, stance);
            else if (b.equals(owner)) result.put(a, stance);
        });
        return Map.copyOf(result);
    }

    public Map<String, FofStance> teamRelations(String team) {
        Map<String, FofStance> result = new HashMap<>();
        teams.forEach((key, stance) -> {
            String[] parts = key.split("/", 2);
            if (parts[0].equals(team)) result.put(parts[1], stance);
            else if (parts[1].equals(team)) result.put(parts[0], stance);
        });
        return Map.copyOf(result);
    }

    public List<PendingChange> pendingFor(UUID player) {
        List<PendingChange> result = new ArrayList<>();
        pending.values().forEach(change -> {
            if (change.requester().equals(player) || change.other().equals(player)) result.add(change);
        });
        return List.copyOf(result);
    }

    public boolean setTeamRelation(String first, String second, @Nullable FofStance stance) {
        if (first == null || second == null || first.isBlank() || second.isBlank() || first.equals(second)) return false;
        String key = teamKey(first, second);
        if (stance == null) teams.remove(key); else teams.put(key, stance);
        setDirty();
        return true;
    }

    public boolean forcePlayerRelation(UUID first, UUID second, @Nullable FofStance stance) {
        if (first == null || second == null || first.equals(second)) return false;
        String key = playerKey(first, second);
        if (stance == null) players.remove(key); else players.put(key, stance);
        pending.remove(key);
        setDirty();
        return true;
    }

    public List<EventView> events(long tick) {
        cleanup(tick);
        return events.values().stream().map(EventRecord::view).toList();
    }

    public boolean startEvent(String id, Set<UUID> left, Set<UUID> right,
                              FofStance stance, long expiresAt) {
        return startEvent(id, left, right, Set.of(), Set.of(), stance, expiresAt);
    }

    public boolean startEvent(String id, Set<UUID> left, Set<UUID> right,
                              Set<String> leftTeams, Set<String> rightTeams,
                              FofStance stance, long expiresAt) {
        if (id == null || id.isBlank() || events.containsKey(id) || stance == null || left == null || right == null
            || leftTeams == null || rightTeams == null || left.isEmpty() && leftTeams.isEmpty() || right.isEmpty() && rightTeams.isEmpty()
            || left.stream().anyMatch(right::contains)
            || leftTeams.stream().anyMatch(rightTeams::contains)) return false;
        if (conflictsWithEvents(id, left, right, leftTeams, rightTeams, stance)) return false;
        events.put(id, new EventRecord(id, Set.copyOf(left), Set.copyOf(right), Set.copyOf(leftTeams), Set.copyOf(rightTeams), stance, expiresAt));
        setDirty();
        return true;
    }

    public boolean endEvent(String id) {
        if (events.remove(id) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public boolean addEventParticipant(String id, boolean leftSide, UUID player) {
        EventRecord event = events.get(id);
        if (event == null || player == null) return false;
        Set<UUID> left = new HashSet<>(event.left());
        Set<UUID> right = new HashSet<>(event.right());
        if ((leftSide ? right : left).contains(player)) return false;
        if (!(leftSide ? left : right).add(player)) return false;
        if (conflictsWithEvents(id, left, right, event.leftTeams(), event.rightTeams(), event.stance())) return false;
        events.put(id, new EventRecord(id, Set.copyOf(left), Set.copyOf(right), event.leftTeams(), event.rightTeams(), event.stance(), event.expiresAt()));
        setDirty();
        return true;
    }

    public boolean removeEventParticipant(String id, UUID player) {
        EventRecord event = events.get(id);
        if (event == null || player == null) return false;
        Set<UUID> left = new HashSet<>(event.left());
        Set<UUID> right = new HashSet<>(event.right());
        boolean removed = left.remove(player) | right.remove(player);
        if (!removed || left.isEmpty() && event.leftTeams().isEmpty() || right.isEmpty() && event.rightTeams().isEmpty()) return false;
        events.put(id, new EventRecord(id, Set.copyOf(left), Set.copyOf(right), event.leftTeams(), event.rightTeams(), event.stance(), event.expiresAt()));
        setDirty();
        return true;
    }

    private boolean conflictsWithEvents(String id, Set<UUID> left, Set<UUID> right,
                                        Set<String> leftTeams, Set<String> rightTeams, FofStance stance) {
        for (EventRecord event : events.values()) {
            if (event.id().equals(id)) continue;
            boolean sameLeft = intersects(event.left(), left) || intersects(event.leftTeams(), leftTeams);
            boolean sameRight = intersects(event.right(), right) || intersects(event.rightTeams(), rightTeams);
            boolean crossLeft = intersects(event.left(), right) || intersects(event.leftTeams(), rightTeams);
            boolean crossRight = intersects(event.right(), left) || intersects(event.rightTeams(), leftTeams);
            boolean overlap = sameLeft && sameRight || crossLeft && crossRight;
            if (overlap && event.stance() != stance) return true;
        }
        return false;
    }

    private static <T> boolean intersects(Set<T> first, Set<T> second) {
        return first.stream().anyMatch(second::contains);
    }

    public void recordHostileAction(UUID attacker, UUID defender, long tick) {
        if (attacker == null || defender == null || attacker.equals(defender)) return;
        combat.put(playerKey(attacker, defender), tick);
        retaliation.put(defender + "/" + attacker, tick);
        setDirty();
    }

    public boolean mayRetaliate(UUID defender, UUID attacker, long tick) {
        Long last = retaliation.get(defender + "/" + attacker);
        return last != null && tick - last <= COMBAT_LOCK_TICKS;
    }

    public static FofRelations load(CompoundTag tag) {
        FofRelations data = new FofRelations();
        loadStances(tag.getList("Players", Tag.TAG_COMPOUND), data.players);
        loadStances(tag.getList("Teams", Tag.TAG_COMPOUND), data.teams);
        ListTag pendingTags = tag.getList("Pending", Tag.TAG_COMPOUND);
        for (int i = 0; i < pendingTags.size(); i++) {
            CompoundTag entry = pendingTags.getCompound(i);
            if (!entry.hasUUID("Requester") || !entry.hasUUID("Other")) continue;
            FofStance desired = entry.contains("Desired", Tag.TAG_INT) ? FofStance.fromOrdinal(entry.getInt("Desired")) : null;
            data.pending.put(playerKey(entry.getUUID("Requester"), entry.getUUID("Other")),
                new PendingChange(entry.getUUID("Requester"), entry.getUUID("Other"), desired, entry.getBoolean("Accepted")));
        }
        ListTag combatTags = tag.getList("Combat", Tag.TAG_COMPOUND);
        for (int i = 0; i < combatTags.size(); i++) {
            CompoundTag entry = combatTags.getCompound(i);
            if (entry.hasUUID("A") && entry.hasUUID("B")) data.combat.put(playerKey(entry.getUUID("A"), entry.getUUID("B")), entry.getLong("Tick"));
        }
        ListTag eventTags = tag.getList("Events", Tag.TAG_COMPOUND);
        for (int i = 0; i < eventTags.size(); i++) {
            CompoundTag entry = eventTags.getCompound(i);
            FofStance stance = FofStance.fromOrdinal(entry.getInt("Stance"));
            Set<UUID> left = readUuids(entry.getList("Left", Tag.TAG_COMPOUND));
            Set<UUID> right = readUuids(entry.getList("Right", Tag.TAG_COMPOUND));
            String id = entry.getString("Id");
            if (!id.isEmpty() && stance != null)
                data.events.put(id, new EventRecord(id, left, right,
                    readStrings(entry.getList("LeftTeams", Tag.TAG_STRING)), readStrings(entry.getList("RightTeams", Tag.TAG_STRING)),
                    stance, entry.getLong("ExpiresAt")));
        }
        ListTag retaliationTags = tag.getList("Retaliation", Tag.TAG_COMPOUND);
        for (int i = 0; i < retaliationTags.size(); i++) {
            CompoundTag entry = retaliationTags.getCompound(i);
            if (entry.hasUUID("Defender") && entry.hasUUID("Attacker"))
                data.retaliation.put(entry.getUUID("Defender") + "/" + entry.getUUID("Attacker"), entry.getLong("Tick"));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("Players", saveStances(players));
        tag.put("Teams", saveStances(teams));
        ListTag pendingTags = new ListTag();
        pending.values().forEach(change -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Requester", change.requester()); entry.putUUID("Other", change.other());
            if (change.desired() != null) entry.putInt("Desired", change.desired().ordinal());
            entry.putBoolean("Accepted", change.acceptedByOther()); pendingTags.add(entry);
        });
        tag.put("Pending", pendingTags);
        ListTag combatTags = new ListTag();
        combat.forEach((key, tick) -> {
            String[] parts = key.split("/", 2); CompoundTag entry = new CompoundTag();
            entry.putUUID("A", UUID.fromString(parts[0])); entry.putUUID("B", UUID.fromString(parts[1])); entry.putLong("Tick", tick); combatTags.add(entry);
        });
        tag.put("Combat", combatTags);
        ListTag eventTags = new ListTag();
        events.values().forEach(event -> {
            CompoundTag entry = new CompoundTag(); entry.putString("Id", event.id());
            entry.putInt("Stance", event.stance().ordinal()); entry.putLong("ExpiresAt", event.expiresAt());
            entry.put("Left", writeUuids(event.left())); entry.put("Right", writeUuids(event.right())); eventTags.add(entry);
            entry.put("LeftTeams", writeStrings(event.leftTeams())); entry.put("RightTeams", writeStrings(event.rightTeams()));
        });
        tag.put("Events", eventTags);
        ListTag retaliationTags = new ListTag();
        retaliation.forEach((key, tick) -> {
            String[] parts = key.split("/", 2); CompoundTag entry = new CompoundTag();
            entry.putUUID("Defender", UUID.fromString(parts[0])); entry.putUUID("Attacker", UUID.fromString(parts[1]));
            entry.putLong("Tick", tick); retaliationTags.add(entry);
        });
        tag.put("Retaliation", retaliationTags);
        return tag;
    }

    private void cleanup(long tick) {
        if (lastCleanupTick == tick) return;
        lastCleanupTick = tick;
        boolean changed = events.values().removeIf(event -> event.expiresAt() > 0 && event.expiresAt() <= tick);
        changed |= combat.values().removeIf(last -> tick - last > COMBAT_LOCK_TICKS);
        changed |= retaliation.values().removeIf(last -> tick - last > COMBAT_LOCK_TICKS);
        for (var entry : new ArrayList<>(pending.entrySet())) {
            PendingChange change = entry.getValue();
            if (change.acceptedByOther() && isCombatQuiet(entry.getKey(), tick)
                && eventFor(change.requester(), change.other()) == null) applyPending(entry.getKey(), change);
        }
        if (changed) setDirty();
    }

    private void applyApprovedIfReady(UUID a, UUID b, long tick) {
        String key = playerKey(a, b); PendingChange change = pending.get(key);
        if (change != null && change.acceptedByOther() && isCombatQuiet(key, tick)
            && eventFor(a, b) == null) applyPending(key, change);
    }

    private void applyPending(String key, PendingChange change) {
        if (change.desired() == null) players.remove(key); else players.put(key, change.desired());
        pending.remove(key); setDirty();
    }

    private boolean isCombatQuiet(String key, long tick) {
        Long last = combat.get(key); return last == null || tick - last > COMBAT_LOCK_TICKS;
    }

    @Nullable private EventRecord eventFor(UUID a, UUID b) {
        return eventFor(a, null, b, null);
    }

    @Nullable private EventRecord eventFor(@Nullable UUID a, @Nullable String teamA,
                                           @Nullable UUID b, @Nullable String teamB) {
        for (EventRecord event : events.values()) {
            boolean direct = eventSideContains(a, teamA, event.left(), event.leftTeams())
                && eventSideContains(b, teamB, event.right(), event.rightTeams());
            boolean reverse = eventSideContains(b, teamB, event.left(), event.leftTeams())
                && eventSideContains(a, teamA, event.right(), event.rightTeams());
            if (direct || reverse) return event;
        }
        return null;
    }

    private static boolean eventSideContains(@Nullable UUID player, @Nullable String team,
                                             Set<UUID> players, Set<String> teams) {
        return player != null ? players.contains(player) : team != null && teams.contains(team);
    }

    private static String playerKey(UUID first, UUID second) {
        return first.toString().compareTo(second.toString()) < 0 ? first + "/" + second : second + "/" + first;
    }

    private static String teamKey(String first, String second) {
        return first.compareTo(second) < 0 ? first + "/" + second : second + "/" + first;
    }

    private static void loadStances(ListTag list, Map<String, FofStance> output) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i); String key = entry.getString("Key");
            FofStance stance = FofStance.fromOrdinal(entry.getInt("Stance"));
            if (!key.isEmpty() && stance != null) output.put(key, stance);
        }
    }

    private static ListTag saveStances(Map<String, FofStance> source) {
        ListTag list = new ListTag();
        source.forEach((key, stance) -> { CompoundTag entry = new CompoundTag(); entry.putString("Key", key); entry.putInt("Stance", stance.ordinal()); list.add(entry); });
        return list;
    }

    private static Set<UUID> readUuids(ListTag list) {
        Set<UUID> result = new HashSet<>();
        for (int i = 0; i < list.size(); i++) if (list.getCompound(i).hasUUID("Id")) result.add(list.getCompound(i).getUUID("Id"));
        return result;
    }

    private static ListTag writeUuids(Set<UUID> ids) {
        ListTag list = new ListTag();
        ids.forEach(id -> { CompoundTag entry = new CompoundTag(); entry.putUUID("Id", id); list.add(entry); });
        return list;
    }

    private static Set<String> readStrings(ListTag list) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < list.size(); i++) if (!list.getString(i).isEmpty()) result.add(list.getString(i));
        return result;
    }

    private static ListTag writeStrings(Set<String> values) {
        ListTag list = new ListTag();
        values.forEach(value -> list.add(StringTag.valueOf(value)));
        return list;
    }
}

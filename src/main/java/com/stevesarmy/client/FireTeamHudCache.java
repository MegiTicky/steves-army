package com.stevesarmy.client;

import com.stevesarmy.network.SquadActivitySyncPacket.ActivityEntry;
import com.stevesarmy.squad.FireTeam;
import net.minecraft.client.Minecraft;
import java.util.*;

/** Rebuilt at most twice a second; never loads entities or requests paths. */
public final class FireTeamHudCache {
    public record Row(FireTeam team, int living, int unknown, ActivityEntry activity, float suppression, int progress, int eta) {}
    private record Travel(long generation, double start, double previous, long tick) {}
    private static final Map<FireTeam, Travel> travel = new EnumMap<>(FireTeam.class);
    private static Object level;
    private static long next;
    private static List<Row> rows = List.of();
    private FireTeamHudCache() {}
    public static List<Row> rows() {
        Minecraft mc = Minecraft.getInstance();
        if (level != mc.level) { level = mc.level; next = 0; rows = List.of(); travel.clear(); }
        long now = System.currentTimeMillis();
        if (now < next) return rows;
        next = now + 500;
        if (mc.level == null || now - ClientSquadData.INSTANCE.getLastUpdateTick() > 10000) return rows = List.of();
        List<Row> result = new ArrayList<>();
        var roster = ClientSquadData.INSTANCE.getAllEntries();
        var activities = ClientSquadActivityData.INSTANCE.getActivities();
        for (FireTeam team : new FireTeam[]{FireTeam.ALPHA, FireTeam.BRAVO, FireTeam.CHARLIE, FireTeam.DELTA}) {
            int living = 0, unknown = 0;
            for (var e : roster) if (e.getFireTeam() == team) {
                if (!e.loaded) unknown++; else if (e.health > 0) living++;
            }
            ActivityEntry activity = activities.stream().filter(e -> e.fireTeam() == team).findFirst()
                .orElseGet(() -> activities.stream().filter(e -> e.fireTeam() == FireTeam.ALL).findFirst().orElse(null));
            float suppression = -1;
            for (var e : ClientFireTeamSuppressionData.INSTANCE.getEntries()) if (e.fireTeam() == team) suppression = e.level();
            int progress = -1, eta = -1;
            if (activity != null && activity.type().name().equals("GO_TO") && unknown == 0
                    && activity.dimension() == mc.level.dimension().location().hashCode()) {
                double distance = 0; int observed = 0; boolean combat = false;
                for (var e : roster) if (e.getFireTeam() == team && e.loaded && e.health > 0) {
                    var entity = mc.level.getEntity(e.entityIntId);
                    if (entity != null) {
                        distance += entity.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(activity.objective()));
                        observed++;
                        if (entity instanceof com.stevesarmy.entity.SoldierEntity soldier) combat |= soldier.getSyncedSuppressionLevel() > .1f;
                    }
                }
                if (observed > 0) {
                    distance /= observed;
                    Travel old = travel.get(team);
                    if (old == null || old.generation() != activity.generation()) {
                        old = new Travel(activity.generation(), distance, distance, now);
                        travel.put(team, old);
                    }
                    progress = (int)Math.max(0, Math.min(95, 100 * (1 - distance / Math.max(1, old.start()))));
                    if (now - old.tick() >= 3000) {
                        double speed = (old.previous() - distance) * 1000 / (now - old.tick());
                        if (!combat && suppression <= .1f && speed > .15) eta = (int)Math.min(3600, distance / speed);
                        travel.put(team, new Travel(activity.generation(), old.start(), distance, now));
                    } else travel.putIfAbsent(team, old);
                }
            } else travel.remove(team);
            result.add(new Row(team, living, unknown, activity, suppression, progress, eta));
        }
        return rows = List.copyOf(result);
    }
}

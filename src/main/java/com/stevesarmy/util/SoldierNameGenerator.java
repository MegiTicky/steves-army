package com.stevesarmy.util;

import net.minecraft.util.RandomSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SoldierNameGenerator {
    private static final List<String> RANKS = List.of(
        "Pvt.", "PFC", "Cpl.", "Sgt."
    );

    private static final List<String> LAST_NAMES = List.of(
        "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis", "Rodriguez", "Martinez",
        "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin",
        "Lee", "Perez", "Thompson", "White", "Harris", "Sanchez", "Clark", "Ramirez", "Lewis", "Robinson"
    );

    private static final Map<UUID, Set<String>> usedLastNamesByOwner = new HashMap<>();

    public static String generate(RandomSource random) {
        return RANKS.get(random.nextInt(RANKS.size())) + " " + LAST_NAMES.get(random.nextInt(LAST_NAMES.size()));
    }

    public static String generateForOwner(RandomSource random, UUID ownerUUID) {
        Set<String> used = usedLastNamesByOwner.computeIfAbsent(ownerUUID, k -> new HashSet<>());
        List<String> available = LAST_NAMES.stream()
            .filter(name -> !used.contains(name))
            .toList();
        String last;
        if (available.isEmpty()) {
            used.clear();
            last = LAST_NAMES.get(random.nextInt(LAST_NAMES.size()));
        } else {
            last = available.get(random.nextInt(available.size()));
        }
        used.add(last);
        return RANKS.get(random.nextInt(RANKS.size())) + " " + last;
    }
}
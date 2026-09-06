package com.stevesarmy.skin;

import com.stevesarmy.StevesArmyMod;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server-side registry of user-provided soldier skins.
 * Scans {@code <game dir>/stevesarmy/skins/*.png}; the filename (without
 * extension) is the skin name. Only filenames are read here; the pixels are
 * loaded client-side by SoldierSkinLoader, so the same folder works for
 * singleplayer and dedicated servers.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class SoldierSkinManager {

    private static final String FOLDER_NAME = "stevesarmy";
    private static final String SKINS_DIR = "skins";

    private static volatile List<String> skinNames = List.of();
    private static volatile boolean scanned = false;

    private SoldierSkinManager() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        reload();
    }

    public static Path getSkinFolder() {
        return FMLPaths.GAMEDIR.get().resolve(FOLDER_NAME).resolve(SKINS_DIR);
    }

    /** Rescans the skins folder; creates it (with a README) when missing. */
    public static synchronized void reload() {
        List<String> found = new ArrayList<>();
        try {
            Path folder = getSkinFolder();
            if (!Files.isDirectory(folder)) {
                Files.createDirectories(folder);
                writeReadme(folder);
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.png")) {
                for (Path file : stream) {
                    String name = stripExtension(file.getFileName().toString());
                    if (!name.isEmpty() && !found.contains(name)) {
                        found.add(name);
                    }
                }
            }
        } catch (IOException e) {
            StevesArmyMod.LOGGER.warn("[Skins] Failed to scan skins folder: {}", e.toString());
        }
        Collections.sort(found, String.CASE_INSENSITIVE_ORDER);
        skinNames = List.copyOf(found);
        scanned = true;
        StevesArmyMod.LOGGER.info("[Skins] Found {} skin(s) in {}", found.size(), getSkinFolder());
    }

    /** Known skin names (sorted, case-insensitive); scans the folder on first access. */
    public static List<String> getSkinNames() {
        if (!scanned) {
            reload();
        }
        return skinNames;
    }

    public static boolean isKnown(String name) {
        return name != null && !name.isEmpty() && getSkinNames().contains(name);
    }

    /** Random skin name, or null when no skins are available. */
    public static String randomSkinName() {
        List<String> names = getSkinNames();
        if (names.isEmpty()) {
            return null;
        }
        return names.get(ThreadLocalRandom.current().nextInt(names.size()));
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private static void writeReadme(Path folder) throws IOException {
        Path readme = folder.resolve("README.txt");
        if (!Files.exists(readme)) {
            Files.writeString(readme, String.join("\n",
                "Steve's Army custom soldier skins.",
                "",
                "Drop player-skin PNG files here (64x64, or 64x32 legacy).",
                "The filename without .png is the skin name, e.g. ranger.png -> \"ranger\".",
                "",
                "New skins are picked up after F3+T (singleplayer) or a server restart;",
                "you can also run /stevesarmy_debug skins reload.",
                "Use the Skin Knife item on a soldier to cycle or reset its skin,",
                "and enable skins.randomizeOnSpawn in the server config for random",
                "skins on newly spawned soldiers."),
                StandardCharsets.UTF_8);
        }
    }
}

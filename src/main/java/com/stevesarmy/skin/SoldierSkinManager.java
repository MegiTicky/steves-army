package com.stevesarmy.skin;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.SyncSoldierSkinsPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
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
 * extension) is the skin name. Only filenames are read for validation; the
 * pixels are loaded client-side by SoldierSkinLoader from the local folder or
 * from bytes pushed by {@link #sendAllTo} on dedicated servers.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID)
public final class SoldierSkinManager {

    private static final String FOLDER_NAME = "stevesarmy";
    private static final String SKINS_DIR = "skins";

    /** Max bytes for one skin PNG to be transferred over the network. */
    private static final int MAX_SKIN_BYTES = 256 * 1024;
    /** Total transfer budget per player sync; skins beyond it are skipped. */
    private static final int MAX_TOTAL_TRANSFER_BYTES = 8 * 1024 * 1024;

    private static volatile List<String> skinNames = List.of();
    private static volatile boolean scanned = false;

    private SoldierSkinManager() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        reload();
    }

    /** Pushes every valid skin's bytes to a player (dedicated-server support). */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendAllTo(player);
        }
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

    /**
     * Sends every valid skin's PNG bytes to the player so dedicated-server
     * clients can render skins they do not have locally. Oversized, corrupt,
     * or wrong-dimension files are skipped (the client falls back to default).
     */
    public static void sendAllTo(ServerPlayer player) {
        List<SyncSoldierSkinsPacket.SkinData> entries = new ArrayList<>();
        int totalBytes = 0;
        for (String name : getSkinNames()) {
            byte[] data = readSkinForTransfer(name);
            if (data == null) {
                StevesArmyMod.LOGGER.warn("[Skins] Not transferring '{}': missing, oversized, or not a 64x64/64x32 PNG", name);
                continue;
            }
            totalBytes += data.length;
            if (totalBytes > MAX_TOTAL_TRANSFER_BYTES) {
                StevesArmyMod.LOGGER.warn("[Skins] Transfer budget ({} bytes) exceeded; remaining skins not sent",
                    MAX_TOTAL_TRANSFER_BYTES);
                break;
            }
            entries.add(new SyncSoldierSkinsPacket.SkinData(name, data));
        }
        NetworkHandler.sendTo(player, new SyncSoldierSkinsPacket(entries));
    }

    /** Reads and validates one skin's PNG bytes for network transfer; null if unknown or invalid. */
    public static byte[] readSkinForTransfer(String name) {
        if (!isKnown(name)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(getSkinFolder().resolve(name + ".png"));
            if (bytes.length > MAX_SKIN_BYTES) {
                return null;
            }
            int[] dims = pngDimensions(bytes);
            if (dims == null || !isSupportedSize(dims[0], dims[1])) {
                return null;
            }
            return bytes;
        } catch (IOException e) {
            return null;
        }
    }

    /** True for the supported player-skin dimensions. */
    public static boolean isSupportedSize(int width, int height) {
        return width == 64 && (height == 64 || height == 32);
    }

    /**
     * Returns {width, height} parsed from the PNG IHDR header, or null if the
     * bytes are not a PNG. Deliberately dependency-free: blaze3d's NativeImage
     * does not exist on dedicated servers.
     */
    private static int[] pngDimensions(byte[] bytes) {
        if (bytes == null || bytes.length < 24) {
            return null;
        }
        boolean signature = (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
            && (bytes[4] & 0xFF) == 0x0D && (bytes[5] & 0xFF) == 0x0A && (bytes[6] & 0xFF) == 0x1A && (bytes[7] & 0xFF) == 0x0A;
        boolean ihdr = bytes[12] == 'I' && bytes[13] == 'H' && bytes[14] == 'D' && bytes[15] == 'R';
        if (!signature || !ihdr) {
            return null;
        }
        int width = ((bytes[16] & 0xFF) << 24) | ((bytes[17] & 0xFF) << 16) | ((bytes[18] & 0xFF) << 8) | (bytes[19] & 0xFF);
        int height = ((bytes[20] & 0xFF) << 24) | ((bytes[21] & 0xFF) << 16) | ((bytes[22] & 0xFF) << 8) | (bytes[23] & 0xFF);
        return new int[]{width, height};
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
                "On a dedicated server these files are pushed to clients automatically;",
                "skins changed while the server runs need /stevesarmy_debug skins reload.",
                "Use the Skin Knife item on a soldier to pick a skin, and enable",
                "skins.randomizeOnSpawn in the server config for random skins on",
                "newly spawned soldiers."),
                StandardCharsets.UTF_8);
        }
    }
}

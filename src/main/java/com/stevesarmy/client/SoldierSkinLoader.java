package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.network.NetworkHandler;
import com.stevesarmy.network.RequestSkinPacket;
import com.stevesarmy.network.SyncSoldierSkinsPacket;
import com.stevesarmy.skin.SoldierSkinManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Client-side loader for user-provided soldier skins. Sources, in resolution
 * priority: the local {@code <game dir>/stevesarmy/skins} folder (uploaded as
 * DynamicTextures), resource packs at
 * {@code assets/steves_army/textures/entity/soldier/skins/<name>.png}, and
 * skins whose PNG bytes were pushed by the server (dedicated-server support).
 * The reload listener re-registers everything on F3+T, and unknown names are
 * lazily fetched from the local folder or requested from the server once per
 * session.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SoldierSkinLoader {

    /** Names of local folder skins whose DynamicTexture is currently registered. */
    private static final Set<String> FOLDER_SKINS = new HashSet<>();
    /** Cached resource-pack texture lookups (empty Optional = not present). */
    private static final Map<String, Optional<ResourceLocation>> RESOURCE_PACK_CACHE = new HashMap<>();
    /** Local skin names already tried via lazy load this session (prevents per-frame retries). */
    private static final Set<String> LAZY_ATTEMPTED = new HashSet<>();
    /** Server-pushed skins: name -> PNG bytes (kept so resource reloads can re-register). */
    private static final Map<String, byte[]> REMOTE_BYTES = new HashMap<>();
    /** Server-pushed skins: name -> registered texture id. */
    private static final Map<String, ResourceLocation> REMOTE_TEXTURES = new HashMap<>();
    /** Names already requested from the server this session. */
    private static final Set<String> REQUEST_ATTEMPTED = new HashSet<>();

    private SoldierSkinLoader() {}

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
                SoldierSkinLoader.reload();
            }
        });
    }

    /** Rescans the local folder and (re)registers one DynamicTexture per skin. */
    public static synchronized void reload() {
        FOLDER_SKINS.clear();
        RESOURCE_PACK_CACHE.clear();
        LAZY_ATTEMPTED.clear();
        SoldierSkinManager.reload();
        for (String name : SoldierSkinManager.getSkinNames()) {
            Path file = SoldierSkinManager.getSkinFolder().resolve(name + ".png");
            try (InputStream in = Files.newInputStream(file)) {
                NativeImage image = NativeImage.read(in);
                if (!SoldierSkinManager.isSupportedSize(image.getWidth(), image.getHeight())) {
                    StevesArmyMod.LOGGER.warn("[Skins] Skipping '{}': {}x{} is unsupported (need 64x64 or 64x32)",
                        name, image.getWidth(), image.getHeight());
                    image.close();
                    continue;
                }
                // Registering under the same id closes and replaces any previous texture.
                Minecraft.getInstance().getTextureManager()
                    .register(folderTextureId(name), new DynamicTexture(image));
                FOLDER_SKINS.add(name);
            } catch (Exception e) {
                StevesArmyMod.LOGGER.warn("[Skins] Failed to load '{}': {}", name, e.toString());
            }
        }
        // Resource reload drops registered textures; rebuild the remote ones from stored bytes.
        for (Map.Entry<String, byte[]> entry : new HashMap<>(REMOTE_BYTES).entrySet()) {
            registerRemoteTexture(entry.getKey(), entry.getValue());
        }
        StevesArmyMod.LOGGER.info("[Skins] Registered {} folder and {} server skin texture(s)",
            FOLDER_SKINS.size(), REMOTE_TEXTURES.size());
    }

    /** Stores and registers skins pushed by the server (main thread via packet enqueueWork). */
    public static synchronized void receiveServerSkins(List<SyncSoldierSkinsPacket.SkinData> entries) {
        int stored = 0;
        for (SyncSoldierSkinsPacket.SkinData entry : entries) {
            if (REMOTE_BYTES.containsKey(entry.name())) {
                continue;
            }
            if (registerRemoteTexture(entry.name(), entry.data())) {
                stored++;
            }
        }
        if (stored > 0) {
            StevesArmyMod.LOGGER.info("[Skins] Received {} server skin(s) ({} total)", stored, REMOTE_BYTES.size());
        }
    }

    /** Validates, stores, and registers one remote skin; false if the bytes are not usable. */
    private static boolean registerRemoteTexture(String name, byte[] data) {
        try (InputStream in = new ByteArrayInputStream(data)) {
            NativeImage image = NativeImage.read(in);
            if (!SoldierSkinManager.isSupportedSize(image.getWidth(), image.getHeight())) {
                StevesArmyMod.LOGGER.warn("[Skins] Skipping server skin '{}': {}x{} is unsupported",
                    name, image.getWidth(), image.getHeight());
                image.close();
                return false;
            }
            ResourceLocation id = remoteTextureId(name);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
            REMOTE_BYTES.put(name, data);
            REMOTE_TEXTURES.put(name, id);
            return true;
        } catch (Exception e) {
            StevesArmyMod.LOGGER.warn("[Skins] Failed to decode server skin '{}': {}", name, e.toString());
            return false;
        }
    }

    /** Drops all server-pushed skins (world disconnect). */
    public static synchronized void clearRemote() {
        for (ResourceLocation id : REMOTE_TEXTURES.values()) {
            Minecraft.getInstance().getTextureManager().release(id);
        }
        REMOTE_BYTES.clear();
        REMOTE_TEXTURES.clear();
        REQUEST_ATTEMPTED.clear();
    }

    /**
     * Resolves a skin name to a texture, or null to fall back to the default
     * soldier texture. Folder skins win over resource packs and server skins;
     * unknown names get one lazy local-folder attempt and one server request.
     */
    public static ResourceLocation resolve(String skinName) {
        if (skinName == null || skinName.isEmpty()) {
            return null;
        }
        if (FOLDER_SKINS.contains(skinName)) {
            return folderTextureId(skinName);
        }
        ResourceLocation packTexture = resourcePackTexture(skinName).orElse(null);
        if (packTexture != null) {
            return packTexture;
        }
        ResourceLocation remote = REMOTE_TEXTURES.get(skinName);
        if (remote != null) {
            return remote;
        }
        // The file may exist locally but postdate startup; try it once.
        ResourceLocation lazy = lazyLoad(skinName);
        if (lazy != null) {
            return lazy;
        }
        // Dedicated server: ask for the bytes once; the reply registers the texture.
        requestFromServer(skinName);
        return null;
    }

    /** One-time attempt to load a local folder skin that was not present at startup. */
    private static synchronized ResourceLocation lazyLoad(String name) {
        if (LAZY_ATTEMPTED.contains(name)) {
            return null;
        }
        LAZY_ATTEMPTED.add(name);
        Path file = SoldierSkinManager.getSkinFolder().resolve(name + ".png");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            if (!SoldierSkinManager.isSupportedSize(image.getWidth(), image.getHeight())) {
                StevesArmyMod.LOGGER.warn("[Skins] Skipping '{}': {}x{} is unsupported (need 64x64 or 64x32)",
                    name, image.getWidth(), image.getHeight());
                image.close();
                return null;
            }
            Minecraft.getInstance().getTextureManager()
                .register(folderTextureId(name), new DynamicTexture(image));
            FOLDER_SKINS.add(name);
            StevesArmyMod.LOGGER.info("[Skins] Lazy-loaded '{}'", name);
            return folderTextureId(name);
        } catch (Exception e) {
            StevesArmyMod.LOGGER.warn("[Skins] Failed to load '{}': {}", name, e.toString());
            return null;
        }
    }

    /** One-time request for a synced skin name the client cannot resolve itself. */
    private static void requestFromServer(String name) {
        if (REQUEST_ATTEMPTED.add(name)) {
            NetworkHandler.INSTANCE.sendToServer(new RequestSkinPacket(name));
        }
    }

    /** Texture id under which a local folder skin is (or would be) registered. */
    public static ResourceLocation folderTexture(String name) {
        return folderTextureId(name);
    }

    /** Texture id under which a server-pushed skin is registered, or null. */
    public static ResourceLocation remoteTexture(String name) {
        return REMOTE_TEXTURES.get(name);
    }

    /** Names of skins pushed by the server this session (sorted, case-insensitive). */
    public static synchronized List<String> getRemoteNames() {
        List<String> names = new ArrayList<>(REMOTE_BYTES.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static Optional<ResourceLocation> resourcePackTexture(String name) {
        synchronized (RESOURCE_PACK_CACHE) {
            return RESOURCE_PACK_CACHE.computeIfAbsent(name, n -> {
                ResourceLocation id = new ResourceLocation(StevesArmyMod.MODID,
                    "textures/entity/soldier/skins/" + n.toLowerCase(Locale.ROOT) + ".png");
                return Minecraft.getInstance().getResourceManager().getResource(id).isPresent()
                    ? Optional.of(id)
                    : Optional.<ResourceLocation>empty();
            });
        }
    }

    private static ResourceLocation folderTextureId(String name) {
        return new ResourceLocation(StevesArmyMod.MODID, "skins/" + name.toLowerCase(Locale.ROOT));
    }

    private static ResourceLocation remoteTextureId(String name) {
        return new ResourceLocation(StevesArmyMod.MODID, "skins_remote/" + name.toLowerCase(Locale.ROOT));
    }
}

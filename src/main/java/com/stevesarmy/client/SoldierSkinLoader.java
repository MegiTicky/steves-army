package com.stevesarmy.client;

import com.stevesarmy.StevesArmyMod;
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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Client-side loader for user-provided soldier skins. Each PNG in
 * {@code <game dir>/stevesarmy/skins} is uploaded as a DynamicTexture
 * registered at {@code steves_army:skins/<name>}. The listener also runs on
 * resource reload (F3+T), which rescan both the folder and the textures, so
 * new PNG files are picked up without a game restart.
 */
@Mod.EventBusSubscriber(modid = StevesArmyMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SoldierSkinLoader {

    /** Names of skins whose DynamicTexture is currently registered. */
    private static final Set<String> FOLDER_SKINS = new HashSet<>();
    /** Cached resource-pack texture lookups (empty Optional = not present). */
    private static final Map<String, Optional<ResourceLocation>> RESOURCE_PACK_CACHE = new HashMap<>();

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

    /** Rescans the skins folder and (re)registers one DynamicTexture per valid PNG. */
    public static synchronized void reload() {
        FOLDER_SKINS.clear();
        RESOURCE_PACK_CACHE.clear();
        SoldierSkinManager.reload();
        for (String name : SoldierSkinManager.getSkinNames()) {
            Path file = SoldierSkinManager.getSkinFolder().resolve(name + ".png");
            try (InputStream in = Files.newInputStream(file)) {
                NativeImage image = NativeImage.read(in);
                if (!isSupportedSize(image.getWidth(), image.getHeight())) {
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
        StevesArmyMod.LOGGER.info("[Skins] Registered {} folder skin texture(s)", FOLDER_SKINS.size());
    }

    /**
     * Resolves a skin name to a texture, or null to fall back to the default
     * soldier texture. Folder skins win; resource packs can provide skins at
     * {@code assets/steves_army/textures/entity/soldier/skins/<name>.png}.
     */
    public static ResourceLocation resolve(String skinName) {
        if (skinName == null || skinName.isEmpty()) {
            return null;
        }
        if (FOLDER_SKINS.contains(skinName)) {
            return folderTextureId(skinName);
        }
        return resourcePackTexture(skinName).orElse(null);
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

    private static boolean isSupportedSize(int width, int height) {
        return width == 64 && (height == 64 || height == 32);
    }
}

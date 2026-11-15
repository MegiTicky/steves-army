package com.stevesarmy.mixins;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.api.distmarker.Dist;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

public class OptionalMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    private boolean hasLegacyTaczSounds() {
        try {
            return MixinService.getService().getBytecodeProvider().getClassNode("com.tacz.guns.client.resource.GunDisplayInstance").methods.stream()
                .anyMatch(method -> method.name.equals("getSounds")
                    && method.desc.equals("(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;"));
        } catch (Throwable ignored) {
            return false;
        }
    }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() {
        if (FMLEnvironment.dist != Dist.CLIENT) return List.of();
        List<String> mixins = new ArrayList<>();
        if (FMLLoader.getLoadingModList().getModFileById("pointblank") != null) mixins.add("GunItemRendererMixin");
        if (FMLLoader.getLoadingModList().getModFileById("tacz") != null && hasLegacyTaczSounds()) mixins.add("GunDisplayInstanceMixin");
        return mixins;
    }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}

package com.stevesarmy.squad;

import net.minecraft.nbt.CompoundTag;

/**
 * Squad-wide resupply settings. Applied by Support soldiers to every friendly
 * soldier and the owning player: below a threshold the support throws a pouch,
 * topping the target back up to the configured "resupply to" amounts.
 */
public class ResupplyConfig {
    public static final ResupplyConfig DEFAULT = new ResupplyConfig(20, 1, 60, 2);

    private int ammoThreshold;
    private int healingThreshold;
    private int resupplyToAmmo;
    private int resupplyToHeals;

    public ResupplyConfig(int ammoThreshold, int healingThreshold, int resupplyToAmmo, int resupplyToHeals) {
        this.ammoThreshold = Math.max(0, ammoThreshold);
        this.healingThreshold = Math.max(0, healingThreshold);
        this.resupplyToAmmo = Math.max(0, resupplyToAmmo);
        this.resupplyToHeals = Math.max(0, resupplyToHeals);
    }

    public int ammoThreshold() { return ammoThreshold; }
    public int healingThreshold() { return healingThreshold; }
    public int resupplyToAmmo() { return resupplyToAmmo; }
    public int resupplyToHeals() { return resupplyToHeals; }

    public ResupplyConfig withAmmoThreshold(int value) {
        return new ResupplyConfig(value, healingThreshold, resupplyToAmmo, resupplyToHeals);
    }

    public ResupplyConfig withHealingThreshold(int value) {
        return new ResupplyConfig(ammoThreshold, value, resupplyToAmmo, resupplyToHeals);
    }

    public ResupplyConfig withResupplyToAmmo(int value) {
        return new ResupplyConfig(ammoThreshold, healingThreshold, value, resupplyToHeals);
    }

    public ResupplyConfig withResupplyToHeals(int value) {
        return new ResupplyConfig(ammoThreshold, healingThreshold, resupplyToAmmo, value);
    }

    public static ResupplyConfig fromNbt(CompoundTag tag) {
        return new ResupplyConfig(
            tag.getInt("AmmoThreshold"),
            tag.getInt("HealingThreshold"),
            tag.getInt("ResupplyToAmmo"),
            tag.getInt("ResupplyToHeals"));
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("AmmoThreshold", ammoThreshold);
        tag.putInt("HealingThreshold", healingThreshold);
        tag.putInt("ResupplyToAmmo", resupplyToAmmo);
        tag.putInt("ResupplyToHeals", resupplyToHeals);
        return tag;
    }
}

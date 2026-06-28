package com.stevesarmy.registry;

import com.stevesarmy.StevesArmyMod;
import com.stevesarmy.inventory.SoldierInventory;
import com.stevesarmy.inventory.SoldierInventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENUS = 
        DeferredRegister.create(ForgeRegistries.MENU_TYPES, StevesArmyMod.MODID);

    public static final RegistryObject<MenuType<SoldierInventoryMenu>> SOLDIER_INVENTORY = 
        MENUS.register("soldier_inventory", 
            () -> IForgeMenuType.create((windowId, inv, data) -> {
                int soldierId = data.readInt();
                int size = data.readInt();
                SoldierInventory soldierInv = new SoldierInventory();
                for (int i = 0; i < size; i++) {
                    if (data.readBoolean()) {
                        soldierInv.setItem(i, data.readItem());
                    }
                }
                return new SoldierInventoryMenu(windowId, inv, soldierInv, soldierId);
            }));

    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}
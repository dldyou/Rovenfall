package org.dldyou.rovenfall.administration;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.dldyou.rovenfall.Rovenfall;

/** Dedicated menu identities that open the administrator console without a vanilla chest screen. */
public final class RovenfallAdministrationMenus {
    private static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, Rovenfall.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<ChestMenu>> HOME =
            register("administration_home", PlayerMenuNetwork.MenuKind.ADMIN_HOME);
    public static final DeferredHolder<MenuType<?>, MenuType<ChestMenu>> ECONOMY =
            register("administration_economy", PlayerMenuNetwork.MenuKind.ADMIN_ECONOMY);
    public static final DeferredHolder<MenuType<?>, MenuType<ChestMenu>> WORLD =
            register("administration_world", PlayerMenuNetwork.MenuKind.ADMIN_WORLD);
    public static final DeferredHolder<MenuType<?>, MenuType<ChestMenu>> RPG_BOSS =
            register("administration_rpg_boss", PlayerMenuNetwork.MenuKind.ADMIN_RPG_BOSS);
    public static final DeferredHolder<MenuType<?>, MenuType<ChestMenu>> OPERATIONS =
            register("administration_operations", PlayerMenuNetwork.MenuKind.ADMIN_OPERATIONS);

    private RovenfallAdministrationMenus() {
    }

    public static void register(IEventBus modBus) {
        MENU_TYPES.register(modBus);
    }

    private static DeferredHolder<MenuType<?>, MenuType<ChestMenu>> register(
            String name, PlayerMenuNetwork.MenuKind kind) {
        return MENU_TYPES.register(name, () -> new MenuType<>(
                (containerId, inventory) -> new ChestMenu(
                        menuType(kind), containerId, inventory, new SimpleContainer(54), 6),
                FeatureFlags.DEFAULT_FLAGS));
    }

    static MenuType<ChestMenu> menuType(PlayerMenuNetwork.MenuKind kind) {
        return switch (kind) {
            case ADMIN_HOME -> HOME.get();
            case ADMIN_ECONOMY -> ECONOMY.get();
            case ADMIN_WORLD -> WORLD.get();
            case ADMIN_RPG_BOSS -> RPG_BOSS.get();
            case ADMIN_OPERATIONS -> OPERATIONS.get();
            default -> throw new IllegalArgumentException("Not an administration menu: " + kind);
        };
    }
}

package org.dldyou.rovenfall.administration;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.rpg.RpgDefinitionSnapshot;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.rpg.RpgPlayerState;

/** Native, read-only player navigation backed entirely by current server state. */
public final class PlayerDashboardMenu extends ChestMenu {
    static final int MENU_SIZE = 27;
    private static final int BACK_SLOT = 18;
    private static final int REFRESH_SLOT = 26;

    enum Page {
        HOME,
        ECONOMY,
        CLAIMS,
        RPG
    }

    enum Action {
        NONE,
        OPEN_ECONOMY,
        OPEN_SHOPS,
        OPEN_CLAIMS,
        OPEN_RPG,
        BACK,
        REFRESH,
        UNAVAILABLE
    }

    private final ServerPlayer viewer;
    private final UUID viewerId;
    private final SimpleContainer dashboard;
    private Page page;
    private long lastHandledGameTime = Long.MIN_VALUE;

    private PlayerDashboardMenu(
            int containerId,
            Inventory inventory,
            ServerPlayer viewer,
            SimpleContainer dashboard,
            Page initialPage) {
        super(MenuType.GENERIC_9x3, containerId, inventory, dashboard, 3);
        this.viewer = viewer;
        this.viewerId = viewer.getUUID();
        this.dashboard = dashboard;
        this.page = Objects.requireNonNull(initialPage);
        render();
    }

    public static void open(ServerPlayer player) {
        open(player, Page.HOME);
    }

    static void open(ServerPlayer player, Page initialPage) {
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) -> new PlayerDashboardMenu(
                        containerId, inventory, (ServerPlayer) viewer,
                        new SimpleContainer(MENU_SIZE), initialPage),
                Component.translatable("gui.rovenfall.player.title")));
    }

    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !viewerId.equals(serverPlayer.getUUID())
                || slotIndex < 0
                || slotIndex >= MENU_SIZE) {
            return;
        }
        Action action = actionAt(page, slotIndex);
        long gameTime = viewer.level().getGameTime();
        if (action == Action.NONE || !canHandleClick(lastHandledGameTime, gameTime)) {
            return;
        }
        lastHandledGameTime = gameTime;
        switch (action) {
            case OPEN_ECONOMY -> page = Page.ECONOMY;
            case OPEN_SHOPS -> {
                PlayerShopMenu.open(viewer);
                return;
            }
            case OPEN_CLAIMS -> page = Page.CLAIMS;
            case OPEN_RPG -> page = Page.RPG;
            case BACK -> page = Page.HOME;
            case UNAVAILABLE -> {
                viewer.sendOverlayMessage(Component.translatable("gui.rovenfall.player.unavailable"));
                return;
            }
            case REFRESH, NONE -> {
            }
        }
        render();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive() && viewerId.equals(player.getUUID());
    }

    static Action actionAt(Page page, int slot) {
        if (slot == REFRESH_SLOT) {
            return Action.REFRESH;
        }
        if (page != Page.HOME && slot == BACK_SLOT) {
            return Action.BACK;
        }
        return switch (page) {
            case HOME -> switch (slot) {
                case 10 -> Action.OPEN_ECONOMY;
                case 13 -> Action.OPEN_CLAIMS;
                case 16 -> Action.OPEN_RPG;
                default -> Action.NONE;
            };
            case ECONOMY -> slot == 15 ? Action.OPEN_SHOPS : Action.NONE;
            case CLAIMS, RPG -> slot == 24 ? Action.UNAVAILABLE : Action.NONE;
        };
    }

    static boolean canHandleClick(long lastHandledGameTime, long gameTime) {
        return lastHandledGameTime != gameTime;
    }

    static DashboardSnapshot snapshot(
            PlatformSavedData platform,
            RpgPlayerState rpg,
            UUID playerId,
            ClaimKey currentClaimKey) {
        Claim currentClaim = platform.claim(currentClaimKey).orElse(null);
        List<Optional<Identifier>> activeSkills = java.util.stream.IntStream
                .range(0, RpgPlayerState.MAX_ACTIVE_SKILL_SLOTS)
                .mapToObj(slot -> Optional.ofNullable(rpg.activeSkillSlots().get(slot)))
                .toList();
        int learnedSkills = rpg.careers().values().stream()
                .mapToInt(progress -> progress.learnedSkills().size())
                .sum();
        return new DashboardSnapshot(
                platform.economyBalance(playerId).orElse(0L),
                platform.economyBalance(playerId).isPresent(),
                platform.claimCount(playerId),
                currentClaimKey,
                platform.isProtectedRegion(currentClaimKey),
                Optional.ofNullable(currentClaim).map(Claim::ownerId),
                Optional.ofNullable(currentClaim).map(claim -> claim.roleOf(playerId)),
                Optional.ofNullable(currentClaim).map(Claim::settings),
                rpg.activeCareer(),
                rpg.activityXp().size(),
                rpg.careers().size(),
                learnedSkills,
                activeSkills);
    }

    private DashboardSnapshot snapshot() {
        var server = viewer.level().getServer();
        UUID playerId = viewer.getUUID();
        return snapshot(
                PlatformSavedData.get(server),
                RpgPlayerSavedData.get(server).state(playerId),
                playerId,
                ClaimKey.at(viewer.level().dimension(), viewer.blockPosition()));
    }

    private void render() {
        dashboard.clearContent();
        DashboardSnapshot snapshot = snapshot();
        RpgDefinitionSnapshot definitions = RpgDefinitionReloadListener.snapshot(viewer.level().getServer());
        switch (page) {
            case HOME -> renderHome(snapshot, definitions);
            case ECONOMY -> renderEconomy(snapshot);
            case CLAIMS -> renderClaims(snapshot);
            case RPG -> renderRpg(snapshot, definitions);
        }
        dashboard.setItem(REFRESH_SLOT, icon(
                Items.CLOCK,
                Component.translatable("gui.rovenfall.player.refresh"),
                Component.translatable("gui.rovenfall.player.click")));
        broadcastChanges();
    }

    private void renderHome(DashboardSnapshot snapshot, RpgDefinitionSnapshot definitions) {
        dashboard.setItem(4, icon(
                Items.COMPASS,
                Component.translatable("gui.rovenfall.player.home"),
                Component.translatable("gui.rovenfall.player.read_only")));
        dashboard.setItem(10, icon(
                Items.EMERALD,
                Component.translatable("gui.rovenfall.player.economy"),
                Component.translatable("gui.rovenfall.player.balance", snapshot.balance()),
                Component.translatable("gui.rovenfall.player.click")));
        dashboard.setItem(13, icon(
                Items.GRASS_BLOCK,
                Component.translatable("gui.rovenfall.player.claims"),
                Component.translatable("gui.rovenfall.player.owned_claims", snapshot.ownedClaims()),
                claimStatus(snapshot),
                Component.translatable("gui.rovenfall.player.click")));
        dashboard.setItem(16, icon(
                Items.EXPERIENCE_BOTTLE,
                Component.translatable("gui.rovenfall.player.rpg"),
                Component.translatable(
                        "gui.rovenfall.player.active_career",
                        snapshot.activeCareer().map(id -> careerName(definitions, id))
                                .orElseGet(() -> Component.translatable("gui.rovenfall.player.none"))),
                Component.translatable(
                        "gui.rovenfall.player.bound_skills",
                        snapshot.activeSkills().stream().filter(Optional::isPresent).count(),
                        RpgPlayerState.MAX_ACTIVE_SKILL_SLOTS),
                Component.translatable("gui.rovenfall.player.click")));
    }

    private void renderEconomy(DashboardSnapshot snapshot) {
        addBackButton();
        dashboard.setItem(4, icon(
                Items.EMERALD,
                Component.translatable("gui.rovenfall.player.economy"),
                Component.translatable("gui.rovenfall.player.read_only")));
        dashboard.setItem(12, icon(
                snapshot.hasEconomyAccount() ? Items.GOLD_INGOT : Items.IRON_INGOT,
                Component.translatable("gui.rovenfall.player.balance_title"),
                Component.translatable("gui.rovenfall.player.balance", snapshot.balance()),
                Component.translatable(snapshot.hasEconomyAccount()
                        ? "gui.rovenfall.player.account.ready"
                        : "gui.rovenfall.player.account.missing")));
        dashboard.setItem(15, icon(
                Items.CHEST,
                Component.translatable("gui.rovenfall.player.shop"),
                Component.translatable("gui.rovenfall.player.click")));
    }

    private void renderClaims(DashboardSnapshot snapshot) {
        addBackButton();
        dashboard.setItem(4, icon(
                Items.GRASS_BLOCK,
                Component.translatable("gui.rovenfall.player.claims"),
                Component.translatable("gui.rovenfall.player.owned_claims", snapshot.ownedClaims())));
        dashboard.setItem(10, icon(
                Items.MAP,
                Component.translatable("gui.rovenfall.player.current_chunk"),
                Component.translatable(
                        "gui.rovenfall.player.claim_location",
                        snapshot.currentClaimKey().dimension().identifier().toString(),
                        snapshot.currentClaimKey().chunkX(),
                        snapshot.currentClaimKey().chunkZ()),
                claimStatus(snapshot)));
        snapshot.claimRole().ifPresent(role -> dashboard.setItem(13, icon(
                Items.PLAYER_HEAD,
                Component.translatable("gui.rovenfall.player.claim_role"),
                Component.translatable("gui.rovenfall.player.role", Component.translatable(role.translationKey())))));
        snapshot.claimSettings().ifPresent(settings -> dashboard.setItem(16, icon(
                Items.OAK_DOOR,
                Component.translatable("gui.rovenfall.player.claim_settings"),
                Component.translatable(
                        "gui.rovenfall.player.entry_restricted",
                        enabled(settings.entryRestricted())),
                Component.translatable(
                        "gui.rovenfall.player.public_interactions",
                        enabled(settings.publicInteractions())))));
        dashboard.setItem(24, icon(
                Items.BARRIER,
                Component.translatable("gui.rovenfall.player.claim_actions"),
                Component.translatable("gui.rovenfall.player.planned", "#79")));
    }

    private void renderRpg(DashboardSnapshot snapshot, RpgDefinitionSnapshot definitions) {
        addBackButton();
        dashboard.setItem(4, icon(
                Items.EXPERIENCE_BOTTLE,
                Component.translatable("gui.rovenfall.player.rpg"),
                Component.translatable("gui.rovenfall.player.read_only")));
        dashboard.setItem(10, icon(
                Items.IRON_SWORD,
                Component.translatable("gui.rovenfall.player.career"),
                Component.translatable(
                        "gui.rovenfall.player.active_career",
                        snapshot.activeCareer().map(id -> careerName(definitions, id))
                                .orElseGet(() -> Component.translatable("gui.rovenfall.player.none"))),
                Component.translatable("gui.rovenfall.player.learned_careers", snapshot.learnedCareers())));
        dashboard.setItem(12, icon(
                Items.EXPERIENCE_BOTTLE,
                Component.translatable("gui.rovenfall.player.activities"),
                Component.translatable("gui.rovenfall.player.activity_tracks", snapshot.activityTracks())));
        dashboard.setItem(14, icon(
                Items.BOOK,
                Component.translatable("gui.rovenfall.player.skills"),
                Component.translatable("gui.rovenfall.player.learned_skills", snapshot.learnedSkills())));
        for (int slot = 0; slot < snapshot.activeSkills().size(); slot++) {
            Optional<Identifier> skill = snapshot.activeSkills().get(slot);
            dashboard.setItem(19 + slot, icon(
                    skill.isPresent() ? Items.ENCHANTED_BOOK : Items.PAPER,
                    Component.translatable("gui.rovenfall.player.active_slot", slot + 1),
                    skill.map(id -> skillName(definitions, id))
                            .orElseGet(() -> Component.translatable("gui.rovenfall.player.empty"))));
        }
        dashboard.setItem(24, icon(
                Items.BARRIER,
                Component.translatable("gui.rovenfall.player.rpg_actions"),
                Component.translatable("gui.rovenfall.player.planned", "#80")));
    }

    private void addBackButton() {
        dashboard.setItem(BACK_SLOT, icon(
                Items.ARROW,
                Component.translatable("gui.rovenfall.player.back"),
                Component.translatable("gui.rovenfall.player.click")));
    }

    private static Component claimStatus(DashboardSnapshot snapshot) {
        if (snapshot.protectedRegion()) {
            return Component.translatable("gui.rovenfall.player.claim.protected");
        }
        if (snapshot.claimOwner().isEmpty()) {
            return Component.translatable("gui.rovenfall.player.claim.unclaimed");
        }
        return snapshot.claimRole().filter(role -> role == ClaimRole.OWNER).isPresent()
                ? Component.translatable("gui.rovenfall.player.claim.owned")
                : Component.translatable("gui.rovenfall.player.claim.other");
    }

    private static Component enabled(boolean enabled) {
        return Component.translatable(enabled
                ? "gui.rovenfall.player.enabled"
                : "gui.rovenfall.player.disabled");
    }

    private static Component careerName(RpgDefinitionSnapshot definitions, Identifier id) {
        return definitions.career(id)
                .<Component>map(definition -> Component.translatable(definition.translationKey()))
                .orElseGet(() -> Component.literal(id.toString()));
    }

    private static Component skillName(RpgDefinitionSnapshot definitions, Identifier id) {
        return definitions.skill(id)
                .<Component>map(definition -> Component.translatable(definition.translationKey()))
                .orElseGet(() -> Component.literal(id.toString()));
    }

    static ItemStack icon(Item item, Component name, Component... lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name);
        stack.set(DataComponents.LORE, new ItemLore(List.of(lore).stream()
                .map(line -> line.copy().withStyle(ChatFormatting.GRAY))
                .map(Component.class::cast)
                .toList()));
        return stack;
    }

    record DashboardSnapshot(
            long balance,
            boolean hasEconomyAccount,
            int ownedClaims,
            ClaimKey currentClaimKey,
            boolean protectedRegion,
            Optional<UUID> claimOwner,
            Optional<ClaimRole> claimRole,
            Optional<org.dldyou.rovenfall.claims.ClaimSettings> claimSettings,
            Optional<Identifier> activeCareer,
            int activityTracks,
            int learnedCareers,
            int learnedSkills,
            List<Optional<Identifier>> activeSkills) {
        DashboardSnapshot {
            claimOwner = claimOwner == null ? Optional.empty() : claimOwner;
            claimRole = claimRole == null ? Optional.empty() : claimRole;
            claimSettings = claimSettings == null ? Optional.empty() : claimSettings;
            activeCareer = activeCareer == null ? Optional.empty() : activeCareer;
            activeSkills = List.copyOf(activeSkills);
        }
    }
}

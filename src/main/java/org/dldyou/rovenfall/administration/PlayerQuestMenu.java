package org.dldyou.rovenfall.administration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
import org.dldyou.rovenfall.quest.ContractJourneyView;
import org.dldyou.rovenfall.quest.QuestDefinition;
import org.dldyou.rovenfall.quest.QuestDefinitionReloadListener;
import org.dldyou.rovenfall.quest.QuestJourneyView;
import org.dldyou.rovenfall.quest.QuestPlayerSavedData;
import org.dldyou.rovenfall.quest.QuestPlayerState;
import org.dldyou.rovenfall.quest.RepeatableContractService;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.rpg.RpgDefinitionSnapshot;

/** Read-only, server-owned quest board and next-step guide. */
public final class PlayerQuestMenu extends ChestMenu {
    static final int MENU_SIZE = 54;
    static final int PAGE_SIZE = QuestJourneyView.MAX_PAGE_SIZE;
    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int BACK_SLOT = 45;
    private static final int CONTRACTS_SLOT = 46;
    private static final int[] CONTRACT_SLOTS = {20, 22, 24};
    private static final int PREVIOUS_SLOT = 48;
    private static final int GUIDE_SLOT = 49;
    private static final int NEXT_SLOT = 50;
    private static final int REFRESH_SLOT = 53;

    enum Page {
        LIST,
        DETAIL,
        CONTRACTS
    }

    enum Action {
        NONE,
        SELECT,
        BACK,
        CONTRACTS,
        PREVIOUS,
        GUIDE,
        NEXT,
        REFRESH
    }

    private final ServerPlayer viewer;
    private final UUID viewerId;
    private final SimpleContainer content;
    private Page page = Page.LIST;
    private int listPage;
    private int detailPage;
    private QuestJourneyView.QuestRow selected;
    private List<QuestJourneyView.QuestRow> displayedRows = List.of();
    private QuestJourneyView renderedView;
    private ContractJourneyView renderedContracts;
    private long renderedRevision;
    private QuestPlayerState renderedState = QuestPlayerState.EMPTY;
    private boolean renderedWritable;
    private long lastHandledGameTime = Long.MIN_VALUE;

    private PlayerQuestMenu(
            int containerId,
            Inventory inventory,
            ServerPlayer viewer,
            SimpleContainer content) {
        super(MenuType.GENERIC_9x6, containerId, inventory, content, 6);
        this.viewer = viewer;
        this.viewerId = viewer.getUUID();
        this.content = content;
        render();
        PlayerMenuNetwork.seedMenuSession(this, UUID.randomUUID());
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) -> new PlayerQuestMenu(
                        containerId, inventory, (ServerPlayer) viewer, new SimpleContainer(MENU_SIZE)),
                Component.translatable("gui.rovenfall.quest.title")))
                .ifPresent(ignored -> PlayerMenuNetwork.sendMenuIdentity(player));
    }

    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !viewerId.equals(serverPlayer.getUUID())
                || slotIndex < 0
                || slotIndex >= MENU_SIZE
                || !PlayerMenuNetwork.isPrimaryAction(buttonNum, input)) {
            return;
        }
        Action action = actionAt(page, slotIndex);
        long gameTime = viewer.level().getGameTime();
        if (action == Action.NONE
                || !PlayerDashboardMenu.canHandleClick(lastHandledGameTime, gameTime)) {
            return;
        }
        lastHandledGameTime = gameTime;
        if (action == Action.REFRESH) {
            if (page != Page.CONTRACTS) {
                resetToList();
            }
            render();
            return;
        }
        if (action == Action.BACK) {
            back();
            return;
        }
        if (!sessionCurrent()) {
            stale();
            return;
        }
        switch (action) {
            case SELECT -> select(slotIndex);
            case CONTRACTS -> toggleContracts();
            case PREVIOUS -> previous();
            case GUIDE -> openNextStep();
            case NEXT -> next();
            case NONE, BACK, REFRESH -> {
            }
        }
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
        if (slot == BACK_SLOT) {
            return Action.BACK;
        }
        if (slot == CONTRACTS_SLOT && page != Page.DETAIL) {
            return Action.CONTRACTS;
        }
        if (slot == REFRESH_SLOT) {
            return Action.REFRESH;
        }
        if (page == Page.CONTRACTS) {
            return Action.NONE;
        }
        if (slot == PREVIOUS_SLOT) {
            return Action.PREVIOUS;
        }
        if (slot == GUIDE_SLOT) {
            return Action.GUIDE;
        }
        if (slot == NEXT_SLOT) {
            return Action.NEXT;
        }
        return page == Page.LIST && contentOffset(slot) >= 0 ? Action.SELECT : Action.NONE;
    }

    static boolean isCurrent(
            long expectedRevision,
            QuestPlayerState expectedState,
            boolean expectedWritable,
            long currentRevision,
            QuestPlayerState currentState,
            boolean currentWritable) {
        return expectedRevision == currentRevision
                && expectedWritable == currentWritable
                && Objects.equals(expectedState, currentState);
    }

    static int boundedPage(int page, int entries) {
        int last = entries == 0 ? 0 : (entries - 1) / PAGE_SIZE;
        return Math.clamp(page, 0, last);
    }

    static boolean shouldEnsureAssignments(Page page) {
        return page == Page.CONTRACTS;
    }

    static String statusKey(QuestJourneyView.Status status) {
        return "gui.rovenfall.quest.status." + switch (status) {
            case PREREQUISITE_LOCKED -> "locked";
            case PENDING -> "reward_pending";
            default -> status.name().toLowerCase(Locale.ROOT);
        };
    }

    static Component objectiveLine(
            QuestJourneyView.ObjectiveRow objective,
            RpgDefinitionSnapshot rpgDefinitions) {
        return switch (objective.kind()) {
            case ACTIVITY -> Component.translatable(
                    "gui.rovenfall.quest.objective.activity",
                    activityName(rpgDefinitions, objective.target()),
                    objective.progress(),
                    objective.requiredCount());
            case SHOP_TRADE -> Component.translatable(
                    "gui.rovenfall.quest.objective.shop_trade",
                    objective.progress(), objective.requiredCount());
            case CLAIM_PURCHASE -> Component.translatable(
                    "gui.rovenfall.quest.objective.claim_purchase",
                    objective.progress(), objective.requiredCount());
            case BOSS_DEFEAT -> Component.translatable(
                    "gui.rovenfall.quest.objective.boss_defeat",
                    objective.progress(), objective.requiredCount());
        };
    }

    static Component nextStepLine(
            QuestJourneyView.NextStep step,
            RpgDefinitionSnapshot rpgDefinitions) {
        return switch (step.kind()) {
            case ACTIVITY -> Component.translatable(
                    "gui.rovenfall.quest.objective.activity",
                    activityName(rpgDefinitions, step.target()),
                    step.progress(), step.requiredCount());
            case SHOP_TRADE -> Component.translatable(
                    "gui.rovenfall.quest.objective.shop_trade", step.progress(), step.requiredCount());
            case CLAIM_PURCHASE -> Component.translatable(
                    "gui.rovenfall.quest.objective.claim_purchase", step.progress(), step.requiredCount());
            case BOSS_DEFEAT -> Component.translatable(
                    "gui.rovenfall.quest.objective.boss_defeat", step.progress(), step.requiredCount());
        };
    }

    private void select(int slot) {
        int offset = contentOffset(slot);
        if (offset < 0 || offset >= displayedRows.size()) {
            render();
            return;
        }
        selected = displayedRows.get(offset);
        page = Page.DETAIL;
        detailPage = 0;
        render();
    }

    private void previous() {
        if (page == Page.LIST) {
            listPage = Math.max(0, listPage - 1);
        } else {
            detailPage = Math.max(0, detailPage - 1);
        }
        render();
    }

    private void next() {
        if (page == Page.LIST) {
            int last = Math.max(0, renderedView.totalPages() - 1);
            if (listPage < last) {
                listPage++;
            }
        } else {
            int entries = selected == null ? 0 : selected.objectives().size();
            int last = entries == 0 ? 0 : (entries - 1) / PAGE_SIZE;
            if (detailPage < last) {
                detailPage++;
            }
        }
        render();
    }

    private void toggleContracts() {
        if (page == Page.CONTRACTS) {
            resetToList();
        } else {
            page = Page.CONTRACTS;
            selected = null;
            detailPage = 0;
        }
        render();
    }

    private void openNextStep() {
        if (!renderedWritable) {
            viewer.sendOverlayMessage(Component.translatable("gui.rovenfall.quest.read_only"));
            render();
            return;
        }
        QuestJourneyView.NextStep step = renderedView.nextStep().orElse(null);
        if (step == null) {
            viewer.sendOverlayMessage(Component.translatable("gui.rovenfall.quest.next_step.none"));
            return;
        }
        switch (step.kind()) {
            case ACTIVITY, BOSS_DEFEAT -> PlayerRpgMenu.open(viewer);
            case SHOP_TRADE -> PlayerShopMenu.open(viewer);
            case CLAIM_PURCHASE -> PlayerClaimMenu.open(viewer);
        }
    }

    private void back() {
        if (page == Page.DETAIL || page == Page.CONTRACTS) {
            page = Page.LIST;
            selected = null;
            detailPage = 0;
            render();
            return;
        }
        PlayerDashboardMenu.open(viewer);
    }

    private void stale() {
        viewer.sendOverlayMessage(Component.translatable("gui.rovenfall.quest.stale"));
        resetToList();
        render();
    }

    private void resetToList() {
        page = Page.LIST;
        selected = null;
        detailPage = 0;
    }

    private boolean sessionCurrent() {
        var server = viewer.level().getServer();
        QuestDefinitionReloadListener.VersionedSnapshot definitions =
                QuestDefinitionReloadListener.versioned(server);
        QuestPlayerSavedData saved = QuestPlayerSavedData.get(server);
        return isCurrent(
                renderedRevision, renderedState, renderedWritable,
                definitions.revision(), saved.state(viewerId), saved.isWritable());
    }

    private void render() {
        var server = viewer.level().getServer();
        QuestDefinitionReloadListener.VersionedSnapshot versioned =
                QuestDefinitionReloadListener.versioned(server);
        QuestPlayerSavedData saved = QuestPlayerSavedData.get(server);
        long now = System.currentTimeMillis();
        RepeatableContractService.AssignmentResult assignment = null;
        if (shouldEnsureAssignments(page)) {
            assignment = RepeatableContractService.ensureAssignments(
                    saved, versioned.snapshot(), viewerId, now);
        }
        QuestPlayerState state = saved.state(viewerId);
        boolean writable = saved.isWritable();
        boolean contractsWritable = writable && (assignment == null
                || assignment.status() == RepeatableContractService.AssignmentStatus.SUCCESS
                || assignment.status() == RepeatableContractService.AssignmentStatus.UNCHANGED);
        if (page == Page.DETAIL && selected != null && renderedView != null
                && !isCurrent(renderedRevision, renderedState, renderedWritable,
                        versioned.revision(), state, writable)) {
            resetToList();
        }

        renderedView = QuestJourneyView.create(
                versioned.snapshot(), state, versioned.revision(), writable, listPage, PAGE_SIZE);
        renderedContracts = ContractJourneyView.create(
                versioned.snapshot(), state, versioned.revision(), contractsWritable, now);
        renderedRevision = versioned.revision();
        renderedState = state;
        renderedWritable = writable;
        listPage = renderedView.page();

        content.clearContent();
        switch (page) {
            case LIST -> renderList();
            case DETAIL -> renderDetail();
            case CONTRACTS -> renderContracts();
        }
        if (page != Page.DETAIL) {
            addContractsToggle();
        }
        content.setItem(REFRESH_SLOT, icon(
                Items.CLOCK,
                "gui.rovenfall.player.refresh",
                Component.translatable("gui.rovenfall.player.click")));
        broadcastChanges();
    }

    private void renderList() {
        List<Component> header = new ArrayList<>();
        header.add(Component.translatable("gui.rovenfall.quest.summary"));
        header.add(Component.translatable("gui.rovenfall.quest.count", renderedView.totalEntries()));
        header.add(pageLine(renderedView.page(), renderedView.totalPages(), renderedView.totalEntries()));
        if (!renderedWritable) {
            header.add(Component.translatable("gui.rovenfall.quest.read_only"));
        }
        content.setItem(4, PlayerDashboardMenu.icon(
                Items.WRITABLE_BOOK,
                Component.translatable("gui.rovenfall.quest.title"),
                header.toArray(Component[]::new)));

        displayedRows = renderedView.entries();
        for (int index = 0; index < displayedRows.size(); index++) {
            content.setItem(CONTENT_SLOTS[index], questIcon(displayedRows.get(index), true));
        }
        if (displayedRows.isEmpty()) {
            content.setItem(22, icon(
                    Items.PAPER,
                    "gui.rovenfall.quest.empty",
                    Component.translatable("gui.rovenfall.quest.next_step.none")));
        }
        addNavigation(renderedView.page(), renderedView.totalEntries());
    }

    private void renderDetail() {
        displayedRows = List.of();
        if (selected == null) {
            resetToList();
            renderList();
            return;
        }
        content.setItem(4, questIcon(selected, false));
        List<QuestJourneyView.ObjectiveRow> objectives = selected.objectives();
        detailPage = boundedPage(detailPage, objectives.size());
        int from = Math.min(objectives.size(), detailPage * PAGE_SIZE);
        int to = Math.min(objectives.size(), from + PAGE_SIZE);
        RpgDefinitionSnapshot rpgDefinitions = RpgDefinitionReloadListener.snapshot(viewer.level().getServer());
        for (int index = from; index < to; index++) {
            content.setItem(CONTENT_SLOTS[index - from], objectiveIcon(objectives.get(index), rpgDefinitions));
        }
        addNavigation(detailPage, objectives.size());
    }

    private void renderContracts() {
        displayedRows = List.of();
        List<Component> header = new ArrayList<>();
        header.add(Component.translatable("gui.rovenfall.quest.contracts.summary"));
        header.add(Component.translatable(
                "gui.rovenfall.quest.contracts.count", renderedContracts.entries().size()));
        if (!renderedContracts.writable()) {
            header.add(Component.translatable("gui.rovenfall.quest.contract.read_only"));
        }
        content.setItem(4, PlayerDashboardMenu.icon(
                Items.FILLED_MAP,
                Component.translatable("gui.rovenfall.quest.contracts"),
                header.toArray(Component[]::new)));
        for (int index = 0; index < renderedContracts.entries().size(); index++) {
            content.setItem(CONTRACT_SLOTS[index], contractIcon(renderedContracts.entries().get(index)));
        }
        if (renderedContracts.entries().isEmpty()) {
            content.setItem(22, icon(
                    Items.PAPER,
                    "gui.rovenfall.quest.contracts.empty",
                    Component.translatable("gui.rovenfall.quest.contracts.refresh_hint")));
        }
        addBack();
    }

    private ItemStack contractIcon(ContractJourneyView.ContractRow row) {
        List<Component> lore = new ArrayList<>();
        row.descriptionTranslationKey().ifPresent(key -> lore.add(Component.translatable(key)));
        lore.add(Component.translatable(cadenceKey(row.key().window().cadence())));
        lore.add(Component.translatable(
                "gui.rovenfall.quest.status", Component.translatable(statusKey(row.status()))));
        RpgDefinitionSnapshot rpgDefinitions = RpgDefinitionReloadListener.snapshot(viewer.level().getServer());
        row.objective().ifPresent(objective -> lore.add(objectiveLine(objective, rpgDefinitions)));
        addRewardLines(lore, row.status(), row.rewardPreview(), rpgDefinitions);
        lore.add(Component.translatable(refreshKey(row.key().window().cadence())));
        lore.add(Component.translatable(
                "gui.rovenfall.quest.contract.technical",
                row.key().templateId().toString(), row.key().window().windowStartEpochDay()));
        return PlayerDashboardMenu.icon(
                statusItem(row.status()),
                row.translationKey().<Component>map(Component::translatable)
                        .orElseGet(() -> Component.translatable("gui.rovenfall.quest.unavailable_content")),
                lore.toArray(Component[]::new));
    }

    private void addContractsToggle() {
        boolean contracts = page == Page.CONTRACTS;
        content.setItem(CONTRACTS_SLOT, PlayerDashboardMenu.icon(
                contracts ? Items.WRITABLE_BOOK : Items.FILLED_MAP,
                Component.translatable(contracts
                        ? "gui.rovenfall.quest.story"
                        : "gui.rovenfall.quest.contracts"),
                Component.translatable(contracts
                        ? "gui.rovenfall.quest.story.hint"
                        : "gui.rovenfall.quest.contracts.hint"),
                Component.translatable("gui.rovenfall.player.click")));
    }

    private static String cadenceKey(QuestDefinition.Cadence cadence) {
        return cadence == QuestDefinition.Cadence.DAILY
                ? "gui.rovenfall.quest.contract.daily"
                : "gui.rovenfall.quest.contract.weekly";
    }

    private static String refreshKey(QuestDefinition.Cadence cadence) {
        return cadence == QuestDefinition.Cadence.DAILY
                ? "gui.rovenfall.quest.contract.refresh.daily"
                : "gui.rovenfall.quest.contract.refresh.weekly";
    }

    private ItemStack questIcon(QuestJourneyView.QuestRow row, boolean clickable) {
        List<Component> lore = new ArrayList<>();
        row.descriptionTranslationKey().ifPresent(key -> lore.add(Component.translatable(key)));
        lore.add(Component.translatable(
                "gui.rovenfall.quest.status", Component.translatable(statusKey(row.status()))));
        if (!row.objectives().isEmpty()) {
            long completed = row.objectives().stream().filter(QuestJourneyView.ObjectiveRow::complete).count();
            if (!clickable) {
                lore.add(Component.translatable("gui.rovenfall.quest.objectives"));
            }
            lore.add(Component.translatable(
                    "gui.rovenfall.quest.progress", completed, row.objectives().size()));
        }
        RpgDefinitionSnapshot rpgDefinitions = RpgDefinitionReloadListener.snapshot(viewer.level().getServer());
        row.objectives().stream().filter(objective -> !objective.complete()).findFirst()
                .ifPresent(objective -> lore.add(objectiveLine(objective, rpgDefinitions)));
        row.missingPrerequisites().stream().limit(8).forEach(prerequisite -> lore.add(Component.translatable(
                "gui.rovenfall.quest.prerequisite",
                prerequisite.translationKey().<Component>map(Component::translatable)
                        .orElseGet(() -> Component.translatable("gui.rovenfall.quest.unavailable_content")))));
        addRewardLines(lore, row.status(), row.rewardPreview(), rpgDefinitions);
        if (clickable) {
            lore.add(Component.translatable("gui.rovenfall.player.click"));
        }
        lore.add(Component.translatable("gui.rovenfall.quest.technical.quest_id", row.id().toString()));
        return PlayerDashboardMenu.icon(
                statusItem(row.status()),
                row.translationKey().<Component>map(Component::translatable)
                        .orElseGet(() -> Component.translatable("gui.rovenfall.quest.unavailable_content")),
                lore.toArray(Component[]::new));
    }

    private static ItemStack objectiveIcon(
            QuestJourneyView.ObjectiveRow objective,
            RpgDefinitionSnapshot rpgDefinitions) {
        return PlayerDashboardMenu.icon(
                objective.complete() ? Items.EMERALD : Items.COMPASS,
                objectiveLine(objective, rpgDefinitions),
                Component.translatable(
                        "gui.rovenfall.quest.progress", objective.progress(), objective.requiredCount()),
                Component.translatable("gui.rovenfall.quest.technical.objective_id", objective.id().toString()));
    }

    private static void addRewardLines(
            List<Component> lore,
            QuestJourneyView.Status status,
            Optional<QuestJourneyView.RewardPreview> reward,
            RpgDefinitionSnapshot rpgDefinitions) {
        if (reward.isEmpty()) {
            lore.add(Component.translatable(switch (status) {
                case COMPLETED, UNRESOLVED, DEFINITION_CHANGED ->
                    "gui.rovenfall.quest.reward.unavailable";
                default -> "gui.rovenfall.quest.reward.none";
            }));
            return;
        }
        QuestJourneyView.RewardPreview preview = reward.orElseThrow();
        lore.add(Component.translatable("gui.rovenfall.quest.reward"));
        if (preview.currency() > 0) {
            lore.add(Component.translatable("gui.rovenfall.quest.reward.currency", preview.currency()));
        }
        if (preview.activityXp() > 0) {
            lore.add(Component.translatable(
                    "gui.rovenfall.quest.reward.activity_xp",
                    activityName(rpgDefinitions, preview.activity()), preview.activityXp()));
        }
    }

    private void addNavigation(int currentPage, int entries) {
        addBack();
        if (currentPage > 0) {
            content.setItem(PREVIOUS_SLOT, icon(Items.ARROW, "gui.rovenfall.player.previous"));
        }
        if ((long) (currentPage + 1) * PAGE_SIZE < entries) {
            content.setItem(NEXT_SLOT, icon(Items.ARROW, "gui.rovenfall.player.next"));
        }
        renderedView.nextStep().ifPresent(step -> content.setItem(
                GUIDE_SLOT,
                PlayerDashboardMenu.icon(
                        renderedWritable ? Items.COMPASS : Items.BARRIER,
                        Component.translatable("gui.rovenfall.quest.guide"),
                        Component.translatable(
                                "gui.rovenfall.quest.next_step",
                                nextStepLine(step, RpgDefinitionReloadListener.snapshot(
                                        viewer.level().getServer()))),
                        Component.translatable(renderedWritable
                                ? "gui.rovenfall.player.click"
                                : "gui.rovenfall.quest.read_only"))));
    }

    private void addBack() {
        content.setItem(BACK_SLOT, icon(
                Items.ARROW,
                "gui.rovenfall.player.back",
                Component.translatable("gui.rovenfall.player.click")));
    }

    private static Component activityName(
            RpgDefinitionSnapshot definitions,
            Optional<Identifier> activity) {
        return activity.flatMap(definitions::activity)
                .<Component>map(definition -> Component.translatable(definition.translationKey()))
                .orElseGet(() -> Component.translatable("gui.rovenfall.player.unknown_activity"));
    }

    private static Item statusItem(QuestJourneyView.Status status) {
        return switch (status) {
            case AVAILABLE -> Items.BOOK;
            case IN_PROGRESS -> Items.COMPASS;
            case PREREQUISITE_LOCKED -> Items.IRON_BARS;
            case PENDING -> Items.CLOCK;
            case COMPLETED -> Items.EMERALD;
            case UNRESOLVED, DEFINITION_CHANGED -> Items.BARRIER;
        };
    }

    private static Component pageLine(int page, int pages, int entries) {
        return Component.translatable(
                "gui.rovenfall.player.page", entries == 0 ? 0 : page + 1, pages, entries);
    }

    private static ItemStack icon(Item item, String key, Component... lore) {
        return PlayerDashboardMenu.icon(item, Component.translatable(key), lore);
    }

    private static int contentOffset(int slot) {
        for (int index = 0; index < CONTENT_SLOTS.length; index++) {
            if (CONTENT_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }
}

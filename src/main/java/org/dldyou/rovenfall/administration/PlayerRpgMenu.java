package org.dldyou.rovenfall.administration;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
import net.minecraft.world.item.Items;
import org.dldyou.rovenfall.rpg.ActivityDefinition;
import org.dldyou.rovenfall.rpg.ActivityXpConfig;
import org.dldyou.rovenfall.rpg.CareerDefinition;
import org.dldyou.rovenfall.rpg.CareerProgressionService;
import org.dldyou.rovenfall.rpg.PlayerCareerPromotionService;
import org.dldyou.rovenfall.rpg.PlayerRpgView;
import org.dldyou.rovenfall.rpg.RpgActiveSkillService;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.rpg.RpgDefinitionSnapshot;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.rpg.RpgPlayerState;
import org.dldyou.rovenfall.rpg.RpgItemCost;
import org.dldyou.rovenfall.rpg.RpgItemPayment;
import org.dldyou.rovenfall.rpg.RpgSkillNetwork;
import org.dldyou.rovenfall.rpg.RpgSkillResetCoordinator;
import org.dldyou.rovenfall.rpg.RpgSkillService;
import org.dldyou.rovenfall.rpg.SkillDefinition;
import org.dldyou.rovenfall.rpg.SkillResetPlan;
import org.dldyou.rovenfall.Rovenfall;

/** Server-authoritative career and skill tree navigation using the native container session. */
public final class PlayerRpgMenu extends ChestMenu {
    private static final Identifier DENIED_ACTION = Identifier.fromNamespaceAndPath(
            Rovenfall.MOD_ID, "rpg_gui_action_denied");
    static final int PAGE_SIZE = 28;
    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int[] CONFIRM_CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 30, 31, 32, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int BACK_SLOT = 45;
    private static final int PREVIOUS_SLOT = 48;
    private static final int HOME_SLOT = 49;
    private static final int NEXT_SLOT = 50;
    private static final int REFRESH_SLOT = 53;

    enum Page {
        HOME,
        ACTIVITIES,
        CAREERS,
        CAREER_DETAIL,
        SKILLS,
        SKILL_DETAIL,
        CONFIRM
    }

    enum Mutation {
        PROMOTE,
        SWITCH,
        RESET_BRANCH,
        RESET_FULL
    }

    private final ServerPlayer viewer;
    private final UUID viewerId;
    private final SimpleContainer content;
    private Page page = Page.HOME;
    private Page returnPage = Page.HOME;
    private int pageIndex;
    private Identifier selectedCareer;
    private Identifier selectedSkill;
    private Confirmation confirmation;
    private long renderedRevision;
    private RpgPlayerState renderedState = RpgPlayerState.EMPTY;
    private long lastHandledGameTime = Long.MIN_VALUE;

    private PlayerRpgMenu(
            int containerId,
            Inventory inventory,
            ServerPlayer viewer,
            SimpleContainer content,
            ReopenState initial) {
        super(MenuType.GENERIC_9x6, containerId, inventory, content, 6);
        this.viewer = viewer;
        this.viewerId = viewer.getUUID();
        this.content = content;
        this.page = initial.page();
        this.pageIndex = initial.pageIndex();
        this.selectedCareer = initial.selectedCareer();
        this.selectedSkill = initial.selectedSkill();
        render();
        PlayerMenuNetwork.seedMenuSession(this, UUID.randomUUID());
    }

    public static void open(ServerPlayer player) {
        open(player, ReopenState.HOME);
    }

    private static void open(ServerPlayer player, ReopenState initial) {
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) -> new PlayerRpgMenu(
                        containerId, inventory, (ServerPlayer) viewer, new SimpleContainer(54), initial),
                Component.translatable("gui.rovenfall.rpg.title")));
    }

    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !viewerId.equals(serverPlayer.getUUID())
                || slotIndex < 0 || slotIndex >= 54
                || !PlayerMenuNetwork.isPrimaryAction(buttonNum, input)
                || !isActionSlot(slotIndex)) {
            return;
        }
        long gameTime = viewer.level().getGameTime();
        if (!PlayerDashboardMenu.canHandleClick(lastHandledGameTime, gameTime)) {
            return;
        }
        lastHandledGameTime = gameTime;
        if (slotIndex == REFRESH_SLOT) {
            confirmation = null;
            render();
            return;
        }
        if (slotIndex == HOME_SLOT) {
            page = Page.HOME;
            pageIndex = 0;
            selectedCareer = null;
            selectedSkill = null;
            confirmation = null;
            render();
            return;
        }
        if (slotIndex == BACK_SLOT) {
            back();
            return;
        }
        if (slotIndex == PREVIOUS_SLOT) {
            pageIndex--;
            render();
            return;
        }
        if (slotIndex == NEXT_SLOT) {
            pageIndex++;
            render();
            return;
        }
        switch (page) {
            case HOME -> handleHome(slotIndex);
            case ACTIVITIES -> {
            }
            case CAREERS -> selectCareer(slotIndex);
            case CAREER_DETAIL -> handleCareerDetail(slotIndex);
            case SKILLS -> selectSkill(slotIndex);
            case SKILL_DETAIL -> handleSkillDetail(slotIndex);
            case CONFIRM -> handleConfirmation(slotIndex);
        }
    }

    @Override
    public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int slotIndex) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive() && viewerId.equals(player.getUUID());
    }

    private void handleHome(int slot) {
        page = switch (slot) {
            case 11 -> Page.ACTIVITIES;
            case 13 -> Page.CAREERS;
            case 15 -> Page.SKILLS;
            default -> page;
        };
        pageIndex = 0;
        render();
    }

    private void selectCareer(int slot) {
        int offset = contentOffset(slot);
        List<PlayerRpgView.CareerRow> careers = view().careers();
        int index = pageIndex * PAGE_SIZE + offset;
        if (offset < 0 || index < 0 || index >= careers.size()) {
            render();
            return;
        }
        selectedCareer = careers.get(index).id();
        page = Page.CAREER_DETAIL;
        render();
    }

    private void selectSkill(int slot) {
        int offset = contentOffset(slot);
        List<PlayerRpgView.SkillRow> skills = view().skills();
        int index = pageIndex * PAGE_SIZE + offset;
        if (offset < 0 || index < 0 || index >= skills.size()) {
            render();
            return;
        }
        selectedSkill = skills.get(index).id();
        page = Page.SKILL_DETAIL;
        render();
    }

    private void handleCareerDetail(int slot) {
        PlayerRpgView.CareerRow career = selectedCareerRow().orElse(null);
        if (career == null || slot != 31) {
            return;
        }
        if (career.unresolved() || career.active() || career.lock().locked()) {
            viewer.sendOverlayMessage(Component.translatable(lockKey(career.lock())));
            render();
            return;
        }
        prepareConfirmation(career.promoted() ? Mutation.SWITCH : Mutation.PROMOTE,
                career.id(), Optional.empty());
    }

    private void handleSkillDetail(int slot) {
        PlayerRpgView.SkillRow skill = selectedSkillRow().orElse(null);
        if (skill == null) {
            return;
        }
        if (slot == 20) {
            learn(skill);
            return;
        }
        if (slot >= 28 && slot <= 31) {
            assign(skill, slot - 28);
            return;
        }
        if (slot == 33 && skill.rank() > 0) {
            prepareReset(SkillResetPlan.Mode.BRANCH, skill.id());
            return;
        }
        if (slot == 34 && skill.career().isPresent()) {
            prepareReset(SkillResetPlan.Mode.FULL, skill.career().orElseThrow());
        }
    }

    private void learn(PlayerRpgView.SkillRow skill) {
        if (!sessionCurrent()) {
            stale();
            return;
        }
        if (!beginMutation()) {
            return;
        }
        RpgSkillService.Result result = RpgSkillService.learn(
                rpg(), definitions(), viewerId, skill.id(), Instant.now().toEpochMilli(),
                UUID.randomUUID(), "player_gui");
        result(result.status() == RpgSkillService.Status.SUCCESS, result.status().name());
    }

    private void assign(PlayerRpgView.SkillRow skill, int slot) {
        if (!sessionCurrent()) {
            stale();
            return;
        }
        Optional<Identifier> requested = Objects.equals(rpg().state(viewerId).activeSkillSlots().get(slot), skill.id())
                ? Optional.empty() : Optional.of(skill.id());
        if (!beginMutation()) {
            return;
        }
        RpgActiveSkillService.SlotResult result = RpgActiveSkillService.assignSlot(
                rpg(), definitions(), viewerId, slot, requested, ActivityXpConfig.activeSkillSlots(),
                Instant.now().toEpochMilli(), UUID.randomUUID(), "player_gui");
        if (result.status() == RpgActiveSkillService.Status.SUCCESS) {
            RpgSkillNetwork.sync(viewer);
        }
        result(result.status() == RpgActiveSkillService.Status.SUCCESS, result.status().name());
    }

    private void prepareReset(SkillResetPlan.Mode mode, Identifier target) {
        if (!sessionCurrent()) {
            stale();
            return;
        }
        RpgSkillService.ResetPreparation preparation = RpgSkillService.prepareReset(
                rpg(), definitions(), viewerId, mode, target);
        if (preparation.status() != RpgSkillService.Status.SUCCESS) {
            result(false, preparation.status().name());
            return;
        }
        prepareConfirmation(
                mode == SkillResetPlan.Mode.BRANCH ? Mutation.RESET_BRANCH : Mutation.RESET_FULL,
                target, preparation.plan());
    }

    private void prepareConfirmation(Mutation mutation, Identifier target, Optional<SkillResetPlan> plan) {
        returnPage = page;
        long cost = mutation == Mutation.PROMOTE
                ? definitions().career(target).map(CareerDefinition::promotionCost).orElse(0L)
                : plan.map(value -> ActivityXpConfig.skillResetCost(value.mode())).orElse(0L);
        confirmation = new Confirmation(
                mutation, target, plan, renderedRevision, renderedState,
                plan.map(SkillResetPlan::refundedPoints).orElse(0L),
                cost, PlatformSavedData.get(viewer.level().getServer())
                        .economyBalance(viewerId).orElse(0L),
                currentItemCosts(mutation, target), ownedItemCounts(currentItemCosts(mutation, target)));
        page = Page.CONFIRM;
        pageIndex = 0;
        render();
    }

    private void handleConfirmation(int slot) {
        if (slot == 29) {
            page = returnPage;
            confirmation = null;
            render();
            return;
        }
        if (slot != 33 || confirmation == null) {
            return;
        }
        if (!canConfirm(confirmation.definitionRevision(), confirmation.state(),
                currentRevision(), rpg().state(viewerId))) {
            stale();
            return;
        }
        long currentCost = currentCost(confirmation);
        long currentBalance = PlatformSavedData.get(viewer.level().getServer())
                .economyBalance(viewerId).orElse(0L);
        if (!canConfirmEconomy(confirmation.balance(), confirmation.cost(), currentBalance, currentCost)) {
            stale();
            return;
        }
        List<RpgItemCost> currentItems = currentItemCosts(confirmation.mutation(), confirmation.target());
        if (!canConfirmItems(
                confirmation.itemCosts(), confirmation.ownedItems(), currentItems, ownedItemCounts(currentItems))) {
            stale();
            return;
        }
        if (!beginMutation()) {
            return;
        }
        Confirmation action = confirmation;
        boolean success;
        String status;
        switch (action.mutation()) {
            case PROMOTE -> {
                PlayerCareerPromotionService.Result result = PlayerCareerPromotionService.promote(
                        viewer, action.target(), Instant.now().toEpochMilli());
                success = result.status() == PlayerCareerPromotionService.Status.SUCCESS;
                status = result.status().name();
            }
            case SWITCH -> {
                CareerProgressionService.Result result = CareerProgressionService.switchActive(
                        rpg(), definitions(), viewerId, action.target(), Instant.now().toEpochMilli(),
                        UUID.randomUUID(), "player_gui");
                success = result.status() == CareerProgressionService.Status.SUCCESS;
                status = result.status().name();
            }
            case RESET_BRANCH, RESET_FULL -> {
                SkillResetPlan.Mode mode = action.mutation() == Mutation.RESET_BRANCH
                        ? SkillResetPlan.Mode.BRANCH : SkillResetPlan.Mode.FULL;
                RpgSkillResetCoordinator.Result result = RpgSkillResetCoordinator.reset(
                        viewer, mode, action.target(), Instant.now().toEpochMilli(), UUID.randomUUID());
                success = result.status() == RpgSkillResetCoordinator.Status.SUCCESS;
                status = result.status().name();
            }
            default -> throw new IllegalStateException("Unknown RPG GUI mutation " + action.mutation());
        }
        if (success) {
            RpgSkillNetwork.sync(viewer);
        }
        confirmation = null;
        page = returnPage;
        result(success, status);
    }

    private void result(boolean success, String status) {
        viewer.sendOverlayMessage(Component.translatable(resultKey(success, status)));
        reopen();
    }

    static String resultKey(boolean success, String status) {
        if (status.equals("ITEM_PAYMENT_FAILED")) {
            return "gui.rovenfall.rpg.result.item_payment_failed";
        }
        return status.equals("COMPLETION_FAILED") || status.equals("RPG_FAILED")
                ? "gui.rovenfall.rpg.result.pending"
                : success ? "gui.rovenfall.rpg.result.success" : "gui.rovenfall.rpg.result.failed";
    }

    private boolean beginMutation() {
        if (PlayerMenuNetwork.beginMutation(viewerId, viewer.level().getGameTime())) {
            return true;
        }
        viewer.sendOverlayMessage(Component.translatable("gui.rovenfall.rpg.result.rate_limit"));
        reopen();
        return false;
    }

    private void stale() {
        UUID auditId = UUID.randomUUID();
        PlatformSavedData.get(viewer.level().getServer()).appendDeniedAudit(new AuditEntry(
                Instant.now().toEpochMilli(), viewerId, DENIED_ACTION, viewerId.toString(),
                Optional.of(viewer.level().dimension().identifier()), Optional.of(viewer.blockPosition()),
                Long.toString(renderedRevision), Long.toString(currentRevision()),
                "stale_session", auditId), 1_000L);
        confirmation = null;
        page = returnPage == Page.CONFIRM ? Page.HOME : returnPage;
        viewer.sendOverlayMessage(Component.translatable("gui.rovenfall.rpg.result.stale"));
        reopen();
    }

    private void back() {
        switch (page) {
            case HOME -> {
                PlayerDashboardMenu.open(viewer);
                return;
            }
            case ACTIVITIES, CAREERS, SKILLS -> page = Page.HOME;
            case CAREER_DETAIL -> page = Page.CAREERS;
            case SKILL_DETAIL -> page = Page.SKILLS;
            case CONFIRM -> page = returnPage;
        }
        confirmation = null;
        render();
    }

    private void render() {
        content.clearContent();
        switch (page) {
            case HOME -> renderHome();
            case ACTIVITIES -> renderActivities();
            case CAREERS -> renderCareers();
            case CAREER_DETAIL -> renderCareerDetail();
            case SKILLS -> renderSkills();
            case SKILL_DETAIL -> renderSkillDetail();
            case CONFIRM -> renderConfirmation();
        }
        content.setItem(REFRESH_SLOT, icon(Items.CLOCK, "gui.rovenfall.player.refresh",
                Component.translatable("gui.rovenfall.player.click")));
        renderedRevision = currentRevision();
        renderedState = rpg().state(viewerId);
        broadcastChanges();
    }

    private void renderHome() {
        PlayerRpgView view = view();
        content.setItem(4, PlayerDashboardMenu.icon(
                Items.EXPERIENCE_BOTTLE, Component.translatable("gui.rovenfall.rpg.title"),
                Component.translatable("gui.rovenfall.player.balance", view.balance()),
                Component.translatable("gui.rovenfall.rpg.definition_revision", view.definitionRevision())));
        content.setItem(11, PlayerDashboardMenu.icon(
                Items.COMPASS, Component.translatable("gui.rovenfall.rpg.activities"),
                Component.translatable("gui.rovenfall.rpg.count", view.activities().size()),
                Component.translatable("gui.rovenfall.player.click")));
        content.setItem(13, PlayerDashboardMenu.icon(
                Items.IRON_SWORD, Component.translatable("gui.rovenfall.rpg.careers"),
                Component.translatable("gui.rovenfall.rpg.active_career",
                        rpg().state(viewerId).activeCareer().map(this::careerName)
                                .orElseGet(() -> Component.translatable("gui.rovenfall.player.none"))),
                Component.translatable("gui.rovenfall.player.click")));
        content.setItem(15, PlayerDashboardMenu.icon(
                Items.ENCHANTED_BOOK, Component.translatable("gui.rovenfall.rpg.skills"),
                Component.translatable("gui.rovenfall.rpg.count", view.skills().size()),
                Component.translatable("gui.rovenfall.player.click")));
        for (PlayerRpgView.SlotRow slot : view.slots()) {
            content.setItem(28 + slot.slot(), slotIcon(slot));
        }
        addBackHome();
    }

    private void renderActivities() {
        List<PlayerRpgView.ActivityRow> rows = view().activities();
        pageIndex = boundedPage(pageIndex, rows.size());
        content.setItem(4, header(Items.COMPASS, "gui.rovenfall.rpg.activities", rows.size()));
        pageEntries(rows).forEach(entry -> content.setItem(
                CONTENT_SLOTS[entry.offset()], activityIcon(entry.value())));
        addNavigation(rows.size());
    }

    private void renderCareers() {
        List<PlayerRpgView.CareerRow> rows = view().careers();
        pageIndex = boundedPage(pageIndex, rows.size());
        content.setItem(4, header(Items.IRON_SWORD, "gui.rovenfall.rpg.careers", rows.size()));
        pageEntries(rows).forEach(entry -> content.setItem(
                CONTENT_SLOTS[entry.offset()], careerIcon(entry.value(), true)));
        addNavigation(rows.size());
    }

    private void renderCareerDetail() {
        PlayerRpgView.CareerRow row = selectedCareerRow().orElse(null);
        if (row == null) {
            page = Page.CAREERS;
            renderCareers();
            return;
        }
        content.setItem(13, careerIcon(row, false));
        if (!row.unresolved()) {
            content.setItem(31, PlayerDashboardMenu.icon(
                    row.active() || row.lock().locked() ? Items.BARRIER : Items.EMERALD,
                    Component.translatable(row.promoted()
                            ? row.active() ? "gui.rovenfall.rpg.career.active" : "gui.rovenfall.rpg.career.switch"
                            : "gui.rovenfall.rpg.career.promote"),
                    row.lock().locked() ? lockLine(row.lock())
                            : Component.translatable("gui.rovenfall.player.click")));
        }
        addBackHome();
    }

    private void renderSkills() {
        List<PlayerRpgView.SkillRow> rows = view().skills();
        pageIndex = boundedPage(pageIndex, rows.size());
        content.setItem(4, header(Items.ENCHANTED_BOOK, "gui.rovenfall.rpg.skills", rows.size()));
        pageEntries(rows).forEach(entry -> content.setItem(
                CONTENT_SLOTS[entry.offset()], skillIcon(entry.value(), true)));
        addNavigation(rows.size());
    }

    private void renderSkillDetail() {
        PlayerRpgView.SkillRow row = selectedSkillRow().orElse(null);
        if (row == null) {
            page = Page.SKILLS;
            renderSkills();
            return;
        }
        content.setItem(13, skillIcon(row, false));
        content.setItem(20, PlayerDashboardMenu.icon(
                row.lock().locked() ? Items.BARRIER : Items.LAPIS_LAZULI,
                Component.translatable("gui.rovenfall.rpg.skill.learn"),
                row.lock().locked() ? lockLine(row.lock())
                        : Component.translatable("gui.rovenfall.rpg.skill.next_rank", row.rank() + 1),
                Component.translatable("gui.rovenfall.rpg.skill.point_cost", row.pointCost())));
        if (row.kind().filter(kind -> kind == SkillDefinition.Kind.ACTIVE).isPresent() && row.rank() > 0) {
            List<PlayerRpgView.SlotRow> slots = view().slots();
            for (PlayerRpgView.SlotRow slot : slots) {
                boolean assigned = slot.skill().filter(row.id()::equals).isPresent();
                content.setItem(28 + slot.slot(), PlayerDashboardMenu.icon(
                        assigned ? Items.EMERALD : Items.PAPER,
                        Component.translatable("gui.rovenfall.rpg.slot", slot.slot() + 1),
                        Component.translatable(assigned
                                ? "gui.rovenfall.rpg.slot.unassign"
                                : "gui.rovenfall.rpg.slot.assign"),
                        Component.translatable("gui.rovenfall.player.click")));
            }
        }
        if (row.rank() > 0) {
            content.setItem(33, resetIcon(SkillResetPlan.Mode.BRANCH, row.id()));
            row.career().ifPresent(career ->
                    content.setItem(34, resetIcon(SkillResetPlan.Mode.FULL, career)));
        }
        addBackHome();
    }

    private void renderConfirmation() {
        if (confirmation == null) {
            page = returnPage;
            render();
            return;
        }
        List<Component> consequences = new java.util.ArrayList<>();
        consequences.add(Component.translatable("gui.rovenfall.rpg.confirm.action." +
                confirmation.mutation().name().toLowerCase(Locale.ROOT)));
        consequences.add(Component.literal(confirmation.target().toString()));
        if (confirmation.mutation() == Mutation.PROMOTE || confirmation.mutation() == Mutation.SWITCH) {
            consequences.add(Component.translatable(
                    "gui.rovenfall.rpg.confirm.career_change",
                    confirmation.state().activeCareer().map(this::careerName)
                            .orElseGet(() -> Component.translatable("gui.rovenfall.player.none")),
                    careerName(confirmation.target())));
            consequences.add(Component.translatable(
                    "gui.rovenfall.rpg.confirm.slots_affected",
                    affectedSlots(confirmation.state(), confirmation.target())));
        }
        consequences.add(Component.translatable("gui.rovenfall.rpg.confirm.cost", confirmation.cost()));
        consequences.add(Component.translatable(
                "gui.rovenfall.rpg.confirm.balance_after",
                Math.max(0L, confirmation.balance() - confirmation.cost())));
        consequences.add(Component.translatable("gui.rovenfall.rpg.confirm.refund", confirmation.refundedPoints()));
        for (int index = 0; index < confirmation.itemCosts().size(); index++) {
            RpgItemCost item = confirmation.itemCosts().get(index);
            consequences.add(Component.translatable(
                    "gui.rovenfall.rpg.confirm.item_cost", item.item().toString(), item.count(),
                    confirmation.ownedItems().get(index)));
        }
        content.setItem(4, PlayerDashboardMenu.icon(
                Items.WRITABLE_BOOK, Component.translatable("gui.rovenfall.rpg.confirm.title"),
                consequences.toArray(Component[]::new)));
        confirmation.plan().ifPresent(plan -> {
            pageIndex = boundedPage(pageIndex, plan.removedSkills().size(), CONFIRM_CONTENT_SLOTS.length);
            int from = Math.min(plan.removedSkills().size(), pageIndex * CONFIRM_CONTENT_SLOTS.length);
            int to = Math.min(plan.removedSkills().size(), from + CONFIRM_CONTENT_SLOTS.length);
            for (int index = from; index < to; index++) {
                SkillResetPlan.RemovedSkill removed = plan.removedSkills().get(index);
                content.setItem(CONFIRM_CONTENT_SLOTS[index - from], PlayerDashboardMenu.icon(
                        Items.BOOK, skillName(removed.skill()),
                        Component.translatable("gui.rovenfall.rpg.skill.rank", removed.rank(), removed.rank()),
                        Component.translatable("gui.rovenfall.rpg.confirm.refund", removed.refundedPoints())));
            }
            if (pageIndex > 0) {
                content.setItem(PREVIOUS_SLOT, icon(Items.ARROW, "gui.rovenfall.player.previous"));
            }
            if ((long) (pageIndex + 1) * CONFIRM_CONTENT_SLOTS.length < plan.removedSkills().size()) {
                content.setItem(NEXT_SLOT, icon(Items.ARROW, "gui.rovenfall.player.next"));
            }
        });
        content.setItem(29, icon(Items.BARRIER, "gui.rovenfall.player.cancel",
                Component.translatable("gui.rovenfall.player.click")));
        content.setItem(33, icon(Items.EMERALD, "gui.rovenfall.player.confirm",
                Component.translatable("gui.rovenfall.player.click")));
        addBackHome();
    }

    private net.minecraft.world.item.ItemStack activityIcon(PlayerRpgView.ActivityRow row) {
        return PlayerDashboardMenu.icon(
                row.unresolved() ? Items.BARRIER : Items.EXPERIENCE_BOTTLE,
                name(row.translationKey(), row.id()),
                Component.translatable("gui.rovenfall.rpg.activity.level", row.level()),
                Component.translatable("gui.rovenfall.rpg.xp", row.experience()),
                row.nextLevelXp() == 0 ? Component.translatable("gui.rovenfall.rpg.max_level")
                        : Component.translatable("gui.rovenfall.rpg.next_xp", row.nextLevelXp()),
                unresolved(row.unresolved(), row.id()));
    }

    private net.minecraft.world.item.ItemStack careerIcon(PlayerRpgView.CareerRow row, boolean clickable) {
        List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.translatable("gui.rovenfall.rpg.career.tier", row.tier()));
        lore.add(Component.translatable("gui.rovenfall.rpg.career.rank", row.rank()));
        lore.add(Component.translatable("gui.rovenfall.rpg.xp", row.experience()));
        lore.add(Component.translatable("gui.rovenfall.rpg.skill.points", row.skillPoints()));
        lore.add(Component.translatable("gui.rovenfall.rpg.career.cost", row.promotionCost()));
        definitions().career(row.id()).ifPresent(definition -> definition.promotionItems().forEach(item ->
                lore.add(itemCostLine(item))));
        row.requirements().stream().limit(8).forEach(requirement -> lore.add(requirementLine(requirement)));
        if (row.active()) {
            lore.add(Component.translatable("gui.rovenfall.rpg.career.active"));
        } else if (row.inActiveLineage()) {
            lore.add(Component.translatable("gui.rovenfall.rpg.career.lineage"));
        }
        if (row.unresolved()) {
            lore.add(unresolved(true, row.id()));
        } else if (row.lock().locked()) {
            lore.add(lockLine(row.lock()));
        }
        if (clickable) {
            lore.add(Component.translatable("gui.rovenfall.player.click"));
        }
        return PlayerDashboardMenu.icon(
                row.unresolved() || row.lock().locked() ? Items.BARRIER
                        : row.active() ? Items.DIAMOND_SWORD : Items.IRON_SWORD,
                name(row.translationKey(), row.id()), lore.toArray(Component[]::new));
    }

    private net.minecraft.world.item.ItemStack skillIcon(PlayerRpgView.SkillRow row, boolean clickable) {
        List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.translatable("gui.rovenfall.rpg.skill.rank", row.rank(), row.maxRank()));
        lore.add(Component.translatable("gui.rovenfall.rpg.skill.point_cost", row.pointCost()));
        row.career().ifPresent(career -> lore.add(Component.translatable(
                "gui.rovenfall.rpg.skill.career", careerName(career))));
        row.kind().ifPresent(kind -> lore.add(Component.translatable(
                "gui.rovenfall.rpg.skill.kind." + kind.getSerializedName())));
        if (row.career().isPresent() && !row.activeLineage()) {
            lore.add(Component.translatable("gui.rovenfall.rpg.skill.inactive_lineage"));
        }
        row.requirements().stream().limit(8).forEach(requirement -> lore.add(requirementLine(requirement)));
        if (row.cooldownTicks() > 0) {
            lore.add(Component.translatable("gui.rovenfall.rpg.skill.cooldown", row.cooldownTicks()));
        }
        if (row.unresolved()) {
            lore.add(unresolved(true, row.id()));
        } else if (row.lock().locked()) {
            lore.add(lockLine(row.lock()));
        }
        if (clickable) {
            lore.add(Component.translatable("gui.rovenfall.player.click"));
        }
        return PlayerDashboardMenu.icon(
                row.unresolved() || (row.rank() == 0 && row.lock().locked()) ? Items.BARRIER
                        : row.rank() > 0 ? Items.ENCHANTED_BOOK : Items.BOOK,
                name(row.translationKey(), row.id()), lore.toArray(Component[]::new));
    }

    private net.minecraft.world.item.ItemStack slotIcon(PlayerRpgView.SlotRow row) {
        return PlayerDashboardMenu.icon(
                row.skill().isPresent() ? row.unresolved() ? Items.BARRIER : Items.ENCHANTED_BOOK : Items.PAPER,
                Component.translatable("gui.rovenfall.rpg.slot", row.slot() + 1),
                row.skill().map(this::skillName)
                        .orElseGet(() -> Component.translatable("gui.rovenfall.player.empty")),
                Component.translatable("gui.rovenfall.rpg.skill.cooldown", row.cooldownTicks()),
                row.unresolved() ? Component.translatable(
                        "gui.rovenfall.rpg.unresolved", row.skill().orElseThrow().toString()) : Component.empty());
    }

    private net.minecraft.world.item.ItemStack resetIcon(SkillResetPlan.Mode mode, Identifier target) {
        List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.translatable("gui.rovenfall.rpg.confirm.cost", ActivityXpConfig.skillResetCost(mode)));
        RpgSkillResetCoordinator.resetItemCosts(definitions(), mode, target)
                .forEach(item -> lore.add(itemCostLine(item)));
        lore.add(Component.translatable("gui.rovenfall.player.click"));
        return PlayerDashboardMenu.icon(
                Items.REDSTONE,
                Component.translatable("gui.rovenfall.rpg.reset." + mode.getSerializedName()),
                lore.toArray(Component[]::new));
    }

    private net.minecraft.world.item.ItemStack header(Item item, String key, int count) {
        return PlayerDashboardMenu.icon(item, Component.translatable(key),
                Component.translatable("gui.rovenfall.rpg.count", count), pageLine(count));
    }

    private net.minecraft.world.item.ItemStack icon(Item item, String key, Component... lore) {
        return PlayerDashboardMenu.icon(item, Component.translatable(key), lore);
    }

    private void addNavigation(int entries) {
        addBackHome();
        if (pageIndex > 0) {
            content.setItem(PREVIOUS_SLOT, icon(Items.ARROW, "gui.rovenfall.player.previous"));
        }
        if ((long) (pageIndex + 1) * PAGE_SIZE < entries) {
            content.setItem(NEXT_SLOT, icon(Items.ARROW, "gui.rovenfall.player.next"));
        }
    }

    private void addBackHome() {
        content.setItem(BACK_SLOT, icon(Items.ARROW, "gui.rovenfall.player.back",
                Component.translatable("gui.rovenfall.player.click")));
        if (page != Page.HOME) {
            content.setItem(HOME_SLOT, icon(Items.NETHER_STAR, "gui.rovenfall.player.home",
                    Component.translatable("gui.rovenfall.player.click")));
        }
    }

    private Optional<PlayerRpgView.CareerRow> selectedCareerRow() {
        return selectedCareer == null ? Optional.empty() : view().careers().stream()
                .filter(row -> row.id().equals(selectedCareer)).findFirst();
    }

    private Optional<PlayerRpgView.SkillRow> selectedSkillRow() {
        return selectedSkill == null ? Optional.empty() : view().skills().stream()
                .filter(row -> row.id().equals(selectedSkill)).findFirst();
    }

    private PlayerRpgView view() {
        var server = viewer.level().getServer();
        return PlayerRpgView.create(
                definitions(), rpg().state(viewerId), currentRevision(),
                PlatformSavedData.get(server).economyBalance(viewerId).orElse(0L),
                viewer.level().getGameTime());
    }

    private RpgPlayerSavedData rpg() {
        return RpgPlayerSavedData.get(viewer.level().getServer());
    }

    private RpgDefinitionSnapshot definitions() {
        return RpgDefinitionReloadListener.snapshot(viewer.level().getServer());
    }

    private long currentRevision() {
        return RpgDefinitionReloadListener.revision(viewer.level().getServer());
    }

    private boolean sessionCurrent() {
        return canConfirm(renderedRevision, renderedState, currentRevision(), rpg().state(viewerId));
    }

    static boolean canConfirm(
            long expectedRevision, RpgPlayerState expectedState, long currentRevision, RpgPlayerState currentState) {
        return expectedRevision > 0 && expectedRevision == currentRevision
                && Objects.equals(expectedState, currentState);
    }

    static boolean canConfirmEconomy(
            long expectedBalance, long expectedCost, long currentBalance, long currentCost) {
        return expectedBalance >= 0 && expectedCost >= 0
                && expectedBalance == currentBalance && expectedCost == currentCost;
    }

    static boolean canConfirmItems(
            List<RpgItemCost> expectedCosts,
            List<Long> expectedOwned,
            List<RpgItemCost> currentCosts,
            List<Long> currentOwned) {
        return expectedCosts != null && expectedOwned != null
                && expectedCosts.equals(currentCosts) && expectedOwned.equals(currentOwned)
                && expectedCosts.size() == expectedOwned.size();
    }

    private List<RpgItemCost> currentItemCosts(Mutation mutation, Identifier target) {
        return switch (mutation) {
            case PROMOTE -> definitions().career(target).map(CareerDefinition::promotionItems).orElse(List.of());
            case RESET_BRANCH -> RpgSkillResetCoordinator.resetItemCosts(
                    definitions(), SkillResetPlan.Mode.BRANCH, target);
            case RESET_FULL -> RpgSkillResetCoordinator.resetItemCosts(
                    definitions(), SkillResetPlan.Mode.FULL, target);
            case SWITCH -> List.of();
        };
    }

    private List<Long> ownedItemCounts(List<RpgItemCost> costs) {
        return costs.stream().map(cost -> RpgItemPayment.owned(viewer, cost.item())).toList();
    }

    private Component itemCostLine(RpgItemCost item) {
        return Component.translatable("gui.rovenfall.rpg.confirm.item_cost",
                item.item().toString(), item.count(), RpgItemPayment.owned(viewer, item.item()));
    }

    private long currentCost(Confirmation action) {
        return switch (action.mutation()) {
            case PROMOTE -> definitions().career(action.target())
                    .map(CareerDefinition::promotionCost).orElse(-1L);
            case SWITCH -> 0L;
            case RESET_BRANCH -> ActivityXpConfig.skillResetCost(SkillResetPlan.Mode.BRANCH);
            case RESET_FULL -> ActivityXpConfig.skillResetCost(SkillResetPlan.Mode.FULL);
        };
    }

    private void reopen() {
        Page target = page == Page.CONFIRM ? returnPage : page;
        open(viewer, new ReopenState(target, pageIndex, selectedCareer, selectedSkill));
    }

    static int boundedPage(int page, int entries) {
        return boundedPage(page, entries, PAGE_SIZE);
    }

    private static int boundedPage(int page, int entries, int pageSize) {
        int last = entries == 0 ? 0 : (entries - 1) / pageSize;
        return Math.clamp(page, 0, last);
    }

    private <T> List<PageEntry<T>> pageEntries(List<T> entries) {
        int from = Math.min(entries.size(), pageIndex * PAGE_SIZE);
        int to = Math.min(entries.size(), from + PAGE_SIZE);
        return java.util.stream.IntStream.range(from, to)
                .mapToObj(index -> new PageEntry<>(index - from, entries.get(index))).toList();
    }

    private static int contentOffset(int slot) {
        for (int index = 0; index < CONTENT_SLOTS.length; index++) {
            if (CONTENT_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    private boolean isActionSlot(int slot) {
        if (slot == BACK_SLOT || slot == HOME_SLOT || slot == REFRESH_SLOT
                || slot == PREVIOUS_SLOT || slot == NEXT_SLOT) {
            return true;
        }
        return switch (page) {
            case HOME -> slot == 11 || slot == 13 || slot == 15;
            case ACTIVITIES -> false;
            case CAREERS, SKILLS -> contentOffset(slot) >= 0;
            case CAREER_DETAIL -> slot == 31;
            case SKILL_DETAIL -> slot == 20 || slot >= 28 && slot <= 31 || slot == 33 || slot == 34;
            case CONFIRM -> slot == 29 || slot == 33;
        };
    }

    private Component pageLine(int count) {
        int pages = count == 0 ? 0 : (count + PAGE_SIZE - 1) / PAGE_SIZE;
        return Component.translatable("gui.rovenfall.player.page", count == 0 ? 0 : pageIndex + 1, pages, count);
    }

    private Component activityName(Identifier id) {
        return definitions().activity(id).<Component>map(definition ->
                Component.translatable(definition.translationKey())).orElseGet(() -> Component.literal(id.toString()));
    }

    private long affectedSlots(RpgPlayerState state, Identifier targetCareer) {
        Set<Identifier> lineage = careerLineage(targetCareer);
        return state.activeSkillSlots().values().stream()
                .filter(skill -> definitions().skill(skill)
                        .map(definition -> !lineage.contains(definition.career()))
                        .orElse(true))
                .count();
    }

    private Set<Identifier> careerLineage(Identifier career) {
        Set<Identifier> result = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<Identifier> remaining = new java.util.ArrayDeque<>();
        remaining.add(career);
        while (!remaining.isEmpty()) {
            Identifier current = remaining.removeFirst();
            if (result.add(current)) {
                definitions().career(current).ifPresent(definition -> remaining.addAll(definition.parents()));
            }
        }
        return Set.copyOf(result);
    }

    private Component careerName(Identifier id) {
        return definitions().career(id).<Component>map(definition ->
                Component.translatable(definition.translationKey())).orElseGet(() -> Component.literal(id.toString()));
    }

    private Component skillName(Identifier id) {
        return definitions().skill(id).<Component>map(definition ->
                Component.translatable(definition.translationKey())).orElseGet(() -> Component.literal(id.toString()));
    }

    private Component name(Optional<String> translationKey, Identifier id) {
        return translationKey.<Component>map(Component::translatable)
                .orElseGet(() -> Component.literal(id.toString()));
    }

    private Component requirementLine(PlayerRpgView.Requirement requirement) {
        Component target = requirement.target() == null
                ? Component.translatable("gui.rovenfall.player.balance_title")
                : definitions().activity(requirement.target()).isPresent()
                        ? activityName(requirement.target())
                        : definitions().career(requirement.target()).isPresent()
                                ? careerName(requirement.target()) : skillName(requirement.target());
        return Component.translatable(
                requirement.met() ? "gui.rovenfall.rpg.requirement.met" : "gui.rovenfall.rpg.requirement.locked",
                target, requirement.actual(), requirement.required());
    }

    private Component lockLine(PlayerRpgView.Lock lock) {
        return Component.translatable(lockKey(lock),
                lock.blocker().map(id -> definitions().activity(id).isPresent() ? activityName(id)
                        : definitions().career(id).isPresent() ? careerName(id) : skillName(id))
                        .orElseGet(() -> Component.translatable("gui.rovenfall.player.balance_title")),
                lock.actual(), lock.required());
    }

    private static String lockKey(PlayerRpgView.Lock lock) {
        return "gui.rovenfall.rpg.lock." + lock.reason().name().toLowerCase(Locale.ROOT);
    }

    private static Component unresolved(boolean unresolved, Identifier id) {
        return unresolved ? Component.translatable("gui.rovenfall.rpg.unresolved", id.toString()) : Component.empty();
    }

    private record PageEntry<T>(int offset, T value) {
    }

    private record Confirmation(
            Mutation mutation,
            Identifier target,
            Optional<SkillResetPlan> plan,
            long definitionRevision,
            RpgPlayerState state,
            long refundedPoints,
            long cost,
            long balance,
            List<RpgItemCost> itemCosts,
            List<Long> ownedItems) {
        Confirmation {
            plan = plan == null ? Optional.empty() : plan;
            itemCosts = itemCosts == null ? List.of() : List.copyOf(itemCosts);
            ownedItems = ownedItems == null ? List.of() : List.copyOf(ownedItems);
        }
    }

    private record ReopenState(
            Page page,
            int pageIndex,
            Identifier selectedCareer,
            Identifier selectedSkill) {
        private static final ReopenState HOME = new ReopenState(Page.HOME, 0, null, null);
    }
}

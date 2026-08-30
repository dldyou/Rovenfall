package org.dldyou.rovenfall.administration;

import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.dldyou.rovenfall.mobs.BossEncounterState;
import org.dldyou.rovenfall.mobs.BossRewardOperation;
import org.dldyou.rovenfall.mobs.MobContentReloadListener;
import org.dldyou.rovenfall.rpg.RpgAdministrationViewService;
import org.dldyou.rovenfall.rpg.RpgDefinitionReloadListener;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.rpg.RpgPlayerState;
import org.dldyou.rovenfall.rpg.RpgSkillService;
import org.dldyou.rovenfall.rpg.SkillResetPlan;

/** Server-authoritative inventory workflow for RPG content and boss operations. */
public final class AdministrationRpgBossMenu extends ChestMenu implements AdministrationTextInputMenu {
    static final int MENU_SIZE = 54;
    static final int CONTENT_START = 9;
    static final int CONTENT_SIZE = 36;
    static final int BACK_SLOT = 45;
    static final int PRIMARY_SLOT = 46;
    static final int PREVIOUS_SLOT = 47;
    static final int SECONDARY_SLOT = 48;
    static final int CENTER_SLOT = 49;
    static final int TERTIARY_SLOT = 50;
    static final int NEXT_SLOT = 51;
    static final int DANGER_SLOT = 52;
    static final int REFRESH_SLOT = 53;
    static final int CONFIRM_SLOT = 31;
    static final int CANCEL_SLOT = 33;

    private final ServerPlayer viewer;
    private final UUID viewerId;
    private final SimpleContainer contents;
    private final AdministrationReadViewService.Domain entryDomain;
    private Mode mode;
    private Mode returnMode;
    private FormKind formKind;
    private String query = "";
    private String formError = "";
    private int page;
    private boolean suspiciousOnly;
    private UUID selectedPlayer;
    private RpgAdministrationViewService.ProgressionEntry selectedProgression;
    private AdministrationRpgBossViewService.DefinitionRow selectedDefinition;
    private UUID selectedEncounter;
    private AdministrationRpgBossActionService.PendingAction pending;
    private AdministrationRpgBossActionService.Result result;
    private long lastHandledGameTime = Long.MIN_VALUE;

    private AdministrationRpgBossMenu(
            int containerId,
            Inventory inventory,
            ServerPlayer viewer,
            SimpleContainer contents,
            AdministrationReadViewService.Domain entryDomain) {
        super(RovenfallAdministrationMenus.RPG_BOSS.get(), containerId, inventory, contents, 6);
        this.viewer = viewer;
        this.viewerId = viewer.getUUID();
        this.contents = contents;
        this.entryDomain = entryDomain;
        this.mode = entryDomain == AdministrationReadViewService.Domain.RPG
                ? Mode.RPG_PLAYERS : Mode.ENCOUNTERS;
        render();
        PlayerMenuNetwork.seedMenuSession(this, UUID.randomUUID());
    }

    public static boolean open(ServerPlayer player, AdministrationReadViewService.Domain domain) {
        if (player == null || domain == null
                || domain != AdministrationReadViewService.Domain.RPG
                        && domain != AdministrationReadViewService.Domain.ENCOUNTERS
                || !canView(player, domain)) {
            return false;
        }
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) -> new AdministrationRpgBossMenu(
                        containerId, inventory, (ServerPlayer) viewer, new SimpleContainer(MENU_SIZE), domain),
                Component.translatable("gui.rovenfall.admin.rpg_boss.title")))
                .ifPresent(ignored -> PlayerMenuNetwork.sendMenuIdentity(player));
        return true;
    }

    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !viewerId.equals(serverPlayer.getUUID())
                || slotIndex < 0 || slotIndex >= MENU_SIZE
                || !PlayerMenuNetwork.isPrimaryAction(buttonNum, input)) {
            return;
        }
        long gameTime = viewer.level().getGameTime();
        if (!PlayerDashboardMenu.canHandleClick(lastHandledGameTime, gameTime)) {
            return;
        }
        lastHandledGameTime = gameTime;
        if (!canView(viewer, entryDomain)) {
            denyAndClose();
            return;
        }
        if (slotIndex == REFRESH_SLOT) {
            render();
            return;
        }
        if (slotIndex == BACK_SLOT) {
            back();
            return;
        }
        switch (mode) {
            case RPG_PLAYERS -> clickRpgPlayers(slotIndex);
            case RPG_PLAYER -> clickRpgPlayer(slotIndex);
            case PROGRESSION -> clickProgression(slotIndex);
            case HISTORY -> clickHistory(slotIndex);
            case PROMOTIONS -> clickPromotions(slotIndex);
            case DEFINITIONS -> clickDefinitions(slotIndex);
            case RELOAD_STATUS -> clickReloadStatus(slotIndex);
            case MUTATIONS -> clickMutations(slotIndex);
            case ENCOUNTERS -> clickEncounters(slotIndex);
            case ENCOUNTER_DETAIL -> clickEncounterDetail(slotIndex);
            case PARTICIPANTS, REWARDS -> clickSimplePage(slotIndex);
            case FORM -> {
            }
            case PREVIEW -> {
                if (slotIndex == CONFIRM_SLOT) {
                    confirm();
                } else if (slotIndex == CANCEL_SLOT) {
                    cancelForm();
                }
            }
            case RESULT -> {
                if (slotIndex == CONFIRM_SLOT) {
                    finishResult();
                }
            }
        }
    }

    @Override
    public boolean applyTextInput(ServerPlayer player, String input) {
        if (!viewerId.equals(player.getUUID()) || input == null
                || input.length() > AdministrationTextInputMenu.MAX_INPUT_LENGTH
                || !canView(viewer, entryDomain)) {
            return false;
        }
        if (mode == Mode.FORM) {
            Optional<List<String>> structured = AdministrationStructuredFormCodec.decode(formType(formKind), input);
            return structured.isPresent()
                    ? parseForm(legacyFormInput(formKind, structured.orElseThrow()))
                    : parseForm(input);
        }
        if (!searchable(mode)) {
            return false;
        }
        if (input.length() > AdministrationReadViewService.MAX_QUERY_LENGTH) {
            formError = "query_too_long";
            render();
            return false;
        }
        query = input.strip();
        page = 0;
        formError = "";
        render();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive() && viewerId.equals(player.getUUID()) && canView(viewer, entryDomain);
    }

    static boolean canAdjustXp(AdminRole role) {
        return role == AdminRole.MODERATOR || role == AdminRole.OWNER;
    }

    static boolean canManageContent(AdminRole role) {
        return role == AdminRole.CONTENT_MANAGER || role == AdminRole.OWNER;
    }

    static boolean canRecoverBoss(AdminRole role) {
        return role == AdminRole.OWNER;
    }

    private static boolean canView(ServerPlayer player, AdministrationReadViewService.Domain domain) {
        return AdministrationControlCenterMenu.resolveRole(player).filter(domain::allowedFor).isPresent();
    }

    private AdminRole currentRole() {
        return AdministrationControlCenterMenu.resolveRole(viewer).orElse(null);
    }

    private void clickRpgPlayers(int slot) {
        if (slot == CENTER_SLOT) {
            mode = Mode.DEFINITIONS;
            query = "";
            page = 0;
        } else if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (contentIndex(slot) >= 0) {
            var resultPage = playersPage();
            int index = contentIndex(slot);
            if (index >= resultPage.entries().size()) {
                return;
            }
            selectedPlayer = resultPage.entries().get(index).playerId();
            mode = Mode.RPG_PLAYER;
            page = 0;
        } else {
            return;
        }
        render();
    }

    private void clickRpgPlayer(int slot) {
        if (selectedPlayer == null) {
            mode = Mode.RPG_PLAYERS;
        } else if (slot == PRIMARY_SLOT) {
            mode = Mode.PROGRESSION;
            page = 0;
        } else if (slot == SECONDARY_SLOT) {
            mode = Mode.HISTORY;
            query = "";
            page = 0;
            suspiciousOnly = false;
        } else if (slot == CENTER_SLOT && canManageContent(currentRole())) {
            mode = Mode.PROMOTIONS;
            query = "";
            page = 0;
        } else {
            return;
        }
        render();
    }

    private void clickProgression(int slot) {
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
            render();
            return;
        }
        if (slot == NEXT_SLOT) {
            page++;
            render();
            return;
        }
        int index = contentIndex(slot);
        var resultPage = progressionPage();
        if (index < 0 || index >= resultPage.entries().size()) {
            return;
        }
        var entry = resultPage.entries().get(index);
        if (entry.kind() == RpgAdministrationViewService.EntryKind.ACTIVITY && canAdjustXp(currentRole())) {
            selectedProgression = entry;
            enterForm(FormKind.XP, Mode.PROGRESSION);
        } else if (entry.kind() == RpgAdministrationViewService.EntryKind.CAREER
                && canManageContent(currentRole())) {
            selectedProgression = entry;
            enterForm(FormKind.SKILL_FULL, Mode.PROGRESSION);
        } else if (entry.kind() == RpgAdministrationViewService.EntryKind.SKILL
                && canManageContent(currentRole())) {
            selectedProgression = entry;
            enterForm(FormKind.SKILL_BRANCH, Mode.PROGRESSION);
        }
    }

    private void clickHistory(int slot) {
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (slot == CENTER_SLOT) {
            suspiciousOnly = !suspiciousOnly;
            page = 0;
        } else {
            return;
        }
        render();
    }

    private void clickPromotions(int slot) {
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (contentIndex(slot) >= 0 && canManageContent(currentRole())) {
            var resultPage = promotionsPage();
            int index = contentIndex(slot);
            if (index >= resultPage.entries().size()) {
                return;
            }
            selectedDefinition = resultPage.entries().get(index);
            enterForm(FormKind.PROMOTION, Mode.PROMOTIONS);
            return;
        } else {
            return;
        }
        render();
    }

    private void clickDefinitions(int slot) {
        if (slot == PRIMARY_SLOT && canManageContent(currentRole())) {
            enterForm(FormKind.RELOAD, Mode.DEFINITIONS);
            return;
        }
        if (slot == CENTER_SLOT) {
            mode = Mode.RELOAD_STATUS;
            page = 0;
        } else if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else {
            return;
        }
        render();
    }

    private void clickReloadStatus(int slot) {
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (slot == PRIMARY_SLOT && canManageContent(currentRole())) {
            enterForm(FormKind.RELOAD, Mode.RELOAD_STATUS);
            return;
        } else {
            return;
        }
        render();
    }

    private void clickMutations(int slot) {
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else {
            return;
        }
        render();
    }

    private void clickEncounters(int slot) {
        if (slot == PRIMARY_SLOT && canRecoverBoss(currentRole())) {
            enterForm(FormKind.BOSS_RECOVER, Mode.ENCOUNTERS);
            return;
        }
        if (slot == CENTER_SLOT) {
            mode = Mode.MUTATIONS;
            query = "";
            page = 0;
        } else if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (contentIndex(slot) >= 0) {
            var resultPage = encountersPage();
            int index = contentIndex(slot);
            if (index >= resultPage.entries().size()) {
                return;
            }
            selectedEncounter = resultPage.entries().get(index).encounterId();
            mode = Mode.ENCOUNTER_DETAIL;
            page = 0;
        } else {
            return;
        }
        render();
    }

    private void clickEncounterDetail(int slot) {
        if (selectedEncounter == null || BossAdministrationViewService.encounter(server(), selectedEncounter).isEmpty()) {
            mode = Mode.ENCOUNTERS;
        } else if (slot == PRIMARY_SLOT) {
            mode = Mode.PARTICIPANTS;
            query = "";
            page = 0;
        } else if (slot == SECONDARY_SLOT) {
            mode = Mode.REWARDS;
            query = "";
            page = 0;
        } else if (slot == DANGER_SLOT && canRecoverBoss(currentRole())) {
            enterForm(FormKind.BOSS_RESET, Mode.ENCOUNTER_DETAIL);
            return;
        } else {
            return;
        }
        render();
    }

    private void clickSimplePage(int slot) {
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else {
            return;
        }
        render();
    }

    private boolean parseForm(String input) {
        UUID transactionId = UUID.randomUUID();
        RpgPlayerSavedData rpg = RpgPlayerSavedData.get(server());
        RpgPlayerState playerState = selectedPlayer == null ? null : rpg.state(selectedPlayer);
        long revision = RpgDefinitionReloadListener.revision(server());
        pending = switch (formKind) {
            case XP -> AdministrationRpgBossFormParser.parseXp(input)
                    .filter(value -> selectedProgression != null && playerState != null)
                    .map(value -> new AdministrationRpgBossActionService.XpAction(
                            transactionId, selectedPlayer, playerState, revision, selectedProgression.id(),
                            value.delta(), value.reason()))
                    .orElse(null);
            case PROMOTION -> reason(input)
                    .filter(value -> selectedDefinition != null && playerState != null)
                    .map(value -> new AdministrationRpgBossActionService.PromotionAction(
                            transactionId, selectedPlayer, playerState, revision, selectedDefinition.id(), value))
                    .orElse(null);
            case SKILL_FULL, SKILL_BRANCH -> reason(input)
                    .filter(value -> selectedProgression != null && playerState != null)
                    .flatMap(value -> {
                        SkillResetPlan.Mode resetMode = formKind == FormKind.SKILL_FULL
                                ? SkillResetPlan.Mode.FULL : SkillResetPlan.Mode.BRANCH;
                        RpgSkillService.ResetPreparation prepared = RpgSkillService.prepareReset(
                                rpg, RpgDefinitionReloadListener.snapshot(server()), selectedPlayer,
                                resetMode, selectedProgression.id());
                        return prepared.status() == RpgSkillService.Status.SUCCESS
                                ? prepared.plan().map(plan -> new AdministrationRpgBossActionService.SkillResetAction(
                                        transactionId, selectedPlayer, playerState, revision, resetMode,
                                        selectedProgression.id(), plan, value))
                                : Optional.empty();
                    }).orElse(null);
            case BOSS_RESET -> reason(input)
                    .filter(value -> selectedEncounter != null)
                    .map(value -> new AdministrationRpgBossActionService.BossResetAction(
                            transactionId, selectedEncounter,
                            AdministrationRpgBossActionService.bossResetEvidence(server(), selectedEncounter), value))
                    .orElse(null);
            case BOSS_RECOVER -> reason(input)
                    .map(value -> new AdministrationRpgBossActionService.BossRecoverAction(
                            transactionId, AdministrationRpgBossActionService.bossRecoveryEvidence(server()), value))
                    .orElse(null);
            case RELOAD -> reason(input)
                    .map(value -> new AdministrationRpgBossActionService.ReloadAction(
                            transactionId, revision, MobContentReloadListener.snapshot(server()), value))
                    .orElse(null);
        };
        if (pending == null || !AdministrationRpgBossActionService.allowed(currentRole(), pending)
                || !AdministrationRpgBossActionService.fresh(server(), pending)) {
            formError = "invalid_form";
            pending = null;
            render();
            return false;
        }
        formError = "";
        mode = Mode.PREVIEW;
        render();
        return true;
    }

    private static Optional<String> reason(String input) {
        return AdministrationRpgBossFormParser.parseReasonOnly(input)
                .map(AdministrationRpgBossFormParser.ReasonForm::reason);
    }

    private static String legacyFormInput(FormKind kind, List<String> values) {
        return kind == FormKind.XP
                ? values.get(0) + " | " + values.get(1)
                : " | " + values.getFirst();
    }

    private void confirm() {
        if (pending == null) {
            return;
        }
        if (!PlayerMenuNetwork.beginMutation(viewerId, viewer.level().getGameTime())) {
            result = new AdministrationRpgBossActionService.Result(
                    AdministrationRpgBossActionService.Status.FAILED, "rate_limited", pending.transactionId());
        } else {
            result = AdministrationRpgBossActionService.execute(viewer, pending);
        }
        mode = Mode.RESULT;
        render();
    }

    private void enterForm(FormKind kind, Mode destination) {
        formKind = kind;
        returnMode = destination;
        formError = "";
        pending = null;
        mode = Mode.FORM;
        render();
    }

    private void cancelForm() {
        pending = null;
        formKind = null;
        mode = returnMode;
        render();
    }

    private void finishResult() {
        boolean succeeded = result != null && result.succeeded();
        if (succeeded && pending instanceof AdministrationRpgBossActionService.ReloadAction) {
            returnMode = Mode.RELOAD_STATUS;
            page = 0;
        } else if (succeeded && pending instanceof AdministrationRpgBossActionService.BossResetAction
                && BossAdministrationViewService.encounter(server(), selectedEncounter).isEmpty()) {
            returnMode = Mode.ENCOUNTERS;
            selectedEncounter = null;
        }
        pending = null;
        formKind = null;
        result = null;
        mode = returnMode;
        render();
    }

    private void back() {
        switch (mode) {
            case RPG_PLAYERS, ENCOUNTERS -> AdministrationControlCenterMenu.open(viewer);
            case RPG_PLAYER -> {
                selectedPlayer = null;
                mode = Mode.RPG_PLAYERS;
                render();
            }
            case PROGRESSION, HISTORY, PROMOTIONS -> {
                selectedProgression = null;
                selectedDefinition = null;
                query = "";
                page = 0;
                mode = Mode.RPG_PLAYER;
                render();
            }
            case DEFINITIONS -> {
                query = "";
                page = 0;
                mode = Mode.RPG_PLAYERS;
                render();
            }
            case RELOAD_STATUS -> {
                page = 0;
                mode = Mode.DEFINITIONS;
                render();
            }
            case MUTATIONS -> {
                query = "";
                page = 0;
                mode = Mode.ENCOUNTERS;
                render();
            }
            case ENCOUNTER_DETAIL -> {
                selectedEncounter = null;
                mode = Mode.ENCOUNTERS;
                render();
            }
            case PARTICIPANTS, REWARDS -> {
                query = "";
                page = 0;
                mode = Mode.ENCOUNTER_DETAIL;
                render();
            }
            case FORM, PREVIEW -> cancelForm();
            case RESULT -> finishResult();
        }
    }

    private void render() {
        contents.clearContent();
        if (!canView(viewer, entryDomain)) {
            denyAndClose();
            return;
        }
        switch (mode) {
            case RPG_PLAYERS -> renderRpgPlayers();
            case RPG_PLAYER -> renderRpgPlayer();
            case PROGRESSION -> renderProgression();
            case HISTORY -> renderHistory();
            case PROMOTIONS -> renderPromotions();
            case DEFINITIONS -> renderDefinitions();
            case RELOAD_STATUS -> renderReloadStatus();
            case MUTATIONS -> renderMutations();
            case ENCOUNTERS -> renderEncounters();
            case ENCOUNTER_DETAIL -> renderEncounterDetail();
            case PARTICIPANTS -> renderParticipants();
            case REWARDS -> renderRewards();
            case FORM -> renderForm();
            case PREVIEW -> renderPreview();
            case RESULT -> renderResult();
        }
        contents.setItem(REFRESH_SLOT, icon(
                Items.COMPASS, "gui.rovenfall.admin.refresh", "gui.rovenfall.admin.click"));
        broadcastChanges();
    }

    private void renderRpgPlayers() {
        var resultPage = playersPage();
        renderHeader(Items.PLAYER_HEAD, "gui.rovenfall.admin.rpg_boss.players",
                resultPage.page(), resultPage.totalPages(), resultPage.totalEntries(), resultPage.truncated());
        markSearchHeader();
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            contents.setItem(CONTENT_START + index, AdministrationPlayerHead.create(
                    row.playerId(), row.displayName(),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.active_career",
                            row.activeCareer().<Component>map(this::definitionName)
                                    .orElseGet(() -> Component.translatable("gui.rovenfall.admin.rpg_boss.none"))),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.progress_counts",
                            row.activities(), row.careers(), row.learnedSkills()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.evidence_count", row.evidenceEntries()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.uuid", row.playerId().toString())));
        }
        contents.setItem(CENTER_SLOT, icon(
                Items.BOOKSHELF, "gui.rovenfall.admin.rpg_boss.definitions", "gui.rovenfall.admin.click"));
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderRpgPlayer() {
        RpgPlayerState state = selectedPlayer == null
                ? RpgPlayerState.EMPTY : RpgPlayerSavedData.get(server()).state(selectedPlayer);
        String displayName = selectedPlayer == null ? "" : PlatformSavedData.get(server()).playerRecord(selectedPlayer)
                .flatMap(PlayerRecord::displayName).orElse("");
        contents.setItem(4, AdministrationPlayerHead.create(
                selectedPlayer == null ? viewerId : selectedPlayer, displayName,
                Component.translatable("gui.rovenfall.admin.rpg_boss.field.active_career",
                        state.activeCareer().<Component>map(this::definitionName)
                                .orElseGet(() -> Component.translatable("gui.rovenfall.admin.rpg_boss.none"))),
                Component.translatable("gui.rovenfall.admin.rpg_boss.field.progress_counts",
                        state.activityXp().size(), state.careers().size(),
                        state.careers().values().stream().mapToInt(value -> value.learnedSkills().size()).sum()),
                Component.translatable("gui.rovenfall.admin.rpg_boss.field.evidence_count",
                        state.provenance().size()),
                selectedPlayer == null ? Component.empty()
                        : Component.translatable("gui.rovenfall.admin.rpg_boss.field.uuid", selectedPlayer.toString())));
        contents.setItem(PRIMARY_SLOT, icon(
                Items.EXPERIENCE_BOTTLE, "gui.rovenfall.admin.rpg_boss.progression", "gui.rovenfall.admin.click"));
        contents.setItem(SECONDARY_SLOT, icon(
                Items.WRITABLE_BOOK, "gui.rovenfall.admin.rpg_boss.history", "gui.rovenfall.admin.click"));
        if (canManageContent(currentRole())) {
            contents.setItem(CENTER_SLOT, icon(
                    Items.NETHER_STAR, "gui.rovenfall.admin.rpg_boss.promotion", "gui.rovenfall.admin.click"));
        }
        renderBack();
    }

    private void renderProgression() {
        var resultPage = progressionPage();
        renderHeader(Items.EXPERIENCE_BOTTLE, "gui.rovenfall.admin.rpg_boss.progression",
                resultPage.page(), resultPage.totalPages(), resultPage.totalEntries(), false);
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var entry = resultPage.entries().get(index);
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    progressionItem(entry.kind()), definitionName(entry.id()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.kind",
                            Component.translatable(kindKey(entry.kind()))),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.value",
                            entry.value(), entry.rank(), entry.points()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.owner",
                            entry.owner().<Component>map(this::definitionName)
                                    .orElseGet(() -> Component.translatable("gui.rovenfall.admin.rpg_boss.none"))),
                    progressionAction(entry)));
        }
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderHistory() {
        var resultPage = historyPage();
        renderHeader(Items.WRITABLE_BOOK, "gui.rovenfall.admin.rpg_boss.history",
                resultPage.page(), resultPage.totalPages(), resultPage.totalEntries(), false);
        markSearchHeader();
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            var evidence = row.evidence();
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    row.suspicions().isEmpty() ? Items.PAPER : Items.REDSTONE,
                    definitionName(evidence.target()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.award",
                            evidence.amount(), evidence.timestamp()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.source", evidence.source()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.suspicion",
                            suspicion(row.suspicions())),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.transaction",
                            evidence.transactionId().toString())));
        }
        contents.setItem(CENTER_SLOT, PlayerDashboardMenu.icon(
                suspiciousOnly ? Items.REDSTONE : Items.PAPER,
                Component.translatable(suspiciousOnly
                        ? "gui.rovenfall.admin.rpg_boss.filter.suspicious"
                        : "gui.rovenfall.admin.rpg_boss.filter.all"),
                Component.translatable("gui.rovenfall.admin.click")));
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderPromotions() {
        var resultPage = promotionsPage();
        renderHeader(Items.NETHER_STAR, "gui.rovenfall.admin.rpg_boss.promotion",
                resultPage.page(), resultPage.totalPages(), resultPage.totalEntries(), false);
        markSearchHeader();
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    Items.NETHER_STAR, definitionName(row.id()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.promotion.select"),
                    Component.literal(row.id().toString())));
        }
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderDefinitions() {
        var resultPage = definitionsPage();
        renderHeader(Items.BOOKSHELF, "gui.rovenfall.admin.rpg_boss.definitions",
                resultPage.page(), resultPage.totalPages(), resultPage.totalEntries(), false);
        markSearchHeader();
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            Component title = row.translationKey().isBlank()
                    ? Component.translatable("gui.rovenfall.admin.rpg_boss.unknown_definition")
                    : Component.translatable(row.translationKey());
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    definitionItem(row.kind()), title,
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.kind",
                            Component.translatable(definitionKindKey(row.kind()))),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.definition_id", row.id().toString())));
        }
        if (canManageContent(currentRole())) {
            contents.setItem(PRIMARY_SLOT, icon(
                    Items.EMERALD, "gui.rovenfall.admin.rpg_boss.reload", "gui.rovenfall.admin.rpg_boss.reload_hint"));
        }
        contents.setItem(CENTER_SLOT, icon(
                Items.CLOCK, "gui.rovenfall.admin.rpg_boss.reload_status", "gui.rovenfall.admin.click"));
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderReloadStatus() {
        var snapshot = AdministrationContentReloadService.snapshot(server());
        contents.setItem(4, PlayerDashboardMenu.icon(
                snapshot.status() == AdministrationContentReloadService.Status.SUCCESS ? Items.EMERALD
                        : snapshot.status() == AdministrationContentReloadService.Status.FAILED ? Items.REDSTONE
                        : Items.CLOCK,
                Component.translatable("gui.rovenfall.admin.rpg_boss.reload_status"),
                Component.translatable("gui.rovenfall.admin.rpg_boss.field.reload_state",
                        Component.translatable(reloadStatusKey(snapshot.status()))),
                Component.translatable("gui.rovenfall.admin.rpg_boss.field.transaction",
                        snapshot.transactionId().equals(new UUID(0L, 0L))
                                ? Component.translatable("gui.rovenfall.admin.rpg_boss.none")
                                : Component.literal(snapshot.transactionId().toString())),
                Component.translatable("gui.rovenfall.admin.rpg_boss.field.reload_times",
                        snapshot.requestedAtEpochMillis(), snapshot.completedAtEpochMillis()),
                Component.translatable("gui.rovenfall.admin.rpg_boss.field.problem_count",
                        snapshot.problems().size())));
        int totalPages = pages(snapshot.problems().size());
        int from = Math.min(page * CONTENT_SIZE, snapshot.problems().size());
        int to = Math.min(from + CONTENT_SIZE, snapshot.problems().size());
        for (int index = from; index < to; index++) {
            var problem = snapshot.problems().get(index);
            contents.setItem(CONTENT_START + index - from, PlayerDashboardMenu.icon(
                    Items.REDSTONE, Component.translatable("gui.rovenfall.admin.rpg_boss.content_problem"),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.problem_source",
                            Component.translatable(problemSourceKey(problem.source()))),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.problem_file",
                            problem.file().toString()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.problem_cause", problem.cause())));
        }
        if (canManageContent(currentRole())) {
            contents.setItem(PRIMARY_SLOT, icon(
                    Items.EMERALD, "gui.rovenfall.admin.rpg_boss.reload", "gui.rovenfall.admin.rpg_boss.reload_hint"));
        }
        renderPagination(page, totalPages);
    }

    private void renderMutations() {
        var resultPage = mutationsPage();
        renderHeader(Items.FERMENTED_SPIDER_EYE, "gui.rovenfall.admin.rpg_boss.mutations",
                resultPage.page(), resultPage.totalPages(), resultPage.totalEntries(), resultPage.truncated());
        markSearchHeader();
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    Items.FERMENTED_SPIDER_EYE,
                    Component.translatable("gui.rovenfall.admin.rpg_boss.mutation_entry"),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.entity", row.entityType().toString()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.location",
                            row.dimension().toString(), row.position().toShortString()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.mutations",
                            row.mutations().stream().map(Identifier::toString).toList().toString())));
        }
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderEncounters() {
        var resultPage = encountersPage();
        renderHeader(Items.DRAGON_HEAD, "gui.rovenfall.admin.rpg_boss.encounters",
                resultPage.page(), resultPage.totalPages(), resultPage.totalEntries(), resultPage.truncated());
        markSearchHeader();
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    Items.DRAGON_HEAD, definitionName(row.bossId()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.stage",
                            Component.translatable(stageKey(row.stage())), row.phaseIndex()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.participants", row.participantCount()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.arena", enabled(row.arenaProtected())),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.encounter",
                            row.encounterId().toString())));
        }
        if (canRecoverBoss(currentRole())) {
            contents.setItem(PRIMARY_SLOT, icon(
                    Items.TOTEM_OF_UNDYING, "gui.rovenfall.admin.rpg_boss.recover_all",
                    "gui.rovenfall.admin.rpg_boss.irreversible"));
        }
        contents.setItem(CENTER_SLOT, icon(
                Items.FERMENTED_SPIDER_EYE, "gui.rovenfall.admin.rpg_boss.mutations", "gui.rovenfall.admin.click"));
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderEncounterDetail() {
        var row = selectedEncounter == null ? null
                : BossAdministrationViewService.encounter(server(), selectedEncounter).orElse(null);
        if (row == null) {
            mode = Mode.ENCOUNTERS;
            renderEncounters();
            return;
        }
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.DRAGON_HEAD, definitionName(row.bossId()),
                Component.translatable("gui.rovenfall.admin.rpg_boss.field.location",
                        row.dimension().toString(), row.center().toShortString()),
                Component.translatable("gui.rovenfall.admin.rpg_boss.field.stage",
                        Component.translatable(stageKey(row.stage())), row.phaseIndex()),
                Component.translatable("gui.rovenfall.admin.rpg_boss.field.arena", enabled(row.arenaProtected())),
                Component.translatable("gui.rovenfall.admin.rpg_boss.field.encounter", row.encounterId().toString()),
                Component.translatable("gui.rovenfall.admin.rpg_boss.field.entity", row.entityId().toString())));
        contents.setItem(PRIMARY_SLOT, icon(
                Items.PLAYER_HEAD, "gui.rovenfall.admin.rpg_boss.participants", "gui.rovenfall.admin.click"));
        contents.setItem(SECONDARY_SLOT, icon(
                Items.CHEST, "gui.rovenfall.admin.rpg_boss.rewards", "gui.rovenfall.admin.click"));
        if (canRecoverBoss(currentRole())) {
            contents.setItem(DANGER_SLOT, icon(
                    Items.BARRIER, "gui.rovenfall.admin.rpg_boss.reset_encounter",
                    "gui.rovenfall.admin.rpg_boss.irreversible"));
        }
        renderBack();
    }

    private void renderParticipants() {
        var resultPage = participantsPage();
        renderHeader(Items.PLAYER_HEAD, "gui.rovenfall.admin.rpg_boss.participants",
                resultPage.page(), resultPage.totalPages(), resultPage.totalEntries(), resultPage.truncated());
        markSearchHeader();
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            long basisPoints = row.totalPoints() == 0 ? 0 : row.points() * 10_000 / row.totalPoints();
            contents.setItem(CONTENT_START + index, AdministrationPlayerHead.create(
                    row.playerId(), playerDisplayName(row.playerId()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.contribution",
                            row.points(), row.totalPoints(), basisPoints),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.uuid", row.playerId().toString())));
        }
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderRewards() {
        var resultPage = rewardsPage();
        renderHeader(Items.CHEST, "gui.rovenfall.admin.rpg_boss.rewards",
                resultPage.page(), resultPage.totalPages(), resultPage.totalEntries(), resultPage.truncated());
        markSearchHeader();
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            String displayName = playerDisplayName(row.playerId());
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    Items.CHEST, displayName.isBlank()
                            ? Component.translatable("gui.rovenfall.player.unknown_player")
                            : Component.literal(displayName),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.transaction",
                            row.transactionId().toString()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.contribution",
                            row.points(), row.totalPoints(),
                            row.totalPoints() == 0 ? 0 : row.points() * 10_000 / row.totalPoints()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.reward",
                            row.currency(), row.experience(), row.itemStacks()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.reward_phase",
                            Component.translatable(rewardPhaseKey(row.phase())), row.cooldownUntilEpochMillis()),
                    Component.translatable("gui.rovenfall.admin.rpg_boss.field.uuid", row.playerId().toString())));
        }
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderForm() {
        ItemStack header = PlayerDashboardMenu.icon(
                Items.WRITABLE_BOOK, Component.translatable("gui.rovenfall.admin.rpg_boss.form.title"),
                Component.translatable(formHint(formKind)),
                Component.translatable("gui.rovenfall.admin.rpg_boss.form.submit"),
                formError.isBlank() ? Component.empty()
                        : Component.translatable("gui.rovenfall.admin.rpg_boss.error", inputError(formError)));
        AdministrationFormType type = formType(formKind);
        AdministrationFormMarker.write(header, new AdministrationFormMarker(type, type.defaults()));
        if (!formError.isBlank()) {
            AdministrationFormMarker.writeError(header);
        }
        contents.setItem(4, header);
        renderBack();
    }

    private void renderPreview() {
        List<Component> lines = previewLines(pending);
        contents.setItem(4, PlayerDashboardMenu.icon(
                irreversible(pending) ? Items.TNT : Items.WRITABLE_BOOK,
                Component.translatable(irreversible(pending)
                        ? "gui.rovenfall.admin.rpg_boss.preview.irreversible"
                        : "gui.rovenfall.admin.rpg_boss.preview"),
                lines.toArray(Component[]::new)));
        if (AdministrationRpgBossActionService.allowed(currentRole(), pending)) {
            contents.setItem(CONFIRM_SLOT, icon(
                    Items.EMERALD, "gui.rovenfall.admin.rpg_boss.confirm",
                    "gui.rovenfall.admin.rpg_boss.confirm_fresh"));
        }
        contents.setItem(CANCEL_SLOT, icon(
                Items.BARRIER, "gui.rovenfall.admin.rpg_boss.cancel", "gui.rovenfall.admin.click"));
        renderBack();
    }

    private void renderResult() {
        boolean succeeded = result != null && result.succeeded();
        contents.setItem(4, PlayerDashboardMenu.icon(
                succeeded ? Items.EMERALD : Items.BARRIER,
                Component.translatable(succeeded
                        ? "gui.rovenfall.admin.rpg_boss.result.success"
                        : "gui.rovenfall.admin.rpg_boss.result.failed"),
                resultDetail(result == null ? "unknown" : result.detail()),
                Component.translatable("gui.rovenfall.admin.rpg_boss.field.transaction",
                        result == null || result.transactionId() == null
                                ? Component.translatable("gui.rovenfall.admin.rpg_boss.none")
                                : Component.literal(result.transactionId().toString()))));
        contents.setItem(CONFIRM_SLOT, icon(
                Items.ARROW, "gui.rovenfall.admin.rpg_boss.continue", "gui.rovenfall.admin.click"));
        renderBack();
    }

    private List<Component> previewLines(AdministrationRpgBossActionService.PendingAction action) {
        List<Component> lines = new ArrayList<>();
        if (action instanceof AdministrationRpgBossActionService.XpAction value) {
            long before = value.expectedPlayerState().activityXp().getOrDefault(value.activityId(), 0L);
            lines.add(Component.translatable("gui.rovenfall.admin.rpg_boss.preview.xp",
                    playerName(value.playerId()), definitionName(value.activityId()), before, before + value.delta()));
        } else if (action instanceof AdministrationRpgBossActionService.PromotionAction value) {
            lines.add(Component.translatable("gui.rovenfall.admin.rpg_boss.preview.promotion",
                    playerName(value.playerId()), definitionName(value.careerId())));
        } else if (action instanceof AdministrationRpgBossActionService.SkillResetAction value) {
            lines.add(Component.translatable("gui.rovenfall.admin.rpg_boss.preview.skill_reset",
                    playerName(value.playerId()), Component.translatable(resetModeKey(value.mode())),
                    definitionName(value.target()), value.expectedPlan().removedSkills().size(),
                    value.expectedPlan().refundedPoints()));
        } else if (action instanceof AdministrationRpgBossActionService.BossResetAction value) {
            lines.add(Component.translatable("gui.rovenfall.admin.rpg_boss.preview.boss_reset",
                    value.encounterId().toString(), value.expectedEvidence().rewards().size()));
        } else if (action instanceof AdministrationRpgBossActionService.BossRecoverAction value) {
            lines.add(Component.translatable("gui.rovenfall.admin.rpg_boss.preview.boss_recover",
                    value.expectedEvidence().encounters().size(), value.expectedEvidence().pendingRewards().size(),
                    value.expectedEvidence().orphanArenas().size()));
        } else if (action instanceof AdministrationRpgBossActionService.ReloadAction value) {
            var rpg = RpgDefinitionReloadListener.snapshot(server());
            lines.add(Component.translatable("gui.rovenfall.admin.rpg_boss.preview.reload",
                    value.expectedRpgRevision(), rpg.activities().size(), rpg.careers().size(),
                    rpg.skills().size(), value.expectedMobSnapshot().size()));
        }
        lines.add(Component.translatable("gui.rovenfall.admin.rpg_boss.preview.reason", action.reason()));
        lines.add(Component.translatable("gui.rovenfall.admin.rpg_boss.preview.transaction",
                action.transactionId().toString()));
        return lines;
    }

    private AdministrationRpgBossViewService.Page<AdministrationRpgBossViewService.PlayerRow> playersPage() {
        return AdministrationRpgBossViewService.players(
                server(), viewerId, authorizationOverride(), query, page);
    }

    private AdministrationRpgBossViewService.Page<AdministrationRpgBossViewService.DefinitionRow> definitionsPage() {
        return AdministrationRpgBossViewService.definitions(
                server(), viewerId, authorizationOverride(), Optional.empty(), query, page);
    }

    private AdministrationRpgBossViewService.Page<AdministrationRpgBossViewService.DefinitionRow> promotionsPage() {
        return AdministrationRpgBossViewService.promotionCandidates(
                server(), viewerId, authorizationOverride(), selectedPlayer, query, page);
    }

    private RpgAdministrationViewService.ProgressionPage progressionPage() {
        return AdministrationRpgBossViewService.progression(
                server(), viewerId, authorizationOverride(), selectedPlayer, page);
    }

    private RpgAdministrationViewService.AwardPage historyPage() {
        if (query.isBlank()) {
            return AdministrationRpgBossViewService.history(
                    server(), viewerId, authorizationOverride(), selectedPlayer, suspiciousOnly, page);
        }
        List<RpgAdministrationViewService.AwardEvidence> matches = new ArrayList<>();
        for (int sourcePage = 0; sourcePage * AdministrationRpgBossViewService.PAGE_SIZE
                < RpgPlayerState.MAX_PROVENANCE; sourcePage++) {
            var source = AdministrationRpgBossViewService.history(
                    server(), viewerId, authorizationOverride(), selectedPlayer, suspiciousOnly, sourcePage);
            source.entries().stream()
                    .filter(entry -> historySearchText(entry).contains(query.strip().toLowerCase(java.util.Locale.ROOT)))
                    .forEach(matches::add);
            if (sourcePage + 1 >= source.totalPages()) {
                break;
            }
        }
        int totalPages = matches.isEmpty() ? 0 : pages(matches.size());
        int from = Math.min(page * CONTENT_SIZE, matches.size());
        int to = Math.min(from + CONTENT_SIZE, matches.size());
        return new RpgAdministrationViewService.AwardPage(
                page, totalPages, matches.size(), matches.subList(from, to));
    }

    private BossAdministrationViewService.Page<BossAdministrationViewService.MutationRow> mutationsPage() {
        return BossAdministrationViewService.activeMutations(server(), query, page, CONTENT_SIZE);
    }

    private BossAdministrationViewService.Page<BossAdministrationViewService.EncounterRow> encountersPage() {
        return BossAdministrationViewService.encounters(server(), query, page, CONTENT_SIZE);
    }

    private BossAdministrationViewService.Page<BossAdministrationViewService.ParticipantRow> participantsPage() {
        return BossAdministrationViewService.participants(server(), selectedEncounter, query, page, CONTENT_SIZE);
    }

    private BossAdministrationViewService.Page<BossAdministrationViewService.RewardRow> rewardsPage() {
        return BossAdministrationViewService.rewards(server(), selectedEncounter, query, page, CONTENT_SIZE);
    }

    private static String historySearchText(RpgAdministrationViewService.AwardEvidence entry) {
        var evidence = entry.evidence();
        return (evidence.target() + " " + evidence.source() + " " + evidence.transactionId() + " "
                + evidence.amount() + " " + entry.suspicions()).toLowerCase(java.util.Locale.ROOT);
    }

    private void renderHeader(
            Item item, String titleKey, int currentPage, int totalPages, int totalEntries, boolean truncated) {
        contents.setItem(4, PlayerDashboardMenu.icon(
                item, Component.translatable(titleKey),
                Component.translatable("gui.rovenfall.admin.page", currentPage + 1, Math.max(1, totalPages)),
                Component.translatable("gui.rovenfall.admin.total", totalEntries),
                Component.translatable(truncated
                        ? "gui.rovenfall.admin.truncated" : "gui.rovenfall.admin.complete"),
                Component.translatable("gui.rovenfall.admin.query", query.isBlank() ? "*" : query),
                formError.isBlank() ? Component.empty()
                        : Component.translatable("gui.rovenfall.admin.rpg_boss.error", inputError(formError))));
    }

    private void markSearchHeader() {
        ItemStack header = contents.getItem(4);
        AdministrationFormMarker.writeSearch(header);
        contents.setItem(4, header);
    }

    private void renderPagination(int currentPage, int totalPages) {
        renderBack();
        if (currentPage > 0) {
            contents.setItem(PREVIOUS_SLOT, icon(
                    Items.ARROW, "gui.rovenfall.admin.previous", "gui.rovenfall.admin.click"));
        }
        if (currentPage + 1 < totalPages) {
            contents.setItem(NEXT_SLOT, icon(
                    Items.ARROW, "gui.rovenfall.admin.next", "gui.rovenfall.admin.click"));
        }
    }

    private void renderBack() {
        contents.setItem(BACK_SLOT, icon(
                Items.ARROW, "gui.rovenfall.admin.back", "gui.rovenfall.admin.click"));
    }

    private org.dldyou.rovenfall.rpg.RpgDefinitionSnapshot definitions() {
        return RpgDefinitionReloadListener.snapshot(server());
    }

    private Component definitionName(Identifier id) {
        var rpg = definitions();
        var mobs = MobContentReloadListener.snapshot(server());
        return rpg.activity(id).<Component>map(value -> Component.translatable(value.translationKey()))
                .or(() -> rpg.career(id).<Component>map(value -> Component.translatable(value.translationKey())))
                .or(() -> rpg.skill(id).<Component>map(value -> Component.translatable(value.translationKey())))
                .or(() -> mobs.mob(id).<Component>map(value -> Component.translatable(value.translationKey())))
                .or(() -> mobs.mutation(id).<Component>map(value -> Component.translatable(value.translationKey())))
                .or(() -> mobs.boss(id).<Component>map(value -> Component.translatable(value.translationKey())))
                .orElseGet(() -> Component.translatable("gui.rovenfall.admin.rpg_boss.unknown_definition"));
    }

    private String playerDisplayName(UUID playerId) {
        return PlatformSavedData.get(server()).playerRecord(playerId)
                .flatMap(PlayerRecord::displayName)
                .orElseGet(() -> {
                    ServerPlayer online = server().getPlayerList().getPlayer(playerId);
                    return online == null ? "" : online.getGameProfile().name();
                });
    }

    private Component playerName(UUID playerId) {
        String displayName = playerDisplayName(playerId);
        return displayName.isBlank()
                ? Component.translatable("gui.rovenfall.player.unknown_player")
                : Component.literal(displayName);
    }

    private Component progressionAction(RpgAdministrationViewService.ProgressionEntry entry) {
        if (entry.kind() == RpgAdministrationViewService.EntryKind.ACTIVITY && canAdjustXp(currentRole())) {
            return Component.translatable("gui.rovenfall.admin.rpg_boss.action.adjust_xp");
        }
        if (entry.kind() == RpgAdministrationViewService.EntryKind.CAREER && canManageContent(currentRole())) {
            return Component.translatable("gui.rovenfall.admin.rpg_boss.action.full_reset");
        }
        if (entry.kind() == RpgAdministrationViewService.EntryKind.SKILL && canManageContent(currentRole())) {
            return Component.translatable("gui.rovenfall.admin.rpg_boss.action.branch_reset");
        }
        return Component.empty();
    }

    private boolean authorizationOverride() {
        return state().roleOf(viewerId).isEmpty();
    }

    private PlatformSavedData state() {
        return PlatformSavedData.get(server());
    }

    private net.minecraft.server.MinecraftServer server() {
        return viewer.level().getServer();
    }

    private void denyAndClose() {
        viewer.sendSystemMessage(Component.translatable("gui.rovenfall.admin.denied"));
        viewer.closeContainer();
    }

    private static int contentIndex(int slot) {
        return slot >= CONTENT_START && slot < CONTENT_START + CONTENT_SIZE ? slot - CONTENT_START : -1;
    }

    private static int pages(int entries) {
        return entries == 0 ? 0 : (entries + CONTENT_SIZE - 1) / CONTENT_SIZE;
    }

    static boolean searchable(Mode mode) {
        return mode == Mode.RPG_PLAYERS || mode == Mode.HISTORY || mode == Mode.PROMOTIONS
                || mode == Mode.DEFINITIONS || mode == Mode.MUTATIONS || mode == Mode.ENCOUNTERS
                || mode == Mode.PARTICIPANTS || mode == Mode.REWARDS;
    }

    private static ItemStack icon(Item item, String title, String lore) {
        return PlayerDashboardMenu.icon(item, Component.translatable(title), Component.translatable(lore));
    }

    private static Component enabled(boolean value) {
        return Component.translatable(value
                ? "gui.rovenfall.player.enabled" : "gui.rovenfall.player.disabled");
    }

    private static Component suspicion(java.util.Set<RpgAdministrationViewService.Suspicion> values) {
        if (values.isEmpty()) {
            return Component.translatable("gui.rovenfall.admin.rpg_boss.suspicion.none");
        }
        Component result = Component.empty();
        boolean first = true;
        for (var value : values.stream().sorted().toList()) {
            if (!first) {
                result = result.copy().append(Component.literal(", "));
            }
            result = result.copy().append(Component.translatable(
                    "gui.rovenfall.admin.rpg_boss.suspicion."
                            + value.name().toLowerCase(java.util.Locale.ROOT)));
            first = false;
        }
        return result;
    }

    private static Component inputError(String value) {
        return Component.translatable("gui.rovenfall.admin.rpg_boss.form.error."
                + ("query_too_long".equals(value) ? "query_too_long" : "invalid_form"));
    }

    private static Component resultDetail(String detail) {
        String suffix = switch (detail) {
            case "success" -> "success";
            case "reload_requested" -> "reload_requested";
            case "duplicate_transaction" -> "duplicate";
            case "stale_confirmation" -> "stale";
            case "unauthorized" -> "unauthorized";
            case "rate_limited" -> "rate_limited";
            case "read_only", "read_only_schema" -> "read_only";
            case "unknown_activity", "unknown_career", "not_found" -> "not_found";
            case "nothing_to_reset", "already_promoted" -> "no_change";
            case "state_conflict", "transaction_conflict", "reload_in_progress", "in_progress" -> "conflict";
            case "rewards_pending", "recovery_pending" -> "recovery_pending";
            case "reload_failed", "failed" -> "reload_failed";
            default -> "invalid";
        };
        return Component.translatable("gui.rovenfall.admin.rpg_boss.result.detail." + suffix);
    }

    private static boolean irreversible(AdministrationRpgBossActionService.PendingAction action) {
        return action instanceof AdministrationRpgBossActionService.SkillResetAction
                || action instanceof AdministrationRpgBossActionService.BossResetAction
                || action instanceof AdministrationRpgBossActionService.BossRecoverAction;
    }

    private static String formHint(FormKind kind) {
        return kind == FormKind.XP
                ? "gui.rovenfall.admin.rpg_boss.form.xp"
                : "gui.rovenfall.admin.rpg_boss.form.reason";
    }

    private static AdministrationFormType formType(FormKind kind) {
        return switch (kind) {
            case XP -> AdministrationFormType.RPG_XP;
            case PROMOTION -> AdministrationFormType.RPG_PROMOTION;
            case SKILL_FULL -> AdministrationFormType.RPG_SKILL_FULL_RESET;
            case SKILL_BRANCH -> AdministrationFormType.RPG_SKILL_BRANCH_RESET;
            case BOSS_RESET -> AdministrationFormType.RPG_BOSS_RESET;
            case BOSS_RECOVER -> AdministrationFormType.RPG_BOSS_RECOVER;
            case RELOAD -> AdministrationFormType.RPG_RELOAD;
        };
    }

    private static String kindKey(RpgAdministrationViewService.EntryKind kind) {
        return "gui.rovenfall.admin.rpg_boss.progression.kind."
                + kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String definitionKindKey(AdministrationRpgBossViewService.DefinitionKind kind) {
        return "gui.rovenfall.admin.rpg_boss.definition.kind."
                + kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String stageKey(BossEncounterState.Stage stage) {
        return "gui.rovenfall.admin.rpg_boss.stage." + stage.getSerializedName();
    }

    private static String rewardPhaseKey(BossRewardOperation.Phase phase) {
        return "gui.rovenfall.admin.rpg_boss.reward.phase." + phase.getSerializedName();
    }

    private static String resetModeKey(SkillResetPlan.Mode mode) {
        return "gui.rovenfall.admin.rpg_boss.reset." + mode.getSerializedName();
    }

    private static String reloadStatusKey(AdministrationContentReloadService.Status status) {
        return "gui.rovenfall.admin.rpg_boss.reload.status."
                + status.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String problemSourceKey(AdministrationContentReloadService.Source source) {
        return "gui.rovenfall.admin.rpg_boss.reload.source."
                + source.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static Item progressionItem(RpgAdministrationViewService.EntryKind kind) {
        return switch (kind) {
            case ACTIVITY -> Items.EXPERIENCE_BOTTLE;
            case CAREER -> Items.NETHER_STAR;
            case SKILL -> Items.ENCHANTED_BOOK;
            case SLOT -> Items.ITEM_FRAME;
            case COOLDOWN -> Items.CLOCK;
        };
    }

    private static Item definitionItem(AdministrationRpgBossViewService.DefinitionKind kind) {
        return switch (kind) {
            case ACTIVITY -> Items.EXPERIENCE_BOTTLE;
            case CAREER -> Items.NETHER_STAR;
            case SKILL -> Items.ENCHANTED_BOOK;
            case MOB -> Items.ZOMBIE_HEAD;
            case MUTATION -> Items.FERMENTED_SPIDER_EYE;
            case ARENA -> Items.STRUCTURE_VOID;
            case CONTRIBUTION -> Items.PLAYER_HEAD;
            case REWARD -> Items.CHEST;
            case BOSS -> Items.DRAGON_HEAD;
        };
    }

    enum Mode {
        RPG_PLAYERS,
        RPG_PLAYER,
        PROGRESSION,
        HISTORY,
        PROMOTIONS,
        DEFINITIONS,
        RELOAD_STATUS,
        MUTATIONS,
        ENCOUNTERS,
        ENCOUNTER_DETAIL,
        PARTICIPANTS,
        REWARDS,
        FORM,
        PREVIEW,
        RESULT
    }

    enum FormKind {
        XP,
        PROMOTION,
        SKILL_FULL,
        SKILL_BRANCH,
        BOSS_RESET,
        BOSS_RECOVER,
        RELOAD
    }
}

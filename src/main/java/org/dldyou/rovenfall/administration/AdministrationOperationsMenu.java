package org.dldyou.rovenfall.administration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
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

/** Server-authoritative inventory workflow for audit, diagnostics, export, and platform recovery. */
public final class AdministrationOperationsMenu extends ChestMenu implements AdministrationTextInputMenu {
    static final int MENU_SIZE = 54;
    static final int CONTENT_START = 9;
    static final int CONTENT_SIZE = 36;
    static final int BACK_SLOT = 45;
    static final int PRIMARY_SLOT = 46;
    static final int PREVIOUS_SLOT = 47;
    static final int SECONDARY_SLOT = 48;
    static final int CENTER_SLOT = 49;
    static final int NEXT_SLOT = 51;
    static final int DANGER_SLOT = 52;
    static final int REFRESH_SLOT = 53;
    static final int CONFIRM_SLOT = 31;
    static final int CANCEL_SLOT = 33;
    private static final long[] METRIC_WINDOWS = {
        java.time.Duration.ofMinutes(15).toMillis(),
        OperationsMetricsService.DEFAULT_WINDOW_MILLIS,
        java.time.Duration.ofHours(6).toMillis(),
        OperationsMetricsService.MAX_WINDOW_MILLIS
    };

    private final ServerPlayer viewer;
    private final UUID viewerId;
    private final SimpleContainer contents;
    private final AdministrationReadViewService.Domain entryDomain;
    private Mode mode;
    private Mode returnMode;
    private FormKind formKind;
    private AuditQuery auditQuery;
    private String query = "";
    private String formError = "";
    private int page;
    private boolean attentionOnly;
    private AdministrationOperationsViewService.AlertFilter alertFilter =
            AdministrationOperationsViewService.AlertFilter.ALL;
    private int metricWindowIndex = 1;
    private AuditEntry selectedAudit;
    private UUID selectedSnapshot;
    private AdministrationOperationsActionService.PendingAction pending;
    private AdministrationOperationsActionService.Result result;
    private long lastHandledGameTime = Long.MIN_VALUE;

    private AdministrationOperationsMenu(
            int containerId,
            Inventory inventory,
            ServerPlayer viewer,
            SimpleContainer contents,
            AdministrationReadViewService.Domain entryDomain) {
        super(MenuType.GENERIC_9x6, containerId, inventory, contents, 6);
        this.viewer = viewer;
        this.viewerId = viewer.getUUID();
        this.contents = contents;
        this.entryDomain = entryDomain;
        this.mode = switch (entryDomain) {
            case AUDIT -> Mode.AUDIT;
            case ALERTS -> Mode.ALERTS;
            case METRICS -> Mode.METRICS;
            default -> throw new IllegalArgumentException("Unsupported operations domain " + entryDomain);
        };
        render();
        PlayerMenuNetwork.seedMenuSession(this, UUID.randomUUID());
    }

    public static boolean open(ServerPlayer player, AdministrationReadViewService.Domain domain) {
        if (player == null || domain == null
                || domain != AdministrationReadViewService.Domain.AUDIT
                        && domain != AdministrationReadViewService.Domain.ALERTS
                        && domain != AdministrationReadViewService.Domain.METRICS
                || !canView(player, domain)) {
            return false;
        }
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) -> new AdministrationOperationsMenu(
                        containerId, inventory, (ServerPlayer) viewer, new SimpleContainer(MENU_SIZE), domain),
                Component.translatable("gui.rovenfall.admin.operations.title")))
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
            case AUDIT -> clickAudit(slotIndex);
            case AUDIT_DETAIL -> clickAuditDetail(slotIndex);
            case ALERTS -> clickAlerts(slotIndex);
            case METRICS -> clickMetrics(slotIndex);
            case SNAPSHOTS -> clickSnapshots(slotIndex);
            case SNAPSHOT_DETAIL -> clickSnapshotDetail(slotIndex);
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
        if (mode == Mode.AUDIT) {
            if (input.startsWith("rf-form/")) {
                Optional<List<String>> structured = AdministrationStructuredFormCodec.decode(
                        AdministrationFormType.OPERATIONS_AUDIT_SEARCH, input);
                auditQuery = structured.flatMap(this::typedAuditQuery).orElse(null);
                if (auditQuery == null) {
                    formError = "invalid_query";
                    render();
                    return false;
                }
            } else if (input.isBlank()) {
                auditQuery = null;
            } else {
                var parsed = AdministrationOperationsFormParser.parseAuditSearch(input, now());
                if (parsed.isEmpty()) {
                    formError = "invalid_query";
                    render();
                    return false;
                }
                auditQuery = parsed.orElseThrow().query();
            }
            page = 0;
            formError = "";
            render();
            return true;
        }
        if (mode != Mode.ALERTS && mode != Mode.SNAPSHOTS) {
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

    private void clickAudit(int slot) {
        if (slot == PRIMARY_SLOT && isOwner()) {
            enterForm(FormKind.EXPORT, Mode.AUDIT);
            return;
        }
        if (slot == CENTER_SLOT) {
            mode = Mode.SNAPSHOTS;
            query = "";
            page = 0;
        } else if (slot == SECONDARY_SLOT) {
            attentionOnly = !attentionOnly;
            page = 0;
        } else if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (contentIndex(slot) >= 0) {
            var resultPage = auditPage();
            int index = contentIndex(slot);
            if (index >= resultPage.entries().size()) {
                return;
            }
            selectedAudit = resultPage.entries().get(index).entry();
            mode = Mode.AUDIT_DETAIL;
        } else {
            return;
        }
        render();
    }

    private void clickAuditDetail(int slot) {
        if (slot == SECONDARY_SLOT && selectedAudit != null
                && receiptVisible(selectedAudit.transactionId())) {
            AdministrationEconomyMenu.openReceipt(viewer, selectedAudit.transactionId());
        }
    }

    private void clickAlerts(int slot) {
        if (slot == CENTER_SLOT) {
            alertFilter = alertFilter.next();
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

    private void clickMetrics(int slot) {
        if (slot == CENTER_SLOT) {
            metricWindowIndex = (metricWindowIndex + 1) % METRIC_WINDOWS.length;
            render();
            return;
        }
        int index = contentIndex(slot);
        List<UUID> evidence = metrics().map(OperationsMetricsService.Result::evidenceTransactionIds)
                .orElse(List.of());
        if (index < 0 || index >= evidence.size()) {
            return;
        }
        long generatedAt = now();
        auditQuery = new AuditQuery(Math.max(0L, generatedAt - AuditQuery.MAX_WINDOW_MILLIS), generatedAt,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(evidence.get(index)));
        page = 0;
        mode = Mode.AUDIT;
        render();
    }

    private void clickSnapshots(int slot) {
        if (slot == PRIMARY_SLOT && isOwner()) {
            enterForm(FormKind.SNAPSHOT_CREATE, Mode.SNAPSHOTS);
            return;
        }
        if (slot == PREVIOUS_SLOT) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT_SLOT) {
            page++;
        } else if (contentIndex(slot) >= 0) {
            var resultPage = snapshotsPage();
            int index = contentIndex(slot);
            if (index >= resultPage.entries().size()) {
                return;
            }
            selectedSnapshot = resultPage.entries().get(index).snapshotId();
            mode = Mode.SNAPSHOT_DETAIL;
        } else {
            return;
        }
        render();
    }

    private void clickSnapshotDetail(int slot) {
        if (slot == DANGER_SLOT && selectedSnapshot != null && isOwner()) {
            enterForm(FormKind.SNAPSHOT_RESTORE, Mode.SNAPSHOT_DETAIL);
        }
    }

    private boolean parseForm(String input) {
        UUID transactionId = UUID.randomUUID();
        pending = switch (formKind) {
            case EXPORT -> AdministrationOperationsFormParser.parseExport(input, now())
                    .flatMap(value -> AdministrationOperationsActionService.prepareExport(
                            server(), transactionId, value.query(), value.reason()))
                    .orElse(null);
            case SNAPSHOT_CREATE -> AdministrationOperationsFormParser.parseReasonOnly(input)
                    .flatMap(value -> AdministrationOperationsActionService.prepareSnapshotCreate(
                            server(), transactionId, UUID.randomUUID(), value.reason()))
                    .orElse(null);
            case SNAPSHOT_RESTORE -> AdministrationOperationsFormParser.parseReasonOnly(input)
                    .filter(value -> selectedSnapshot != null)
                    .flatMap(value -> AdministrationOperationsActionService.prepareSnapshotRestore(
                            server(), transactionId, selectedSnapshot, UUID.randomUUID(), value.reason()))
                    .orElse(null);
        };
        if (pending == null || !AdministrationOperationsActionService.allowed(currentRole())) {
            formError = formKind == FormKind.EXPORT ? "invalid_export" : "invalid_form";
            pending = null;
            render();
            return false;
        }
        formError = "";
        mode = Mode.PREVIEW;
        render();
        return true;
    }

    private String legacyFormInput(FormKind kind, List<String> values) {
        if (kind != FormKind.EXPORT) {
            return " | " + values.getFirst();
        }
        long until = now();
        long duration = switch (values.getFirst()) {
            case "hour" -> 60L * 60 * 1_000;
            case "day" -> 24L * 60 * 60 * 1_000;
            case "week" -> 7L * 24 * 60 * 60 * 1_000;
            case "month" -> AuditQuery.MAX_WINDOW_MILLIS;
            default -> 0L;
        };
        long since = Math.max(0L, until - duration);
        return "since=" + since + " until=" + until + " | " + values.get(1);
    }

    private Optional<AuditQuery> typedAuditQuery(List<String> values) {
        if (!AdministrationFormType.OPERATIONS_AUDIT_SEARCH.accepts(values)) {
            return Optional.empty();
        }
        long until = now();
        long duration = switch (values.getFirst()) {
            case "hour" -> 60L * 60 * 1_000;
            case "day" -> 24L * 60 * 60 * 1_000;
            case "week" -> 7L * 24 * 60 * 60 * 1_000;
            case "month" -> AuditQuery.MAX_WINDOW_MILLIS;
            default -> 0L;
        };
        try {
            return Optional.of(new AuditQuery(
                    Math.max(0L, until - duration), until,
                    Optional.empty(), Optional.empty(),
                    Optional.of(values.get(1).strip()).filter(value -> !value.isEmpty()), Optional.empty()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private void confirm() {
        if (pending == null) {
            return;
        }
        if (!PlayerMenuNetwork.beginMutation(viewerId, viewer.level().getGameTime())) {
            result = new AdministrationOperationsActionService.Result(
                    AdministrationOperationsActionService.Status.FAILED, "rate_limited", pending.transactionId());
        } else {
            result = AdministrationOperationsActionService.execute(viewer, pending);
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
        if (succeeded && pending instanceof AdministrationOperationsActionService.SnapshotCreateAction) {
            returnMode = Mode.SNAPSHOTS;
            page = 0;
        } else if (succeeded && pending instanceof AdministrationOperationsActionService.SnapshotRestoreAction) {
            returnMode = Mode.SNAPSHOTS;
            selectedSnapshot = null;
            page = 0;
        }
        pending = null;
        result = null;
        formKind = null;
        mode = returnMode;
        render();
    }

    private void back() {
        switch (mode) {
            case AUDIT, ALERTS, METRICS -> AdministrationControlCenterMenu.open(viewer);
            case AUDIT_DETAIL -> {
                selectedAudit = null;
                mode = Mode.AUDIT;
                render();
            }
            case SNAPSHOTS -> {
                query = "";
                page = 0;
                mode = Mode.AUDIT;
                render();
            }
            case SNAPSHOT_DETAIL -> {
                selectedSnapshot = null;
                mode = Mode.SNAPSHOTS;
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
            case AUDIT -> renderAudit();
            case AUDIT_DETAIL -> renderAuditDetail();
            case ALERTS -> renderAlerts();
            case METRICS -> renderMetrics();
            case SNAPSHOTS -> renderSnapshots();
            case SNAPSHOT_DETAIL -> renderSnapshotDetail();
            case FORM -> renderForm();
            case PREVIEW -> renderPreview();
            case RESULT -> renderResult();
        }
        contents.setItem(REFRESH_SLOT, icon(
                Items.COMPASS, "gui.rovenfall.admin.refresh", "gui.rovenfall.admin.click"));
        broadcastChanges();
    }

    private void renderAudit() {
        var resultPage = auditPage();
        page = resultPage.page();
        renderHeader(Items.WRITABLE_BOOK, "gui.rovenfall.admin.operations.audit",
                resultPage.page(), resultPage.totalPages(), resultPage.totalEntries(), resultPage.truncated(),
                auditQuery == null ? "*" : auditQuery.canonical());
        ItemStack header = contents.getItem(4);
        AdministrationFormMarker.write(header, new AdministrationFormMarker(
                AdministrationFormType.OPERATIONS_AUDIT_SEARCH,
                auditFormDefaults()));
        if (!formError.isBlank()) {
            AdministrationFormMarker.writeError(header);
        }
        contents.setItem(4, header);
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            AuditEntry entry = row.entry();
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    row.attention() ? Items.REDSTONE_TORCH : Items.WRITTEN_BOOK,
                    Component.translatable("gui.rovenfall.admin.operations.audit_entry"),
                    Component.translatable("gui.rovenfall.admin.operations.field.timestamp",
                            entry.timestampEpochMillis()),
                    Component.translatable("gui.rovenfall.admin.operations.field.target", entry.target()),
                    Component.literal(entry.actionType().toString()),
                    Component.translatable("gui.rovenfall.admin.operations.field.actor", entry.actorId().toString()),
                    Component.translatable("gui.rovenfall.admin.operations.field.transaction",
                            entry.transactionId().toString())));
        }
        if (isOwner()) {
            contents.setItem(PRIMARY_SLOT, icon(Items.WRITABLE_BOOK,
                    "gui.rovenfall.admin.operations.export", "gui.rovenfall.admin.operations.export_hint"));
        }
        contents.setItem(SECONDARY_SLOT, PlayerDashboardMenu.icon(
                attentionOnly ? Items.REDSTONE_TORCH : Items.HOPPER,
                Component.translatable(attentionOnly
                        ? "gui.rovenfall.admin.operations.filter.attention"
                        : "gui.rovenfall.admin.operations.filter.all"),
                Component.translatable("gui.rovenfall.admin.click")));
        contents.setItem(CENTER_SLOT, icon(Items.ENDER_CHEST,
                "gui.rovenfall.admin.operations.snapshots", "gui.rovenfall.admin.click"));
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderAuditDetail() {
        if (selectedAudit == null) {
            mode = Mode.AUDIT;
            renderAudit();
            return;
        }
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.WRITTEN_BOOK, Component.translatable("gui.rovenfall.admin.operations.audit_detail"),
                Component.translatable("gui.rovenfall.admin.operations.field.timestamp",
                        selectedAudit.timestampEpochMillis()),
                Component.translatable("gui.rovenfall.admin.operations.field.before", selectedAudit.beforeValue()),
                Component.translatable("gui.rovenfall.admin.operations.field.after", selectedAudit.afterValue()),
                Component.translatable("gui.rovenfall.admin.operations.field.reason", selectedAudit.reason()),
                Component.translatable("gui.rovenfall.admin.operations.field.target", selectedAudit.target()),
                Component.literal(selectedAudit.actionType().toString()),
                Component.translatable("gui.rovenfall.admin.operations.field.actor", selectedAudit.actorId().toString()),
                Component.translatable("gui.rovenfall.admin.operations.field.transaction",
                        selectedAudit.transactionId().toString())));
        if (receiptVisible(selectedAudit.transactionId())) {
            contents.setItem(SECONDARY_SLOT, icon(Items.GOLD_INGOT,
                    "gui.rovenfall.admin.operations.open_receipt",
                    "gui.rovenfall.admin.operations.open_receipt_hint"));
        }
        renderBack();
    }

    private void renderAlerts() {
        var resultPage = alertsPage();
        page = resultPage.page();
        renderHeader(Items.BELL, "gui.rovenfall.admin.operations.alerts",
                resultPage.page(), resultPage.totalPages(), resultPage.totalEntries(), resultPage.truncated(),
                query.isBlank() ? "*" : query);
        markSearchHeader();
        for (int index = 0; index < resultPage.entries().size(); index++) {
            EconomyAlert alert = resultPage.entries().get(index).alert();
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    Items.REDSTONE_TORCH,
                    Component.translatable(alert.type() == EconomyAlert.Type.AMOUNT
                            ? "gui.rovenfall.admin.operations.alert.amount"
                            : "gui.rovenfall.admin.operations.alert.rate"),
                    Component.translatable("gui.rovenfall.admin.operations.field.observed",
                            alert.observedValue(), alert.threshold()),
                    Component.translatable("gui.rovenfall.admin.operations.field.timestamp",
                            alert.timestampEpochMillis()),
                    Component.translatable("gui.rovenfall.admin.operations.field.player", alert.playerId().toString()),
                    Component.translatable("gui.rovenfall.admin.operations.field.transaction",
                            alert.transactionId().toString())));
        }
        contents.setItem(CENTER_SLOT, PlayerDashboardMenu.icon(
                Items.HOPPER, Component.translatable(alertFilterKey(alertFilter)),
                Component.translatable("gui.rovenfall.admin.click")));
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderMetrics() {
        Optional<OperationsMetricsService.Result> value = metrics();
        if (value.isEmpty()) {
            denyAndClose();
            return;
        }
        OperationsMetricsService.Result metric = value.orElseThrow();
        contents.setItem(4, PlayerDashboardMenu.icon(
                metric.hasAnomaly() ? Items.REDSTONE_TORCH : Items.COMPARATOR,
                Component.translatable("gui.rovenfall.admin.operations.metrics"),
                Component.translatable("gui.rovenfall.admin.operations.field.metric_window",
                        metric.windowMillis(), metric.generatedAtEpochMillis()),
                Component.translatable("gui.rovenfall.admin.operations.field.metric_economy",
                        metric.economyTransactionCount(), metric.amountAlertCount(), metric.rateAlertCount()),
                Component.translatable("gui.rovenfall.admin.operations.field.metric_requests",
                        metric.deniedRequestCount(), metric.malformedRequestCount()),
                Component.translatable("gui.rovenfall.admin.operations.field.metric_rpg",
                        metric.suspiciousRpgAwardCount(), metric.scannedRpgPlayers()),
                Component.translatable("gui.rovenfall.admin.operations.field.metric_recovery",
                        metric.activeEncounterCount(), metric.pendingRewardCount(), metric.pendingRecoveryCount()),
                Component.translatable(metric.rpgTruncated()
                        ? "gui.rovenfall.admin.truncated" : "gui.rovenfall.admin.complete")));
        List<UUID> evidence = metric.evidenceTransactionIds();
        for (int index = 0; index < evidence.size(); index++) {
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    Items.WRITTEN_BOOK,
                    Component.translatable("gui.rovenfall.admin.operations.metric_evidence"),
                    Component.translatable("gui.rovenfall.admin.operations.metric_evidence_hint"),
                    Component.literal(evidence.get(index).toString())));
        }
        contents.setItem(CENTER_SLOT, PlayerDashboardMenu.icon(
                Items.CLOCK, Component.translatable("gui.rovenfall.admin.operations.metric_window",
                        METRIC_WINDOWS[metricWindowIndex]),
                Component.translatable("gui.rovenfall.admin.click")));
        renderBack();
    }

    private void renderSnapshots() {
        var resultPage = snapshotsPage();
        page = resultPage.page();
        renderHeader(Items.ENDER_CHEST, "gui.rovenfall.admin.operations.snapshots",
                resultPage.page(), resultPage.totalPages(), resultPage.totalEntries(), resultPage.truncated(),
                query.isBlank() ? "*" : query);
        markSearchHeader();
        for (int index = 0; index < resultPage.entries().size(); index++) {
            var row = resultPage.entries().get(index);
            contents.setItem(CONTENT_START + index, PlayerDashboardMenu.icon(
                    row.kind() == AdministrationOperationsViewService.SnapshotKind.SAFETY
                            ? Items.TOTEM_OF_UNDYING : Items.ENDER_CHEST,
                    Component.translatable(snapshotKindKey(row.kind())),
                    Component.translatable("gui.rovenfall.admin.operations.field.snapshot_kind",
                            Component.translatable(snapshotKindKey(row.kind()))),
                    Component.translatable("gui.rovenfall.admin.operations.field.timestamp",
                            row.recordedAtEpochMillis()),
                    Component.translatable("gui.rovenfall.admin.operations.field.transaction",
                            row.auditTransactionId().toString()),
                    Component.literal(row.snapshotId().toString())));
        }
        if (isOwner()) {
            contents.setItem(PRIMARY_SLOT, icon(Items.EMERALD,
                    "gui.rovenfall.admin.operations.snapshot_create",
                    "gui.rovenfall.admin.operations.snapshot_create_hint"));
        }
        renderPagination(resultPage.page(), resultPage.totalPages());
    }

    private void renderSnapshotDetail() {
        if (selectedSnapshot == null) {
            mode = Mode.SNAPSHOTS;
            renderSnapshots();
            return;
        }
        contents.setItem(4, PlayerDashboardMenu.icon(
                Items.ENDER_CHEST, Component.translatable("gui.rovenfall.admin.operations.snapshot_detail"),
                Component.translatable("gui.rovenfall.admin.operations.snapshot_restore_warning"),
                Component.translatable("gui.rovenfall.admin.operations.command_fallback.snapshot"),
                Component.literal(selectedSnapshot.toString())));
        if (isOwner()) {
            contents.setItem(DANGER_SLOT, icon(Items.TNT,
                    "gui.rovenfall.admin.operations.snapshot_restore",
                    "gui.rovenfall.admin.operations.irreversible"));
        }
        renderBack();
    }

    private void renderForm() {
        ItemStack header = PlayerDashboardMenu.icon(
                Items.WRITABLE_BOOK, Component.translatable("gui.rovenfall.admin.operations.form.title"),
                Component.translatable(formHint(formKind)),
                Component.translatable("gui.rovenfall.admin.operations.form.submit"),
                formError.isBlank() ? Component.empty()
                        : Component.translatable("gui.rovenfall.admin.operations.error", inputError(formError)));
        AdministrationFormType type = formType(formKind);
        AdministrationFormMarker.write(header, new AdministrationFormMarker(type, type.defaults()));
        if (!formError.isBlank()) {
            AdministrationFormMarker.writeError(header);
        }
        contents.setItem(4, header);
        renderBack();
    }

    private void renderPreview() {
        contents.setItem(4, PlayerDashboardMenu.icon(
                pending instanceof AdministrationOperationsActionService.SnapshotRestoreAction
                        ? Items.TNT : Items.WRITABLE_BOOK,
                Component.translatable(pending instanceof AdministrationOperationsActionService.SnapshotRestoreAction
                        ? "gui.rovenfall.admin.operations.preview.irreversible"
                        : "gui.rovenfall.admin.operations.preview"),
                previewLines().toArray(Component[]::new)));
        if (AdministrationOperationsActionService.allowed(currentRole())) {
            contents.setItem(CONFIRM_SLOT, icon(Items.EMERALD,
                    "gui.rovenfall.admin.operations.confirm",
                    "gui.rovenfall.admin.operations.confirm_fresh"));
        }
        contents.setItem(CANCEL_SLOT, icon(Items.BARRIER,
                "gui.rovenfall.admin.operations.cancel", "gui.rovenfall.admin.click"));
        renderBack();
    }

    private void renderResult() {
        boolean succeeded = result != null && result.succeeded();
        contents.setItem(4, PlayerDashboardMenu.icon(
                succeeded ? Items.EMERALD : Items.BARRIER,
                Component.translatable(succeeded
                        ? "gui.rovenfall.admin.operations.result.success"
                        : "gui.rovenfall.admin.operations.result.failed"),
                resultDetail(result == null ? "unknown" : result.detail()),
                Component.translatable("gui.rovenfall.admin.operations.field.transaction",
                        result == null || result.transactionId() == null
                                ? Component.translatable("gui.rovenfall.admin.operations.none")
                                : Component.literal(result.transactionId().toString()))));
        contents.setItem(CONFIRM_SLOT, icon(Items.ARROW,
                "gui.rovenfall.admin.operations.continue", "gui.rovenfall.admin.click"));
        renderBack();
    }

    private List<Component> previewLines() {
        if (pending instanceof AdministrationOperationsActionService.ExportAction value) {
            return List.of(
                    Component.translatable("gui.rovenfall.admin.operations.preview.export",
                            value.expectedSelection().totalEntries(), AuditExportService.MAX_EXPORT_ROWS,
                            AuditExportService.MAX_EXPORT_BYTES),
                    Component.translatable("gui.rovenfall.admin.operations.preview.query", value.query().canonical()),
                    Component.translatable("gui.rovenfall.admin.operations.preview.reason", value.reason()),
                    Component.translatable("gui.rovenfall.admin.operations.preview.transaction",
                            value.transactionId().toString()));
        }
        if (pending instanceof AdministrationOperationsActionService.SnapshotCreateAction value) {
            return List.of(
                    Component.translatable("gui.rovenfall.admin.operations.preview.snapshot_create",
                            value.snapshotId().toString(), value.expectedLiveEvidence().bytes(),
                            value.expectedLiveEvidence().sha256()),
                    Component.translatable("gui.rovenfall.admin.operations.preview.reason", value.reason()),
                    Component.translatable("gui.rovenfall.admin.operations.preview.transaction",
                            value.transactionId().toString()));
        }
        if (pending instanceof AdministrationOperationsActionService.SnapshotRestoreAction value) {
            return List.of(
                    Component.translatable("gui.rovenfall.admin.operations.preview.snapshot_restore",
                            value.snapshotId().toString(), value.safetySnapshotId().toString()),
                    Component.translatable("gui.rovenfall.admin.operations.preview.snapshot_evidence",
                            value.expectedTargetEvidence().bytes(), value.expectedTargetEvidence().sha256()),
                    Component.translatable("gui.rovenfall.admin.operations.preview.reason", value.reason()),
                    Component.translatable("gui.rovenfall.admin.operations.preview.transaction",
                            value.transactionId().toString()));
        }
        return List.of(Component.translatable("gui.rovenfall.admin.operations.result.detail.invalid"));
    }

    private void renderHeader(
            Item item,
            String titleKey,
            int currentPage,
            int totalPages,
            int totalEntries,
            boolean truncated,
            String renderedQuery) {
        contents.setItem(4, PlayerDashboardMenu.icon(
                item, Component.translatable(titleKey),
                Component.translatable("gui.rovenfall.admin.page", currentPage + 1, Math.max(1, totalPages)),
                Component.translatable("gui.rovenfall.admin.total", totalEntries),
                Component.translatable(truncated
                        ? "gui.rovenfall.admin.truncated" : "gui.rovenfall.admin.complete"),
                Component.translatable("gui.rovenfall.admin.query", renderedQuery),
                Component.translatable("gui.rovenfall.admin.operations.command_fallback"),
                formError.isBlank() ? Component.empty()
                        : Component.translatable("gui.rovenfall.admin.operations.error", inputError(formError))));
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

    private AdministrationOperationsViewService.Page<AdministrationOperationsViewService.AuditRow> auditPage() {
        return AdministrationOperationsViewService.audit(
                server(), viewerId, authorizationOverride(), currentAuditQuery(), attentionOnly, page);
    }

    private AdministrationOperationsViewService.Page<AdministrationOperationsViewService.AlertRow> alertsPage() {
        return AdministrationOperationsViewService.alerts(
                server(), viewerId, authorizationOverride(), alertFilter, query, page);
    }

    private AdministrationOperationsViewService.Page<AdministrationOperationsViewService.SnapshotRow> snapshotsPage() {
        return AdministrationOperationsViewService.snapshots(
                server(), viewerId, authorizationOverride(), query, page);
    }

    private Optional<OperationsMetricsService.Result> metrics() {
        return AdministrationOperationsViewService.metrics(
                server(), viewerId, authorizationOverride(), now(), METRIC_WINDOWS[metricWindowIndex]);
    }

    private AuditQuery currentAuditQuery() {
        long now = now();
        return auditQuery == null
                ? new AuditQuery(Math.max(0L, now - AuditQuery.MAX_WINDOW_MILLIS), now,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())
                : auditQuery;
    }

    private List<String> auditFormDefaults() {
        if (auditQuery == null) {
            return List.of("month", "");
        }
        long duration = auditQuery.untilEpochMillis() - auditQuery.sinceEpochMillis();
        String window = duration <= 60L * 60 * 1_000 ? "hour"
                : duration <= 24L * 60 * 60 * 1_000 ? "day"
                : duration <= 7L * 24 * 60 * 60 * 1_000 ? "week"
                : "month";
        return List.of(window, auditQuery.targetPrefix().orElse(""));
    }

    private boolean receiptVisible(UUID transactionId) {
        return transactionId != null
                && PlatformSavedData.get(server()).economyReceipt(transactionId).isPresent()
                && currentRole() != null
                && AdministrationReadViewService.Domain.RECEIPTS.allowedFor(currentRole());
    }

    private AdminRole currentRole() {
        return AdministrationControlCenterMenu.resolveRole(viewer).orElse(null);
    }

    private boolean isOwner() {
        return AdministrationOperationsActionService.allowed(currentRole());
    }

    private boolean authorizationOverride() {
        return PlatformSavedData.get(server()).roleOf(viewerId).isEmpty();
    }

    private net.minecraft.server.MinecraftServer server() {
        return viewer.level().getServer();
    }

    private long now() {
        return Instant.now().toEpochMilli();
    }

    private void denyAndClose() {
        viewer.sendSystemMessage(Component.translatable("gui.rovenfall.admin.denied"));
        viewer.closeContainer();
    }

    private static boolean canView(ServerPlayer player, AdministrationReadViewService.Domain domain) {
        return AdministrationControlCenterMenu.resolveRole(player).filter(domain::allowedFor).isPresent();
    }

    private static int contentIndex(int slot) {
        return slot >= CONTENT_START && slot < CONTENT_START + CONTENT_SIZE ? slot - CONTENT_START : -1;
    }

    private static ItemStack icon(Item item, String title, String lore) {
        return PlayerDashboardMenu.icon(item, Component.translatable(title), Component.translatable(lore));
    }

    private static String alertFilterKey(AdministrationOperationsViewService.AlertFilter filter) {
        return "gui.rovenfall.admin.operations.alert.filter."
                + filter.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String snapshotKindKey(AdministrationOperationsViewService.SnapshotKind kind) {
        return "gui.rovenfall.admin.operations.snapshot.kind."
                + kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String formHint(FormKind kind) {
        return kind == FormKind.EXPORT
                ? "gui.rovenfall.admin.operations.form.export"
                : "gui.rovenfall.admin.operations.form.reason";
    }

    private static AdministrationFormType formType(FormKind kind) {
        return switch (kind) {
            case EXPORT -> AdministrationFormType.OPERATIONS_EXPORT;
            case SNAPSHOT_CREATE -> AdministrationFormType.OPERATIONS_SNAPSHOT_CREATE;
            case SNAPSHOT_RESTORE -> AdministrationFormType.OPERATIONS_SNAPSHOT_RESTORE;
        };
    }

    private static Component inputError(String detail) {
        String suffix = switch (detail) {
            case "query_too_long" -> "query_too_long";
            case "invalid_query" -> "invalid_query";
            case "invalid_export" -> "invalid_export";
            default -> "invalid_form";
        };
        return Component.translatable("gui.rovenfall.admin.operations.form.error." + suffix);
    }

    private static Component resultDetail(String detail) {
        String suffix = switch (detail) {
            case "success" -> "success";
            case "duplicate_transaction" -> "duplicate";
            case "stale_confirmation" -> "stale";
            case "unauthorized" -> "unauthorized";
            case "rate_limited" -> "rate_limited";
            case "read_only_schema" -> "read_only";
            case "limit_exceeded" -> "limit";
            case "write_failed", "storage_error", "safety_snapshot_failed" -> "storage";
            case "snapshot_unavailable" -> "snapshot";
            case "dependency_locked" -> "locked";
            case "transaction_conflict", "transaction_evidence_conflict" -> "conflict";
            default -> "invalid";
        };
        return Component.translatable("gui.rovenfall.admin.operations.result.detail." + suffix);
    }

    private enum Mode {
        AUDIT,
        AUDIT_DETAIL,
        ALERTS,
        METRICS,
        SNAPSHOTS,
        SNAPSHOT_DETAIL,
        FORM,
        PREVIEW,
        RESULT
    }

    private enum FormKind {
        EXPORT,
        SNAPSHOT_CREATE,
        SNAPSHOT_RESTORE
    }
}

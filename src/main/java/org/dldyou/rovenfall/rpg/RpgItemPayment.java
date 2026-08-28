package org.dldyou.rovenfall.rpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.administration.RpgSkillOperation;

/** Player-root journal that makes inventory consumption recoverable with the player's inventory save. */
public final class RpgItemPayment {
    private static final String MARKER_KEY = "rovenfall:rpg_item_payment";
    private static final String RECEIPTS_KEY = "rovenfall:rpg_item_payment_receipts";
    private static final int MAX_RECEIPTS = 10_000;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final Codec<List<ItemStack>> INVENTORY_CODEC =
            ItemStack.OPTIONAL_CODEC.listOf(Inventory.INVENTORY_SIZE, Inventory.INVENTORY_SIZE);
    private static final Codec<List<Receipt>> RECEIPTS_CODEC =
            Receipt.CODEC.listOf(0, MAX_RECEIPTS);

    private record Receipt(UUID transactionId, long timestampEpochMillis) {
        private static final Codec<Receipt> CODEC = RecordCodecBuilder.<Receipt>create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("transaction").forGetter(Receipt::transactionId),
                Codec.LONG.fieldOf("timestamp").forGetter(Receipt::timestampEpochMillis)
        ).apply(instance, Receipt::new)).validate(receipt -> receipt.transactionId == null
                || ZERO_UUID.equals(receipt.transactionId) || receipt.timestampEpochMillis < 0
                ? DataResult.error(() -> "Invalid RPG item payment receipt")
                : DataResult.success(receipt));
    }

    enum Status {
        SUCCESS,
        INSUFFICIENT_ITEMS,
        CONFLICT,
        INVALID_MARKER
    }

    record Result(Status status, Optional<Marker> marker) {
        Result {
            marker = marker == null ? Optional.empty() : marker;
        }
    }

    record Marker(
            UUID transactionId,
            RpgSkillOperation.Kind kind,
            SkillResetPlan.Mode mode,
            Identifier target,
            long currencyCost,
            long timestampEpochMillis,
            List<RpgItemCost> itemCosts,
            Optional<SkillResetPlan> plan,
            List<ItemStack> beforeInventory,
            List<ItemStack> afterInventory) {
        static final Codec<Marker> CODEC = RecordCodecBuilder.<Marker>create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("transaction").forGetter(Marker::transactionId),
                RpgSkillOperation.Kind.CODEC.fieldOf("kind").forGetter(Marker::kind),
                SkillResetPlan.Mode.CODEC.fieldOf("mode").forGetter(Marker::mode),
                Identifier.CODEC.fieldOf("target").forGetter(Marker::target),
                Codec.LONG.fieldOf("currency_cost").forGetter(Marker::currencyCost),
                Codec.LONG.fieldOf("timestamp").forGetter(Marker::timestampEpochMillis),
                RpgItemCost.LIST_CODEC.fieldOf("item_costs").forGetter(Marker::itemCosts),
                SkillResetPlan.CODEC.optionalFieldOf("plan").forGetter(Marker::plan),
                INVENTORY_CODEC.fieldOf("before_inventory").forGetter(Marker::beforeInventory),
                INVENTORY_CODEC.fieldOf("after_inventory").forGetter(Marker::afterInventory)
        ).apply(instance, Marker::new)).validate(Marker::validate);

        Marker {
            itemCosts = itemCosts == null ? List.of() : List.copyOf(itemCosts);
            plan = plan == null ? Optional.empty() : plan;
            beforeInventory = copy(beforeInventory);
            afterInventory = copy(afterInventory);
        }

        @Override
        public List<ItemStack> beforeInventory() {
            return copy(beforeInventory);
        }

        @Override
        public List<ItemStack> afterInventory() {
            return copy(afterInventory);
        }

        boolean matches(RpgSkillOperation operation, UUID transaction) {
            return transactionId.equals(transaction)
                    && kind == operation.kind()
                    && mode == operation.mode()
                    && target.equals(operation.target())
                    && currencyCost == operation.cost()
                    && timestampEpochMillis == operation.timestampEpochMillis()
                    && itemCosts.equals(operation.itemCosts())
                    && (operation.itemCountsBefore().isEmpty()
                            || counts(beforeInventory, itemCosts).equals(operation.itemCountsBefore()))
                    && (operation.itemCountsAfter().isEmpty()
                            || counts(afterInventory, itemCosts).equals(operation.itemCountsAfter()))
                    && (kind != RpgSkillOperation.Kind.SKILL_RESET
                            || operation.plan().isEmpty() || plan.equals(operation.plan()));
        }

        List<Long> countsBefore() {
            return counts(beforeInventory, itemCosts);
        }

        List<Long> countsAfter() {
            return counts(afterInventory, itemCosts);
        }

        private static DataResult<Marker> validate(Marker marker) {
            if (marker == null || marker.transactionId == null || ZERO_UUID.equals(marker.transactionId)
                    || marker.kind == null || marker.mode == null || marker.target == null
                    || marker.currencyCost < 0 || marker.timestampEpochMillis < 0
                    || marker.itemCosts == null || marker.itemCosts.isEmpty()
                    || marker.itemCosts.size() > RpgItemCost.MAX_ENTRIES
                    || marker.itemCosts.stream().anyMatch(item -> item == null || item.item() == null
                            || item.count() < 1 || item.count() > RpgItemCost.MAX_COUNT)
                    || marker.itemCosts.stream().map(RpgItemCost::item).distinct().count()
                            != marker.itemCosts.size()
                    || !validInventory(marker.beforeInventory) || !validInventory(marker.afterInventory)
                    || marker.kind == RpgSkillOperation.Kind.CAREER_PROMOTION && marker.plan.isPresent()
                    || marker.kind == RpgSkillOperation.Kind.SKILL_RESET && marker.plan.isEmpty()) {
                return DataResult.error(() -> "Invalid RPG item payment marker");
            }
            return DataResult.success(marker);
        }
    }

    private RpgItemPayment() {
    }

    static Result prepare(ServerPlayer player, UUID transactionId, RpgSkillOperation operation) {
        if (player == null || transactionId == null || operation == null || operation.itemCosts().isEmpty()
                || !player.getUUID().equals(operation.playerId())) {
            return new Result(Status.CONFLICT, Optional.empty());
        }
        Optional<List<Receipt>> receipts = readReceipts(player);
        if (receipts.isEmpty()) {
            return new Result(Status.INVALID_MARKER, Optional.empty());
        }
        if (receipts.orElseThrow().stream().anyMatch(receipt -> receipt.transactionId().equals(transactionId))) {
            return new Result(Status.SUCCESS, Optional.empty());
        }
        Result loaded = read(player);
        if (loaded.status() == Status.INVALID_MARKER) {
            return loaded;
        }
        if (loaded.marker().isPresent()) {
            Marker marker = loaded.marker().orElseThrow();
            if (!marker.matches(operation, transactionId)) {
                return new Result(Status.CONFLICT, Optional.of(marker));
            }
            List<ItemStack> current = snapshot(player);
            if (ItemStack.listMatches(current, marker.afterInventory)) {
                return new Result(Status.SUCCESS, Optional.of(marker));
            }
            if (ItemStack.listMatches(current, marker.beforeInventory)) {
                replace(player, marker.afterInventory);
                return new Result(Status.SUCCESS, Optional.of(marker));
            }
            return new Result(Status.CONFLICT, Optional.of(marker));
        }

        List<ItemStack> before = snapshot(player);
        List<Long> currentCounts = counts(before, operation.itemCosts());
        if (operation.hasInventoryEvidence()) {
            if (currentCounts.equals(operation.itemCountsAfter())) {
                return new Result(Status.SUCCESS, Optional.empty());
            }
            if (!currentCounts.equals(operation.itemCountsBefore())) {
                return new Result(Status.CONFLICT, Optional.empty());
            }
        }
        List<ItemStack> after = copy(before);
        if (!remove(after, operation.itemCosts())) {
            return new Result(Status.INSUFFICIENT_ITEMS, Optional.empty());
        }
        Marker marker = new Marker(
                transactionId, operation.kind(), operation.mode(), operation.target(), operation.cost(),
                operation.timestampEpochMillis(), operation.itemCosts(), operation.plan(), before, after);
        try {
            player.getPersistentData().store(
                    MARKER_KEY, Marker.CODEC,
                    RegistryOps.create(NbtOps.INSTANCE, player.level().registryAccess()), marker);
            replace(player, after);
            return new Result(Status.SUCCESS, Optional.of(marker));
        } catch (RuntimeException exception) {
            player.getPersistentData().remove(MARKER_KEY);
            replace(player, before);
            return new Result(Status.INVALID_MARKER, Optional.empty());
        }
    }

    static Result read(ServerPlayer player) {
        if (player == null || !player.getPersistentData().contains(MARKER_KEY)) {
            return new Result(Status.SUCCESS, Optional.empty());
        }
        Optional<Marker> marker = player.getPersistentData().read(
                MARKER_KEY, Marker.CODEC,
                RegistryOps.create(NbtOps.INSTANCE, player.level().registryAccess()));
        return marker.map(value -> new Result(Status.SUCCESS, Optional.of(value)))
                .orElseGet(() -> new Result(Status.INVALID_MARKER, Optional.empty()));
    }

    static Status rollback(ServerPlayer player, UUID transactionId) {
        Result loaded = read(player);
        if (loaded.status() != Status.SUCCESS || loaded.marker().isEmpty()) {
            return loaded.status();
        }
        Marker marker = loaded.marker().orElseThrow();
        if (!marker.transactionId().equals(transactionId)) {
            return Status.CONFLICT;
        }
        List<ItemStack> current = snapshot(player);
        if (ItemStack.listMatches(current, marker.afterInventory)) {
            replace(player, marker.beforeInventory);
        } else if (!ItemStack.listMatches(current, marker.beforeInventory)) {
            return Status.CONFLICT;
        }
        player.getPersistentData().remove(MARKER_KEY);
        return Status.SUCCESS;
    }

    static Status complete(ServerPlayer player, UUID transactionId, long timestampEpochMillis) {
        if (player == null || transactionId == null || ZERO_UUID.equals(transactionId)
                || timestampEpochMillis < 0) {
            return Status.CONFLICT;
        }
        Result loaded = read(player);
        if (loaded.status() != Status.SUCCESS) {
            return loaded.status();
        }
        if (loaded.marker().isPresent()
                && !loaded.marker().orElseThrow().transactionId().equals(transactionId)) {
            return Status.CONFLICT;
        }
        Optional<List<Receipt>> loadedReceipts = readReceipts(player);
        if (loadedReceipts.isEmpty()) {
            return Status.INVALID_MARKER;
        }
        List<Receipt> receipts = new ArrayList<>(loadedReceipts.orElseThrow().stream()
                .filter(receipt -> PlatformSavedData.isEconomyRecoveryWindow(
                        receipt.timestampEpochMillis(), System.currentTimeMillis()))
                .toList());
        if (receipts.stream().noneMatch(receipt -> receipt.transactionId().equals(transactionId))) {
            if (receipts.size() >= MAX_RECEIPTS) {
                return Status.CONFLICT;
            }
            receipts.add(new Receipt(transactionId, timestampEpochMillis));
        }
        try {
            player.getPersistentData().store(RECEIPTS_KEY, RECEIPTS_CODEC, List.copyOf(receipts));
        } catch (RuntimeException exception) {
            return Status.INVALID_MARKER;
        }
        player.getPersistentData().remove(MARKER_KEY);
        return Status.SUCCESS;
    }

    public static long owned(ServerPlayer player, Identifier itemId) {
        return snapshot(player).stream()
                .filter(stack -> !stack.isEmpty()
                        && itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem())))
                .mapToLong(ItemStack::getCount)
                .sum();
    }

    private static boolean remove(List<ItemStack> inventory, List<RpgItemCost> costs) {
        for (RpgItemCost cost : costs) {
            long owned = inventory.stream()
                    .filter(stack -> !stack.isEmpty()
                            && cost.item().equals(BuiltInRegistries.ITEM.getKey(stack.getItem())))
                    .mapToLong(ItemStack::getCount)
                    .sum();
            if (owned < cost.count()) {
                return false;
            }
        }
        for (RpgItemCost cost : costs) {
            int remaining = cost.count();
            for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
                ItemStack stack = inventory.get(slot);
                if (!stack.isEmpty() && cost.item().equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                    int removed = Math.min(remaining, stack.getCount());
                    stack.shrink(removed);
                    if (stack.isEmpty()) {
                        inventory.set(slot, ItemStack.EMPTY);
                    }
                    remaining -= removed;
                }
            }
        }
        return true;
    }

    private static Optional<List<Receipt>> readReceipts(ServerPlayer player) {
        if (!player.getPersistentData().contains(RECEIPTS_KEY)) {
            return Optional.of(List.of());
        }
        return player.getPersistentData().read(RECEIPTS_KEY, RECEIPTS_CODEC);
    }

    private static List<Long> counts(List<ItemStack> inventory, List<RpgItemCost> costs) {
        return costs.stream().map(cost -> inventory.stream()
                .filter(stack -> !stack.isEmpty()
                        && cost.item().equals(BuiltInRegistries.ITEM.getKey(stack.getItem())))
                .mapToLong(ItemStack::getCount)
                .sum()).toList();
    }

    private static List<ItemStack> snapshot(ServerPlayer player) {
        return copy(player.getInventory().getNonEquipmentItems());
    }

    private static List<ItemStack> copy(List<ItemStack> inventory) {
        if (inventory == null) {
            return List.of();
        }
        return inventory.stream().map(stack -> stack == null ? ItemStack.EMPTY : stack.copy())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static void replace(ServerPlayer player, List<ItemStack> inventory) {
        var target = player.getInventory().getNonEquipmentItems();
        for (int slot = 0; slot < target.size(); slot++) {
            target.set(slot, inventory.get(slot).copy());
        }
        player.getInventory().setChanged();
    }

    private static boolean validInventory(List<ItemStack> inventory) {
        return inventory != null && inventory.size() == Inventory.INVENTORY_SIZE
                && inventory.stream().allMatch(stack -> stack != null
                        && (stack.isEmpty() || ItemStack.validateStrict(stack).error().isEmpty()));
    }
}

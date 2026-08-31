package org.dldyou.rovenfall.rpg;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.Tags;
import org.dldyou.rovenfall.administration.ClaimProtectionService;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimConfig;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.mobs.BossEncounterRuntime;
import org.dldyou.rovenfall.quest.QuestProgressRuntime;

/** NeoForge adapters for server-observed activity outcomes. */
public final class RpgActivityEvents {
    private static final Identifier COMBAT = id("combat");
    private static final Identifier COOKING = id("cooking");
    private static final Identifier EXPLORATION = id("exploration");
    private static final Identifier HUNTING = id("hunting");
    private static final Identifier BUILDING = id("building");
    private static final Identifier MINING = id("mining");
    private static final Identifier FARMING = id("farming");
    private static final CombatContributionTracker COMBAT_CONTRIBUTIONS = new CombatContributionTracker();

    private RpgActivityEvents() {}

    public static Map<String, String> mapping() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("combat", "LivingDamageEvent.Post (positive applied health damage)");
        result.put("cooking", "PlayerEvent.ItemCraftedEvent/ItemSmeltedEvent (completed food result)");
        result.put("mining", "BlockDropsEvent (completed natural ore break under protection policy)");
        result.put("exploration", "AdvancementEvent.AdvancementEarnEvent (one server-earned advancement)");
        result.put("hunting", "LivingDeathEvent/LivingDamageEvent.Post (server-recorded qualifying contribution)");
        result.put("building", "BlockEvent.EntityPlaceEvent (builder placement on a retained claim)");
        result.put("farming", "BlockDropsEvent/BabyEntitySpawnEvent (mature crop harvest or breeding completion)");
        return Map.copyOf(result);
    }

    public static void onDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        if (BossEncounterRuntime.isManagedEncounterEntity(event.getEntity())) {
            return;
        }
        long timestamp = System.currentTimeMillis();
        ServerPlayer player = playerFrom(event.getSource().getEntity());
        if (event.getHealthDamage() > 0 && player != null
                && !player.getUUID().equals(event.getEntity().getUUID())
                && COMBAT_CONTRIBUTIONS.record(event.getEntity().getUUID(), player.getUUID(),
                        event.getHealthDamage(), timestamp)) {
            award(player, COMBAT, 1, "combat:" + event.getEntity().getUUID());
        }
        if (event.getEntity().isDeadOrDying() && !(event.getEntity() instanceof ServerPlayer)) {
            awardHunting(level.getServer(), event.getEntity().getUUID(), timestamp);
        }
    }

    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() || event.getEntity() instanceof ServerPlayer
                || !(event.getEntity().level() instanceof ServerLevel)
                || BossEncounterRuntime.isManagedEncounterEntity(event.getEntity())) {
            return;
        }
        COMBAT_CONTRIBUTIONS.markDeath(event.getEntity().getUUID(), System.currentTimeMillis());
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        long timestamp = System.currentTimeMillis();
        for (CombatContributionTracker.HuntingCredit credit
                : COMBAT_CONTRIBUTIONS.drainPendingDeaths(timestamp)) {
            for (UUID playerId : credit.playerIds()) {
                award(event.getServer(), playerId, HUNTING, 1,
                        "hunting:" + credit.targetId(), timestamp);
            }
        }
    }

    private static void awardHunting(MinecraftServer server, UUID targetId, long timestamp) {
        List<UUID> creditedPlayers = COMBAT_CONTRIBUTIONS.consumePendingHuntingCredit(targetId, timestamp);
        for (UUID playerId : creditedPlayers) {
            award(server, playerId, HUNTING, 1, "hunting:" + targetId, timestamp);
        }
    }

    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        ServerPlayer player = playerFrom(event.getEntity());
        if (player == null || event.getCrafting().isEmpty()
                || event.getCrafting().get(DataComponents.FOOD) == null) {
            return;
        }
        award(player, COOKING, 1, "cooking:" + net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(event.getCrafting().getItem()));
    }

    public static void onSmelted(PlayerEvent.ItemSmeltedEvent event) {
        ServerPlayer player = playerFrom(event.getEntity());
        if (player == null || event.getSmelting().isEmpty()
                || event.getSmelting().get(DataComponents.FOOD) == null) {
            return;
        }
        award(player, COOKING, 1, "cooking:" + net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(event.getSmelting().getItem()));
    }

    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        ServerPlayer player = playerFrom(event.getEntity());
        if (player == null) {
            return;
        }
        AdvancementHolder advancement = event.getAdvancement();
        if (!ActivityXpConfig.isExplorationAdvancement(advancement.id())) {
            return;
        }
        award(player, EXPLORATION, 1, "exploration:" + advancement.id());
    }

    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (isMiningResource(event.getPlacedBlock())) {
            ActivityWorldSavedData.get(level.getServer()).markSynthetic(level.dimension(), event.getPos());
        }
        ServerPlayer player = playerFrom(event.getEntity());
        if (player == null || !buildingAllowed(player, level, event.getPos())) {
            return;
        }
        award(player, BUILDING, 1, blockSource(BUILDING, level, event.getPos()));
    }

    public static void onBlockDrops(BlockDropsEvent event) {
        Optional<Identifier> activity = blockActivity(event.getState());
        if (event.isCanceled() || activity.isEmpty()) {
            return;
        }
        if (activity.orElseThrow().equals(MINING)
                && ActivityWorldSavedData.get(event.getLevel().getServer())
                        .consumeSynthetic(event.getLevel().dimension(), event.getPos())) {
            return;
        }
        ServerPlayer player = playerFrom(event.getBreaker());
        if (player == null || !blockActionAllowed(player, event.getLevel(), event.getPos())) {
            return;
        }
        award(player, activity.orElseThrow(), 1,
                blockSource(activity.orElseThrow(), event.getLevel(), event.getPos()));
    }

    public static void onPistonPre(PistonEvent.Pre event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) {
            return;
        }
        net.minecraft.core.Direction movement = event.getPistonMoveType().isExtend
                ? event.getDirection()
                : event.getDirection().getOpposite();
        ActivityWorldSavedData.get(level.getServer())
                .propagatePistonMove(level.dimension(), resolver.getToPush(), movement);
    }

    public static void onBreeding(BabyEntitySpawnEvent event) {
        ServerPlayer player = playerFrom(event.getCausedByPlayer());
        if (event.isCanceled() || player == null || event.getChild() == null
                || !(event.getParentA().level() instanceof ServerLevel level)
                || event.getParentB().level() != level || event.getChild().level() != level
                || !actionAllowed(player, level, event.getParentA().blockPosition(),
                        ClaimProtectionService.Action.ENTITY)
                || !actionAllowed(player, level, event.getParentB().blockPosition(),
                        ClaimProtectionService.Action.ENTITY)
                || !actionAllowed(player, level, event.getChild().blockPosition(),
                        ClaimProtectionService.Action.ENTITY)) {
            return;
        }
        award(player, FARMING, 1, "farming:breeding:" + event.getChild().getUUID());
    }

    public static Optional<Identifier> blockActivity(BlockState state) {
        if (state == null) {
            return Optional.empty();
        }
        if (matureCrop(state)) {
            return Optional.of(FARMING);
        }
        return isMiningResource(state) ? Optional.of(MINING) : Optional.empty();
    }

    private static boolean isMiningResource(BlockState state) {
        return state != null && state.is(Tags.Blocks.ORES);
    }

    private static boolean matureCrop(BlockState state) {
        if (!state.is(BlockTags.CROPS)) {
            return false;
        }
        for (var property : state.getProperties()) {
            if (property instanceof IntegerProperty age && age.getName().equals("age")) {
                int maximum = age.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
                return state.getValue(age) == maximum;
            }
        }
        return true;
    }

    private static boolean blockActionAllowed(ServerPlayer player, ServerLevel level, net.minecraft.core.BlockPos pos) {
        return actionAllowed(player, level, pos, ClaimProtectionService.Action.BUILD);
    }

    private static boolean buildingAllowed(ServerPlayer player, ServerLevel level, net.minecraft.core.BlockPos pos) {
        if (!blockActionAllowed(player, level, pos)) {
            return false;
        }
        Claim claim = PlatformSavedData.get(level.getServer())
                .claim(ClaimKey.at(level.dimension(), pos))
                .orElse(null);
        return claim != null && claim.roleOf(player.getUUID()).atLeast(ClaimRole.BUILDER);
    }

    private static boolean actionAllowed(
            ServerPlayer player,
            ServerLevel level,
            net.minecraft.core.BlockPos pos,
            ClaimProtectionService.Action action) {
        var server = level.getServer();
        ClaimKey key = ClaimKey.at(level.dimension(), pos);
        return ClaimProtectionService.evaluate(
                PlatformSavedData.get(server),
                player.getUUID(),
                false,
                server.overworld().dimension(),
                server.overworld().getRespawnData().pos(),
                ClaimConfig.protectedSpawnRadiusChunks(),
                key,
                action).allowed();
    }

    private static String blockSource(
            Identifier activity, ServerLevel level, net.minecraft.core.BlockPos position) {
        return activity.getPath() + ":" + level.dimension().identifier() + ":" + position.asLong();
    }

    private static void award(ServerPlayer player, Identifier activity, long amount, String source) {
        MinecraftServer server = player.level().getServer();
        if (server == null || player.level().isClientSide()) return;
        award(server, player.getUUID(), activity, amount, source, System.currentTimeMillis());
    }

    private static void award(
            MinecraftServer server,
            UUID playerId,
            Identifier activity,
            long amount,
            String source,
            long timestamp) {
        UUID transactionId = UUID.randomUUID();
        RpgPlayerSavedData state = RpgPlayerSavedData.get(server);
        RpgDefinitionSnapshot definitions = RpgDefinitionReloadListener.snapshot(server);
        boolean retainQuestEvidence = QuestProgressRuntime.shouldCaptureActivityEvidence(
                server, playerId, activity);
        if (retainQuestEvidence) {
            QuestProgressRuntime.prepareActivityEvidenceCapacity(server, playerId, timestamp);
        }
        var result = retainQuestEvidence
                ? ActivityXpAwardService.awardObservedActivity(
                        state, definitions, playerId, activity, amount, timestamp, transactionId, source)
                : ActivityXpAwardService.award(
                        state, definitions, playerId, activity, amount, timestamp, transactionId, source);
        if (retainQuestEvidence && result.status() == ActivityXpAwardService.Status.SUCCESS) {
            QuestProgressRuntime.acceptActivityEvidence(server, playerId, transactionId);
        }
    }

    private static ServerPlayer playerFrom(Entity entity) {
        if (entity instanceof ServerPlayer player && !(player instanceof FakePlayer)) return player;
        if (entity instanceof Projectile projectile && projectile.getOwner() instanceof ServerPlayer player
                && !(player instanceof FakePlayer)) return player;
        return null;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }
}

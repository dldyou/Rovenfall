package org.dldyou.rovenfall.administration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.TriState;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.level.block.CreateFluidSourceEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.dldyou.rovenfall.claims.ClaimConfig;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRole;
import org.dldyou.rovenfall.world.WorldTopology;

public final class ClaimProtectionEvents {
    private static final long FEEDBACK_INTERVAL_MILLIS = 1_000L;
    private final Map<UUID, Long> lastFeedbackByPlayer = new HashMap<>();
    private final Map<UUID, SafePosition> lastAllowedPositionByPlayer = new HashMap<>();

    private ClaimProtectionEvents() {
    }

    public static void register(IEventBus eventBus) {
        ClaimProtectionEvents handler = new ClaimProtectionEvents();
        eventBus.addListener(handler::onBreakBlock);
        eventBus.addListener(handler::onLeftClickBlock);
        eventBus.addListener(handler::onRightClickBlock);
        eventBus.addListener(handler::onRightClickItem);
        eventBus.addListener(handler::onUseItemOnBlock);
        eventBus.addListener(handler::onEntityInteract);
        eventBus.addListener(handler::onAttackEntity);
        eventBus.addListener(handler::onEntityMount);
        eventBus.addListener(handler::onEntityInvulnerabilityCheck);
        eventBus.addListener(handler::onProjectileImpact);
        eventBus.addListener(handler::onEntityPlace);
        eventBus.addListener(handler::onBlockToolModification);
        eventBus.addListener(handler::onFarmlandTrample);
        eventBus.addListener(handler::onLivingDestroyBlock);
        eventBus.addListener(handler::onFluidPlaceBlock);
        eventBus.addListener(handler::onCreateFluidSource);
        eventBus.addListener(handler::onNeighborNotify);
        eventBus.addListener(handler::onPistonPre);
        eventBus.addListener(handler::onExplosionDetonate);
        eventBus.addListener(handler::onEntityJoinLevel);
        eventBus.addListener(handler::onPlayerTick);
        eventBus.addListener(handler::onPlayerLoggedOut);
    }

    private void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        boolean locked = PlatformSavedData.get(level.getServer()).isWildernessOperationLocked();
        if (blocksEntityJoin(WorldTopology.isWilderness(level.dimension()), locked, event.loadedFromDisk())) {
            event.setCanceled(true);
        }
    }

    static boolean blocksEntityJoin(boolean wilderness, boolean operationLocked, boolean loadedFromDisk) {
        return wilderness && operationLocked && !loadedFromDisk;
    }

    private void onBreakBlock(BreakBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (denyAny(level, affectedBlockPositions(level, event.getPos()), player,
                ClaimProtectionService.Action.BUILD)) {
            event.setCanceled(true);
            event.setNotifyClient(true);
        }
    }

    private void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel() instanceof ServerLevel level
                && event.getEntity() instanceof ServerPlayer player
                && deny(level, event.getPos(), player, ClaimProtectionService.Action.BUILD)) {
            event.setCanceled(true);
        }
    }

    private void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Access access = firstDeniedAccess(
                level,
                affectedBlockPositions(level, event.getPos()),
                player,
                ClaimProtectionService.Action.INTERACT);
        if (access != null) {
            deny(access, player);
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }
        access = access(level, event.getPos(), player, ClaimProtectionService.Action.INTERACT);
        if (access.decision.reason() != ClaimProtectionService.Reason.OUTSIDE_HUB
                && !access.decision.role().atLeast(ClaimRole.BUILDER)) {
            event.setUseItem(TriState.FALSE);
        }
    }

    private void onUseItemOnBlock(UseItemOnBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        List<BlockPos> targets = affectedBlockPositions(level, event.getPos());
        if (event.getItemStack().getItem() instanceof FlintAndSteelItem
                || event.getItemStack().getItem() instanceof FireChargeItem) {
            BlockPos fireTarget = event.getPos();
            BlockState modified = level.getBlockState(fireTarget).getToolModifiedState(
                    event.getUseOnContext(),
                    net.neoforged.neoforge.common.ItemAbilities.FIRESTARTER_LIGHT,
                    false);
            if (modified == null && event.getFace() != null) {
                fireTarget = fireTarget.relative(event.getFace());
            }
            targets = List.of(fireTarget);
        }
        if (denyAny(level, targets, player, ClaimProtectionService.Action.BUILD)) {
            event.cancelWithResult(InteractionResult.FAIL);
        }
    }

    private void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (event.getItemStack().getItem() instanceof BucketItem bucket) {
            protectBucketUse(event, level, player, bucket);
            return;
        }
        if (!(event.getItemStack().getItem() instanceof BoatItem)) {
            return;
        }
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getViewVector(1.0F).scale(5.0D));
        HitResult hit = level.clip(new ClipContext(
                start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
        if (hit.getType() == HitResult.Type.BLOCK
                && deny(level, BlockPos.containing(hit.getLocation()), player,
                        ClaimProtectionService.Action.BUILD)) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    private void protectBucketUse(
            PlayerInteractEvent.RightClickItem event,
            ServerLevel level,
            ServerPlayer player,
            BucketItem bucket) {
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));
        HitResult result = level.clip(new ClipContext(
                start, end, ClipContext.Block.OUTLINE, bucket.getFluidContext(), player));
        if (!(result instanceof BlockHitResult hit)) {
            return;
        }
        BlockPos clicked = hit.getBlockPos();
        BlockPos target = clicked;
        if (bucket.content != Fluids.EMPTY) {
            var clickedState = level.getBlockState(clicked);
            boolean containedWater = bucket.content == Fluids.WATER
                    && clickedState.getBlock() instanceof LiquidBlockContainer container
                    && container.canPlaceLiquid(player, level, clicked, clickedState, bucket.content);
            target = containedWater ? clicked : clicked.relative(hit.getDirection());
        }
        if (deny(level, target, player, ClaimProtectionService.Action.BUILD)) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    private void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel() instanceof ServerLevel level
                && event.getEntity() instanceof ServerPlayer player
                && deny(level, event.getTarget().blockPosition(), player, ClaimProtectionService.Action.ENTITY)) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    private void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && player.level() instanceof ServerLevel level
                && deny(level, event.getTarget().blockPosition(), player, ClaimProtectionService.Action.ENTITY)) {
            event.setCanceled(true);
        }
    }

    private void onEntityMount(EntityMountEvent event) {
        if (!event.isMounting() || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntityMounting() instanceof ServerPlayer player)
                || event.getEntityBeingMounted() == null) {
            return;
        }
        if (deny(level, event.getEntityBeingMounted().blockPosition(), player,
                ClaimProtectionService.Action.ENTITY)) {
            event.setCanceled(true);
        }
    }

    private void onEntityInvulnerabilityCheck(EntityInvulnerabilityCheckEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level
                && event.getSource().getEntity() instanceof ServerPlayer player
                && deny(level, event.getEntity().blockPosition(), player,
                        ClaimProtectionService.Action.ENTITY)) {
            event.setInvulnerable(true);
        }
    }

    private void onProjectileImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile().level() instanceof ServerLevel level)) {
            return;
        }
        ClaimProtectionService.Action action = event.getProjectile()
                instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow
                ? ClaimProtectionService.Action.INTERACT
                : ClaimProtectionService.Action.BUILD;
        List<BlockPos> targets;
        if (event.getRayTraceResult() instanceof BlockHitResult hit) {
            targets = new ArrayList<>(affectedBlockPositions(level, hit.getBlockPos()));
            if (event.getProjectile() instanceof SmallFireball) {
                targets.add(hit.getBlockPos().relative(hit.getDirection()));
            }
        } else if (event.getRayTraceResult() instanceof EntityHitResult hit) {
            targets = List.of(hit.getEntity().blockPosition());
            action = ClaimProtectionService.Action.ENTITY;
        } else {
            return;
        }
        boolean denied;
        if (event.getProjectile().getOwner() instanceof ServerPlayer player) {
            denied = denyAny(level, targets, player, action);
        } else {
            Entity owner = event.getProjectile().getOwner();
            ClaimKey source = owner == null ? null : ClaimKey.at(level.dimension(), owner.blockPosition());
            ClaimKey deniedTarget = targets.stream()
                    .map(target -> ClaimKey.at(level.dimension(), target))
                    .filter(target -> source == null
                            ? !systemMayModify(level, target)
                            : !environmentMayModify(level, source, target))
                    .findFirst()
                    .orElse(null);
            denied = deniedTarget != null;
            if (denied) {
                auditEnvironmentDenied(level, deniedTarget, action);
            }
        }
        if (denied) {
            event.getProjectile().discard();
            event.setCanceled(true);
        }
    }

    private void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            boolean denied = event instanceof BlockEvent.EntityMultiPlaceEvent multi
                    ? multi.getReplacedBlockSnapshots().stream()
                            .anyMatch(snapshot -> deny(
                                    level, snapshot.getPos(), player, ClaimProtectionService.Action.BUILD))
                    : deny(level, event.getPos(), player, ClaimProtectionService.Action.BUILD);
            if (denied) {
                event.setCanceled(true);
            }
            return;
        }
        ClaimKey source = ClaimKey.at(level.dimension(), event.getPos());
        if (!systemMayModify(level, source)) {
            auditEnvironmentDenied(level, source, ClaimProtectionService.Action.BUILD);
            event.setCanceled(true);
        }
    }

    private void onBlockToolModification(BlockEvent.BlockToolModificationEvent event) {
        if (!event.isSimulated() && event.getLevel() instanceof ServerLevel level
                && event.getPlayer() instanceof ServerPlayer player
                && deny(level, event.getPos(), player, ClaimProtectionService.Action.BUILD)) {
            event.setCanceled(true);
        }
    }

    private void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            if (deny(level, event.getPos(), player, ClaimProtectionService.Action.BUILD)) {
                event.setCanceled(true);
            }
            return;
        }
        ClaimKey key = ClaimKey.at(level.dimension(), event.getPos());
        if (!systemMayModify(level, key)) {
            auditEnvironmentDenied(level, key, ClaimProtectionService.Action.BUILD);
            event.setCanceled(true);
        }
    }

    private void onLivingDestroyBlock(LivingDestroyBlockEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            if (deny(level, event.getPos(), player, ClaimProtectionService.Action.BUILD)) {
                event.setCanceled(true);
            }
            return;
        }
        ClaimKey target = ClaimKey.at(level.dimension(), event.getPos());
        if (!systemMayModify(level, target)) {
            auditEnvironmentDenied(level, target, ClaimProtectionService.Action.BUILD);
            event.setCanceled(true);
        }
    }

    private void onFluidPlaceBlock(BlockEvent.FluidPlaceBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ClaimKey source = ClaimKey.at(level.dimension(), event.getLiquidPos());
        ClaimKey target = ClaimKey.at(level.dimension(), event.getPos());
        if (!environmentMayModify(level, source, target)) {
            auditEnvironmentDenied(level, target, ClaimProtectionService.Action.BUILD);
            event.setCanceled(true);
        }
    }

    private void onCreateFluidSource(CreateFluidSourceEvent event) {
        ClaimKey key = ClaimKey.at(event.getLevel().dimension(), event.getPos());
        if (!environmentMayModify(event.getLevel(), key, key)) {
            auditEnvironmentDenied(event.getLevel(), key, ClaimProtectionService.Action.BUILD);
            event.setCanConvert(false);
        }
    }

    private void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ClaimKey source = ClaimKey.at(level.dimension(), event.getPos());
        boolean denied = event.getNotifiedSides().stream()
                .map(event.getPos()::relative)
                .map(pos -> ClaimKey.at(level.dimension(), pos))
                .anyMatch(target -> !environmentMayModify(level, source, target));
        if (denied) {
            auditEnvironmentDenied(level, source, ClaimProtectionService.Action.INTERACT);
            event.setCanceled(true);
        }
    }

    private void onPistonPre(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) {
            return;
        }
        ClaimKey piston = ClaimKey.at(level.dimension(), event.getPos());
        ClaimKey arm = ClaimKey.at(level.dimension(), event.getPos().relative(event.getDirection()));
        Direction movement = event.getPistonMoveType().isExtend
                ? event.getDirection()
                : event.getDirection().getOpposite();
        boolean denied = !environmentMayModify(level, piston, arm)
                || resolver.getToDestroy().stream()
                        .map(pos -> ClaimKey.at(level.dimension(), pos))
                        .anyMatch(target -> !environmentMayModify(level, piston, target))
                || resolver.getToPush().stream().anyMatch(pos -> {
                    ClaimKey source = ClaimKey.at(level.dimension(), pos);
                    ClaimKey target = ClaimKey.at(level.dimension(), pos.relative(movement));
                    return !environmentMayModify(level, piston, source)
                            || !environmentMayModify(level, piston, target);
                });
        if (denied) {
            auditEnvironmentDenied(level, piston, ClaimProtectionService.Action.BUILD);
            event.setCanceled(true);
        }
    }

    private void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Entity sourceEntity = event.getExplosion().getIndirectSourceEntity();
        if (sourceEntity == null) {
            sourceEntity = event.getExplosion().getDirectSourceEntity();
        }
        ServerPlayer player = sourceEntity instanceof ServerPlayer value ? value : null;
        event.getAffectedBlocks().removeIf(pos -> {
            ClaimKey target = ClaimKey.at(level.dimension(), pos);
            Access playerAccess = player == null
                    ? null
                    : access(level, pos, player, ClaimProtectionService.Action.BUILD);
            boolean denied = playerAccess == null
                    ? !systemMayModify(level, target)
                    : !playerAccess.decision.allowed();
            if (denied) {
                if (player == null) {
                    auditEnvironmentDenied(level, target, ClaimProtectionService.Action.BUILD);
                } else {
                    deny(playerAccess, player);
                }
            }
            return denied;
        });
        event.getAffectedEntities().removeIf(entity -> {
            if (player == null) {
                ClaimKey target = ClaimKey.at(level.dimension(), entity.blockPosition());
                boolean denied = !systemMayModify(level, target);
                if (denied) {
                    auditEnvironmentDenied(level, target, ClaimProtectionService.Action.ENTITY);
                }
                return denied;
            }
            Access access = access(level, entity.blockPosition(), player, ClaimProtectionService.Action.ENTITY);
            if (!access.decision.allowed()) {
                deny(access, player);
                return true;
            }
            return false;
        });
    }

    private void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        Access access = access(level, player.blockPosition(), player, ClaimProtectionService.Action.ENTRY);
        if (access.decision.allowed()) {
            lastAllowedPositionByPlayer.put(player.getUUID(), new SafePosition(level.dimension(), player.position()));
            return;
        }
        deny(access, player);
        SafePosition safe = lastAllowedPositionByPlayer.get(player.getUUID());
        if (safe != null && safe.dimension.equals(level.dimension())) {
            player.teleportTo(safe.position.x, safe.position.y, safe.position.z);
            return;
        }
        BlockPos spawn = level.getServer().overworld().getRespawnData().pos();
        player.teleportTo(spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5);
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerId = event.getEntity().getUUID();
        lastFeedbackByPlayer.remove(playerId);
        lastAllowedPositionByPlayer.remove(playerId);
    }

    private boolean deny(
            ServerLevel level,
            BlockPos position,
            ServerPlayer player,
            ClaimProtectionService.Action action) {
        Access access = access(level, position, player, action);
        if (access.decision.allowed()) {
            return false;
        }
        deny(access, player);
        return true;
    }

    private boolean denyAny(
            ServerLevel level,
            List<BlockPos> positions,
            ServerPlayer player,
            ClaimProtectionService.Action action) {
        Access denied = firstDeniedAccess(level, positions, player, action);
        if (denied == null) {
            return false;
        }
        deny(denied, player);
        return true;
    }

    private Access firstDeniedAccess(
            ServerLevel level,
            List<BlockPos> positions,
            ServerPlayer player,
            ClaimProtectionService.Action action) {
        for (BlockPos position : positions) {
            Access candidate = access(level, position, player, action);
            if (!candidate.decision.allowed()) {
                return candidate;
            }
        }
        return null;
    }

    static List<BlockPos> affectedBlockPositions(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        List<BlockPos> positions = new ArrayList<>(2);
        positions.add(position.immutable());
        BlockPos connected = null;
        if (state.getBlock() instanceof ChestBlock
                && state.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE) {
            connected = ChestBlock.getConnectedBlockPos(position, state);
        } else if (state.getBlock() instanceof BedBlock) {
            connected = position.relative(BedBlock.getConnectedDirection(state));
        } else if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            connected = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                    ? position.above()
                    : position.below();
        }
        if (connected != null && level.getBlockState(connected).is(state.getBlock())) {
            positions.add(connected.immutable());
        }
        return List.copyOf(positions);
    }

    private void deny(Access access, ServerPlayer player) {
        long now = Instant.now().toEpochMilli();
        ClaimProtectionService.auditDenied(
                access.state, player.getUUID(), access.key, access.action, access.decision, now);
        Long previous = lastFeedbackByPlayer.get(player.getUUID());
        if (player.connection != null
                && (previous == null || now - previous >= FEEDBACK_INTERVAL_MILLIS)) {
            lastFeedbackByPlayer.put(player.getUUID(), now);
            player.sendOverlayMessage(Component.translatable(access.action.denialTranslationKey()));
        }
    }

    private Access access(
            ServerLevel level,
            BlockPos position,
            ServerPlayer player,
            ClaimProtectionService.Action action) {
        PlatformSavedData state = PlatformSavedData.get(level.getServer());
        ClaimKey key = ClaimKey.at(level.dimension(), position);
        if (player instanceof FakePlayer) {
            return new Access(state, key, action, new ClaimProtectionService.Decision(
                    false, ClaimProtectionService.Reason.FAKE_PLAYER, ClaimRole.VISITOR, Optional.empty()));
        }
        var hub = level.getServer().overworld();
        boolean nativeOverride = player.permissions().hasPermission(Permissions.COMMANDS_OWNER)
                && !state.hasAnyAdminRoles();
        var decision = ClaimProtectionService.evaluate(
                state,
                player.getUUID(),
                nativeOverride,
                WorldTopology.HUB,
                hub.getRespawnData().pos(),
                ClaimConfig.protectedSpawnRadiusChunks(),
                key,
                action);
        return new Access(state, key, action, decision);
    }

    private boolean environmentMayModify(ServerLevel level, ClaimKey source, ClaimKey target) {
        return ClaimProtectionHooks.environmentMayModify(level, source, target);
    }

    private boolean systemMayModify(ServerLevel level, ClaimKey target) {
        return ClaimProtectionHooks.systemMayModify(level, target);
    }

    private void auditEnvironmentDenied(
            ServerLevel level, ClaimKey key, ClaimProtectionService.Action action) {
        ClaimProtectionHooks.auditEnvironmentDenied(level, key, action);
    }

    private record Access(
            PlatformSavedData state,
            ClaimKey key,
            ClaimProtectionService.Action action,
            ClaimProtectionService.Decision decision) {
    }

    private record SafePosition(net.minecraft.resources.ResourceKey<Level> dimension, Vec3 position) {
    }
}

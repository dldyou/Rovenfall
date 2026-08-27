package org.dldyou.rovenfall.rpg;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.dldyou.rovenfall.administration.PlatformSavedData;
import org.dldyou.rovenfall.claims.Claim;
import org.dldyou.rovenfall.claims.ClaimKey;
import org.dldyou.rovenfall.claims.ClaimRole;

/** NeoForge adapters for server-observed activity outcomes. */
public final class RpgActivityEvents {
    private static final Identifier COMBAT = id("combat");
    private static final Identifier COOKING = id("cooking");
    private static final Identifier EXPLORATION = id("exploration");
    private static final Identifier HUNTING = id("hunting");
    private static final Identifier BUILDING = id("building");

    private RpgActivityEvents() {}

    public static Map<String, String> mapping() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("combat", "LivingDamageEvent.Post (positive applied health damage)");
        result.put("cooking", "PlayerEvent.ItemCraftedEvent/ItemSmeltedEvent (completed food result)");
        result.put("mining", "HELD: no native event proves natural generation; fail-closed");
        result.put("exploration", "AdvancementEvent.AdvancementEarnEvent (one server-earned advancement)");
        result.put("hunting", "LivingDeathEvent (server death credited to player)");
        result.put("building", "BlockEvent.EntityPlaceEvent (player placement on owned builder claim)");
        result.put("farming", "HELD: no native event proves mature harvest/breeding completion; fail-closed");
        return Map.copyOf(result);
    }

    public static void onDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || event.getHealthDamage() <= 0) return;
        ServerPlayer player = playerFrom(event.getSource().getEntity());
        if (player == null || player.getUUID().equals(event.getEntity().getUUID())) return;
        award(player, COMBAT, 1, "combat:" + event.getEntity().getUUID());
    }

    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel) || event.getEntity() instanceof ServerPlayer) return;
        ServerPlayer player = playerFrom(event.getSource().getEntity());
        if (player == null || player.getUUID().equals(event.getEntity().getUUID())) return;
        award(player, HUNTING, 1, "hunting:" + event.getEntity().getUUID());
    }

    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getCrafting().isEmpty()
                || event.getCrafting().get(DataComponents.FOOD) == null) return;
        award(player, COOKING, 1, "cooking:" + net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(event.getCrafting().getItem()));
    }

    public static void onSmelted(PlayerEvent.ItemSmeltedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getSmelting().isEmpty()
                || event.getSmelting().get(DataComponents.FOOD) == null) return;
        award(player, COOKING, 1, "cooking:" + net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(event.getSmelting().getItem()));
    }

    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        AdvancementHolder advancement = event.getAdvancement();
        award(player, EXPLORATION, 1, "exploration:" + advancement.id());
    }

    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) return;
        ClaimKey key = new ClaimKey(level.dimension(), event.getPos().getX() >> 4, event.getPos().getZ() >> 4);
        Claim claim = PlatformSavedData.get(level.getServer()).claim(key).orElse(null);
        if (claim == null || !claim.roleOf(player.getUUID()).atLeast(ClaimRole.BUILDER)) return;
        award(player, BUILDING, 1, "building:" + level.dimension().identifier() + ":" + event.getPos().asLong());
    }

    private static void award(ServerPlayer player, Identifier activity, long amount, String source) {
        MinecraftServer server = player.level().getServer();
        if (server == null || player.level().isClientSide()) return;
        ActivityXpAwardService.award(RpgPlayerSavedData.get(server), RpgDefinitionReloadListener.snapshot(server),
                player.getUUID(), activity, amount, System.currentTimeMillis(), UUID.randomUUID(), source);
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

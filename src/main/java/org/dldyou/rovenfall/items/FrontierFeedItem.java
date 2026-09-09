package org.dldyou.rovenfall.items;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.dldyou.rovenfall.Rovenfall;

public final class FrontierFeedItem extends Item {
    private static final TagKey<EntityType<?>> ELIGIBLE_ANIMALS = TagKey.create(
            Registries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(Rovenfall.MOD_ID, "frontier_feed_animals"));

    public FrontierFeedItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(
            ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (stack.isEmpty()
                || !(target instanceof Animal animal)
                || !animal.getType().builtInRegistryHolder().is(ELIGIBLE_ANIMALS)
                || animal.getAge() != 0
                || !animal.canFallInLove()) {
            return InteractionResult.PASS;
        }
        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer instanceof FakePlayer) {
            return InteractionResult.FAIL;
        }
        animal.setInLove(serverPlayer);
        stack.consume(1, serverPlayer);
        serverPlayer.awardStat(Stats.ITEM_USED.get(this));
        return InteractionResult.SUCCESS_SERVER;
    }
}

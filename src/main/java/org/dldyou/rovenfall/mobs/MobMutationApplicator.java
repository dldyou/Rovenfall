package org.dldyou.rovenfall.mobs;

import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.goal.LeapAtTargetGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsTargetGoal;
import org.dldyou.rovenfall.Rovenfall;

public final class MobMutationApplicator {
    public static final String MUTATION_TAG = "RovenfallMutation";

    private MobMutationApplicator() {
    }

    public static Optional<Identifier> mutationId(Mob mob) {
        String value = mob.getPersistentData().getStringOr(MUTATION_TAG, "");
        return value.isEmpty() ? Optional.empty() : Optional.ofNullable(Identifier.tryParse(value));
    }

    public static boolean apply(Mob mob, MobMutationCatalog.ResolvedMutation mutation, boolean loadedFromDisk) {
        if (mob == null || mutation == null) {
            return false;
        }
        Optional<Identifier> retained = mutationId(mob);
        if (retained.isPresent() && !retained.orElseThrow().equals(mutation.id())) {
            return false;
        }
        if (!loadedFromDisk && retained.isPresent()) {
            return false;
        }
        var definition = mutation.definition();
        for (var change : definition.attributes()) {
            var holder = BuiltInRegistries.ATTRIBUTE.get(change.attributeId()).orElse(null);
            if (holder == null) {
                return false;
            }
            var attribute = mob.getAttribute(holder);
            if (attribute == null) {
                return false;
            }
            Identifier modifierId = Identifier.fromNamespaceAndPath(
                    Rovenfall.MOD_ID,
                    "mutation/" + mutation.id().getPath() + "/" + change.attributeId().getPath());
            attribute.addOrReplacePermanentModifier(new AttributeModifier(
                    modifierId, change.amount(), change.operation()));
        }
        if (!loadedFromDisk) {
            mob.setHealth(mob.getMaxHealth());
        }
        mob.setGlowingTag(definition.glowing());
        mob.setCustomName(Component.translatable(definition.translationKey()).withStyle(ChatFormatting.GOLD));
        mob.setCustomNameVisible(true);
        if (!loadedFromDisk || retained.isPresent()) {
            addAi(mob, definition.aiModifier());
        }
        mob.getPersistentData().putString(MUTATION_TAG, mutation.id().toString());
        return true;
    }

    private static void addAi(Mob mob, MobMutationDefinition.AiModifier modifier) {
        switch (modifier) {
            case LEAP -> mob.goalSelector.addGoal(2, new LeapAtTargetGoal(mob, 0.45F));
            case RELENTLESS -> {
                if (mob instanceof PathfinderMob pathfinder) {
                    mob.goalSelector.addGoal(2, new MoveTowardsTargetGoal(pathfinder, 1.25, 32.0F));
                }
            }
        }
    }
}

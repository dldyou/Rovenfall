package org.dldyou.rovenfall.exploration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.dldyou.rovenfall.world.WorldTopology;

/** Server-owned discovery location and presentation metadata. */
public record ExplorationDefinition(
        String titleTranslationKey,
        String descriptionTranslationKey,
        int version,
        ResourceKey<Level> dimension,
        BlockPos position,
        int radius,
        boolean publicGuidance,
        Optional<Long> activityXp) {
    public static final int MAX_VERSION = 1_000_000;
    public static final int MAX_RADIUS = 64;
    public static final long MAX_ACTIVITY_XP = 1_000_000_000L;

    private static final Codec<Long> XP_CODEC = Codec.LONG.validate(value ->
            value >= 1 && value <= MAX_ACTIVITY_XP
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Exploration activity XP must be between 1 and " + MAX_ACTIVITY_XP));

    public static final Codec<ExplorationDefinition> CODEC = RecordCodecBuilder
            .<ExplorationDefinition>create(instance -> instance.group(
                    Codec.string(1, 160).fieldOf("title_translation_key")
                            .forGetter(ExplorationDefinition::titleTranslationKey),
                    Codec.string(1, 160).fieldOf("description_translation_key")
                            .forGetter(ExplorationDefinition::descriptionTranslationKey),
                    Codec.intRange(1, MAX_VERSION).fieldOf("version").forGetter(ExplorationDefinition::version),
                    Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(ExplorationDefinition::dimension),
                    BlockPos.CODEC.fieldOf("position").forGetter(ExplorationDefinition::position),
                    Codec.intRange(1, MAX_RADIUS).fieldOf("radius").forGetter(ExplorationDefinition::radius),
                    Codec.BOOL.optionalFieldOf("public_guidance", false)
                            .forGetter(ExplorationDefinition::publicGuidance),
                    XP_CODEC.optionalFieldOf("activity_xp").forGetter(ExplorationDefinition::activityXp)
            ).apply(instance, ExplorationDefinition::new)).validate(ExplorationDefinition::validate);

    public ExplorationDefinition {
        activityXp = activityXp == null ? Optional.empty() : activityXp;
    }

    public boolean isValid() {
        return titleTranslationKey != null && descriptionTranslationKey != null
                && version >= 1 && version <= MAX_VERSION
                && dimension != null && (WorldTopology.isHub(dimension) || WorldTopology.isWilderness(dimension))
                && position != null && Level.isInSpawnableBounds(position)
                && radius >= 1 && radius <= MAX_RADIUS
                && activityXp != null
                && activityXp.filter(value -> value < 1 || value > MAX_ACTIVITY_XP).isEmpty();
    }

    private static DataResult<ExplorationDefinition> validate(ExplorationDefinition definition) {
        return definition != null && definition.isValid()
                ? DataResult.success(definition)
                : DataResult.error(() -> "Exploration definition is invalid");
    }
}

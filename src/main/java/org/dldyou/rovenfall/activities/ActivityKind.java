package org.dldyou.rovenfall.activities;

import com.mojang.serialization.Codec;
import java.util.Optional;
import net.minecraft.util.StringRepresentable;

public enum ActivityKind implements StringRepresentable {
    COMBAT_DAMAGE("combat_damage", ActivityTrack.COMBAT),
    COMBAT_DEFENSE("combat_defense", ActivityTrack.COMBAT),
    COMBAT_SKILL_USE("combat_skill_use", ActivityTrack.COMBAT),
    COOKING_RESULT("cooking_result", ActivityTrack.COOKING),
    NATURAL_RESOURCE_BREAK("natural_resource_break", ActivityTrack.MINING),
    EXPLORATION_DISCOVERY("exploration_discovery", ActivityTrack.EXPLORATION),
    HUNTING_CONTRIBUTION("hunting_contribution", ActivityTrack.HUNTING),
    BUILDING_PLACEMENT("building_placement", ActivityTrack.BUILDING),
    MATURE_CROP_HARVEST("mature_crop_harvest", ActivityTrack.FARMING),
    BREEDING_COMPLETION("breeding_completion", ActivityTrack.FARMING);

    public static final Codec<ActivityKind> CODEC = StringRepresentable.fromEnum(ActivityKind::values);
    private final String id;
    private final ActivityTrack track;

    ActivityKind(String id, ActivityTrack track) {
        this.id = id;
        this.track = track;
    }

    public ActivityTrack track() {
        return track;
    }

    public Optional<String> validationError(ActivityTrack requestedTrack, ActivityProvenance provenance) {
        if (requestedTrack != track) {
            return Optional.of("activity kind " + id + " does not belong to track "
                    + (requestedTrack == null ? "null" : requestedTrack.getSerializedName()));
        }
        if (provenance == null) {
            return Optional.of("activity provenance is missing");
        }
        if (this == NATURAL_RESOURCE_BREAK && !provenance.natural()) {
            return Optional.of("natural resource evidence must be marked natural");
        }
        if (this == EXPLORATION_DISCOVERY && !provenance.firstDiscovery()) {
            return Optional.of("exploration evidence must be marked as a first discovery");
        }
        if (this == MATURE_CROP_HARVEST && !provenance.mature()) {
            return Optional.of("crop evidence must be marked mature");
        }
        if (this != NATURAL_RESOURCE_BREAK && provenance.natural()) {
            return Optional.of("natural provenance is not valid for " + id);
        }
        if (this != EXPLORATION_DISCOVERY && provenance.firstDiscovery()) {
            return Optional.of("first-discovery provenance is not valid for " + id);
        }
        if (this != MATURE_CROP_HARVEST && provenance.mature()) {
            return Optional.of("mature provenance is not valid for " + id);
        }
        return Optional.empty();
    }

    @Override
    public String getSerializedName() {
        return id;
    }
}

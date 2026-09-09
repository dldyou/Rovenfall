package org.dldyou.rovenfall.activities;

import com.mojang.serialization.Codec;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.util.StringRepresentable;

public enum ActivityTrack implements StringRepresentable {
    COMBAT("combat"),
    COOKING("cooking"),
    MINING("mining"),
    EXPLORATION("exploration"),
    HUNTING("hunting"),
    BUILDING("building"),
    FARMING("farming");

    public static final Codec<ActivityTrack> CODEC = StringRepresentable.fromEnum(ActivityTrack::values);
    private final String id;

    ActivityTrack(String id) {
        this.id = id;
    }

    public String translationKey() {
        return "activity_track.rovenfall." + id;
    }

    public static Optional<ActivityTrack> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(track -> track.id.equals(normalized)).findFirst();
    }

    @Override
    public String getSerializedName() {
        return id;
    }
}

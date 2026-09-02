package org.dldyou.rovenfall.mobs;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.level.Level;

public final class CinderWisp extends Blaze {
    public CinderWisp(EntityType<? extends Blaze> entityType, Level level) {
        super(entityType, level);
        xpReward = 16;
    }
}

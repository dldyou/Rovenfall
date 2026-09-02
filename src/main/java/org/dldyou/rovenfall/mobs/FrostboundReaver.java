package org.dldyou.rovenfall.mobs;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.skeleton.Stray;
import net.minecraft.world.level.Level;

public final class FrostboundReaver extends Stray {
    public FrostboundReaver(EntityType<? extends Stray> entityType, Level level) {
        super(entityType, level);
        xpReward = 14;
    }
}

package org.dldyou.rovenfall.mobs;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.level.Level;

public final class RuneboundArcher extends Skeleton {
    public RuneboundArcher(EntityType<? extends Skeleton> entityType, Level level) {
        super(entityType, level);
        xpReward = 10;
    }
}

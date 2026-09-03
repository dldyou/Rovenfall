package org.dldyou.rovenfall.mobs;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.level.Level;

public final class TideboundRaider extends Drowned {
    public TideboundRaider(EntityType<? extends Drowned> entityType, Level level) {
        super(entityType, level);
        xpReward = 14;
    }
}

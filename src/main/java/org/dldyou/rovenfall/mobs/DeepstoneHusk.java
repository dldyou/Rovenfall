package org.dldyou.rovenfall.mobs;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.Level;

public final class DeepstoneHusk extends Husk {
    public DeepstoneHusk(EntityType<? extends Husk> entityType, Level level) {
        super(entityType, level);
        xpReward = 15;
    }
}

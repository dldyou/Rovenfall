package org.dldyou.rovenfall.mobs;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;

public final class AshenStalker extends Zombie {
    public AshenStalker(EntityType<? extends Zombie> entityType, Level level) {
        super(entityType, level);
        xpReward = 8;
    }

    @Override
    protected boolean isSunSensitive() {
        return false;
    }
}

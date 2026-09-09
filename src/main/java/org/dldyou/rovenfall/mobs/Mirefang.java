package org.dldyou.rovenfall.mobs;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.spider.CaveSpider;
import net.minecraft.world.level.Level;

public final class Mirefang extends CaveSpider {
    public Mirefang(EntityType<? extends CaveSpider> entityType, Level level) {
        super(entityType, level);
        xpReward = 12;
    }
}

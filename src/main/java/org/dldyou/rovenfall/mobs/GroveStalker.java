package org.dldyou.rovenfall.mobs;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.level.Level;

public final class GroveStalker extends Spider {
    public GroveStalker(EntityType<? extends Spider> type, Level level) {
        super(type, level);
    }

    @Override
    protected int getBaseExperienceReward(ServerLevel level) {
        return RovenfallMobRuntime.experienceReward(level, RovenfallMobEntities.GROVE_STALKER_ID);
    }

    @Override
    protected void dropFromLootTable(ServerLevel level, DamageSource source, boolean playerKilled) {
        RovenfallMobRuntime.dropConfiguredLoot(
                this, level, source, playerKilled, RovenfallMobEntities.GROVE_STALKER_ID);
    }
}

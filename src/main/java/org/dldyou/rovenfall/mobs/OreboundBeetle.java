package org.dldyou.rovenfall.mobs;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.level.Level;

public final class OreboundBeetle extends Silverfish {
    public OreboundBeetle(EntityType<? extends Silverfish> type, Level level) {
        super(type, level);
    }

    @Override
    protected int getBaseExperienceReward(ServerLevel level) {
        return RovenfallMobRuntime.experienceReward(level, RovenfallMobEntities.OREBOUND_BEETLE_ID);
    }

    @Override
    protected void dropFromLootTable(ServerLevel level, DamageSource source, boolean playerKilled) {
        RovenfallMobRuntime.dropConfiguredLoot(
                this, level, source, playerKilled, RovenfallMobEntities.OREBOUND_BEETLE_ID);
    }
}

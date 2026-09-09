package org.dldyou.rovenfall.items;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class HuntingWeaponItem extends Item {
    private final HitEffect hitEffect;

    public HuntingWeaponItem(Properties properties, HitEffect hitEffect) {
        super(properties);
        this.hitEffect = hitEffect;
    }

    @Override
    public void postHurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        super.postHurtEnemy(stack, target, attacker);
        if (attacker.level().isClientSide()) {
            return;
        }
        switch (hitEffect) {
            case POISON -> target.addEffect(
                    new MobEffectInstance(MobEffects.POISON, hitEffect.durationTicks), attacker);
            case IGNITE -> target.igniteForTicks(hitEffect.durationTicks);
            case WEAKEN -> target.addEffect(
                    new MobEffectInstance(MobEffects.WEAKNESS, hitEffect.durationTicks), attacker);
        }
    }

    public enum HitEffect {
        POISON(20 * 3),
        IGNITE(20 * 4),
        WEAKEN(20 * 5);

        private final int durationTicks;

        HitEffect(int durationTicks) {
            this.durationTicks = durationTicks;
        }
    }
}

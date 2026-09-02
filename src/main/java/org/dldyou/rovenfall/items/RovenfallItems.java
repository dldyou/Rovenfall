package org.dldyou.rovenfall.items;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.Foods;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.Consumables;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.RemoveStatusEffectsConsumeEffect;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.dldyou.rovenfall.Rovenfall;

public final class RovenfallItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Rovenfall.MOD_ID);

    public static final DeferredItem<Item> ASHEN_RESIDUE = ITEMS.registerSimpleItem("ashen_residue");
    public static final DeferredItem<Item> RUNEBOUND_FRAGMENT = ITEMS.registerSimpleItem("runebound_fragment");
    public static final DeferredItem<Item> MIREFANG_GLAND = ITEMS.registerSimpleItem("mirefang_gland");
    public static final DeferredItem<Item> CINDER_CORE = ITEMS.registerSimpleItem(
            "cinder_core", properties -> properties.fireResistant());
    public static final DeferredItem<Item> FROSTBOUND_SHARD = ITEMS.registerSimpleItem("frostbound_shard");
    public static final DeferredItem<Item> TIDEBOUND_SCALE = ITEMS.registerSimpleItem("tidebound_scale");
    public static final DeferredItem<Item> DEEPSTONE_CORE = ITEMS.registerSimpleItem("deepstone_core");
    public static final DeferredItem<Item> WARDEN_CORE = ITEMS.registerSimpleItem(
            "warden_core",
            properties -> properties
                    .stacksTo(16)
                    .rarity(Rarity.EPIC)
                    .fireResistant()
                    .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
                    .component(DataComponents.LORE, new ItemLore(List.of(
                            Component.translatable("item.rovenfall.warden_core.effect")
                                    .withStyle(ChatFormatting.GRAY)))));
    public static final DeferredItem<Item> MIREGUARD_TONIC = ITEMS.registerSimpleItem(
            "mireguard_tonic",
            properties -> tonic(properties, mireguardEffect(), "item.rovenfall.mireguard_tonic.effect"));
    public static final DeferredItem<Item> CINDERWARD_TONIC = ITEMS.registerSimpleItem(
            "cinderward_tonic",
            properties -> tonic(properties, cinderwardEffect(), "item.rovenfall.cinderward_tonic.effect"));
    public static final DeferredItem<Item> ASHVEIL_TONIC = ITEMS.registerSimpleItem(
            "ashveil_tonic",
            properties -> tonic(properties, ashveilEffect(), "item.rovenfall.ashveil_tonic.effect"));
    public static final DeferredItem<Item> RUNEWARD_TONIC = ITEMS.registerSimpleItem(
            "runeward_tonic",
            properties -> tonic(properties, runewardEffect(), "item.rovenfall.runeward_tonic.effect"));
    public static final DeferredItem<Item> FROSTSTEP_TONIC = ITEMS.registerSimpleItem(
            "froststep_tonic",
            properties -> tonic(properties, froststepEffect(), "item.rovenfall.froststep_tonic.effect"));
    public static final DeferredItem<Item> TIDEBREATH_TONIC = ITEMS.registerSimpleItem(
            "tidebreath_tonic",
            properties -> tonic(properties, tidebreathEffect(), "item.rovenfall.tidebreath_tonic.effect"));
    public static final DeferredItem<Item> DEEPSIGHT_TONIC = ITEMS.registerSimpleItem(
            "deepsight_tonic",
            properties -> tonic(properties, deepsightEffect(), "item.rovenfall.deepsight_tonic.effect"));
    public static final DeferredItem<Item> FRONTIER_STEW = ITEMS.registerSimpleItem(
            "frontier_stew",
            properties -> properties
                    .stacksTo(16)
                    .rarity(Rarity.UNCOMMON)
                    .food(Foods.RABBIT_STEW, frontierStewEffect())
                    .usingConvertsTo(Items.BOWL)
                    .component(DataComponents.LORE, new ItemLore(List.of(
                            Component.translatable("item.rovenfall.frontier_stew.effect")
                                    .withStyle(ChatFormatting.GRAY)))));
    public static final DeferredItem<Item> HIGHLAND_CHEESE = ITEMS.registerSimpleItem(
            "highland_cheese",
            properties -> properties
                    .stacksTo(16)
                    .rarity(Rarity.UNCOMMON)
                    .food(Foods.COOKED_CHICKEN, highlandCheeseEffect())
                    .component(DataComponents.LORE, new ItemLore(List.of(
                            Component.translatable("item.rovenfall.highland_cheese.effect")
                                    .withStyle(ChatFormatting.GRAY)))));
    public static final DeferredItem<FrontierFeedItem> FRONTIER_FEED = ITEMS.registerItem(
            "frontier_feed",
            properties -> new FrontierFeedItem(properties.component(
                    DataComponents.LORE,
                    new ItemLore(List.of(Component.translatable("item.rovenfall.frontier_feed.effect")
                            .withStyle(ChatFormatting.GRAY))))));
    public static final DeferredItem<HuntingWeaponItem> MIREFANG_DAGGER = ITEMS.registerItem(
            "mirefang_dagger",
            properties -> new HuntingWeaponItem(
                    weapon(properties.sword(ToolMaterial.IRON, 2.0F, -1.8F),
                            "item.rovenfall.mirefang_dagger.effect"),
                    HuntingWeaponItem.HitEffect.POISON));
    public static final DeferredItem<HuntingWeaponItem> CINDERBRAND = ITEMS.registerItem(
            "cinderbrand",
            properties -> new HuntingWeaponItem(
                    weapon(properties.sword(ToolMaterial.IRON, 3.0F, -2.4F).fireResistant(),
                            "item.rovenfall.cinderbrand.effect"),
                    HuntingWeaponItem.HitEffect.IGNITE));
    public static final DeferredItem<HuntingWeaponItem> WARDENBREAKER = ITEMS.registerItem(
            "wardenbreaker",
            properties -> new HuntingWeaponItem(
                    weapon(properties.sword(ToolMaterial.NETHERITE, 4.0F, -2.4F).fireResistant(),
                            "item.rovenfall.wardenbreaker.effect")
                            .rarity(Rarity.EPIC)
                            .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true),
                    HuntingWeaponItem.HitEffect.WEAKEN));
    public static final DeferredItem<BossChallengeSigilItem> WARDEN_CHALLENGE_SIGIL = ITEMS.registerItem(
            "warden_challenge_sigil",
            properties -> new BossChallengeSigilItem(properties
                    .stacksTo(16)
                    .rarity(Rarity.EPIC)
                    .fireResistant()
                    .component(DataComponents.LORE, new ItemLore(List.of(
                            Component.translatable("item.rovenfall.warden_challenge_sigil.effect")
                                    .withStyle(ChatFormatting.GRAY))))));

    private RovenfallItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    private static Item.Properties tonic(Item.Properties properties, Consumable consumable, String loreKey) {
        return properties
                .stacksTo(16)
                .rarity(Rarity.UNCOMMON)
                .component(DataComponents.CONSUMABLE, consumable)
                .component(DataComponents.LORE, new ItemLore(List.of(
                        Component.translatable(loreKey).withStyle(ChatFormatting.GRAY))))
                .usingConvertsTo(Items.GLASS_BOTTLE);
    }

    private static Item.Properties weapon(Item.Properties properties, String loreKey) {
        return properties
                .rarity(Rarity.UNCOMMON)
                .component(DataComponents.LORE, new ItemLore(List.of(
                        Component.translatable(loreKey).withStyle(ChatFormatting.GRAY))));
    }

    private static Consumable mireguardEffect() {
        return Consumables.defaultDrink()
                .onConsume(new RemoveStatusEffectsConsumeEffect(MobEffects.POISON))
                .onConsume(new ApplyStatusEffectsConsumeEffect(
                        new MobEffectInstance(MobEffects.REGENERATION, 20 * 10)))
                .build();
    }

    private static Consumable cinderwardEffect() {
        return Consumables.defaultDrink()
                .onConsume(new ApplyStatusEffectsConsumeEffect(
                        new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 20 * 60 * 3)))
                .build();
    }

    private static Consumable ashveilEffect() {
        return Consumables.defaultDrink()
                .onConsume(new ApplyStatusEffectsConsumeEffect(
                        new MobEffectInstance(MobEffects.SPEED, 20 * 60)))
                .onConsume(new ApplyStatusEffectsConsumeEffect(
                        new MobEffectInstance(MobEffects.INVISIBILITY, 20 * 15)))
                .build();
    }

    private static Consumable runewardEffect() {
        return Consumables.defaultDrink()
                .onConsume(new ApplyStatusEffectsConsumeEffect(
                        new MobEffectInstance(MobEffects.ABSORPTION, 20 * 60 * 2)))
                .onConsume(new ApplyStatusEffectsConsumeEffect(
                        new MobEffectInstance(MobEffects.RESISTANCE, 20 * 30)))
                .build();
    }

    private static Consumable froststepEffect() {
        return Consumables.defaultDrink()
                .onConsume(new RemoveStatusEffectsConsumeEffect(MobEffects.SLOWNESS))
                .onConsume(new ApplyStatusEffectsConsumeEffect(
                        new MobEffectInstance(MobEffects.JUMP_BOOST, 20 * 60 * 2, 1)))
                .onConsume(new ApplyStatusEffectsConsumeEffect(
                        new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 30)))
                .build();
    }

    private static Consumable tidebreathEffect() {
        return Consumables.defaultDrink()
                .onConsume(new RemoveStatusEffectsConsumeEffect(MobEffects.MINING_FATIGUE))
                .onConsume(new ApplyStatusEffectsConsumeEffect(
                        new MobEffectInstance(MobEffects.WATER_BREATHING, 20 * 60 * 3)))
                .onConsume(new ApplyStatusEffectsConsumeEffect(
                        new MobEffectInstance(MobEffects.DOLPHINS_GRACE, 20 * 45)))
                .build();
    }

    private static Consumable deepsightEffect() {
        return Consumables.defaultDrink()
                .onConsume(new RemoveStatusEffectsConsumeEffect(MobEffects.DARKNESS))
                .onConsume(new ApplyStatusEffectsConsumeEffect(
                        new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * 60 * 5)))
                .onConsume(new ApplyStatusEffectsConsumeEffect(
                        new MobEffectInstance(MobEffects.HASTE, 20 * 60 * 2, 1)))
                .build();
    }

    private static Consumable frontierStewEffect() {
        return Consumables.defaultFood()
                .onConsume(new ApplyStatusEffectsConsumeEffect(
                        new MobEffectInstance(MobEffects.REGENERATION, 20 * 5)))
                .build();
    }

    private static Consumable highlandCheeseEffect() {
        return Consumables.defaultFood()
                .onConsume(new ApplyStatusEffectsConsumeEffect(
                        new MobEffectInstance(MobEffects.JUMP_BOOST, 20 * 45)))
                .build();
    }
}

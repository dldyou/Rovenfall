package org.dldyou.rovenfall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.dldyou.rovenfall.activities.ActivityTrack;
import org.dldyou.rovenfall.administration.ActiveSkillService;
import org.dldyou.rovenfall.administration.ActivityChallengeService;
import org.dldyou.rovenfall.administration.DailyContractService;
import org.dldyou.rovenfall.administration.WeeklyExpeditionService;
import org.dldyou.rovenfall.administration.CareerPromotionService;
import org.dldyou.rovenfall.administration.CareerSkillService;
import org.dldyou.rovenfall.administration.ClaimManagementService;
import org.dldyou.rovenfall.administration.ClaimProtectionService;
import org.dldyou.rovenfall.administration.ClaimPurchaseService;
import org.junit.jupiter.api.Test;

final class GameplayLocalizationCatalogTest {
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "admin_role.rovenfall.viewer",
            "admin_role.rovenfall.moderator",
            "admin_role.rovenfall.economy_manager",
            "admin_role.rovenfall.content_manager",
            "admin_role.rovenfall.owner",
            "shop_template.rovenfall.foundation",
            "shop_template.rovenfall.wilderness_outfitter",
            "command.rovenfall.admin.role.set.success",
            "command.rovenfall.admin.economy.grant.success",
            "command.rovenfall.admin.economy.error.insufficient_funds",
            "command.rovenfall.admin.shop.success",
            "command.rovenfall.admin.shop.error.invalid_request",
            "command.rovenfall.shop.info.header",
            "command.rovenfall.shop.info.offer",
            "command.rovenfall.shop.info.unavailable",
            "command.rovenfall.shop.info.stock_unlimited",
            "command.rovenfall.shop.info.stock_finite",
            "command.rovenfall.admin.snapshot.error.dependency_locked",
            "command.rovenfall.admin.snapshot.restore.duplicate",
            "command.rovenfall.admin.snapshot.error.invalid_transaction",
            "config.rovenfall.economy.initial_balance",
            "config.rovenfall.claims.sale_refund_percent",
            "config.rovenfall.claims.protected_spawn_radius_chunks",
            "claim_role.rovenfall.manager",
            "economy_transaction_kind.rovenfall.claim_sale",
            "command.rovenfall.claim.transfer.accept.success",
            "command.rovenfall.claim.sell.success",
            "command.rovenfall.claim.explain.buy",
            "command.rovenfall.claim.explain.action",
            "command.rovenfall.claim.explain.role",
            "message.rovenfall.claim.denied.build",
            "message.rovenfall.claim.denied.interact",
            "message.rovenfall.claim.denied.entity",
            "message.rovenfall.claim.denied.entry",
            "command.rovenfall.admin.wilderness.reset.scheduled",
            "message.rovenfall.wilderness.reset.complete",
            "wilderness_reset_phase.rovenfall.completed",
            "message.rovenfall.activity.awarded",
            "message.rovenfall.activity.awarded_with_career",
            "message.rovenfall.activity.level_up",
            "message.rovenfall.career.level_up",
            "command.rovenfall.activity.header",
            "command.rovenfall.activity.line_max",
            "command.rovenfall.activity.line_unavailable",
            "activity_challenge.rovenfall.first_steps",
            "activity_challenge_description.rovenfall.master_of_trades",
            "command.rovenfall.challenge.header",
            "command.rovenfall.challenge.info.requirement",
            "command.rovenfall.challenge.claim.success",
            "command.rovenfall.challenge.error.reward_failed",
            "daily_contract.rovenfall.iron_rush",
            "daily_contract_description.rovenfall.ashen_pursuit",
            "daily_contract.rovenfall.rune_breaker",
            "daily_contract_description.rovenfall.rune_breaker",
            "daily_contract.rovenfall.warden_trial",
            "daily_contract_description.rovenfall.warden_trial",
            "daily_contract.rovenfall.frozen_front",
            "daily_contract_description.rovenfall.frozen_front",
            "daily_contract.rovenfall.sunken_patrol",
            "daily_contract_description.rovenfall.sunken_patrol",
            "daily_contract.rovenfall.depths_watch",
            "daily_contract_description.rovenfall.depths_watch",
            "command.rovenfall.contract.header",
            "command.rovenfall.contract.claim.success",
            "weekly_expedition.rovenfall.supply_lines",
            "weekly_expedition_description.rovenfall.wilderness_campaign",
            "weekly_expedition.rovenfall.warden_oath",
            "weekly_expedition_description.rovenfall.warden_oath",
            "command.rovenfall.expedition.header",
            "command.rovenfall.expedition.claim.success",
            "career.rovenfall.adventurer",
            "career.rovenfall.warrior",
            "career.rovenfall.artisan",
            "career.rovenfall.scout",
            "command.rovenfall.career.info.header",
            "command.rovenfall.career.info.catalog.header",
            "command.rovenfall.career.info.catalog.line",
            "command.rovenfall.career.explain.summary",
            "command.rovenfall.career.promote.success",
            "command.rovenfall.career.promote.error",
            "career_skill.rovenfall.well_traveled",
            "career_skill.rovenfall.battle_focus",
            "career_skill.rovenfall.trailblazer",
            "career_skill.rovenfall.keen_senses",
            "command.rovenfall.skill.info.header",
            "command.rovenfall.skill.explain.unlock",
            "command.rovenfall.skill.explain.reset",
            "command.rovenfall.skill.unlock.success",
            "command.rovenfall.skill.reset.success",
            "command.rovenfall.skill.explain.active",
            "command.rovenfall.active_skill.slots.header",
            "message.rovenfall.active_skill.used",
            "key.rovenfall.active_skill_1",
            "item.rovenfall.mirefang_gland",
            "item.rovenfall.cinder_core",
            "item.rovenfall.frostbound_shard",
            "item.rovenfall.tidebound_scale",
            "item.rovenfall.deepstone_core",
            "item.rovenfall.ashen_residue",
            "item.rovenfall.runebound_fragment",
            "item.rovenfall.mireguard_tonic",
            "item.rovenfall.cinderward_tonic",
            "item.rovenfall.ashveil_tonic",
            "item.rovenfall.runeward_tonic",
            "item.rovenfall.froststep_tonic",
            "item.rovenfall.tidebreath_tonic",
            "item.rovenfall.deepsight_tonic",
            "item.rovenfall.frontier_stew",
            "item.rovenfall.frontier_feed",
            "item.rovenfall.highland_cheese",
            "item.rovenfall.mireguard_tonic.effect",
            "item.rovenfall.cinderward_tonic.effect",
            "item.rovenfall.ashveil_tonic.effect",
            "item.rovenfall.runeward_tonic.effect",
            "item.rovenfall.froststep_tonic.effect",
            "item.rovenfall.tidebreath_tonic.effect",
            "item.rovenfall.deepsight_tonic.effect",
            "item.rovenfall.frontier_stew.effect",
            "item.rovenfall.frontier_feed.effect",
            "item.rovenfall.highland_cheese.effect",
            "item.rovenfall.mirefang_dagger",
            "item.rovenfall.mirefang_dagger.effect",
            "item.rovenfall.cinderbrand",
            "item.rovenfall.cinderbrand.effect",
            "item.rovenfall.warden_challenge_sigil",
            "item.rovenfall.warden_challenge_sigil.effect",
            "item.rovenfall.warden_core",
            "item.rovenfall.warden_core.effect",
            "item.rovenfall.wardenbreaker",
            "item.rovenfall.wardenbreaker.effect",
            "advancements.rovenfall.wilderness.root.title",
            "advancements.rovenfall.wilderness.root.description",
            "advancements.rovenfall.wilderness.hunt_the_frontier.title",
            "advancements.rovenfall.wilderness.hunt_the_frontier.description",
            "advancements.rovenfall.wilderness.frontier_alchemist.title",
            "advancements.rovenfall.wilderness.frontier_alchemist.description",
            "advancements.rovenfall.wilderness.beneath_the_frontier.title",
            "advancements.rovenfall.wilderness.beneath_the_frontier.description",
            "advancements.rovenfall.wilderness.frontier_feast.title",
            "advancements.rovenfall.wilderness.frontier_feast.description",
            "advancements.rovenfall.wilderness.highland_herd.title",
            "advancements.rovenfall.wilderness.highland_herd.description",
            "advancements.rovenfall.wilderness.highland_provisions.title",
            "advancements.rovenfall.wilderness.highland_provisions.description",
            "advancements.rovenfall.wilderness.challenge_the_warden.title",
            "advancements.rovenfall.wilderness.challenge_the_warden.description",
            "advancements.rovenfall.wilderness.warden_defeated.title",
            "advancements.rovenfall.wilderness.warden_defeated.description",
            "advancements.rovenfall.wilderness.forge_the_wardenbreaker.title",
            "advancements.rovenfall.wilderness.forge_the_wardenbreaker.description",
            "entity.rovenfall.ashen_stalker",
            "entity.rovenfall.runebound_archer",
            "entity.rovenfall.mirefang",
            "entity.rovenfall.cinder_wisp",
            "entity.rovenfall.frostbound_reaver",
            "entity.rovenfall.tidebound_raider",
            "entity.rovenfall.deepstone_husk",
            "entity.rovenfall.arena_warden",
            "mutation.rovenfall.ashen",
            "mutation.rovenfall.tempest",
            "mutation.rovenfall.bulwark",
            "mutation.rovenfall.frenzied",
            "message.rovenfall.mutation.reward",
            "message.rovenfall.boss.reward",
            "message.rovenfall.boss.challenge_started",
            "message.rovenfall.boss.challenge_active",
            "message.rovenfall.boss.challenge_wrong_world",
            "message.rovenfall.boss.challenge_spawn_failed",
            "message.rovenfall.boss.challenge_unavailable",
            "command.rovenfall.admin.boss.start.success",
            "command.rovenfall.admin.boss.status",
            "command.rovenfall.admin.audit.header",
            "gui.rovenfall.admin.audit.summary",
            "gui.rovenfall.admin.audit.reason",
            "admin_search_scope.rovenfall.players",
            "admin_search_scope.rovenfall.alerts",
            "command.rovenfall.admin.search.error.invalid_scope",
            "command.rovenfall.admin.search.error.invalid_query",
            "gui.rovenfall.admin.search.summary",
            "gui.rovenfall.admin.search.player",
            "gui.rovenfall.admin.search.claim",
            "gui.rovenfall.admin.search.denied",
            "gui.rovenfall.admin.search.alert",
            "targeted_reversal_domain.rovenfall.claim_permission",
            "targeted_reversal_domain.rovenfall.skill",
            "command.rovenfall.admin.reverse.success",
            "command.rovenfall.admin.reverse.error.original_not_reversible",
            "command.rovenfall.admin.reverse.error.current_state_mismatch",
            "command.rovenfall.help.header",
            "command.rovenfall.help.claim",
            "command.rovenfall.help.challenge",
            "command.rovenfall.help.contract",
            "command.rovenfall.help.expedition",
            "command.rovenfall.help.skill",
            "command.rovenfall.admin.help.header",
            "command.rovenfall.admin.help.search",
            "command.rovenfall.admin.help.reverse",
            "command.rovenfall.admin.help.destructive"
    );

    @Test
    void supportedLanguageCatalogsHaveEqualKeySets() {
        Set<String> english = keys("en_us");
        assertEquals(english, keys("ko_kr"));
        assertEquals(english, keys("ja_jp"));
        assertTrue(english.containsAll(REQUIRED_KEYS));
    }

    @Test
    void claimEvaluationCatalogCoversEveryPublicRule() {
        Set<String> english = keys("en_us");
        for (ClaimProtectionService.Action action : ClaimProtectionService.Action.values()) {
            assertTrue(english.contains(action.translationKey()), action.translationKey());
        }
        for (ClaimProtectionService.Reason reason : ClaimProtectionService.Reason.values()) {
            assertTrue(english.contains(reason.translationKey()), reason.translationKey());
        }
        for (ClaimPurchaseService.Status status : Set.of(
                ClaimPurchaseService.Status.SUCCESS,
                ClaimPurchaseService.Status.INVALID_REQUEST,
                ClaimPurchaseService.Status.READ_ONLY_SCHEMA,
                ClaimPurchaseService.Status.NOT_IN_HUB,
                ClaimPurchaseService.Status.INELIGIBLE_CHUNK,
                ClaimPurchaseService.Status.PROTECTED_CHUNK,
                ClaimPurchaseService.Status.ALREADY_CLAIMED,
                ClaimPurchaseService.Status.OWNERSHIP_CAP_REACHED,
                ClaimPurchaseService.Status.ACCOUNT_NOT_FOUND,
                ClaimPurchaseService.Status.INVALID_CONFIGURATION,
                ClaimPurchaseService.Status.PRICE_OVERFLOW,
                ClaimPurchaseService.Status.INSUFFICIENT_FUNDS)) {
            assertTrue(english.contains(status.evaluationTranslationKey()), status.evaluationTranslationKey());
        }
        for (ClaimManagementService.Status status : Set.of(
                ClaimManagementService.Status.SUCCESS,
                ClaimManagementService.Status.NO_CHANGE,
                ClaimManagementService.Status.INVALID_REQUEST,
                ClaimManagementService.Status.READ_ONLY_SCHEMA,
                ClaimManagementService.Status.UNAUTHORIZED,
                ClaimManagementService.Status.CLAIM_NOT_FOUND,
                ClaimManagementService.Status.INVALID_TARGET,
                ClaimManagementService.Status.TRUST_LIMIT_REACHED)) {
            assertTrue(english.contains(status.evaluationTranslationKey()), status.evaluationTranslationKey());
        }
    }

    @Test
    void activityCatalogCoversEveryPermanentTrack() {
        Set<String> english = keys("en_us");
        for (ActivityTrack track : ActivityTrack.values()) {
            assertTrue(english.contains(track.translationKey()), track.translationKey());
        }
    }

    @Test
    void activityChallengeCatalogCoversEveryPublicResult() {
        Set<String> english = keys("en_us");
        for (ActivityChallengeService.Status status : ActivityChallengeService.Status.values()) {
            assertTrue(english.contains(status.translationKey()), status.translationKey());
        }
        for (String id : Set.of(
                "first_steps",
                "deep_delver",
                "homestead",
                "monster_hunter",
                "wilderness_veteran",
                "master_of_trades")) {
            assertTrue(english.contains("activity_challenge.rovenfall." + id), id);
            assertTrue(english.contains("activity_challenge_description.rovenfall." + id), id);
        }
    }

    @Test
    void dailyContractCatalogCoversEveryPublicResult() {
        Set<String> english = keys("en_us");
        for (DailyContractService.Status status : DailyContractService.Status.values()) {
            assertTrue(english.contains(status.translationKey()), status.translationKey());
        }
        for (String id : Set.of(
                "iron_rush", "harvest_rations", "camp_provisions",
                "zombie_cull", "bone_patrol", "ashen_pursuit", "rune_breaker",
                "mirefang_hunt", "cinder_containment", "frozen_front", "sunken_patrol",
                "depths_watch", "frontier_feast", "highland_herd", "highland_provisions",
                "warden_trial")) {
            assertTrue(english.contains("daily_contract.rovenfall." + id), id);
            assertTrue(english.contains("daily_contract_description.rovenfall." + id), id);
        }
    }

    @Test
    void weeklyExpeditionCatalogCoversEveryPublicResult() {
        Set<String> english = keys("en_us");
        for (WeeklyExpeditionService.Status status : WeeklyExpeditionService.Status.values()) {
            assertTrue(english.contains(status.translationKey()), status.translationKey());
        }
        for (String id : Set.of(
                "supply_lines", "threat_control", "wilderness_campaign", "frontier_anomalies",
                "warden_oath")) {
            assertTrue(english.contains("weekly_expedition.rovenfall." + id), id);
            assertTrue(english.contains("weekly_expedition_description.rovenfall." + id), id);
        }
    }

    @Test
    void careerPromotionCatalogCoversEveryPublicResult() {
        Set<String> english = keys("en_us");
        for (CareerPromotionService.Status status : CareerPromotionService.Status.values()) {
            assertTrue(english.contains(status.evaluationTranslationKey()),
                    status.evaluationTranslationKey());
        }
    }

    @Test
    void tierThreeCareerSpecializationsAreLocalized() {
        Set<String> english = keys("en_us");
        for (String id : Set.of(
                "vanguard", "slayer", "architect", "cultivator", "pathfinder", "ranger")) {
            assertTrue(english.contains("career.rovenfall." + id), id);
        }
        for (String id : Set.of(
                "shield_wall", "rallying_cry", "relentless_pursuit", "finishing_blow",
                "master_mason", "structural_insight", "green_thumb", "field_kitchen",
                "cartographer", "cave_sense", "farstrider", "marked_prey")) {
            assertTrue(english.contains("career_skill.rovenfall." + id), id);
        }
    }

    @Test
    void careerSkillCatalogCoversEveryPublicResult() {
        Set<String> english = keys("en_us");
        for (CareerSkillService.Status status : CareerSkillService.Status.values()) {
            assertTrue(english.contains(status.evaluationTranslationKey()),
                    status.evaluationTranslationKey());
        }
    }

    @Test
    void activeSkillCatalogCoversEveryPublicResult() {
        Set<String> english = keys("en_us");
        for (ActiveSkillService.Status status : ActiveSkillService.Status.values()) {
            assertTrue(english.contains(status.translationKey()), status.translationKey());
        }
    }

    private static Set<String> keys(String locale) {
        String path = "/assets/rovenfall/lang/" + locale + ".json";
        var stream = GameplayLocalizationCatalogTest.class.getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject().keySet();
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }
}

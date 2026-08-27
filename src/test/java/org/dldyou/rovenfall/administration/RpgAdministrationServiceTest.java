package org.dldyou.rovenfall.administration;

import static org.dldyou.rovenfall.PersistenceTestHarness.roundTrip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.rpg.ActivityDefinition;
import org.dldyou.rovenfall.rpg.ActivityXpAwardService;
import org.dldyou.rovenfall.rpg.CareerDefinition;
import org.dldyou.rovenfall.rpg.CareerProgressionService;
import org.dldyou.rovenfall.rpg.RpgAdministrativeMutationService;
import org.dldyou.rovenfall.rpg.RpgDefinitionSnapshot;
import org.dldyou.rovenfall.rpg.RpgPlayerSavedData;
import org.dldyou.rovenfall.rpg.RpgPlayerState;
import org.dldyou.rovenfall.rpg.RpgSkillService;
import org.dldyou.rovenfall.rpg.SkillDefinition;
import org.dldyou.rovenfall.rpg.SkillResetPlan;
import org.junit.jupiter.api.Test;

final class RpgAdministrationServiceTest {
    private static final UUID ACTOR = uuid(1);
    private static final UUID PLAYER = uuid(2);
    private static final Identifier COMBAT = id("combat");
    private static final Identifier NOVICE = id("novice");
    private static final Identifier WARRIOR = id("warrior");
    private static final Identifier FOUNDATION = id("foundation");
    private static final Identifier STRIKE = id("strike");

    @Test
    void viewerCanDiagnoseButOnlyModeratorOrOwnerCanMutate() {
        PlatformSavedData viewerPlatform = platformWithRole(AdminRole.VIEWER);
        RpgPlayerSavedData viewerRpg = rpgWithActivity(10, uuid(100));
        assertTrue(RpgAdministrationService.canView(viewerPlatform, ACTOR, false));
        assertFalse(RpgAdministrationService.canManage(viewerPlatform, ACTOR, false));
        var denied = RpgAdministrationService.adjustActivityXp(
                viewerPlatform, viewerRpg, definitions(), ACTOR, false, PLAYER, COMBAT,
                5, "support correction", 100, uuid(10));
        assertEquals(RpgAdministrationService.Status.UNAUTHORIZED, denied.status());
        assertTrue(denied.auditRecorded());
        assertEquals(10, viewerRpg.state(PLAYER).activityXp().get(COMBAT));

        PlatformSavedData platform = platformWithRole(AdminRole.MODERATOR);
        RpgPlayerSavedData rpg = rpgWithActivity(10, uuid(101));
        var adjusted = RpgAdministrationService.adjustActivityXp(
                platform, rpg, definitions(), ACTOR, false, PLAYER, COMBAT,
                5, "support correction", 101, uuid(11));
        assertEquals(RpgAdministrationService.Status.SUCCESS, adjusted.status());
        assertTrue(adjusted.auditRecorded());
        assertEquals(15, rpg.state(PLAYER).activityXp().get(COMBAT));
        assertEquals(RpgAdminOperation.Phase.COMPLETED,
                platform.rpgAdminOperation(uuid(11)).orElseThrow().phase());
        AuditEntry audit = platform.auditPage(0, 10).entries().getFirst();
        assertEquals(ACTOR, audit.actorId());
        assertEquals(uuid(11), audit.transactionId());
        assertEquals("support correction", audit.reason());
        assertTrue(audit.beforeValue().contains("10"));
        assertTrue(audit.afterValue().contains("15"));

        int auditCount = platform.auditPage(0, 50).totalEntries();
        var replay = RpgAdministrationService.adjustActivityXp(
                platform, rpg, definitions(), ACTOR, false, PLAYER, COMBAT,
                5, "support correction", 101, uuid(11));
        assertEquals(RpgAdministrationService.Status.DUPLICATE, replay.status());
        assertEquals(auditCount, platform.auditPage(0, 50).totalEntries());
    }

    @Test
    void platformRolesOwnOnlyTheirRpgMutationCategory() {
        for (AdminRole role : AdminRole.values()) {
            PlatformSavedData xpPlatform = platformWithRole(role);
            var xp = RpgAdministrationService.adjustActivityXp(
                    xpPlatform, rpgWithActivity(10, uuid(400 + role.ordinal())), definitions(),
                    ACTOR, false, PLAYER, COMBAT, 1, "role matrix xp", 400,
                    uuid(410 + role.ordinal()));
            boolean mayAdjustXp = role == AdminRole.MODERATOR || role == AdminRole.OWNER;
            assertEquals(mayAdjustXp ? RpgAdministrationService.Status.SUCCESS
                    : RpgAdministrationService.Status.UNAUTHORIZED, xp.status());

            PlatformSavedData contentPlatform = platformWithRole(role);
            var promotion = RpgAdministrationService.recoverPromotion(
                    contentPlatform, new RpgPlayerSavedData(), definitions(), ACTOR, false,
                    PLAYER, NOVICE, "role matrix promotion", 401, uuid(420 + role.ordinal()));
            boolean mayManageContent = role == AdminRole.CONTENT_MANAGER || role == AdminRole.OWNER;
            assertEquals(mayManageContent ? RpgAdministrationService.Status.SUCCESS
                    : RpgAdministrationService.Status.UNAUTHORIZED, promotion.status());
        }
    }

    @Test
    void pendingJournalCompletesAfterRpgAppliedBeforeRestart() {
        PlatformSavedData platform = platformWithRole(AdminRole.MODERATOR);
        RpgPlayerSavedData rpg = rpgWithActivity(10, uuid(102));
        UUID transaction = uuid(20);
        var operation = new RpgAdminOperation(
                ACTOR, PLAYER, RpgAdminOperation.Action.XP_ADJUST, COMBAT,
                5, 10, Optional.empty(), "restart recovery", 200, RpgAdminOperation.Phase.PENDING);
        assertEquals(PlatformSavedData.RpgAdminOperationBeginStatus.SUCCESS,
                platform.beginRpgAdminOperation(transaction, operation).status());
        assertEquals(RpgAdministrativeMutationService.Status.SUCCESS,
                RpgAdministrativeMutationService.adjustActivityXp(
                        rpg, definitions(), PLAYER, COMBAT, 5, 10, 200, transaction,
                        "admin:" + ACTOR).status());

        PlatformSavedData loadedPlatform = roundTrip(PlatformSavedData.CODEC, platform);
        RpgPlayerSavedData loadedRpg = roundTrip(RpgPlayerSavedData.CODEC, rpg);
        var recovered = RpgAdministrationService.adjustActivityXp(
                loadedPlatform, loadedRpg, definitions(), ACTOR, false, PLAYER, COMBAT,
                5, "restart recovery", 200, transaction);
        assertEquals(RpgAdministrationService.Status.SUCCESS, recovered.status());
        assertTrue(recovered.auditRecorded());
        assertEquals(15, loadedRpg.state(PLAYER).activityXp().get(COMBAT));
        assertEquals(RpgAdminOperation.Phase.COMPLETED,
                loadedPlatform.rpgAdminOperation(transaction).orElseThrow().phase());
    }

    @Test
    void promotionRecoveryAndNoChargeResetRemainAudited() {
        PlatformSavedData platform = platformWithRole(AdminRole.CONTENT_MANAGER);
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        assertEquals(CareerProgressionService.Status.SUCCESS,
                CareerProgressionService.promote(
                        rpg, definitions(), PLAYER, NOVICE, 299, uuid(299), "test").status());
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(
                        rpg, definitions(), PLAYER, COMBAT, 10, 300, uuid(300), "test:rank").status());

        var promoted = RpgAdministrationService.recoverPromotion(
                platform, rpg, definitions(), ACTOR, false, PLAYER, WARRIOR,
                "restore lost promotion", 301, uuid(30));
        assertEquals(RpgAdministrationService.Status.SUCCESS, promoted.status());

        assertTrue(platform.economyBalance(PLAYER).isEmpty());
        RpgPlayerSavedData resetRpg = new RpgPlayerSavedData();
        assertEquals(RpgAdministrationService.Status.SUCCESS,
                RpgAdministrationService.recoverPromotion(
                        platform, resetRpg, definitions(), ACTOR, false, PLAYER, NOVICE,
                        "reset fixture root", 302, uuid(32)).status());
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(
                        resetRpg, definitions(), PLAYER, COMBAT, 10, 303, uuid(303), "test:novice").status());
        assertEquals(RpgSkillService.Status.SUCCESS,
                RpgSkillService.learn(
                        resetRpg, definitions(), PLAYER, FOUNDATION, 304, uuid(304), "test").status());
        assertEquals(RpgAdministrationService.Status.SUCCESS,
                RpgAdministrationService.recoverPromotion(
                        platform, resetRpg, definitions(), ACTOR, false, PLAYER, WARRIOR,
                        "reset fixture branch", 305, uuid(33)).status());
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(
                        resetRpg, definitions(), PLAYER, COMBAT, 10, 306, uuid(306), "test:warrior").status());
        assertEquals(RpgSkillService.Status.SUCCESS,
                RpgSkillService.learn(
                        resetRpg, definitions(), PLAYER, STRIKE, 307, uuid(307), "test").status());
        var reset = RpgAdministrationService.resetSkills(
                platform, resetRpg, definitions(), ACTOR, false, PLAYER,
                SkillResetPlan.Mode.FULL, NOVICE, "support reset", 301, uuid(31));
        assertEquals(RpgAdministrationService.Status.SUCCESS, reset.status());
        assertTrue(platform.economyBalance(PLAYER).isEmpty());
        assertTrue(resetRpg.state(PLAYER).careers().get(NOVICE).learnedSkills().isEmpty());
        assertTrue(resetRpg.state(PLAYER).careers().get(WARRIOR).learnedSkills().isEmpty());
        assertEquals(4, platform.auditPage(0, 50).entries().stream()
                .filter(entry -> entry.actionType().getPath().startsWith("rpg_admin_"))
                .count());
    }

    @Test
    void promotionRecoveryRequiresEveryParentAtMaximumRank() {
        PlatformSavedData platform = platformWithRole(AdminRole.CONTENT_MANAGER);
        RpgPlayerSavedData rpg = new RpgPlayerSavedData();
        assertEquals(CareerProgressionService.Status.SUCCESS,
                CareerProgressionService.promote(
                        rpg, definitions(), PLAYER, NOVICE, 500, uuid(500), "test").status());

        var blocked = RpgAdministrationService.recoverPromotion(
                platform, rpg, definitions(), ACTOR, false, PLAYER, WARRIOR,
                "restore without parent rank", 501, uuid(501));

        assertEquals(RpgAdministrationService.Status.PARENT_RANK_TOO_LOW, blocked.status());
        assertTrue(platform.rpgAdminOperation(uuid(501)).isEmpty());
        assertFalse(rpg.state(PLAYER).careers().containsKey(WARRIOR));
    }

    @Test
    void nativeConsoleActorCanOwnAValidDurableOperation() {
        var operation = new RpgAdminOperation(
                AdministrationService.SYSTEM_ACTOR, PLAYER, RpgAdminOperation.Action.XP_ADJUST, COMBAT,
                1, 0, Optional.empty(), "console recovery", 1, RpgAdminOperation.Phase.PENDING);
        assertTrue(RpgAdminOperation.CODEC.encodeStart(
                com.mojang.serialization.JsonOps.INSTANCE, operation).result().isPresent());
    }

    private static PlatformSavedData platformWithRole(AdminRole role) {
        PlatformSavedData platform = new PlatformSavedData();
        assertEquals(AdministrationService.RoleChangeStatus.SUCCESS,
                AdministrationService.changeRole(
                        platform, AdministrationService.SYSTEM_ACTOR, true, ACTOR,
                        role.getSerializedName(), "test setup", 1, UUID.randomUUID()).status());
        return platform;
    }

    private static RpgPlayerSavedData rpgWithActivity(long amount, UUID transactionId) {
        RpgPlayerSavedData state = new RpgPlayerSavedData();
        assertEquals(ActivityXpAwardService.Status.SUCCESS,
                ActivityXpAwardService.award(
                        state, definitions(), PLAYER, COMBAT, amount, 10, transactionId, "test:activity").status());
        return state;
    }

    private static RpgDefinitionSnapshot definitions() {
        return RpgDefinitionSnapshot.compile(
                List.of(new RpgDefinitionSnapshot.ActivitySource(
                        id("activities/combat"), "test", COMBAT,
                        new ActivityDefinition("activity.rovenfall.combat", List.of(10L, 20L)))),
                List.of(
                        new RpgDefinitionSnapshot.CareerSource(
                                id("careers/novice"), "test", NOVICE,
                                new CareerDefinition("career.rovenfall.novice", 1, List.of(),
                                        List.of(10L), 0, List.of(), 1)),
                        new RpgDefinitionSnapshot.CareerSource(
                                id("careers/warrior"), "test", WARRIOR,
                                new CareerDefinition("career.rovenfall.warrior", 2, List.of(NOVICE),
                                        List.of(10L), 0,
                                        List.of(new CareerDefinition.ActivityRequirement(COMBAT, 2)), 1))),
                List.of(
                        new RpgDefinitionSnapshot.SkillSource(
                                id("skills/foundation"), "test", FOUNDATION,
                                new SkillDefinition(
                                        "skill.rovenfall.foundation", NOVICE, SkillDefinition.Kind.PASSIVE,
                                        1, 1, List.of(), Optional.empty(),
                                        Optional.of(new SkillDefinition.PassiveEffect(
                                                SkillDefinition.EffectType.DAMAGE_DEALT, 100)), Optional.empty())),
                        new RpgDefinitionSnapshot.SkillSource(
                                id("skills/strike"), "test", STRIKE,
                                new SkillDefinition(
                                        "skill.rovenfall.strike", WARRIOR, SkillDefinition.Kind.ACTIVE,
                                        1, 1, List.of(new SkillDefinition.Prerequisite(FOUNDATION, 1)),
                                        Optional.of(20), Optional.empty(),
                                        Optional.of(new SkillDefinition.ActiveEffect(
                                                SkillDefinition.EffectType.DAMAGE_DEALT,
                                                SkillDefinition.TargetType.LIVING_ENTITY,
                                                100, 20, 4.0))))));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("rovenfall", path);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}

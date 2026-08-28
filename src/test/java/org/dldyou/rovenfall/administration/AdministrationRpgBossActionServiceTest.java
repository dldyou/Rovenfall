package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.mobs.MobContentSnapshot;
import org.dldyou.rovenfall.rpg.RpgPlayerState;
import org.dldyou.rovenfall.rpg.SkillResetPlan;
import org.junit.jupiter.api.Test;

final class AdministrationRpgBossActionServiceTest {
    @Test
    void actionKindsUseTheirExactRoleBoundaries() {
        UUID transactionId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        Identifier target = Identifier.fromNamespaceAndPath("rovenfall", "test");
        var xp = new AdministrationRpgBossActionService.XpAction(
                transactionId, playerId, RpgPlayerState.EMPTY, 1, target, 1, "reason");
        var promotion = new AdministrationRpgBossActionService.PromotionAction(
                transactionId, playerId, RpgPlayerState.EMPTY, 1, target, "reason");
        var reset = new AdministrationRpgBossActionService.SkillResetAction(
                transactionId, playerId, RpgPlayerState.EMPTY, 1, SkillResetPlan.Mode.BRANCH, target,
                new SkillResetPlan(SkillResetPlan.Mode.BRANCH, target,
                        List.of(new SkillResetPlan.RemovedSkill(target, target, 1, 1))),
                "reason");
        var boss = new AdministrationRpgBossActionService.BossResetAction(
                transactionId, UUID.randomUUID(),
                new AdministrationRpgBossActionService.BossResetEvidence(
                        Optional.empty(), Optional.empty(), List.of()),
                "reason");
        var recovery = new AdministrationRpgBossActionService.BossRecoverAction(
                transactionId,
                new AdministrationRpgBossActionService.BossRecoveryEvidence(List.of(), List.of(), List.of()),
                "reason");
        var reload = new AdministrationRpgBossActionService.ReloadAction(
                transactionId, 1, MobContentSnapshot.empty(), "reason");

        assertTrue(AdministrationRpgBossActionService.allowed(AdminRole.MODERATOR, xp));
        assertTrue(AdministrationRpgBossActionService.allowed(AdminRole.OWNER, xp));
        assertFalse(AdministrationRpgBossActionService.allowed(AdminRole.CONTENT_MANAGER, xp));
        assertFalse(AdministrationRpgBossActionService.allowed(AdminRole.ECONOMY_MANAGER, xp));

        for (var contentAction : List.of(promotion, reset, reload)) {
            assertTrue(AdministrationRpgBossActionService.allowed(AdminRole.CONTENT_MANAGER, contentAction));
            assertTrue(AdministrationRpgBossActionService.allowed(AdminRole.OWNER, contentAction));
            assertFalse(AdministrationRpgBossActionService.allowed(AdminRole.MODERATOR, contentAction));
            assertFalse(AdministrationRpgBossActionService.allowed(AdminRole.VIEWER, contentAction));
        }
        for (var ownerAction : List.of(boss, recovery)) {
            assertTrue(AdministrationRpgBossActionService.allowed(AdminRole.OWNER, ownerAction));
            assertFalse(AdministrationRpgBossActionService.allowed(AdminRole.CONTENT_MANAGER, ownerAction));
            assertFalse(AdministrationRpgBossActionService.allowed(AdminRole.MODERATOR, ownerAction));
            assertFalse(AdministrationRpgBossActionService.allowed(AdminRole.VIEWER, ownerAction));
        }
        assertFalse(AdministrationRpgBossActionService.allowed(null, xp));
        assertFalse(AdministrationRpgBossActionService.allowed(AdminRole.OWNER, null));
    }
}

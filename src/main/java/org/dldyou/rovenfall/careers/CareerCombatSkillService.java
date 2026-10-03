package org.dldyou.rovenfall.careers;

/** Applies bounded, server-owned combat modifiers from the active profession lineage. */
public final class CareerCombatSkillService {
    private CareerCombatSkillService() {
    }

    public static float modifyDamage(
            CareerCatalog catalog,
            PlayerCareerState attacker,
            PlayerCareerState defender,
            float amount) {
        if (catalog == null || !Float.isFinite(amount) || amount <= 0) {
            return amount;
        }
        int outgoing = catalog.combatBonusBasisPoints(
                attacker, CareerSkillEffect.Type.OUTGOING_DAMAGE_BONUS);
        int reduction = catalog.combatBonusBasisPoints(
                defender, CareerSkillEffect.Type.INCOMING_DAMAGE_REDUCTION);
        double changed = amount * (10_000L + outgoing) / 10_000.0;
        changed = changed * (10_000L - reduction) / 10_000.0;
        return Double.isFinite(changed) && changed >= 0 && changed <= Float.MAX_VALUE
                ? (float) changed
                : amount;
    }
}

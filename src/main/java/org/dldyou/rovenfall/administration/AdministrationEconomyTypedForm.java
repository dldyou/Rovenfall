package org.dldyou.rovenfall.administration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.economy.ShopInstance;
import org.dldyou.rovenfall.economy.ShopTemplateSnapshot;

/** Converts ordinary economy controls to the legacy parser grammar at the server menu boundary. */
final class AdministrationEconomyTypedForm {
    private AdministrationEconomyTypedForm() {
    }

    static Optional<String> legacy(
            AdministrationFormType type,
            List<String> values,
            UUID transactionId,
            Identifier selectedTemplate,
            Identifier selectedItem,
            Identifier selectedOffer) {
        if (type == null || values == null || transactionId == null || !type.accepts(values)) {
            return Optional.empty();
        }
        try {
            return switch (type) {
                case ECONOMY_GRANT, ECONOMY_DEBIT -> amount(values);
                case ECONOMY_SHOP_CREATE -> selectedTemplate == null ? Optional.empty() : Optional.of(
                        AdministrationGeneratedIdentifier.fromTransaction("shop", transactionId) + "," + selectedTemplate
                                + " | " + reason(values, 0));
                case ECONOMY_SHOP_ACCESS -> Optional.of(values.getFirst() + " | " + reason(values, 1));
                case ECONOMY_OFFER_UPSERT -> offer(values, transactionId, selectedItem, selectedOffer);
                case ECONOMY_RESTOCK -> restock(values, transactionId, selectedOffer);
                case ECONOMY_SHOP_DELETE, ECONOMY_SHOP_BIND_HERE, ECONOMY_SHOP_UNBIND,
                        ECONOMY_OFFER_REMOVE, ECONOMY_REVERSE_STRICT, ECONOMY_REVERSE_COMPENSATE ->
                        Optional.of(" | " + reason(values, 0));
                default -> Optional.empty();
            };
        } catch (IllegalArgumentException | ArithmeticException exception) {
            return Optional.empty();
        }
    }

    private static Optional<String> amount(List<String> values) {
        return Optional.of(values.getFirst() + " | " + reason(values, 1));
    }

    private static Optional<String> offer(
            List<String> values, UUID transactionId, Identifier selectedItem, Identifier selectedOffer) {
        if (selectedItem == null) {
            return Optional.empty();
        }
        String direction = values.get(0);
        String buy = values.get(1);
        String sell = values.get(2);
        String stockMode = values.get(3);
        String stock = values.get(4);
        String maximum = values.get(5);
        String count = values.get(6);
        String reason = reason(values, 7);
        boolean buyEnabled = direction.equals("buy") || direction.equals("both");
        boolean sellEnabled = direction.equals("sell") || direction.equals("both");
        if (buyEnabled != !buy.isEmpty() || sellEnabled != !sell.isEmpty()) {
            return Optional.empty();
        }
        String legacyStock;
        String legacyMaximum;
        if (stockMode.equals("unlimited")) {
            if (!stock.isEmpty() || !maximum.isEmpty()) {
                return Optional.empty();
            }
            legacyStock = "-1";
            legacyMaximum = "-1";
        } else if (stockMode.equals("finite") && !stock.isEmpty() && !maximum.isEmpty()) {
            long current = Long.parseLong(stock);
            long max = Long.parseLong(maximum);
            if (current > max) {
                return Optional.empty();
            }
            legacyStock = stock;
            legacyMaximum = maximum;
        } else {
            return Optional.empty();
        }
        Identifier offerId = selectedOffer == null
                ? AdministrationGeneratedIdentifier.fromTransaction("offer", transactionId) : selectedOffer;
        return Optional.of(offerId + "," + selectedItem + "," + count + ","
                + (buyEnabled ? buy : "none") + "," + (sellEnabled ? sell : "none") + ","
                + legacyStock + "," + legacyMaximum + " | " + reason);
    }

    private static Optional<String> restock(List<String> values, UUID transactionId, Identifier selectedOffer) {
        Identifier offerId = selectedOffer == null
                ? AdministrationGeneratedIdentifier.fromTransaction("offer", transactionId) : selectedOffer;
        String reason = reason(values, 3);
        return values.getFirst().equals("false")
                ? Optional.of(offerId + ",clear | " + reason)
                : Optional.of(offerId + "," + values.get(1) + "," + values.get(2) + " | " + reason);
    }

    private static String reason(List<String> values, int index) {
        String reason = values.get(index).strip();
        if (reason.isEmpty() || reason.length() > AdministrationService.MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("invalid reason");
        }
        return reason;
    }
}

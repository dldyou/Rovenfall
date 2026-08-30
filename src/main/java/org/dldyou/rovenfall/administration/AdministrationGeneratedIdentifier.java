package org.dldyou.rovenfall.administration;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.dldyou.rovenfall.Rovenfall;

/** Creates opaque internal identifiers so operators never have to invent resource IDs. */
final class AdministrationGeneratedIdentifier {
    private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    private AdministrationGeneratedIdentifier() {
    }

    static Identifier fromTransaction(String kind, UUID transactionId) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(transactionId, "transactionId");
        String normalized = kind.toLowerCase(Locale.ROOT);
        if (!KIND.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid generated identifier kind");
        }
        return Identifier.fromNamespaceAndPath(
                Rovenfall.MOD_ID,
                "managed/" + normalized + "/" + transactionId.toString().replace("-", ""));
    }
}

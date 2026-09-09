package org.dldyou.rovenfall.administration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AdminTokenStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void tokenIsGeneratedOnceAndComparedWithoutAcceptingPrefixes() throws IOException {
        Path path = temporaryDirectory.resolve("config").resolve(AdminTokenStore.FILE_NAME);

        AdminTokenStore.Token first = AdminTokenStore.loadOrCreate(path, new SecureRandom());
        AdminTokenStore.Token second = AdminTokenStore.loadOrCreate(path, new SecureRandom());

        assertEquals(first.value(), second.value());
        assertTrue(first.value().length() >= 32);
        assertTrue(first.matches(first.value()));
        assertFalse(first.matches(first.value().substring(1)));
        assertFalse(first.matches(first.value() + "x"));
        assertFalse(first.matches(null));
        assertEquals(first.value(), Files.readString(path, StandardCharsets.UTF_8).strip());
        assertEquals(path.toAbsolutePath().normalize(), first.path().orElseThrow());
    }

    @Test
    void emptyOrShortExistingSecretFailsClosed() throws IOException {
        Path path = temporaryDirectory.resolve(AdminTokenStore.FILE_NAME);
        Files.writeString(path, "short", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> AdminTokenStore.loadOrCreate(path, new SecureRandom()));
    }
}

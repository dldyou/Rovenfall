package org.dldyou.rovenfall.administration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import net.neoforged.fml.loading.FMLPaths;

/** Generates and validates the bearer secret used by the loopback bridge. */
final class AdminTokenStore {
    static final String ENVIRONMENT_VARIABLE = "ROVENFALL_ADMIN_TOKEN";
    static final String FILE_NAME = "rovenfall-admin.token";
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 256;

    private AdminTokenStore() {
    }

    static Token load() throws IOException {
        Optional<String> environment = normalize(System.getenv(ENVIRONMENT_VARIABLE));
        if (environment.isPresent()) {
            return new Token(environment.orElseThrow(), Optional.empty());
        }
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        return loadOrCreate(path, new SecureRandom());
    }

    static Token loadOrCreate(Path path, SecureRandom random) throws IOException {
        if (path == null || random == null) {
            throw new IllegalArgumentException("Admin token storage is missing");
        }
        if (Files.exists(path)) {
            String existing = normalize(Files.readString(path, StandardCharsets.UTF_8))
                    .orElseThrow(() -> new IOException("Admin token file is empty or invalid: " + path));
            return new Token(existing, Optional.of(path));
        }

        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            Files.writeString(path, generated + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            restrictOwnerAccess(path);
            return new Token(generated, Optional.of(path));
        } catch (java.nio.file.FileAlreadyExistsException exception) {
            String existing = normalize(Files.readString(path, StandardCharsets.UTF_8))
                    .orElseThrow(() -> new IOException("Admin token file is empty or invalid: " + path));
            return new Token(existing, Optional.of(path));
        }
    }

    private static void restrictOwnerAccess(Path path) {
        try {
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows ACLs and non-POSIX file systems keep their inherited private config-directory policy.
        }
    }

    private static Optional<String> normalize(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.strip();
        return normalized.length() >= 32 && normalized.length() <= MAX_TOKEN_LENGTH
                ? Optional.of(normalized)
                : Optional.empty();
    }

    record Token(String value, Optional<Path> path) {
        Token {
            if (normalize(value).isEmpty()) {
                throw new IllegalArgumentException("Admin token is invalid");
            }
            path = path == null ? Optional.empty() : path.map(valuePath -> valuePath.toAbsolutePath().normalize());
        }

        boolean matches(String candidate) {
            if (candidate == null || candidate.length() > MAX_TOKEN_LENGTH) {
                return false;
            }
            return MessageDigest.isEqual(
                    value.getBytes(StandardCharsets.UTF_8),
                    candidate.getBytes(StandardCharsets.UTF_8));
        }
    }
}

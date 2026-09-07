// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Stable names for managed SavedData files.
 *
 * <p>These pure, thread-safe helpers perform no I/O, simulation, or authoritative modification. Inputs remain
 * caller-owned and results are immutable. Missing inputs throw {@link NullPointerException}.
 */
public final class ManagedSavedDataNames {
    private static final String NETWORK_PREFIX = "omniresonance_network_";
    private static final String OWNER_PREFIX = "omniresonance_owner_";
    private static final String FILE_EXTENSION = ".dat";

    private ManagedSavedDataNames() {}

    /** Returns an extension-free network storage ID; the class's pure ownership and failure contract applies. */
    public static String network(UUID id) {
        return NETWORK_PREFIX + compactId(id);
    }

    /** Returns an extension-free owner storage ID; the class's pure ownership and failure contract applies. */
    public static String owner(UUID id) {
        return OWNER_PREFIX + compactId(id);
    }

    /** Returns one of the fixed extension-free network bucket IDs without performing I/O or parsing data. */
    public static String networkBucket(UUID id, int bucket) {
        Objects.requireNonNull(id, "id");
        if (bucket < 0 || bucket >= 64) {
            throw new IllegalArgumentException("Network bucket index must be between 0 and 63");
        }
        return NETWORK_PREFIX + compactId(id) + "_bucket_" + (bucket < 10 ? "0" : "") + bucket;
    }

    /**
     * Parses only a canonical network filename, including its {@code .dat} extension.
     *
     * <p>This pure, thread-safe operation neither accesses files nor verifies their kind. Noncanonical names,
     * paths, bucket names, and temporary suffixes return empty; missing input throws {@link NullPointerException}.
     * Ownership stays with the caller and no simulation or modification occurs.
     */
    public static Optional<UUID> parseNetworkFileName(String fileName) {
        Objects.requireNonNull(fileName, "fileName");
        int start = NETWORK_PREFIX.length();
        if (fileName.length() != start + 32 + FILE_EXTENSION.length()
                || !fileName.startsWith(NETWORK_PREFIX)
                || !fileName.endsWith(FILE_EXTENSION)) {
            return Optional.empty();
        }
        for (int index = start; index < start + 32; index++) {
            char character = fileName.charAt(index);
            if (!(character >= '0' && character <= '9') && !(character >= 'a' && character <= 'f')) {
                return Optional.empty();
            }
        }
        long mostSignificantBits = Long.parseUnsignedLong(fileName, start, start + 16, 16);
        long leastSignificantBits = Long.parseUnsignedLong(fileName, start + 16, start + 32, 16);
        return Optional.of(new UUID(mostSignificantBits, leastSignificantBits));
    }

    private static String compactId(UUID id) {
        return Objects.requireNonNull(id, "id").toString().replace("-", "");
    }
}

// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManagedSavedDataNamesTest {
    private static final UUID ID = UUID.fromString("abcdef01-2345-6789-abcd-ef0123456789");

    @Test
    void createsStableLowercaseIdsWithoutAnExtension() {
        assertEquals("omniresonance_network_abcdef0123456789abcdef0123456789", ManagedSavedDataNames.network(ID));
        assertEquals("omniresonance_owner_abcdef0123456789abcdef0123456789", ManagedSavedDataNames.owner(ID));
    }

    @Test
    void parsesOnlyCanonicalNetworkFileNames() {
        assertEquals(
                Optional.of(ID),
                ManagedSavedDataNames.parseNetworkFileName(
                        "omniresonance_network_abcdef0123456789abcdef0123456789.dat"));
        assertEquals(
                Optional.of(new UUID(0, 0)),
                ManagedSavedDataNames.parseNetworkFileName(
                        "omniresonance_network_00000000000000000000000000000000.dat"));
    }

    @Test
    void rejectsAliasesBucketsSuffixesAndPaths() {
        for (String fileName : List.of(
                "",
                "omniresonance_network_abcdef0123456789abcdef0123456789",
                "omniresonance_network_ABCDEF0123456789ABCDEF0123456789.dat",
                "omniresonance_network_abcdef01-2345-6789-abcd-ef0123456789.dat",
                "omniresonance_network_abcdef0123456789abcdef012345678g.dat",
                "omniresonance_network_abcdef0123456789abcdef012345678.dat",
                "omniresonance_network_abcdef0123456789abcdef01234567890.dat",
                "omniresonance_owner_abcdef0123456789abcdef0123456789.dat",
                "omniresonance_network_abcdef0123456789abcdef0123456789_bucket_00.dat",
                "omniresonance_network_abcdef0123456789abcdef0123456789_bucket_63.dat",
                "omniresonance_network_abcdef0123456789abcdef0123456789.dat_old",
                "omniresonance_network_abcdef0123456789abcdef0123456789.dat.tmp",
                "omniresonance_network_abcdef0123456789abcdef0123456789.DAT",
                "../omniresonance_network_abcdef0123456789abcdef0123456789.dat",
                "./omniresonance_network_abcdef0123456789abcdef0123456789.dat",
                "/omniresonance_network_abcdef0123456789abcdef0123456789.dat",
                "dir\\omniresonance_network_abcdef0123456789abcdef0123456789.dat",
                "omniresonance_network_abcdef0123456789abcdef0123456789.dat\n")) {
            assertTrue(ManagedSavedDataNames.parseNetworkFileName(fileName).isEmpty(), fileName);
        }
    }

    @Test
    void rejectsMissingInputs() {
        assertThrows(NullPointerException.class, () -> ManagedSavedDataNames.network(null));
        assertThrows(NullPointerException.class, () -> ManagedSavedDataNames.owner(null));
        assertThrows(NullPointerException.class, () -> ManagedSavedDataNames.parseNetworkFileName(null));
    }
}

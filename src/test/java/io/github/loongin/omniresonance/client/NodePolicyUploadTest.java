// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.networking.ResourcePolicyEditCodec;
import io.github.loongin.omniresonance.transfer.RedstoneCondition;
import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class NodePolicyUploadTest {
    private static ResourcePolicyEdit policy() {
        return new ResourcePolicyEdit(
                1,
                new ResourcePolicyEdit.Scope(ResourceScope.Kind.ALL, List.of()),
                RedstoneCondition.IGNORE,
                null,
                FilterMode.WHITELIST,
                new ResourcePolicyEdit.InputFields(0),
                List.of(),
                List.of(),
                false);
    }

    @Test
    void authorizationDoesNotCommitAndDuplicateReadyCannotRestartSending() {
        var upload = new NodePolicyUpload();
        var id = new UUID(2, 1);
        upload.begin(id, policy(), 10);
        assertTrue(upload.active());
        assertFalse(upload.hasNext());
        assertThrows(IllegalStateException.class, upload::next);
        upload.ready(new UUID(2, 2));
        assertFalse(upload.hasNext());
        upload.ready(id);
        var fragment = upload.next();
        assertEquals(0, fragment.offset());
        assertArrayEquals(ResourcePolicyEditCodec.encode(policy()), fragment.bytes());
        assertFalse(upload.hasNext());
        assertTrue(upload.active(), "The final chunk still waits for the server result");
        upload.ready(id);
        assertFalse(upload.hasNext());
        assertEquals(id, upload.id());
        assertFalse(upload.expired(209));
        assertTrue(upload.expired(210));
    }

    @Test
    void clearDropsAuthorizationAndDeadlineBeforeAnotherUpload() {
        var upload = new NodePolicyUpload();
        var id = new UUID(3, 1);
        upload.begin(id, policy(), 0);
        assertThrows(IllegalStateException.class, () -> upload.begin(new UUID(3, 2), policy(), 1));
        upload.clear();
        upload.clear();
        upload.ready(id);
        assertFalse(upload.active());
        assertFalse(upload.expired(1000));
        assertNull(upload.id());
        upload.begin(new UUID(3, 2), policy(), 1000);
        assertFalse(upload.expired(1000));
        assertFalse(upload.hasNext());
    }
}

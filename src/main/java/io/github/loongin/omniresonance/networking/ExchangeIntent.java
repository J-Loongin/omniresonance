// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.exchange.ExchangeInvitationCode;
import io.github.loongin.omniresonance.exchange.ExchangeTermsDraft;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable untrusted exchange operation body. No actor, network identity, permission or approval snapshot is
 * supplied. The authenticated terminal envelope and its current page must supply context. Values are pure and
 * thread-safe; construction performs no authority access, simulation, persistence or invitation consumption.
 */
public sealed interface ExchangeIntent {
    record ListTunnels(boolean history) implements ExchangeIntent {}

    record Pair(String code) implements ExchangeIntent {
        public Pair {
            ExchangeInvitationCode.decode(code);
        }

        @Override
        public String toString() {
            return "Pair[redacted]";
        }
    }

    record ApprovePair(UUID tunnel, long revision) implements ExchangeIntent {
        public ApprovePair {
            requireRevision(tunnel, revision);
        }
    }

    record ClosePair(UUID tunnel, long revision) implements ExchangeIntent {
        public ClosePair {
            requireRevision(tunnel, revision);
        }
    }

    record ListChannels(UUID tunnel, boolean history) implements ExchangeIntent {
        public ListChannels {
            Objects.requireNonNull(tunnel);
        }
    }

    record CreateChannel(
            UUID tunnel, long revision, io.github.loongin.omniresonance.exchange.ExchangeChannelDraft draft)
            implements ExchangeIntent {
        public CreateChannel {
            requireRevision(tunnel, revision);
            Objects.requireNonNull(draft);
        }
    }

    record ReviseChannel(
            UUID channel, long revision, io.github.loongin.omniresonance.exchange.ExchangeChannelDraft draft)
            implements ExchangeIntent {
        public ReviseChannel {
            requireRevision(channel, revision);
            Objects.requireNonNull(draft);
        }
    }

    record ListRules(boolean history) implements ExchangeIntent {}

    record Detail(UUID agreement) implements ExchangeIntent {
        public Detail {
            Objects.requireNonNull(agreement);
        }
    }

    record IssueCode() implements ExchangeIntent {}

    record RevokeCode(UUID invitation, long revision) implements ExchangeIntent {
        public RevokeCode {
            requireRevision(invitation, revision);
        }
    }

    record Propose(String code, ExchangeTermsDraft draft) implements ExchangeIntent {
        public Propose {
            ExchangeInvitationCode.decode(code);
            Objects.requireNonNull(draft);
        }
    }

    record Revise(UUID agreement, long revision, ExchangeTermsDraft draft) implements ExchangeIntent {
        public Revise {
            requireRevision(agreement, revision);
            Objects.requireNonNull(draft);
        }
    }

    enum Action {
        APPROVE,
        PAUSE,
        RESUME,
        TERMINATE
    }

    record Change(UUID agreement, long revision, Action action) implements ExchangeIntent {
        public Change {
            requireRevision(agreement, revision);
            Objects.requireNonNull(action);
        }
    }

    private static void requireRevision(UUID id, long revision) {
        Objects.requireNonNull(id);
        if (revision < 0) throw new IllegalArgumentException("Invalid exchange object revision");
    }
}

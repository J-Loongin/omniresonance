// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NodeMenuState;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/** One-shot client intent for committing a server-suggested creation name. */
record AutomaticNameCommit(@Nullable Target pending, boolean submitting) {
    AutomaticNameCommit {
        if (pending == null && submitting) {
            throw new IllegalArgumentException("Idle automatic name commit cannot be submitting");
        }
    }

    static AutomaticNameCommit idle() {
        return new AutomaticNameCommit(null, false);
    }

    AutomaticNameCommit arm(Target target) {
        return new AutomaticNameCommit(Objects.requireNonNull(target, "target"), false);
    }

    boolean armed() {
        return pending != null;
    }

    boolean suppressEditor() {
        return submitting;
    }

    Resolution resolveNode(boolean successfulState, @Nullable NodeMenuState state) {
        Target reached = null;
        if (successfulState) {
            if (state instanceof NodeMenuState.BlankEdit) {
                reached = Target.NODE_LINK;
            } else if (state instanceof NodeMenuState.DirectChannelEdit edit && edit.existing() == null) {
                reached = Target.CHANNEL;
            }
        }
        return resolve(reached);
    }

    private Resolution resolve(@Nullable Target reached) {
        if (pending == null || submitting) {
            return new Resolution(idle(), null);
        }
        Target commit = pending == reached ? pending : null;
        return new Resolution(commit == null ? idle() : new AutomaticNameCommit(commit, true), commit);
    }

    enum Target {
        NODE_LINK,
        CHANNEL
    }

    record Resolution(AutomaticNameCommit next, @Nullable Target commit) {
        Resolution {
            Objects.requireNonNull(next, "next");
        }
    }
}

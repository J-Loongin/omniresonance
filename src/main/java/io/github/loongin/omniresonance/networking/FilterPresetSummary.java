// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Immutable owner-library row; editability is informative and always revalidated before a mutation. */
public record FilterPresetSummary(UUID id, String name, long revision, int ruleCount, boolean editable) {
    public FilterPresetSummary {
        Objects.requireNonNull(id, "id");
        if (!new ManagedName(name).value().equals(name) || revision < 0 || ruleCount < 0 || ruleCount > 262144)
            throw new IllegalArgumentException("Invalid preset summary");
    }

    public static FilterPresetSummary read(FriendlyByteBuf buffer) {
        return new FilterPresetSummary(
                buffer.readUUID(),
                NetworkSummary.readName(buffer),
                buffer.readLong(),
                buffer.readInt(),
                buffer.readBoolean());
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(id);
        NetworkSummary.writeName(buffer, name);
        buffer.writeLong(revision).writeInt(ruleCount).writeBoolean(editable);
    }
}

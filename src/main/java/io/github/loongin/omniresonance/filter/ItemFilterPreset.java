// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Immutable exact-ID owner preset. Construction copies rules, bounds native UTF strings and the complete
 * native compound to 16 MiB, and rejects invalid values before publication. Pure value access is thread-safe;
 * matching an ItemVariant uses its server-thread registry contract. No operation mutates authority or simulates. */
public record ItemFilterPreset(UUID id, ManagedName name, long revision, Set<ResourceLocation> itemIds) {
    public static final int MAXIMUM_RULES = 262144;
    public static final int MAXIMUM_ITEM_ID_BYTES = 65535;
    public static final int MAXIMUM_ENCODED_BYTES = 16777216;

    public ItemFilterPreset {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(itemIds, "itemIds");
        if (revision < 0 || itemIds.size() > MAXIMUM_RULES)
            throw new IllegalArgumentException("Invalid preset revision or rule count");
        for (ResourceLocation itemId : itemIds) {
            java.util.Objects.requireNonNull(itemId, "itemId");
            if (itemId.toString().length() > MAXIMUM_ITEM_ID_BYTES)
                throw new IllegalArgumentException("Item ID exceeds hard limit");
        }
        // Native preset compound framing: UUID 32, name 9, revision 19, list 16, END 1 bytes.
        long bytes = 77;
        for (int index = 0; index < name.value().length(); index++) {
            char c = name.value().charAt(index);
            bytes += c >= 1 && c <= 127 ? 1 : c <= 2047 ? 2 : 3;
        }
        for (ResourceLocation itemId : itemIds) bytes += 2L + itemId.toString().length();
        if (bytes > MAXIMUM_ENCODED_BYTES)
            throw new IllegalArgumentException("Preset exceeds managed object byte limit");
        itemIds = Set.copyOf(itemIds);
    }

    /** Matches only the exact item ID on the server thread, without decoding components or changing the variant. */
    public boolean matches(io.github.loongin.omniresonance.transfer.ItemVariant variant) {
        return itemIds.contains(variant.itemId());
    }

    public static boolean allows(
            @Nullable UUID selectedId, @Nullable ItemFilterPreset preset, FilterMode mode, ResourceLocation itemId) {
        java.util.Objects.requireNonNull(mode, "mode");
        java.util.Objects.requireNonNull(itemId, "itemId");
        if (selectedId == null) return true;
        if (preset == null || !selectedId.equals(preset.id())) return false;
        return preset.itemIds().contains(itemId) == (mode == FilterMode.WHITELIST);
    }
}

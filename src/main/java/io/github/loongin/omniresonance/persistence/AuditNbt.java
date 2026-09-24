// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** Strict bounded embedded audit codec; decoded values are detached and input tags remain untouched. */
final class AuditNbt {
    private static final Set<String> FIELDS = Set.of("action", "actor", "target", "game_tick", "summary");

    private AuditNbt() {}

    static ListTag encode(List<AuditEntry> entries) {
        if (entries.size() > AuditRing.MAXIMUM) throw new IllegalArgumentException("Oversize audit ring");
        var result = new ListTag();
        for (var entry : entries) {
            var tag = new CompoundTag();
            tag.putString("action", entry.action().toString());
            tag.putUUID("actor", entry.actor());
            tag.putUUID("target", entry.target());
            tag.putLong("game_tick", entry.gameTick());
            tag.putString("summary", entry.summary());
            result.add(tag);
        }
        return result;
    }

    static List<AuditEntry> decode(ListTag list) {
        if (list.size() > AuditRing.MAXIMUM || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid audit list");
        var entries = new ArrayList<AuditEntry>(list.size());
        for (int i = 0; i < list.size(); i++) {
            var tag = list.getCompound(i);
            if (!tag.getAllKeys().equals(FIELDS)) throw new IllegalArgumentException("Invalid audit fields");
            ManagedDataNbt.requireType(tag, "action", Tag.TAG_STRING);
            ManagedDataNbt.requireType(tag, "summary", Tag.TAG_STRING);
            ManagedDataNbt.requireType(tag, "game_tick", Tag.TAG_LONG);
            String action = tag.getString("action");
            if (action.length() > 128) throw new IllegalArgumentException("Oversize audit action");
            entries.add(new AuditEntry(
                    ResourceLocation.parse(action),
                    ManagedDataNbt.readUuid(tag, "actor"),
                    ManagedDataNbt.readUuid(tag, "target"),
                    tag.getLong("game_tick"),
                    tag.getString("summary")));
        }
        return List.copyOf(entries);
    }
}

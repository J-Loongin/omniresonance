// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/** Bounded local search grammar. Parsing never touches inventory; invalid syntax cannot replace a prior query. */
final class DomainInventoryQuery {
    record Document(
            String name,
            String mod,
            String id,
            String type,
            List<String> tags,
            Supplier<String> tooltip,
            String unit,
            java.util.function.LongSupplier amount) {
        Document(String name, String mod, String id, String type, List<String> tags, Supplier<String> tooltip) {
            this(name, mod, id, type, tags, tooltip, "", () -> -1);
        }

        Document {
            Objects.requireNonNull(name);
            Objects.requireNonNull(mod);
            Objects.requireNonNull(id);
            Objects.requireNonNull(type);
            tags = List.copyOf(tags);
            Objects.requireNonNull(tooltip);
            Objects.requireNonNull(unit);
            Objects.requireNonNull(amount);
        }
    }

    private record Lexeme(String text, boolean alternative, boolean literal) {}

    private record Term(char field, String value, boolean exclude) {
        boolean matches(Document doc) {
            boolean found =
                    switch (field) {
                        case '@' -> doc.mod().contains(value);
                        case '#' -> {
                            boolean match = false;
                            for (String tag : doc.tags())
                                if (tag.contains(value)) {
                                    match = true;
                                    break;
                                }
                            yield match;
                        }
                        case '$' -> {
                            boolean match = doc.name().contains(value)
                                    || doc.id().contains(value)
                                    || doc.type().contains(value)
                                    || doc.unit().toLowerCase(Locale.ROOT).contains(value);
                            if (!match)
                                for (String tag : doc.tags())
                                    if (tag.contains(value) || ("#" + tag).contains(value)) {
                                        match = true;
                                        break;
                                    }
                            long amount = doc.amount().getAsLong();
                            if (!match && amount >= 0) {
                                String quantity = doc.unit().equals("B")
                                        ? DomainFluidDisplay.exactBuckets(amount)
                                        : Long.toString(amount);
                                match = (quantity + " " + doc.unit())
                                        .toLowerCase(Locale.ROOT)
                                        .contains(value);
                            }
                            yield match
                                    || doc.tooltip()
                                            .get()
                                            .toLowerCase(Locale.ROOT)
                                            .contains(value);
                        }
                        case '*' -> doc.id().contains(value);
                        case ':' -> doc.type().equals(value);
                        default -> ClientTextSearch.matchesFolded(doc.name(), value);
                    };
            return exclude != found;
        }
    }

    private final List<List<Term>> groups;

    private DomainInventoryQuery(List<List<Term>> groups) {
        this.groups = List.copyOf(groups);
    }

    static DomainInventoryQuery parse(String query) {
        if (query.length() > 256) throw new IllegalArgumentException("Query is too long");
        var tokens = new ArrayList<Lexeme>();
        var token = new StringBuilder();
        boolean quoted = false, literal = false;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '"') {
                if (token.isEmpty() && quoted) throw new IllegalArgumentException("Empty query phrase");
                if (token.isEmpty() && !quoted) literal = true;
                quoted = !quoted;
                continue;
            }
            if (quoted && c == '\\') {
                if (++i >= query.length() || (query.charAt(i) != '"' && query.charAt(i) != '\\'))
                    throw new IllegalArgumentException("Invalid query escape");
                token.append(query.charAt(i));
                continue;
            }
            if (!quoted && (Character.isWhitespace(c) || c == '|')) {
                if (!token.isEmpty()) {
                    tokens.add(new Lexeme(token.toString(), false, literal));
                    token.setLength(0);
                    literal = false;
                }
                if (c == '|') tokens.add(new Lexeme("", true, false));
            } else token.append(c);
        }
        if (quoted) throw new IllegalArgumentException("Unclosed query phrase");
        if (!token.isEmpty()) tokens.add(new Lexeme(token.toString(), false, literal));
        var groups = new ArrayList<List<Term>>();
        var group = new ArrayList<Term>();
        for (Lexeme lexeme : tokens) {
            String original = lexeme.text();
            if (lexeme.alternative()) {
                if (group.isEmpty()) throw new IllegalArgumentException("Empty query alternative");
                groups.add(List.copyOf(group));
                group.clear();
                continue;
            }
            String value = original.toLowerCase(Locale.ROOT);
            boolean exclude = !lexeme.literal() && value.startsWith("!");
            if (exclude) value = value.substring(1);
            if (value.isEmpty() || !lexeme.literal() && value.startsWith("!"))
                throw new IllegalArgumentException("Missing query value");
            char field = value.charAt(0);
            if (lexeme.literal()) field = ' ';
            else if (value.startsWith("type:")) {
                field = ':';
                value = value.substring(5);
                value = switch (value) {
                    case "item" -> "minecraft:item";
                    case "fluid" -> "minecraft:fluid";
                    case "energy" -> "neoforge:energy";
                    default -> value;
                };
            } else if (field == '@' || field == '#' || field == '$' || field == '*') value = value.substring(1);
            else field = ' ';
            if (value.isEmpty()) throw new IllegalArgumentException("Missing query value");
            group.add(new Term(field, value, exclude));
        }
        if (group.isEmpty() && !groups.isEmpty()) throw new IllegalArgumentException("Empty query alternative");
        groups.add(List.copyOf(group));
        return new DomainInventoryQuery(groups);
    }

    boolean matches(Document doc) {
        for (var group : groups) {
            boolean found = true;
            for (var term : group)
                if (!term.matches(doc)) {
                    found = false;
                    break;
                }
            if (found) return true;
        }
        return false;
    }
}

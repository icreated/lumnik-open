package io.lumnik.hub.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;
import java.util.Locale;

/**
 * A parsed {@code kind: SearchIndex} (#669): the allowlist of one Entity's canonical columns that may
 * leave PostgreSQL for an optional search provider. There is no scope here on purpose — who may see a
 * hit is the Entity's scopes, resolved at query time — and no raw-table source.
 *
 * <p>Every declared field is returned; {@code match} makes it searchable and {@code facet} a facet.
 * So "searchable but not returnable" (an existence oracle) cannot be written, and highlights and
 * facets can only ever show a returnable field.
 *
 * @param language provider-neutral text analysis language; {@code simple} when not declared.
 * @param titleField the one declared field a client shows as the hit's business title (#1044); null
 *                   when none is declared — never inferred from the order of {@code fields}.
 * @param links external links a client draws on a returned field's value (#1051); empty when none,
 *              and then absent from the stored jsonb.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchIndexManifest(String name, String entity, String language, List<Field> fields,
                                  String titleField,
                                  @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Link> links) {

    public SearchIndexManifest {
        fields = List.copyOf(fields);
        links = links == null ? List.of() : List.copyOf(links);
    }

    public SearchIndexManifest(String name, String entity, String language, List<Field> fields,
                               String titleField) {
        this(name, entity, language, fields, titleField, List.of());
    }

    public SearchIndexManifest(String name, String entity, String language, List<Field> fields) {
        this(name, entity, language, fields, null, List.of());
    }

    /** An external page a returned field's value leads to; {@code url} is a template, see {@code LinkTemplate}. */
    public record Link(String field, String url) {}

    /** How a field is matched. {@code phonetic} is deliberately absent until a provider proves it on FR/DE. */
    public enum Match {
        EXACT, TEXT, FUZZY, PREFIX;

        /** The manifest's own spelling, also the stored jsonb's. */
        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** @param match null when the field is returned but not searched — and then absent from the jsonb. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Field(String name, Match match, boolean facet) {}

    public List<String> searchableFields() {
        return fields.stream().filter(f -> f.match() != null).map(Field::name).toList();
    }

    public List<String> returnableFields() {
        return fields.stream().map(Field::name).toList();
    }

    public List<String> facetFields() {
        return fields.stream().filter(Field::facet).map(Field::name).toList();
    }
}

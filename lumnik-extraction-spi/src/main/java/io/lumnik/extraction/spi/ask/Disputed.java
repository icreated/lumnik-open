package io.lumnik.extraction.spi.ask;

import java.util.List;

/**
 * A fused record an analytic answer filtered on while its sources disagree about the filtered
 * column (#874). {@code counted}: the record was counted on its retained value; false: it was
 * excluded although one source holds {@code matched}. {@code sources} is the record's
 * {@code _conflicts} ledger for {@code column}, in ledger order; a source value may be null.
 */
public record Disputed(String key, String column, String label, String matched,
                       String retained, boolean counted, List<SourceValue> sources) {

    public record SourceValue(String src, String value) {}

    public Disputed {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}

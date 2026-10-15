package io.lumnik.extraction.spi.search;

import java.time.Instant;
import java.util.List;

/**
 * The write side of the projection SPI (#671): drains #701's marks and #669's purge obligations into
 * an optional Search provider. Implemented by the same concrete class each provider (#705, #672) uses
 * for {@link IndexedSearchPort} — one configured provider name serves both directions.
 */
public interface ProjectionSink {

    String name();
    ProviderStatus status();

    /**
     * Opens a fresh build generation for (tenantId, searchIndex) against {@code definition} — a
     * PostgreSQL Search or OpenSearch provider needs the language and every field's match mode/facet
     * flag to build its own analyzer/mapping correctly; neither can be inferred from documents alone,
     * and neither provider may read {@code core.search_index} itself (that would be a hidden hub
     * dependency behind the SPI, and would duplicate the manifest parsing the hub already owns). The
     * returned token is opaque, and every call is independent — no identity contract across calls, no
     * reuse for the worker to rely on: crash recovery is the worker's reaper's job, not this method's;
     * a crashed attempt's generation is never resumed, only ever abandoned wholesale.
     *
     * <p>The <b>physical</b> resource this token addresses, however, MUST be the single <i>staging</i>
     * slot this provider keeps per (tenantId, searchIndex) — never the live, published generation
     * {@link #publish} last promoted, which this call MUST NOT touch: a disposable table/index/alias
     * named deterministically from the pair plus a fixed "-building" (or equivalent) suffix, distinct
     * from whatever the live one is named, reset (dropped-and-recreated, or truncated) at the start of
     * this call, rebuilt against {@code definition}. At most one staging slot and one live generation
     * coexist per pair, never zero-or-one. Otherwise a crash between this call and {@link #publish},
     * repeated over time, would accumulate one abandoned staging resource per crash forever; keeping
     * exactly one staging slot per pair means the very next {@code beginGeneration} for that same pair
     * reclaims whatever the previous attempt left there, by construction, with no separate sweep or TTL
     * needed.
     */
    String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition);

    /**
     * Upserts into {@code generation} — the live one for incremental catch-up, a fresh one mid-rebuild.
     *
     * <p>Called from inside the caller's own ambient transaction during a full rebuild's scan (a
     * server-side cursor stays open across every {@code upsert} call until the scan finishes).
     * Implementations MUST NOT enlist their own database work in that ambient transaction: a
     * JDBC-backed provider opens and commits its own, separate, unenlisted connection; a REST-backed
     * provider (HTTP calls never enlist) needs no special care here.
     */
    void upsert(long tenantId, String searchIndex, String generation, List<ProjectionDocument> documents);

    /**
     * Removes documents by {@link ProjectionDocument#id}, never a decomposed key — incremental
     * "row no longer matches" catch-up hands over exactly the {@code canonical_key} its mark carried,
     * which is all that is left once the view row backing it is gone.
     */
    void delete(long tenantId, String searchIndex, String generation, List<String> ids);

    /** Atomically makes {@code generation} the live one; the previously-live generation is dropped after. */
    void publish(long tenantId, String searchIndex, String generation);

    /**
     * {@link #publish(long, String, String)} plus the instant the generation is known complete up to
     * (#730): every mark-covered hub change committed at or before {@code reflectedUpTo} is in the
     * generation. A provider that can report freshness records it in the same atomic step as the
     * cutover; the default drops it, which leaves that provider's answers at "freshness unknown"
     * ({@code IndexedSearchResult.asOf == null}) — legal, and the only honest answer for a provider
     * that does not store it.
     */
    default void publish(long tenantId, String searchIndex, String generation, Instant reflectedUpTo) {
        publish(tenantId, searchIndex, generation);
    }

    /**
     * Raises the live {@code generation}'s reflected-up-to instant (#730). Monotone: a value not newer
     * than what is stored is ignored. A token that is not the live generation any more (a cutover
     * raced this call) is a silent no-op, never an error. The caller has already applied every
     * document the instant covers; a crash before this call only leaves the stored instant too old.
     * Default: no-op (provider does not report freshness).
     */
    default void advanceAsOf(long tenantId, String searchIndex, String generation, Instant reflectedUpTo) {
    }

    /** Discards a generation that failed mid-build; never touches whichever generation is currently live. */
    void abandon(long tenantId, String searchIndex, String generation);

    /**
     * Wipes every document of (tenantId, searchIndex), every generation, live or not. The only purge
     * method the SPI has — a tenant delete's fan-out is simply several {@code core.search_index_purge}
     * rows drained through this one method, never a bulk tenant-wide call.
     */
    void purgeIndex(long tenantId, String searchIndex);

    /** The published generation's own count — never staging, never an estimate. Unavailable is a
     *  provider error, never silently 0. */
    long liveDocumentCount(long tenantId, String searchIndex);
}

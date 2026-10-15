package io.lumnik.hub.connector.mapping;

import java.util.List;
import java.util.Map;

/**
 * One chunk as {@code MappingEngine.prepare} left it, ahead of its write transaction: the rows its
 * ONE transform produced, that transform's {@link MappingState} (the drops and the cell-error
 * count), and the row_hash → vector map embedded for those rows (empty when there is no RAG
 * target). {@code MappingEngine.writePrepared} writes exactly these rows.
 *
 * <p>Nothing transforms the chunk a second time (#888). Cell hooks rewrite the row maps in place,
 * so a second pass ran every hook again on its own output — {@code regex-replace '^' -> 'X-'}
 * landed {@code X-X-42}, {@code split into-array} split the list's {@code toString()} — and a
 * RAG chunk whose row changed between the passes no longer matched its prepared vector's key, so
 * {@code RagWriter} skipped it. Writing the prepared rows makes the hash the vector was embedded
 * under the hash of the row that is written, by construction.
 *
 * @param sourceRows how many source rows came in; {@code writePrepared} refuses a chunk whose
 *                   transform recorded more drops than that
 * @param rows       the rows the transform produced, written as they are
 * @param state      that transform's own state: its drops and its cell-error count
 * @param vectors    row_hash → vector embedded for {@code rows}; empty without a RAG target
 */
public record PreparedChunk(int sourceRows, List<Map<String, Object>> rows, MappingState state,
                            Map<String, float[]> vectors) {}

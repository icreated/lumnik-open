package io.lumnik.hub.connector.api.types;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * What a reader says about a run it could not be sure it read right, collected over the run's chunks (#1885): a guessed
 * encoding, today. It lives in the run's {@link RunScope}, so every chunk's reader writes to the same list, and the
 * engine reads it when the stream ends: a run with a note ends {@code Partial}, the sentences on the run row.
 *
 * <p>Not a loss: nothing was left unread. A sentence is already worded for the integrator. A fact that can repeat a
 * thousand times (one per file of a folder) is a {@link Note} the reader keeps under one key and updates: the run says it
 * once, with a count, and its row never grows with the folder.
 */
public final class RunNotes implements AutoCloseable {

  /** A note whose sentence is worded when the run ends, from what the readers added to it. */
  @FunctionalInterface
  public interface Note {
    /** The sentence, or {@code null} when the note has nothing to say: it is then left out, never joined. */
    String sentence();
  }

  /** The run-scope key: one list per run. */
  public record Key() {}

  private static final Key KEY = new Key();

  private final Map<Object, Note> notes = new LinkedHashMap<>();

  public static RunNotes of(RunScope scope) {
    return scope.resource(KEY, RunNotes::new);
  }

  /** Said once: the same sentence from a second chunk adds nothing. */
  public synchronized void say(String sentence) {
    Objects.requireNonNull(sentence, "a run note needs a sentence: the engine joins them");
    notes.putIfAbsent(sentence, () -> sentence);
  }

  /**
   * The note held under {@code key}, built by {@code factory} on first use. The caller updates it under its own lock:
   * a run's chunks do not overlap, and the engine reads it once the last has ended.
   */
  @SuppressWarnings("unchecked")
  public synchronized <T extends Note> T note(Object key, Supplier<T> factory) {
    return (T) notes.computeIfAbsent(key, k -> factory.get());
  }

  public synchronized List<String> sentences() {
    return notes.values().stream().map(Note::sentence).filter(Objects::nonNull).toList();
  }

  @Override
  public void close() {
    // holds no resource; the run scope only keeps AutoCloseables
  }
}

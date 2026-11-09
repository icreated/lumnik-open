package io.lumnik.hub.connector.api.types;

import io.lumnik.hub.connector.runtime.OwnedRunScope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class RunNotesTest {

  @Test
  void a_sentence_is_said_once() {
    var scope = new OwnedRunScope("test");

    RunNotes.of(scope).say("a");
    RunNotes.of(scope).say("a");
    RunNotes.of(scope).say("b");

    assertThat(RunNotes.of(scope).sentences()).containsExactly("a", "b");
  }

  @Test
  void a_null_sentence_is_refused_where_it_is_said_not_where_the_run_joins_them() {
    var scope = new OwnedRunScope("test");

    assertThatNullPointerException().isThrownBy(() -> RunNotes.of(scope).say(null));
  }

  @Test
  void a_note_with_nothing_to_say_is_left_out_not_joined_as_null() {
    var scope = new OwnedRunScope("test");
    RunNotes.of(scope).note("quiet", () -> (RunNotes.Note) () -> null);
    RunNotes.of(scope).say("a");

    assertThat(RunNotes.of(scope).sentences()).containsExactly("a");
  }
}

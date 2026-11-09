package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.RunNotes;
import io.lumnik.hub.connector.runtime.OwnedRunScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1885: a guessed encoding is said. ICU has no recogniser for the DOS code pages, so a CP850 file is read as
 * windows-1252 and nothing tells the integrator; a guess over bytes above 0x7F is never certain, and the run says so.
 */
class EncodingGuessTest {

  private static final String FOXPRO = "\"M-0201\",\"Fraiseuse Huré\",20190314,20261002,T,12500.00\n"
      + "\"M-0202\",\"Perceuse à colonne\",19980601,,F,0.00\n";

  private static Path write(Path dir, String name, String text, Charset cs) throws Exception {
    Path f = dir.resolve(name);
    Files.write(f, text.getBytes(cs));
    return f;
  }

  @Test
  void a_dos_code_page_file_is_a_weak_guess(@TempDir Path tmp) throws Exception {
    var guess = EncodingDetector.guess(write(tmp, "dos.csv", FOXPRO, Charset.forName("IBM850")));

    assertThat(guess.weak()).as("ICU cannot recognise CP850: it can only be guessed wrong").isTrue();
    var scope = new OwnedRunScope("test");
    RunNotes.of(scope).note(new CsvFileConnectorType.GuessedEncodingsKey(), CsvFileConnectorType.GuessedEncodings::new)
        .add("dos.csv", guess);
    assertThat(RunNotes.of(scope).sentences()).singleElement().asString()
        .contains("dos.csv", guess.charset().name(), "guessed", "parser.encoding")
        .contains(guess.confidence() + "%")
        .doesNotContain("more file");
  }

  @Test
  void a_file_with_no_byte_above_ascii_has_nothing_to_guess(@TempDir Path tmp) throws Exception {
    var guess = EncodingDetector.guess(write(tmp, "ascii.csv", "id,name\n1,Alpha\n2,Beta\n", StandardCharsets.UTF_8));

    assertThat(guess.weak()).as("every single-byte page reads ASCII the same way").isFalse();
  }

  @Test
  void a_file_that_is_valid_utf8_with_accents_is_not_a_weak_guess(@TempDir Path tmp) throws Exception {
    var guess = EncodingDetector.guess(write(tmp, "utf8.csv", "id;nom\n1;Café crème\n2;Été\n", StandardCharsets.UTF_8));

    assertThat(guess.charset()).isEqualTo(StandardCharsets.UTF_8);
    assertThat(guess.weak()).isFalse();
  }

  @Test
  void a_file_that_announces_its_encoding_with_a_bom_is_not_a_guess(@TempDir Path tmp) throws Exception {
    var guess = EncodingDetector.guess(write(tmp, "utf16.csv", "﻿id;nom\n1;Café\n", StandardCharsets.UTF_16LE));

    assertThat(guess.weak()).as("a byte order mark is a declaration").isFalse();
  }

  private static final String FRENCH = "code;libelle;ville\nA1;Électricité générale;Orléans\nA2;Café crème et thé glacé;Lyon\n"
      + "A3;Fenêtre à double vitrage;Besançon\nA4;Garçon de café;Saint-Étienne\nA5;Noël en été;Genève\n";

  @Test
  void a_clear_latin1_file_is_not_a_weak_guess(@TempDir Path tmp) throws Exception {
    var guess = EncodingDetector.guess(write(tmp, "latin1.csv", FRENCH, StandardCharsets.ISO_8859_1));

    assertThat(guess.weak()).as("accents above 0xC0 and no byte in 0x80-0x9F: nothing suspect").isFalse();
  }

  @Test
  void a_windows_1252_file_with_typographic_quotes_is_not_a_weak_guess(@TempDir Path tmp) throws Exception {
    String text = "id,note\n1,\u201CBonjour\u201D il a dit\n2,l\u2019\u00E9t\u00E9 prochain \u2013 peut-\u00EAtre\n";
    var guess = EncodingDetector.guess(write(tmp, "quotes.csv", text, Charset.forName("windows-1252")));

    assertThat(guess.weak()).as("curly quotes and dashes live in 0x80-0x9F too: they are not accents").isFalse();
  }

  @Test
  void a_dos_file_of_several_lines_is_weak_though_ICU_is_as_sure_of_it_as_of_latin1(@TempDir Path tmp) throws Exception {
    var guess = EncodingDetector.guess(write(tmp, "dos.csv", FRENCH, Charset.forName("IBM850")));

    assertThat(guess.confidence()).as("ICU's confidence does not tell them apart").isGreaterThanOrEqualTo(40);
    assertThat(guess.weak()).as("accents sit in 0x80-0x9F next to letters: windows-1252 has punctuation there").isTrue();
  }

  @Test
  void a_guess_ICU_itself_doubts_is_weak(@TempDir Path tmp) throws Exception {
    var guess = EncodingDetector.guess(write(tmp, "short.csv", "id;nom\n1;Café crème\n2;Été\n", Charset.forName("windows-1252")));

    assertThat(guess.confidence()).isLessThan(25);
    assertThat(guess.weak()).isTrue();
  }

  @Test
  void a_declared_encoding_says_nothing(@TempDir Path tmp) throws Exception {
    Path f = write(tmp, "dos.csv", FOXPRO, Charset.forName("IBM850"));
    var scope = new OwnedRunScope("test");
    var o = CsvFileConnectorType.buildParserOverrides(Map.of("encoding", "IBM850"));

    var cfg = CsvFileConnectorType.effectiveConfig(f, o, scope, "dos.csv");

    assertThat(cfg.encoding().name()).isEqualTo("IBM850");
    assertThat(RunNotes.of(scope).sentences()).isEmpty();
  }

  @Test
  void an_undeclared_weak_guess_is_said_to_the_run_once_per_file(@TempDir Path tmp) throws Exception {
    Path f = write(tmp, "dos.csv", FOXPRO, Charset.forName("IBM850"));
    var scope = new OwnedRunScope("test");
    var o = CsvFileConnectorType.buildParserOverrides(Map.of());

    CsvFileConnectorType.effectiveConfig(f, o, scope, "dos.csv");
    CsvFileConnectorType.effectiveConfig(f, o, scope, "dos.csv");

    List<String> said = RunNotes.of(scope).sentences();
    assertThat(said).hasSize(1);
    assertThat(said.get(0)).contains("dos.csv", "parser.encoding");
  }

  @Test
  void a_local_run_reading_an_undeclared_dos_file_says_it_through_its_scope(@TempDir Path tmp) throws Exception {
    Path f = write(tmp, "machines.csv", "code,label,since\n" + FOXPRO.replace(",20261002", ""), Charset.forName("IBM850"));
    var undeclared = new CsvFileConnectorType.ParserOverrides(null, null, true, true,
        List.of("", "NULL", "null", "#N/A"), null);
    var scope = new OwnedRunScope("test");

    new CsvFileConnectorType.CsvGlobCursorReader(f.toString(), undeclared)
        .read(500, ConnectorCursor.empty(), ReaderTestContexts.of(scope));

    assertThat(RunNotes.of(scope).sentences()).singleElement().asString().contains(f.toString(), "parser.encoding");
  }

  @Test
  void a_thousand_guessed_files_make_one_sentence_with_the_first_file_and_a_count(@TempDir Path tmp) throws Exception {
    var scope = new OwnedRunScope("test");
    var o = CsvFileConnectorType.buildParserOverrides(Map.of());
    for (int i = 0; i < 1000; i++) {
      Path f = write(tmp, "dos-" + i + ".csv", FOXPRO, Charset.forName("IBM850"));
      CsvFileConnectorType.effectiveConfig(f, o, scope, "dos-" + i + ".csv");
      CsvFileConnectorType.effectiveConfig(f, o, scope, "dos-" + i + ".csv");   // a later window of the same file
    }

    List<String> said = RunNotes.of(scope).sentences();

    assertThat(said).as("one sentence for the run, not one per file").hasSize(1);
    assertThat(said.get(0)).contains("dos-0.csv", "parser.encoding", "999 more file(s)")
        .doesNotContain("dos-1.csv");
  }
}

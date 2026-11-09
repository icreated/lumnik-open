package io.lumnik.hub.notify;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MailSubjectTest {

    @Test
    void aDecisionIsSubjectedByItsSentence() {
        assertEquals("Chambre froide CF-1: 10.3 °C",
            MailSubject.of("decision.cold-room", Map.of("message", "Chambre froide CF-1: 10.3 °C")));
    }

    @Test
    void aLongSentenceIsCutWithAnEllipsis() {
        String subject = MailSubject.of("decision.x", Map.of("message", "a".repeat(300)));
        assertEquals(MailSubject.MAX_LENGTH, subject.length());
        assertEquals('…', subject.charAt(subject.length() - 1));
    }

    @Test
    void theCutNeverSplitsAnEmoji() {
        String subject = MailSubject.of("decision.x", Map.of("message", "a".repeat(98) + "😀" + "bcd"));
        assertEquals("a".repeat(98) + "😀" + "…", subject);
        assertEquals(MailSubject.MAX_LENGTH, subject.codePointCount(0, subject.length()));
    }

    @Test
    void aSentenceOfExactlyTheLimitIsKept() {
        String sentence = "a".repeat(99) + "😀";
        assertEquals(sentence, MailSubject.of("decision.x", Map.of("message", sentence)));
    }

    @Test
    void lineBreaksNeverReachTheSubject() {
        assertEquals("one two three",
            MailSubject.of("decision.x", Map.of("message", " one\r\ntwo\n three ")));
    }

    @Test
    void noSentenceFallsBackToTheEventType() {
        assertEquals("lumnik event: decision.x", MailSubject.of("decision.x", Map.of()));
        assertEquals("lumnik event: decision.x", MailSubject.of("decision.x", Map.of("message", "  ")));
        assertEquals("lumnik event: decision.x", MailSubject.of("decision.x", Map.of("message", 42)));
        assertEquals("lumnik event: decision.x", MailSubject.of("decision.x", null));
    }

    @Test
    void otherEventsKeepTheEventType() {
        assertEquals("lumnik event: data.row.changed",
            MailSubject.of("data.row.changed", Map.of("message", "not a decision")));
    }
}

package io.lumnik.hub.proposal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reference validator of the proposal-dossier contract, schema 1 (spec 2026-10-03, sections 1-2).
 * Structural only: it never re-verifies evidence — a condition carrying well-formed evidence makes
 * the file VALID_RECHECK, the importer's cue not to trust it. A file breaking several rules is refused
 * for one of them; WHICH one is not part of the contract, so every invalid fixture carries exactly one
 * violation. Production reader since slice 3a (#802); the fixture corpus judges it.
 */
final class DossierReader {

    enum Verdict {
        INVALID, VALID, VALID_RECHECK;

        String dir() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
    }

    enum Reason {
        MALFORMED_HEADER, DUPLICATE_KEY, UNKNOWN_SCHEMA_VERSION, UNKNOWN_FIELD, MISSING_FIELD, NULL_VALUE,
        WRONG_TYPE, BAD_DATE, ANSWER_SOURCE_NOT_PROSPECT, UNKNOWN_ANSWER_KIND, UNKNOWN_ANSWER_VALUE,
        LABEL_MISMATCH, LEAD_SOURCE_NOT_CATALOGUE, DUPLICATE_ID, ORPHAN_BACKED_BY, UNDERIVABLE_ANSWER,
        UNKNOWN_CHECK, UNKNOWN_VERIFIER, DERIVATION_MISMATCH, OUTCOME_WITHOUT_EVIDENCE, OUTCOME_ON_UNVERIFIABLE,
        EMPTY_EVIDENCE, EVIDENCE_ON_UNVERIFIABLE, EVIDENCE_VIA_MISMATCH, EVIDENCE_SOURCE_NOT_DISCOVERY;

        String code() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
    }

    record Result(Verdict verdict, Reason reason) {}

    static final class Refused extends RuntimeException {
        private final Reason reason;

        Refused(Reason reason) {
            super(reason.code(), null, false, false);
            this.reason = reason;
        }

        Reason reason() { return reason; }
    }

    private enum Type { TEXT, INT, LIST, OBJECT }

    /** One object level: its keys, which are required, and what each holds. */
    private record Shape(Map<String, Type> keys, Set<String> required) {}

    private static final Shape ROOT = new Shape(Map.of("lumnik-dossier", Type.INT, "catalogue", Type.TEXT,
        "created", Type.TEXT, "language", Type.TEXT, "words", Type.TEXT, "answers", Type.LIST, "leads", Type.LIST),
        Set.of("lumnik-dossier", "catalogue", "created", "language", "answers", "leads"));
    private static final Shape ANSWER = new Shape(Map.of("id", Type.TEXT, "kind", Type.TEXT, "asked", Type.TEXT,
        "value", Type.TEXT, "label", Type.TEXT, "source", Type.TEXT), Set.of("id", "kind", "asked", "source"));
    private static final Shape LEAD = new Shape(Map.of("id", Type.TEXT, "rule", Type.TEXT, "source", Type.TEXT,
        "title", Type.TEXT, "says", Type.TEXT, "does-not-say", Type.LIST, "proposes", Type.OBJECT,
        "conditions", Type.LIST), Set.of("id", "rule", "source", "title", "says", "does-not-say", "proposes", "conditions"));
    private static final Shape PROPOSES = new Shape(Map.of("kind", Type.TEXT, "uses", Type.LIST), Set.of("kind", "uses"));
    private static final Shape CONDITION = new Shape(Map.of("id", Type.TEXT, "needs", Type.TEXT, "verifier", Type.TEXT,
        "label", Type.TEXT, "backed-by", Type.TEXT, "check", Type.TEXT, "note", Type.TEXT, "evidence", Type.LIST),
        Set.of("id", "needs", "verifier", "label", "check"));
    private static final Shape EVIDENCE = new Shape(Map.of("source", Type.TEXT, "via", Type.TEXT, "target", Type.TEXT,
        "observed", Type.TEXT), Set.of("source", "via", "target", "observed"));

    private static final Set<String> YES_NO_UNKNOWN = Set.of("yes", "no", "unknown");
    private static final Set<String> CHECKS = Set.of("unverified", "missing", "confirmed", "refuted");
    private static final Set<String> VERIFIERS = Set.of("workflow-data-truth", "workflow-apply-door", "none");

    private DossierReader() {}

    static Result judge(String file) {
        try {
            return new Result(validate(header(file)) ? Verdict.VALID_RECHECK : Verdict.VALID, null);
        } catch (Refused r) {
            return new Result(Verdict.INVALID, r.reason());
        }
    }

    /**
     * The file's bytes as text: UTF-8, strictly. {@code new String(bytes, UTF_8)} replaces a malformed
     * sequence with U+FFFD, so a Latin-1 copy of a dossier would be accepted with its accents silently
     * gone; the contract has no reason of its own for it, and the header is what cannot be read.
     */
    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new Refused(Reason.MALFORMED_HEADER);
        }
    }

    /** The dossier as a model, or a {@link Refused} carrying the contract's reason. */
    static Dossier read(String file) {
        JsonNode root = header(file);
        validate(root);
        return build(root);
    }

    private static Dossier build(JsonNode root) {
        List<Dossier.Answer> answers = new ArrayList<>();
        for (JsonNode a : root.get("answers")) {
            answers.add(new Dossier.Answer(text(a, "id"), text(a, "kind"), text(a, "asked"),
                text(a, "value"), text(a, "label"), text(a, "source")));
        }
        List<Dossier.Lead> leads = new ArrayList<>();
        for (JsonNode l : root.get("leads")) {
            List<Dossier.Condition> conditions = new ArrayList<>();
            for (JsonNode c : l.get("conditions")) {
                List<Dossier.Evidence> evidence = null;
                if (c.has("evidence")) {
                    evidence = new ArrayList<>();
                    for (JsonNode e : c.get("evidence")) {
                        evidence.add(new Dossier.Evidence(text(e, "source"), text(e, "via"), text(e, "target"), text(e, "observed")));
                    }
                }
                conditions.add(new Dossier.Condition(text(c, "id"), text(c, "needs"), text(c, "verifier"), text(c, "label"),
                    text(c, "backed-by"), text(c, "check"), text(c, "note"), evidence));
            }
            leads.add(new Dossier.Lead(text(l, "id"), text(l, "rule"), text(l, "source"), text(l, "title"), text(l, "says"),
                strings(l.get("does-not-say")),
                new Dossier.Proposes(text(l.get("proposes"), "kind"), strings(l.get("proposes").get("uses"))), conditions));
        }
        return new Dossier(text(root, "catalogue"), text(root, "created"), text(root, "language"), text(root, "words"), answers, leads);
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v == null ? null : v.asText();
    }

    private static List<String> strings(JsonNode list) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : list) out.add(item.asText());
        return out;
    }

    private static JsonNode header(String file) {
        // One leading byte order mark is ignored (editors on Windows write it); a second one is not part of the contract.
        String text = file.startsWith("\uFEFF") ? file.substring(1) : file;
        text = text.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) throw new Refused(Reason.MALFORMED_HEADER);
        // The closing line is "---", followed by the body or by nothing at all (spec: "the next line ---").
        int end = text.indexOf("\n---\n", 3);
        if (end < 0 && text.endsWith("\n---")) end = text.length() - 4;
        if (end < 0) throw new Refused(Reason.MALFORMED_HEADER);
        try {
            JsonNode root = Catalogue.STRICT_JSON.readTree(text.substring(4, end + 1));
            if (root == null || !root.isObject()) throw new Refused(Reason.MALFORMED_HEADER);
            return root;
        } catch (JsonProcessingException e) {
            boolean duplicate = e.getOriginalMessage() != null && e.getOriginalMessage().startsWith("Duplicate field");
            throw new Refused(duplicate ? Reason.DUPLICATE_KEY : Reason.MALFORMED_HEADER);
        }
    }

    /** @return true when some condition carries evidence the importer must re-verify. */
    private static boolean validate(JsonNode root) {
        // The version first: a schema-2 file is expected to carry fields schema 1 does not know.
        JsonNode version = root.get("lumnik-dossier");
        if (version == null) throw new Refused(Reason.MISSING_FIELD);
        if (!version.isInt()) throw new Refused(Reason.WRONG_TYPE);
        if (version.intValue() != 1) throw new Refused(Reason.UNKNOWN_SCHEMA_VERSION);
        shape(root, ROOT);
        date(root.get("created").asText());

        Map<String, JsonNode> answers = new HashMap<>();
        for (JsonNode a : root.get("answers")) {
            answer(a);
            if (answers.put(a.get("id").asText(), a) != null) throw new Refused(Reason.DUPLICATE_ID);
        }
        Set<String> ids = new HashSet<>();
        boolean recheck = false;
        for (JsonNode lead : root.get("leads")) {
            shape(lead, LEAD);
            shape(lead.get("proposes"), PROPOSES);
            texts(lead.get("does-not-say"));
            texts(lead.get("proposes").get("uses"));
            if (!lead.get("source").asText().equals("catalogue")) throw new Refused(Reason.LEAD_SOURCE_NOT_CATALOGUE);
            if (!ids.add(lead.get("id").asText())) throw new Refused(Reason.DUPLICATE_ID);
            for (JsonNode c : lead.get("conditions")) {
                shape(c, CONDITION);
                if (!ids.add(c.get("id").asText())) throw new Refused(Reason.DUPLICATE_ID);
                recheck |= condition(c, answers);
            }
        }
        return recheck;
    }

    private static void date(String created) {
        if (!created.matches("\\d{4}-\\d{2}-\\d{2}")) throw new Refused(Reason.BAD_DATE);
        try {
            LocalDate.parse(created);   // ISO_LOCAL_DATE resolves strictly: 2026-02-30 is refused
        } catch (DateTimeParseException e) {
            throw new Refused(Reason.BAD_DATE);
        }
    }

    /** Spec section 1, "Answers carry their question's kind": checkable without the catalogue. */
    private static void answer(JsonNode a) {
        shape(a, ANSWER);
        if (!a.get("source").asText().equals("prospect")) throw new Refused(Reason.ANSWER_SOURCE_NOT_PROSPECT);
        boolean answered = a.has("value");
        boolean labelled = a.has("label");
        switch (a.get("kind").asText()) {
            case "yes-no-unknown" -> {
                if (answered && !YES_NO_UNKNOWN.contains(a.get("value").asText())) throw new Refused(Reason.UNKNOWN_ANSWER_VALUE);
                if (labelled) throw new Refused(Reason.LABEL_MISMATCH);
            }
            case "choice" -> {
                if (answered != labelled) throw new Refused(Reason.LABEL_MISMATCH);
            }
            default -> throw new Refused(Reason.UNKNOWN_ANSWER_KIND);
        }
    }

    /** @return true when the condition carries evidence (kept history or a transported outcome). */
    private static boolean condition(JsonNode c, Map<String, JsonNode> answers) {
        String verifier = c.get("verifier").asText();
        String check = c.get("check").asText();
        if (!VERIFIERS.contains(verifier)) throw new Refused(Reason.UNKNOWN_VERIFIER);
        if (!CHECKS.contains(check)) throw new Refused(Reason.UNKNOWN_CHECK);
        String derived = derive(c, answers);
        JsonNode evidence = c.get("evidence");
        if (evidence != null) {
            // One spelling for "no evidence": the key is absent — like "value": null, [] is a second one.
            if (evidence.isEmpty()) throw new Refused(Reason.EMPTY_EVIDENCE);
            if (verifier.equals("none")) throw new Refused(Reason.EVIDENCE_ON_UNVERIFIABLE);
            for (JsonNode e : evidence) {
                shape(e, EVIDENCE);
                if (!e.get("source").asText().equals("discovery")) throw new Refused(Reason.EVIDENCE_SOURCE_NOT_DISCOVERY);
                if (!e.get("via").asText().equals(verifier)) throw new Refused(Reason.EVIDENCE_VIA_MISMATCH);
            }
        }
        boolean outcome = check.equals("confirmed") || check.equals("refuted");
        if (outcome) {
            if (verifier.equals("none")) throw new Refused(Reason.OUTCOME_ON_UNVERIFIABLE);
            if (evidence == null) throw new Refused(Reason.OUTCOME_WITHOUT_EVIDENCE);
        } else if (!check.equals(derived)) {
            // Evidence beside an unverified/missing check is kept history (spec section 2): allowed,
            // never believed — but the check itself must be exactly what the answers give.
            throw new Refused(Reason.DERIVATION_MISMATCH);
        }
        return evidence != null;
    }

    /** The site's table (spec section 2): a pure function of the backing answer. */
    private static String derive(JsonNode c, Map<String, JsonNode> answers) {
        JsonNode backedBy = c.get("backed-by");
        if (backedBy == null) return "missing";
        JsonNode answer = answers.get(backedBy.asText());
        if (answer == null) throw new Refused(Reason.ORPHAN_BACKED_BY);
        if (!answer.get("kind").asText().equals("yes-no-unknown")) throw new Refused(Reason.UNDERIVABLE_ANSWER);
        JsonNode value = answer.get("value");
        if (value == null) return "missing";
        return value.asText().equals("unknown") ? "missing" : "unverified";
    }

    private static void shape(JsonNode node, Shape shape) {
        if (node == null || !node.isObject()) throw new Refused(Reason.WRONG_TYPE);
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            if (!shape.keys().containsKey(it.next())) throw new Refused(Reason.UNKNOWN_FIELD);
        }
        for (Map.Entry<String, Type> key : shape.keys().entrySet()) {
            JsonNode v = node.get(key.getKey());
            if (v == null) {
                if (shape.required().contains(key.getKey())) throw new Refused(Reason.MISSING_FIELD);
                continue;
            }
            if (v.isNull()) throw new Refused(Reason.NULL_VALUE);
            boolean ok = switch (key.getValue()) {
                case TEXT -> v.isTextual();
                case INT -> v.isInt();
                case LIST -> v.isArray();
                case OBJECT -> v.isObject();
            };
            if (!ok) throw new Refused(Reason.WRONG_TYPE);
        }
    }

    private static void texts(JsonNode list) {
        for (JsonNode item : list) if (!item.isTextual()) throw new Refused(Reason.WRONG_TYPE);
    }
}

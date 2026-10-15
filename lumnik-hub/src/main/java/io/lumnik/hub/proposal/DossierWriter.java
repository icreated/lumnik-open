package io.lumnik.hub.proposal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes a dossier: the header between two {@code ---} lines in the layout the lumnik.fr writer uses
 * (fixed key order, two-space indent, one answer and one condition per line), then a body generated
 * from the header. Only the header is read back. The body is French; a language without presentation
 * strings gets the generated comment alone — never an exception.
 */
final class DossierWriter {

    private static final List<String> MONTHS = List.of("janvier", "février", "mars", "avril", "mai", "juin",
        "juillet", "août", "septembre", "octobre", "novembre", "décembre");

    private DossierWriter() {}

    static String write(Dossier d) {
        List<List<String>> top = new ArrayList<>();
        top.add(List.of("\"lumnik-dossier\": 1"));
        top.add(List.of("\"catalogue\": " + JsonText.quote(d.catalogue())));
        top.add(List.of("\"created\": " + JsonText.quote(d.created())));
        top.add(List.of("\"language\": " + JsonText.quote(d.language())));
        if (d.words() != null) top.add(List.of("\"words\": " + JsonText.quote(d.words())));
        top.add(array("answers", d.answers().stream().map(a -> List.of(answer(a))).toList()));
        top.add(array("leads", d.leads().stream().map(l -> {
            List<String> lines = new ArrayList<>();
            lines.add("{");
            lines.addAll(indent(2, lead(l)));
            lines.add("}");
            return lines;
        }).toList()));
        List<String> header = new ArrayList<>();
        header.add("---");
        header.add("{");
        header.addAll(indent(2, members(top)));
        header.add("}");
        header.add("---");
        header.add(body(d));
        header.add("");
        return String.join("\n", header);
    }

    private static List<String> array(String key, List<List<String>> blocks) {
        List<String> lines = new ArrayList<>();
        lines.add(JsonText.quote(key) + ": [");
        lines.addAll(indent(2, members(blocks)));
        lines.add("]");
        return lines;
    }

    private static List<String> lead(Dossier.Lead l) {
        List<List<String>> blocks = new ArrayList<>();
        blocks.add(List.of("\"id\": " + JsonText.quote(l.id())));
        blocks.add(List.of("\"rule\": " + JsonText.quote(l.rule())));
        blocks.add(List.of("\"source\": " + JsonText.quote(l.source())));
        blocks.add(List.of("\"title\": " + JsonText.quote(l.title())));
        blocks.add(List.of("\"says\": " + JsonText.quote(l.says())));
        blocks.add(array("does-not-say", l.doesNotSay().stream().map(t -> List.of(JsonText.quote(t))).toList()));
        blocks.add(List.of("\"proposes\": { \"kind\": " + JsonText.quote(l.proposes().kind()) + ", \"uses\": "
            + inlineList(l.proposes().uses().stream().map(JsonText::quote).toList()) + " }"));
        blocks.add(array("conditions", l.conditions().stream().map(c -> List.of(condition(c))).toList()));
        return members(blocks);
    }

    private static String answer(Dossier.Answer a) {
        List<String> parts = new ArrayList<>();
        parts.add(member("id", a.id()));
        parts.add(member("kind", a.kind()));
        parts.add(member("asked", a.asked()));
        opt(parts, "value", a.value());
        opt(parts, "label", a.label());
        parts.add(member("source", a.source()));
        return obj(parts);
    }

    private static String condition(Dossier.Condition c) {
        List<String> parts = new ArrayList<>();
        parts.add(member("id", c.id()));
        parts.add(member("needs", c.needs()));
        parts.add(member("verifier", c.verifier()));
        parts.add(member("label", c.label()));
        opt(parts, "backed-by", c.backedBy());
        parts.add(member("check", c.check()));
        opt(parts, "note", c.note());
        if (c.evidence() != null) {
            parts.add("\"evidence\": " + inlineList(c.evidence().stream().map(e ->
                "{ " + member("source", e.source()) + ", " + member("via", e.via()) + ", "
                + member("target", e.target()) + ", " + member("observed", e.observed()) + " }").toList()));
        }
        return obj(parts);
    }

    private static void opt(List<String> parts, String key, String value) {
        if (value != null) parts.add(member(key, value));
    }

    private static String obj(List<String> parts) {
        return "{ " + String.join(", ", parts) + " }";
    }

    private static String member(String key, String value) {
        return JsonText.quote(key) + ": " + JsonText.quote(value);
    }

    private static String inlineList(List<String> items) {
        return items.isEmpty() ? "[]" : "[ " + String.join(", ", items) + " ]";
    }

    /** A comma after every block's last line but the final block's. */
    private static List<String> members(List<List<String>> blocks) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            List<String> lines = blocks.get(i);
            for (int j = 0; j < lines.size(); j++) {
                boolean last = i < blocks.size() - 1 && j == lines.size() - 1;
                out.add(last ? lines.get(j) + "," : lines.get(j));
            }
        }
        return out;
    }

    private static List<String> indent(int n, List<String> lines) {
        String pad = " ".repeat(n);
        return lines.stream().map(l -> pad + l).toList();
    }

    // ---- the generated body ------------------------------------------------------------------

    private static final String COMMENT =
        "<!-- Generated from the header above. Only the header is read back; edits below are ignored. -->";

    private static final Map<String, String> VALUE = Map.of("yes", "Oui", "no", "Non", "unknown", "Je ne sais pas", "none", "Pas de réponse");
    private static final Map<String, String> SAID = Map.of(
        "yes", "Vous avez répondu oui — à vérifier.",
        "no", "Vous avez répondu non — à vérifier.",
        "unknown", "Vous ne savez pas — à établir.",
        "none", "Pas de réponse — à établir.",
        "uncovered", "Non couvert par le questionnaire — à établir.");

    private static String body(Dossier d) {
        if (!d.language().equals("fr")) return COMMENT;
        String[] ymd = d.created().split("-");
        List<String> out = new ArrayList<>();
        out.add(COMMENT);
        out.add("");
        out.add("# Dossier lumnik — " + Integer.parseInt(ymd[2]) + " " + MONTHS.get(Integer.parseInt(ymd[1]) - 1) + " " + ymd[0]);
        if (d.words() != null && !d.words().isEmpty()) {
            out.add("");
            for (String line : d.words().split("\n", -1)) out.add("> " + line);
        }
        Map<String, Dossier.Answer> byId = d.answersById();
        out.add("");
        out.add("## Vos réponses");
        out.add("");
        for (Dossier.Answer a : d.answers()) {
            String shown = a.value() == null ? VALUE.get("none") : a.label() != null ? a.label() : VALUE.get(a.value());
            out.add("- " + a.asked() + " — **" + shown + "**");
        }
        out.add("");
        out.add("## Pistes");
        for (Dossier.Lead lead : d.leads()) {
            out.add("");
            out.add("### " + lead.id() + " — " + lead.title());
            out.add("");
            out.add(lead.says());
            out.add("");
            out.add("**Ce que cette piste ne dit pas**");
            out.add("");
            for (String line : lead.doesNotSay()) out.add("- " + line);
            out.add("");
            out.add("**À vérifier**");
            out.add("");
            for (Dossier.Condition c : lead.conditions()) {
                out.add("- " + c.label() + " — " + sentence(c, byId));
                if (c.note() != null) out.add("  " + c.note());
                if (c.evidence() != null) {
                    boolean established = c.check().equals("confirmed") || c.check().equals("refuted");
                    // Schema 1 cannot tell this run's evidence from imported history, so a condition the hub
                    // established lists its evidence without saying who observed it: the old and the new stay
                    // side by side, and none of it is presented as freshly verified.
                    String kept = established ? "Preuve jointe" : "Preuve transportée, non vérifiée par ce site";
                    for (Dossier.Evidence e : c.evidence()) out.add("  - " + kept + " : " + e.target() + " — " + e.observed());
                }
            }
        }
        return String.join("\n", out);
    }

    /** The §2 table rendered; a condition the hub established says so instead of "to be checked". */
    private static String sentence(Dossier.Condition c, Map<String, Dossier.Answer> byId) {
        if (c.check().equals("confirmed")) return "Établi par lumnik sur les données.";
        if (c.check().equals("refuted")) return "Contredit par lumnik sur les données.";
        Dossier.Answer backing = c.backedBy() == null ? null : byId.get(c.backedBy());
        String key = c.backedBy() == null ? "uncovered" : backing.value() != null ? backing.value() : "none";
        return key.equals("no")
            ? "Cette piste suppose : " + c.label() + ". Vous indiquez ne pas l'avoir : elle reste à examiner avec l'intégrateur."
            : SAID.get(key);
    }
}

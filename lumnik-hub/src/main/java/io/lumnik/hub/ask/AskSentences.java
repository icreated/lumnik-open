package io.lumnik.hub.ask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Every fixed sentence the ask door serves in place of a phrased answer — abstentions, guard refusals,
 * the zero-row caveat — in the language the calling surface named (#915). French or English; an absent,
 * unknown or unsupported {@code lang} gets English, the sentence this door always served.
 *
 * <p>These sentences are text only. An abstention is recognised by {@code AskResult.abstained}, never by
 * what its words say, so a translation cannot break a grader.
 */
final class AskSentences {

    /** The "known: …" lists are cut here so a wide card cannot bloat a message. */
    private static final int MAX_SHOWN = 12;

    private AskSentences() {}

    private static boolean french(String lang) {
        return lang != null && "fr".equals(Locale.forLanguageTag(lang).getLanguage());
    }

    private static String pick(String lang, String en, String fr) {
        return french(lang) ? fr : en;
    }

    static String noSchema(String lang) {
        return pick(lang,
            "I have no data declared for this scope yet, so I can't answer that. Ingest a source for this scope first.",
            "Aucune donnée n'est encore déclarée pour ce périmètre, je ne peux donc pas répondre. "
                + "Ingérez d'abord une source pour ce périmètre.");
    }

    static String cannotAnswer(String lang) {
        return pick(lang,
            "I can't answer that from the data available for this scope — the question doesn't map to the declared schema.",
            "Je ne peux pas répondre à partir des données disponibles pour ce périmètre — "
                + "la question ne correspond pas au schéma déclaré.");
    }

    /** @see AbstentionGuard#signpost */
    static String lifecycleSignpost(String lang) {
        return pick(lang,
            " This scope does declare a lifecycle for its documents, and a question about why one"
                + " cannot move on is answered from that declaration rather than from the data — which is"
                + " what this door reads.",
            " Ce périmètre déclare un cycle de vie pour ses documents : la réponse à une question sur ce qui bloque "
                + "l'un d'eux se trouve dans cette déclaration, non dans les données — et seules les données sont lues ici.");
    }

    /** @see AbstentionGuard#signpost */
    static String conflictsSignpost(String lang) {
        return pick(lang,
            " Where this scope's entities record source conflicts or lookup ambiguities, it is written row by"
                + " row, on each fused record, in a conflicts ledger that is deliberately not part of the schema above.",
            " Lorsque les entités de ce périmètre enregistrent des conflits de sources ou des ambiguïtés de recherche, "
                + "ils sont consignés ligne par ligne, sur chaque fiche fusionnée, dans un registre de conflits "
                + "qui ne fait volontairement pas partie du schéma ci-dessus.");
    }

    /** A follow-up the previous question cannot resolve, or whose resolution was not trusted (#1096). */
    static String followUpUnresolved(String lang) {
        return pick(lang,
            "I couldn't tie this question to the previous one. Ask it in full, with the value or the name it refers to.",
            "Je n'ai pas pu rattacher cette question à la précédente. Posez-la en entier, avec la valeur ou le nom "
                + "auquel elle renvoie.");
    }

    static String couldNotTranslate(String lang) {
        return pick(lang,
            "I couldn't translate that into a safe query.",
            "Je n'ai pas pu traduire cela en requête sûre.");
    }

    static String zeroRows(String lang) {
        return pick(lang,
            "The query returned no rows. That can mean there are genuinely none, or that the query did not "
                + "capture your question — verify the SQL before reporting that there are none.",
            "La requête n'a renvoyé aucune ligne. Il peut n'y en avoir réellement aucune, ou la requête n'a pas "
                + "saisi votre question — vérifiez le SQL avant d'affirmer qu'il n'y en a pas.");
    }

    static String unbackedAnswer(String lang) {
        return pick(lang,
            "The phrased answer stated a figure these rows do not back, so it is withheld. "
                + "The rows and the SQL below are the answer.",
            "La réponse rédigée citait un chiffre que ces lignes ne confirment pas ; elle est donc retenue. "
                + "Les lignes et le SQL ci-dessous font foi.");
    }

    /**
     * Leads the answer, written by the code after the figures check: K and N are the code's numbers, not cells.
     * {@code capped}: the query stopped at its LIMIT, so N is a floor.
     */
    static String phrasingSample(String lang, int shown, int total, boolean capped) {
        return capped
            ? pick(lang,
                "The answer summarises the first " + shown + " of at least " + total + " rows.",
                "La réponse résume les " + shown + " premières lignes parmi au moins " + total + " lignes.")
            : pick(lang,
                "The answer summarises the first " + shown + " of the " + total + " rows returned.",
                "La réponse résume les " + shown + " premières lignes parmi les " + total + " lignes renvoyées.");
    }

    /** The query returned as many rows as its LIMIT allows: there may be more, whatever the model read. */
    static String queryCapped(String lang, int limit) {
        return pick(lang,
            "The SQL query stops at " + limit + " rows: there may be more.",
            "La requête SQL s'arrête à " + limit + " lignes : il peut y en avoir davantage.");
    }

    /** The model did not write the answer (timeout, error, output limit): the query ran, so its rows and SQL are the result. */
    static String phrasingFailed(String lang) {
        return pick(lang,
            "The model could not write the answer. The SQL ran and its rows were returned: "
                + "the rows and the SQL below are the result.",
            "Le modèle n'a pas pu rédiger la réponse. La requête SQL a bien été exécutée et ses lignes sont renvoyées : "
                + "les lignes et le SQL ci-dessous font foi.");
    }

    static String queryRefused(String lang, String rootMessage) {
        return pick(lang,
            "I couldn't run that query — the database refused it: " + rootMessage
                + " The SQL is below; if it looks wrong for this scope, re-run discovery.",
            "Je n'ai pas pu exécuter cette requête — la base l'a refusée : " + rootMessage
                + " Le SQL figure ci-dessous ; s'il semble erroné pour ce périmètre, relancez la découverte.");
    }

    static String leak(String lang, String table, List<String> declaredTables) {
        String declared = listed(lang, declaredTables);
        return pick(lang,
            "I won't run this: '" + table + "' is not a table in this scope (declared: " + declared
                + "). The query may only read the tables this scope declares.",
            "Je n'exécuterai pas cette requête : « " + table + " » n'est pas une table de ce périmètre (déclarées : "
                + declared + "). La requête ne peut pas lire de données hors du périmètre déclaré de ce métier.");
    }

    static String undocumentedColumn(String lang, String column, List<String> declaredColumns) {
        String known = listed(lang, declaredColumns);
        return pick(lang,
            "I won't run this: '" + column + "' is not a declared column of this scope (known: " + known
                + "). If it should exist, add it to the entity manifest.",
            "Je n'exécuterai pas cette requête : « " + column + " » n'est pas une colonne déclarée de ce périmètre "
                + "(connues : " + known + "). Si elle devrait exister, ajoutez-la au manifeste de l'entité.");
    }

    static String outOfDomainValue(String lang, String column, String value, List<String> allowed) {
        // SQL-style escape the value so a literal containing a quote stays unambiguous.
        String quoted = value.replace("'", "''");
        String known = String.join(", ", allowed);
        return pick(lang,
            "I won't run this: '" + quoted + "' is not a declared value of " + column + " (known: " + known
                + "). If it should exist, add it to the entity manifest.",
            "Je n'exécuterai pas cette requête : « " + quoted + " » n'est pas une valeur déclarée de " + column
                + " (connues : " + known + "). Si elle devrait exister, ajoutez-la au manifeste de l'entité.");
    }

    /**
     * @param values the probe's rows, in order; the listing is DEDUPLICATED, the count is not
     * @param capped the probe hit its row limit, so the count is a floor and is worded as one
     */
    static String ambiguousFilter(String lang, String label, List<String> values, boolean capped) {
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(values));
        List<String> shown = distinct.size() > AmbiguousFilterGuard.MAX_LISTED
            ? distinct.subList(0, AmbiguousFilterGuard.MAX_LISTED) : distinct;
        int more = distinct.size() - shown.size();
        String list = String.join(", ", shown);
        if (french(lang)) {
            return "Ce filtre correspond à " + (capped ? "au moins " : "") + values.size()
                + " lignes, la question n'a donc pas de réponse unique. Correspondances pour " + label + " : " + list
                + (more > 0 ? ", et " + more + " de plus" : "")
                + ". Reformulez en en désignant une — par exemple en filtrant sur " + label + " plutôt que sur le nom.";
        }
        return "That filter matches " + (capped ? "at least " : "") + values.size()
            + " rows, so the question has no single answer. Matching " + label + ": " + list
            + (more > 0 ? ", and " + more + " more" : "")
            + ". Ask again naming one of them — for example by filtering on " + label + " instead of the name.";
    }

    private static String listed(String lang, List<String> names) {
        if (names.size() <= MAX_SHOWN) return String.join(", ", names);
        return String.join(", ", names.subList(0, MAX_SHOWN))
            + ", … (+" + (names.size() - MAX_SHOWN) + pick(lang, " more)", " de plus)");
    }
}

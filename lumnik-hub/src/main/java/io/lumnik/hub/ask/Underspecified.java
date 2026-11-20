package io.lumnik.hub.ask;

import io.lumnik.common.text.Text;
import io.lumnik.extraction.spi.ask.SchemaCard;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #2096: a question that leaves out what an honest query needs — a radius, a period, the metric of a ranking, a
 * threshold — is answered with what was understood, what is missing, and questions the asker can send instead. The
 * model is not asked: it would choose the 20 km, the 30 days or the revenue the question did not say. Before the model,
 * because a model that answers "my best customers" has already chosen.
 *
 * <p>The check reads the question's words (French and English, accents and case ignored) and the card, nothing else.
 * <b>Precision over recall</b>: a question that names anything that may be what it needs — a number with its unit, a
 * date, "par"/"by", a column of the card — takes the normal path, and a word the card itself declares (a column, a
 * value, a description) is the scope's meaning, not a gap. A suggestion copies the asker's words, the card's names
 * and placeholders in brackets; it never carries a value of its own.
 */
final class Underspecified {

    private Underspecified() {}

    private static final Pattern NEAR = Pattern.compile(
        "\\b(?:autour d(?:e|u|es|')|(?<!plus )proches? d(?:e|u|es|')|pres d(?:e|u|es|')|a proximite d(?:e|u|es|')"
            + "|aux alentours d(?:e|u|es|')|dans les environs d(?:e|u|es|')|around|near(?:by)?|close to)\\b\\s*+(?![\\d.,])"); // "around 500 euros" is an amount
    private static final Pattern DISTANCE = Pattern.compile(
        "\\b\\d+(?:[.,]\\d+)?\\s*(?:km|kilometres?|kilometers?|m|metres?|meters?|miles?|mi)\\b");

    private static final Pattern RANKING = Pattern.compile("\\b(?:meilleur(?:e|s|es)?|pires?|top|best|worst)\\b");
    private static final Pattern RANKING_QUALIFIED = Pattern.compile(
        "\\b(?:par|selon|en|sur|by|in terms of|according to|for|on)\\s+\\S");

    private static final Pattern RECENT = Pattern.compile(
        "(?<!plus )(?<!most )\\b(?:recent(?:e|s|es)?|recemment|dernierement|ces derniers temps|recently|lately)\\b");
    private static final Pattern PERIOD_QUALIFIED = Pattern.compile(
        "\\b(?:\\d+\\s*(?:jours?|semaines?|mois|ans?|annees?|heures?|h|days?|weeks?|months?|years?|hours?)"
            + "|depuis|since|after|apres|avant|before|aujourd'hui|hier|today|yesterday|(?:19|20)\\d{2}"
            + "|(?:ce|cet|cette|this|last|past|previous)\\s+(?:jour|semaine|mois|annee|an|trimestre|day|week|month|year|quarter)"
            + "|(?:jour|semaine|mois|annee|trimestre)\\s+(?:dernier|derniere|passe|passee|precedent|precedente)"
            + "|janvier|fevrier|mars|avril|juin|juillet|aout|septembre|octobre|novembre|decembre"
            + "|january|february|march|april|june|july|august|september|october|november|december)\\b");

    private static final Pattern ADJECTIVE = Pattern.compile(
        "(?<!plus )(?<!moins )(?<!most )(?<!en )(?<!de )\\b(?:gros|grosses?|faibles?|elevee?s?|big|large|high|low|small)\\b");
    private static final Pattern LOW = Pattern.compile("^(?:faibles?|low|small)$");
    private static final Pattern THRESHOLD_QUALIFIED = Pattern.compile(
        "\\d|\\b(?:plus de|moins de|au-dessus|au dessus|en dessous|en-dessous|superieur|inferieur|au moins|above|below"
            + "|over|under|more than|less than|greater|at least|at most)\\b");

    /** Ends the copula a prefix before "near Lyon" carries: "How many customers are" + "within …" reads badly. */
    private static final Pattern TRAILING_COPULA = Pattern.compile("(?i)\\s+(?:sont|est|se trouvent|are|is)$");
    /** A place name as typed: capitalised words ("Lyon", "Saint-Étienne", "Le Mans"), nothing after them. */
    private static final Pattern PLACE = Pattern.compile("^(\\p{Lu}[\\p{L}'’-]*(?:\\s\\p{Lu}[\\p{L}'’-]*){0,2})");
    private static final Pattern NUMERIC = Pattern.compile(
        "(?:numeric|decimal|integer|bigint|smallint|real|double precision|int|int[248]|float[48])\\b.*");
    private static final Pattern CITY = Pattern.compile("\\b(?:ville|city|commune|town|localite)\\b");
    private static final Pattern CLOSING = Pattern.compile("\\s*[?!.]+$");
    private static final int EXAMPLES = 3;

    /** The clarification to serve instead of a query, in {@code lang}; empty when the question takes the normal path. */
    static Optional<String> clarify(String question, SchemaCard card, String lang) {
        if (Text.blank(question) || card == null) return Optional.empty();
        String folded = fold(question);

        // A place only when a name follows, as typed: "proche de la faillite", "near completion" are not geography
        Matcher near = NEAR.matcher(folded);
        if (near.find() && !DISTANCE.matcher(folded).find()) {
            Matcher place = PLACE.matcher(question.substring(near.end()));
            if (place.find()) return Optional.of(radius(question, near, place.group(1).strip(), card, lang));
        }
        Matcher ranking = RANKING.matcher(folded);
        if (ranking.find() && !RANKING_QUALIFIED.matcher(folded.substring(ranking.end())).find()
                && namedColumn(folded, card).isEmpty() && !declares(card, ranking.group())) {
            return Optional.of(AskSentences.clarifyMetric(lang, word(question, ranking), stem(question),
                columnsOfType(card, true)));
        }
        Matcher recent = RECENT.matcher(folded);
        if (recent.find() && !PERIOD_QUALIFIED.matcher(folded).find() && !DateIntent.in(question)
                && !declares(card, recent.group())) {
            return Optional.of(AskSentences.clarifyPeriod(lang, word(question, recent), stem(question),
                columnsOfType(card, false)));
        }
        Matcher adjective = ADJECTIVE.matcher(folded);
        // "élèves" folds to "eleves": the accent the asker typed tells pupils from high values
        if (adjective.find() && word(question, adjective).indexOf('è') < 0 && !THRESHOLD_QUALIFIED.matcher(folded).find() && !declares(card, adjective.group())) {
            // "high revenue" names its column: only the threshold is asked for
            return Optional.of(AskSentences.clarifyThreshold(lang, word(question, adjective), stem(question),
                LOW.matcher(adjective.group()).matches(), namedColumn(folded, card), columnsOfType(card, true)));
        }
        return Optional.empty();
    }

    private static String radius(String question, Matcher near, String named, SchemaCard card, String lang) {
        String understood = word(question, near).strip();
        String prefix = TRAILING_COPULA.matcher(question.substring(0, near.start()).strip()).replaceAll("");
        List<String> suggestions = new ArrayList<>(2);
        Optional<String> city = cityColumn(card);
        if (city.isPresent() && !prefix.isEmpty()) {
            suggestions.add(AskSentences.byCity(lang, prefix, city.get(), named));
        }
        if (card.tables().stream().anyMatch(t -> !t.points().isEmpty()) && !prefix.isEmpty()) {
            suggestions.add(AskSentences.byDistance(lang, prefix));
        }
        String around = understood.endsWith("'") || understood.endsWith("’") ? understood + named : understood + " " + named;
        return AskSentences.clarifyRadius(lang, around, suggestions);
    }

    /** The question as written, without its closing punctuation: a suggestion adds what it lacked. */
    private static String stem(String question) {
        return CLOSING.matcher(question.strip()).replaceAll("");
    }

    /** The asker's own word, as typed: {@link #fold} keeps every index, so the folded match slices the original. */
    private static String word(String question, Matcher m) {
        return question.substring(m.start(), m.end());
    }

    private static Optional<String> cityColumn(SchemaCard card) {
        return card.tables().stream().flatMap(t -> t.columns().stream())
            .filter(c -> !c.name().matches("(?i).*_(?:id|code|no|nr)$"))
            .filter(c -> CITY.matcher(fold(c.name().replace('_', ' ') + " " + (c.label() == null ? "" : c.label()))).find())
            .map(SchemaCard.Column::name).findFirst();
    }

    /** Up to {@value #EXAMPLES} column names of the card: its figures, or its dates. */
    private static List<String> columnsOfType(SchemaCard card, boolean numeric) {
        return card.tables().stream().flatMap(t -> t.columns().stream())
            .filter(c -> {
                String type = c.pgType() == null ? "" : c.pgType().toLowerCase(Locale.ROOT);
                return numeric ? NUMERIC.matcher(type).matches() && !isCoordinate(card, c.name())
                    : type.equals("date") || type.startsWith("timestamp");
            })
            .map(SchemaCard.Column::name).distinct().limit(EXAMPLES).toList();
    }

    private static boolean isCoordinate(SchemaCard card, String column) {
        return card.tables().stream().flatMap(t -> t.points().stream())
            .anyMatch(p -> p.lat().equals(column) || p.lon().equals(column));
    }

    /** The card's column the question names, read as words ("order_date" as "order date"). */
    private static Optional<String> namedColumn(String folded, SchemaCard card) {
        return card.tables().stream().flatMap(t -> t.columns().stream()).map(SchemaCard.Column::name)
            .filter(name -> name.length() > 2 && QuestionWords.startsAWord(folded, fold(name.replace('_', ' '))))
            .findFirst();
    }

    /** Every word the card declares: names, labels, descriptions and allowed values, folded. */
    private static String declaredWords(SchemaCard card) {
        StringBuilder words = new StringBuilder();
        for (SchemaCard.Table t : card.tables()) {
            for (SchemaCard.Column c : t.columns()) {
                words.append(' ').append(c.name().replace('_', ' ')).append(' ').append(Text.blank(c.label()) ? "" : c.label())
                    .append(' ').append(Text.blank(c.description()) ? "" : c.description());
                if (c.allowedValues() != null) words.append(' ').append(String.join(" ", c.allowedValues()));
            }
        }
        return fold(words.toString());
    }

    /** Built only once a trigger matched: most questions never pay for it. */
    private static boolean declares(SchemaCard card, String word) {
        return QuestionWords.startsAWord(declaredWords(card), word);
    }

    /** {@link QuestionWords#fold}, one character for one: a match on the folded text is a slice of the original. */
    static String fold(String text) {
        StringBuilder folded = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char base = c < 0x80 ? c : Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).charAt(0);
            folded.append(Character.toLowerCase(Character.isLetter(base) ? base : c));
        }
        return folded.toString().replace('’', '\'');
    }
}

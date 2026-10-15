package io.lumnik.hub.export.run;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The artifact-name token rule, in one place: which destination fields are templates
 * ({@link #TEMPLATED_FIELDS}), which tokens they may carry ({@link #TOKENS}: {@code ${date}} and
 * {@code ${timestamp}}), the apply-time refusal of anything else ({@link #problem}) and the
 * run-time resolution against a run's artifact time, in UTC ({@link #resolve}). A retry resolves
 * against the same instant, so it publishes the same name. The parser refuses any other token
 * through {@link #problem}, so meeting one in {@link #resolve} is a bug, not an input error.
 */
public final class ArtifactName {

  public static final String DATE = "date";
  public static final String TIMESTAMP = "timestamp";
  public static final List<String> TOKENS = List.of(DATE, TIMESTAMP);

  /** The {@code spec.destination} fields a token may appear in: SFTP path, S3 prefix/filename. */
  public static final List<String> TEMPLATED_FIELDS = List.of("path", "prefix", "filename");

  private static final String SUPPORTED =
      TOKENS.stream().map(t -> "${" + t + "}").collect(Collectors.joining(" and "));
  private static final Pattern TOKEN = Pattern.compile("\\$\\{([^}]*)}");
  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  private ArtifactName() {}

  /**
   * Why {@code template} is not a valid artifact name, for the caller to prefix with the field's
   * path; empty when it is. An unrecognized closed token {@code ${xyz}} and an unterminated
   * {@code ${} alike are broken substitution attempts, not literal text a caller meant to write.
   */
  public static Optional<String> problem(String template) {
    int i = 0;
    while ((i = template.indexOf("${", i)) >= 0) {
      int close = template.indexOf('}', i);
      if (close < 0)
        return Optional.of("unterminated '${' — only " + SUPPORTED
            + " are supported substitution tokens");
      String tokenName = template.substring(i + 2, close);
      if (!TOKENS.contains(tokenName))
        return Optional.of("unrecognized substitution token '${" + tokenName + "}' — only "
            + SUPPORTED + " are supported");
      i = close + 1;
    }
    return Optional.empty();
  }

  public static String resolve(String template, Instant at) {
    Matcher m = TOKEN.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = switch (m.group(1)) {
        case DATE -> DATE_FORMAT.format(at);
        case TIMESTAMP -> TIMESTAMP_FORMAT.format(at);
        default -> throw new IllegalStateException("unknown artifact-name token ${" + m.group(1) + "}");
      };
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }
}

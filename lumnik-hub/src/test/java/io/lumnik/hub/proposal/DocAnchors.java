package io.lumnik.hub.proposal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The heading anchors mkdocs generates for a page: Python-Markdown's default {@code slugify}
 * (NFKD, drop non-ASCII, drop non-word characters, lower-case, collapse runs of '-' and spaces).
 * Lines inside fenced code blocks are not headings — a {@code # comment} in a bash block must not
 * count as an anchor that exists.
 */
final class DocAnchors {

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*#*\\s*$");

    private DocAnchors() {}

    static Set<String> of(Path markdown) {
        try {
            Set<String> anchors = new LinkedHashSet<>();
            boolean inFence = false;
            for (String line : Files.readAllLines(markdown)) {
                if (line.stripLeading().startsWith("```")) {
                    inFence = !inFence;
                    continue;
                }
                if (inFence) continue;
                Matcher m = HEADING.matcher(line);
                if (m.matches()) anchors.add(slug(m.group(1)));
            }
            return anchors;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String slug(String heading) {
        String ascii = Normalizer.normalize(heading, Normalizer.Form.NFKD).replaceAll("[^\\p{ASCII}]", "");
        String words = ascii.replaceAll("[^\\w\\s-]", "").strip().toLowerCase(Locale.ROOT);
        return words.replaceAll("[-\\s]+", "-");
    }
}

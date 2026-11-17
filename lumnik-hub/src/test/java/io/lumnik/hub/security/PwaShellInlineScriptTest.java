package io.lumnik.hub.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** #1994: the PWA's CSP runs no inline code, so the shell's boot code lives in a file. */
class PwaShellInlineScriptTest {

    private static final Path SHELL = Path.of("src/main/resources/META-INF/resources/app/index.html");

    /** script-src and style-src 'self': no inline script, handler, javascript: URL, style block or style attribute. */
    @Test
    void theShellHasNoInlineCode() throws IOException {
        String shell = Files.readString(SHELL);
        for (String inline : new String[] {"<script(?![^>]*\\ssrc\\s*=)", "\\son[a-z]+\\s*=", "javascript:", "<style",
            "\\sstyle\\s*="}) {
            assertThat(Pattern.compile(inline, Pattern.CASE_INSENSITIVE).matcher(shell).find())
                .as("%s would be refused by the PWA's own CSP", inline).isFalse();
        }
    }
}

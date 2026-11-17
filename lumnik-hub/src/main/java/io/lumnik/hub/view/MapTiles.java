package io.lumnik.hub.view;

import io.lumnik.common.text.Text;
import io.lumnik.hub.manifest.LinkTemplate;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The map's background (#2057): none unless the operator names a tile server. Every tile request tells that server
 * which area the user looks at — where the customers are — so the PWA contacts no third party by default, and the
 * Content-Security-Policy names the one host the operator chose, and only when they chose it.
 *
 * <p>The template reaches every browser: a provider key written in it is public, never a server secret (said in
 * {@code docs/deploy.md}). A bad value stops the hub at startup with the property's name, like {@code lumnik.ai.enabled}.
 */
@ApplicationScoped
public class MapTiles {

    /** What the PWA draws under the dots: the URL template and the line of credit the tile provider asks for. */
    public record Background(String tiles, String attribution) {}

    static final String PROPERTY = "lumnik.map.tiles";
    static final int MAX_ATTRIBUTION = 200;

    /** scheme://host[:port]/path — no user info, no placeholder in the authority. */
    private static final Pattern URL = Pattern.compile("(https?)://([A-Za-z0-9.-]{1,253}|\\[[0-9A-Fa-f:.]{2,45}])(:[0-9]{1,5})?(/[^\\s\"'<>\\\\]*)");
    private static final Set<String> PLACEHOLDERS = Set.of("z", "x", "y");
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    @Inject @ConfigProperty(name = PROPERTY)
    Optional<String> tiles;

    @Inject @ConfigProperty(name = "lumnik.map.attribution")
    Optional<String> attribution;

    /**
     * Read once, at construction: the configuration does not change while the hub runs. The CSP filter asks for it when
     * it registers, at boot, so a bad value stops the hub at startup.
     */
    private Optional<Background> background = Optional.empty();

    @PostConstruct
    void read() {
        background = tiles.filter(Text::present).map(t -> new Background(checked(t.strip()), attribution(attribution)));
    }

    /** The operator's background, or empty: no tile server, the PWA draws a neutral one. */
    public Optional<Background> background() {
        return background;
    }

    /** The CSP source of the tile server — scheme, host and port — or empty when there is none. */
    public Optional<String> cspSource() {
        return background().map(b -> origin(b.tiles()));
    }

    static String checked(String template) {
        if (template.length() > LinkTemplate.MAX_URL_LENGTH)
            throw new IllegalStateException(PROPERTY + ": at most " + LinkTemplate.MAX_URL_LENGTH + " characters");
        Matcher m = URL.matcher(template);
        if (!m.matches())
            throw new IllegalStateException(PROPERTY + " must be a tile URL such as https://tile.example.org/{z}/{x}/{y}.png"
                + " — a scheme, a host and a path, no user info, no quote or space");
        if (m.group(1).equals("http") && !LOOPBACK.contains(m.group(2).toLowerCase(Locale.ROOT)))
            throw new IllegalStateException(PROPERTY + " must use https (http only for localhost)");
        try {
            LinkTemplate.checkPlaceholders(m.group(4), PROPERTY, PLACEHOLDERS, "the placeholders the map fills — {z}, {x} and {y}");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        if (!LinkTemplate.placeholders(m.group(4)).containsAll(PLACEHOLDERS))
            throw new IllegalStateException(PROPERTY + " must contain {z}, {x} and {y}");
        return template;
    }

    static String origin(String checkedTemplate) {
        Matcher m = URL.matcher(checkedTemplate);
        if (!m.matches()) throw new IllegalStateException(PROPERTY + " was not checked");
        return m.group(1) + "://" + m.group(2) + (m.group(3) == null ? "" : m.group(3));
    }

    private static String attribution(Optional<String> value) {
        String text = value.filter(Text::present).map(String::strip).orElse(null);
        if (text != null && text.length() > MAX_ATTRIBUTION)
            throw new IllegalStateException("lumnik.map.attribution: at most " + MAX_ATTRIBUTION + " characters");
        return text;
    }
}

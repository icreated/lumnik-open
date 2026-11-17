package io.lumnik.hub.connector.transport;

import io.lumnik.common.text.Text;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The one directory a local transport may read, {@code lumnik.csv.local-root} (#2001). A manifest's local
 * {@code transport.path} used to name any file the hub process could reach: a mounted secret, the hooks directory, a
 * service-account token. The operator now names the folder; unset, the local transport reads nothing.
 *
 * <p>A connector reads its own tenant's folder of it, {@code <root>/<tenant id>} ({@link #forTenant}, asked through
 * {@link LocalRoots}): one folder for the whole deployment would hand every tenant the files dropped for another — the
 * reason the environment stopped being a secret source (#2000). A manifest writes its path relative to that folder
 * ({@link #resolve}), so the same manifest reads the same files on another tenant or another hub.
 *
 * <p>A path is judged on its real location: {@code ..} and symbolic links are followed before the comparison, so
 * neither leaves the root. A path that does not exist yet is judged on its nearest existing parent. What a caller gets
 * back is the path as written — the listing, and the cursors stored from it, keep their shape.
 *
 * <p>A refusal names the setting, never the path: it reaches an integrator, and the path is the server's layout.
 */
public final class LocalRoot {

    private static final String OFF = "the local transport is off on this hub: an operator sets lumnik.csv.local-root "
        + "(env LUMNIK_CSV_LOCAL_ROOT) to the directory it may read";
    /** The refusal of an absolute path: the run's, and the apply door's, which judges the same form (#2001). */
    public static final String ABSOLUTE = "a local path is relative to its tenant's folder of the root "
        + "(lumnik.csv.local-root/<tenant id>): write orders/*.csv, not a path from /";
    /** The operator's root, or the tenant's folder of it, whose real location could not be read: refused, not guessed. */
    private static final String UNREADABLE = "the hub cannot read the real location of lumnik.csv.local-root/<tenant id>: "
        + "an operator checks that the folder exists and the hub may read it";
    /** A path the system cannot even parse (a NUL, say): refused by its form, without quoting it. */
    public static final String UNPARSABLE = "a local path holds a character no path may hold";
    private static final String OUTSIDE =
        "a local path must lie inside its tenant's folder of the root: lumnik.csv.local-root/<tenant id>";

    /**
     * The root's real location; {@code null} when none is set. When it could not be read ({@link #readable} false), the
     * absolute path as configured, kept only to be read again — never compared.
     */
    private final Path dir;
    /** False when the real location could not be read: the root then holds nothing (fail closed). */
    private final boolean readable;

    private LocalRoot(Path dir, boolean readable) {
        this.dir = dir;
        this.readable = readable;
    }

    /** A root at {@code dir} whose real location could not be read — what {@link #at} returns then. */
    static LocalRoot unreadable(Path dir) { return new LocalRoot(dir.toAbsolutePath(), false); }

    /** The operator's setting: absent or blank, the local transport is off. */
    public static LocalRoot of(Optional<String> configured) {
        return configured.map(String::strip).filter(Text::present).map(s -> at(Path.of(s))).orElse(new LocalRoot(null, false));
    }

    /** A root at {@code dir}, relative to the working directory when it is relative. */
    public static LocalRoot at(Path dir) {
        Path real = real(dir);
        // A root whose real location cannot be read holds nothing: every path is refused (fail closed), and the refusal
        // says the root cannot be read. Its written form is kept to be read again, never to be compared.
        return real != null ? new LocalRoot(real, true) : unreadable(dir);
    }

    /**
     * The root narrowed to one tenant's folder, {@code <root>/<tenant id>}; off stays off. Read again at each call: a
     * root that could not be read at boot is read once more, and recovers when it can be.
     */
    public LocalRoot forTenant(long tenantId) {
        return dir == null ? this : at(dir.resolve(Long.toString(tenantId)));
    }

    /**
     * {@code path}, unchanged, when its real location lies inside the root.
     *
     * @throws ConfigValidationException when no root is set, when the root's real location cannot be read, or when the
     *         path leaves the root
     */
    public Path confine(Path path) {
        if (dir == null) throw new ConfigValidationException(OFF);
        if (!readable) throw new ConfigValidationException(UNREADABLE);
        if (!holds(path)) throw new ConfigValidationException(OUTSIDE);
        return path;
    }

    /**
     * A manifest's relative {@code path} as the absolute path it names under this root, confined. Not normalized: a
     * glob is kept as written, and {@link #confine} judges {@code ..} where the system does.
     *
     * @throws ConfigValidationException when no root is set, when the path's form is refused, when the root's real
     *         location cannot be read, or when the path leaves the root
     */
    public String resolve(String path) {
        if (dir == null) throw new ConfigValidationException(OFF);
        formRefusal(path).ifPresent(refusal -> { throw new ConfigValidationException(refusal.message()); });
        return confine(dir.resolve(path)).toString();
    }

    /** Why a local path's form is refused, with the gesture that fixes it. */
    public enum FormRefusal {
        ABSOLUTE_PATH(ABSOLUTE, "Write it from the tenant's folder of the hub's local root: /data/drop/1/orders/*.csv is orders/*.csv"),
        NOT_A_PATH(UNPARSABLE, "Remove the character the hub's filesystem refuses in a path (a NUL, on Linux)");

        private final String message;
        private final String hint;

        FormRefusal(String message, String hint) {
            this.message = message;
            this.hint = hint;
        }

        public String message() { return message; }

        public String hint() { return hint; }
    }

    /**
     * The refusal a manifest's local path earns by its form alone — absolute, or not a path at all — or empty. No
     * root, no tenant, no disk: every door that stores a local path (the manifest's apply, the flat connector and
     * endpoint doors) refuses what the run would, in the run's words.
     */
    public static Optional<FormRefusal> formRefusal(String path) {
        try {
            return Path.of(path).isAbsolute() ? Optional.of(FormRefusal.ABSOLUTE_PATH) : Optional.empty();
        } catch (InvalidPathException unparsable) {
            return Optional.of(FormRefusal.NOT_A_PATH);
        }
    }

    /** Whether {@code file}, links followed, lies inside the root: a listing skips the files that do not. */
    public boolean holds(Path file) {
        if (dir == null || !readable) return false;
        Path real = real(file);
        return real != null && real.startsWith(dir);
    }

    /**
     * The real location of {@code path}: its nearest existing ancestor resolved as the system resolves it, the rest
     * appended. Never normalized first: in {@code link/../x} the {@code ..} climbs from where the link points, not
     * from the folder holding it, and a lexical {@code normalize()} would compare a path the system never opens.
     *
     * @return the real location, or {@code null} when it cannot be read — never the path as written: a location
     *         this cannot establish is one it cannot allow (fail closed)
     */
    private static Path real(Path path) {
        Path abs = path.toAbsolutePath();
        Path existing = abs;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) return abs.normalize();
        try {
            // what follows `existing` does not exist: no link in it, so a lexical normalize is the system's reading
            return existing.toRealPath().resolve(existing.relativize(abs)).normalize();
        } catch (IOException unreadable) {
            return null;
        }
    }
}

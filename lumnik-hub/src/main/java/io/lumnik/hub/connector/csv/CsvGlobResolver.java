package io.lumnik.hub.connector.csv;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.lumnik.hub.connector.transport.LocalRoot;

/**
 * Resolves a path pattern (plain path or glob) to an ordered list of files.
 *
 * <p>Plain paths → singleton list.
 * Glob patterns (containing {@code *} or {@code ?}) → all matches in the parent directory,
 * sorted lexically by filename (ascending).
 */
public final class CsvGlobResolver {

  private CsvGlobResolver() {}

  /** A {@code *} or a {@code ?} makes a folder of files; anything else names one file. */
  public static boolean isGlob(String pathPattern) {
    return pathPattern.contains("*") || pathPattern.contains("?");
  }

  /**
   * Resolve a path pattern to a list of files, sorted by filename ascending.
   *
   * <p>Inside {@code root} only (#2001): a plain path or a glob's folder outside it is refused, and a match whose link
   * leads out of it is skipped.
   *
   * @param pathPattern the absolute file path or glob pattern a manifest's relative path names
   *                    (e.g. {@code <root>/<tenant id>/imports/*.csv})
   * @return sorted list of matching files; empty list if pattern matches nothing
   * @throws io.lumnik.hub.connector.exceptions.ConfigValidationException when the path lies outside {@code root}
   */
  public static List<Path> resolve(LocalRoot root, String pathPattern) throws IOException {
    Path path = Paths.get(pathPattern);

    if (!isGlob(pathPattern)) {
      if (Files.exists(root.confine(path))) return List.of(path);
      return List.of();
    }

    // Glob: split into parent dir + glob expression
    Path parent = path.getParent();
    String glob = path.getFileName().toString();
    if (parent == null) parent = Paths.get(".");
    root.confine(parent);

    List<Path> matches = new ArrayList<>();
    try (var stream = Files.newDirectoryStream(parent, glob)) {
      for (Path p : stream) {
        if (Files.isRegularFile(p) && root.holds(p)) matches.add(p);
      }
    }
    Collections.sort(matches);
    return matches;
  }
}

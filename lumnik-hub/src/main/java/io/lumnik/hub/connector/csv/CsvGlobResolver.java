package io.lumnik.hub.connector.csv;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolves a path pattern (plain path or glob) to an ordered list of files.
 *
 * <p>Plain paths → singleton list.
 * Glob patterns (containing {@code *} or {@code ?}) → all matches in the parent directory,
 * sorted lexically by filename (ascending).
 */
public final class CsvGlobResolver {

  private CsvGlobResolver() {}

  /**
   * Resolve a path pattern to a list of files, sorted by filename ascending.
   *
   * @param pathPattern absolute file path or glob pattern (e.g. {@code /imports/*.csv})
   * @return sorted list of matching files; empty list if pattern matches nothing
   */
  public static List<Path> resolve(String pathPattern) throws IOException {
    Path path = Paths.get(pathPattern);

    if (!pathPattern.contains("*") && !pathPattern.contains("?")) {
      if (Files.exists(path)) return List.of(path);
      return List.of();
    }

    // Glob: split into parent dir + glob expression
    Path parent = path.getParent();
    String glob = path.getFileName().toString();
    if (parent == null) parent = Paths.get(".");

    List<Path> matches = new ArrayList<>();
    try (var stream = Files.newDirectoryStream(parent, glob)) {
      for (Path p : stream) {
        if (Files.isRegularFile(p)) matches.add(p);
      }
    }
    Collections.sort(matches);
    return matches;
  }
}

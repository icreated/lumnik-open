package io.lumnik.hub.rag;

import io.lumnik.identity.user.User;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Resolves the effective RAG scope for a query, applying this precedence:
 * <ol>
 *   <li>Explicit {@code overrideFlag} from CLI ({@code --scope compta}) or palette ({@code :scope}).</li>
 *   <li>Current TUI session scope.</li>
 *   <li>{@link User#getDefaultScope()} from the user profile.</li>
 *   <li>Fallback to {@code "system"}.</li>
 * </ol>
 *
 * <p>The flag {@code "all"} as override returns an empty list, meaning "no tag filter". Callers
 * are responsible for permission checks (admin-only) before passing {@code "all"}.
 */
@ApplicationScoped
public class RagScopeResolver {

  public List<String> resolve(User user, String tuiContext, String overrideFlag) {
    if (overrideFlag != null) {
      if ("all".equals(overrideFlag)) return List.of();
      return List.of(overrideFlag);
    }
    if (tuiContext != null && !tuiContext.isBlank()) {
      return List.of(tuiContext);
    }
    if (user != null && user.getDefaultScope() != null && !user.getDefaultScope().isBlank()) {
      return List.of(user.getDefaultScope());
    }
    return List.of("system");
  }
}

package io.lumnik.hub.entity.resolution;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.CellHook;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a declared {@code normalize:} chain into a normalized value (design doc §5) — an internal
 * orchestrator, not a second SPI. {@code CellHook.apply} mutates a whole row and carries {@code
 * MappingState} authority meant for a mapping run's chunk; neither belongs on the identity path,
 * so every call here builds its own single-cell row and its own throwaway {@code MappingState},
 * touched by nothing else before or after.
 *
 * <p>Only {@link NormalizerAllowlist#ADMITTED} hooks run, refused here even though {@code
 * EntityManifestParser} already refuses the same thing at apply time — a manifest stored before
 * the allowlist tightened, or a caller other than the apply door, must not bypass it.
 */
@ApplicationScoped
public class NormalizationOrchestrator {

  @Inject HookCatalog catalog;

  /**
   * Applies {@code normalizeHookIds} in declared order to {@code rawValue}, on an isolated copy of
   * a single-column cell. Returns {@code null} unchanged (nothing to normalize) without invoking
   * any hook.
   */
  public String normalize(List<String> normalizeHookIds, String field, String rawValue) {
    if (rawValue == null) return null;

    Map<String, Object> cell = new HashMap<>();
    cell.put(field, rawValue);
    MappingState isolated = new MappingState();

    for (String hookId : normalizeHookIds) {
      CellHook hook = admittedHook(hookId);
      ObjectNode params = JsonNodeFactory.instance.objectNode();
      params.putArray("columns").add(field);
      hook.apply(cell, params, isolated);
    }

    Object result = cell.get(field);
    return result == null ? null : result.toString();
  }

  private CellHook admittedHook(String hookId) {
    if (!NormalizerAllowlist.isAdmitted(hookId)) throw new NormalizerNotAdmittedException(hookId);
    CellHook hook = catalog.cell(hookId);
    if (hook == null) throw new NormalizerNotAdmittedException(hookId);
    return hook;
  }
}

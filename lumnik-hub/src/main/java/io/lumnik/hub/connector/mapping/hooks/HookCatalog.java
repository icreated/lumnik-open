package io.lumnik.hub.connector.mapping.hooks;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.*;

@ApplicationScoped
public class HookCatalog {

  @Inject Instance<DatasetHook> datasetHooks;
  @Inject Instance<RowHook>     rowHooks;
  @Inject Instance<CellHook>    cellHooks;

  private final Map<String, DatasetHook> datasetByName = new HashMap<>();
  private final Map<String, RowHook>     rowByName     = new HashMap<>();
  private final Map<String, CellHook>    cellByName    = new HashMap<>();

  @PostConstruct
  void init() {
    for (DatasetHook h : datasetHooks) {
      String name = hookName(h);
      if (name != null) datasetByName.put(name, h);
    }
    for (RowHook h : rowHooks) {
      String name = hookName(h);
      if (name != null) rowByName.put(name, h);
    }
    for (CellHook h : cellHooks) {
      String name = hookName(h);
      if (name != null) cellByName.put(name, h);
    }
  }

  private static String hookName(Object bean) {
    Class<?> c = bean.getClass();
    // CDI proxy: walk up to the real class
    while (c.getName().contains("_ClientProxy") || c.getName().contains("_Subclass")) {
      c = c.getSuperclass();
      if (c == Object.class || c == null) return null;
    }
    HookId ann = c.getAnnotation(HookId.class);
    return ann != null ? ann.value() : null;
  }

  public DatasetHook dataset(String name) { return datasetByName.get(name); }
  public RowHook     row    (String name) { return rowByName.get(name); }
  public CellHook    cell   (String name) { return cellByName.get(name); }

  public List<HookDescriptor> allDatasetHooks() {
    return datasetByName.keySet().stream().sorted()
      .map(n -> new HookDescriptor(n, HookDescriptor.Tier.DATASET, "", ""))
      .toList();
  }

  public List<HookDescriptor> allRowHooks() {
    return rowByName.keySet().stream().sorted()
      .map(n -> new HookDescriptor(n, HookDescriptor.Tier.ROW, "", ""))
      .toList();
  }

  public List<HookDescriptor> allCellHooks() {
    return cellByName.keySet().stream().sorted()
      .map(n -> new HookDescriptor(n, HookDescriptor.Tier.CELL, "", ""))
      .toList();
  }
}

package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.mapping.spec.MappingLoader;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Loads YAML mapping templates from {@code META-INF/mapping/*.yaml} on the classpath.
 *
 * <p>Each template's {@code id} becomes its lookup key. Templates are immutable once loaded;
 * per-tenant overrides live in the DB (see MappingResolver).
 *
 * <p>Uses the standard classloader API instead of ClassGraph to avoid compatibility issues
 * with the Quarkus classloader's internal reflection surface.
 */
@ApplicationScoped
public class MappingTemplateRegistry {

  private static final Logger LOG = Logger.getLogger(MappingTemplateRegistry.class);
  private static final String MAPPING_PATH = "META-INF/mapping";

  private final Map<String, String> templatesYaml = new HashMap<>();

  void onStart(@Observes @Priority(5) StartupEvent ev) {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    try {
      Enumeration<URL> roots = cl.getResources(MAPPING_PATH);
      while (roots.hasMoreElements()) {
        loadFromUrl(roots.nextElement(), cl);
      }
    } catch (Exception e) {
      LOG.warnf(e, "Failed to scan META-INF/mapping/");
    }
    LOG.infof("Loaded %d mapping templates from classpath", templatesYaml.size());
  }

  private void loadFromUrl(URL url, ClassLoader cl) throws Exception {
    String s = url.toString();
    if (s.startsWith("file:")) {
      File dir = new File(url.toURI());
      if (dir.isDirectory()) {
        File[] files = dir.listFiles(f -> f.getName().endsWith(".yaml"));
        if (files != null) {
          for (File f : files) {
            loadYaml(MAPPING_PATH + "/" + f.getName(), cl);
          }
        }
      }
    } else if (s.startsWith("jar:")) {
      JarURLConnection conn = (JarURLConnection) url.openConnection();
      try (JarFile jar = conn.getJarFile()) {
        jar.stream()
            .filter(e -> !e.isDirectory()
                && e.getName().startsWith(MAPPING_PATH + "/")
                && e.getName().endsWith(".yaml"))
            .forEach(e -> loadYaml(e.getName(), cl));
      }
    }
  }

  private void loadYaml(String resourcePath, ClassLoader cl) {
    try (InputStream in = cl.getResourceAsStream(resourcePath)) {
      if (in == null) return;
      String yaml = new String(in.readAllBytes());
      MappingSpec spec = MappingLoader.load(yaml);
      templatesYaml.put(spec.id(), yaml);
      LOG.debugf("Loaded mapping template: %s", spec.id());
    } catch (Exception e) {
      LOG.warnf(e, "Failed to load mapping template %s", resourcePath);
    }
  }

  public String rawYaml(String id) {
    return templatesYaml.get(id);
  }

  public MappingSpec load(String id) {
    String yaml = templatesYaml.get(id);
    if (yaml == null) return null;
    return MappingLoader.load(yaml);
  }

  public Set<String> allTemplateIds() {
    return Collections.unmodifiableSet(templatesYaml.keySet());
  }

  /** For tests and runtime registration (e.g. RAG-generated). */
  public void register(String id, String yaml) {
    templatesYaml.put(id, yaml);
  }
}

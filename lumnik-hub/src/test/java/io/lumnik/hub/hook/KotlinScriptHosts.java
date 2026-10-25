package io.lumnik.hub.hook;

import java.nio.file.Path;
import java.util.Optional;

/** Test-side door to a host over a hooks directory, for tests outside this package. */
public final class KotlinScriptHosts {

  private KotlinScriptHosts() {}

  public static KotlinScriptHost over(Path hooksDir) {
    KotlinScriptHost host = new KotlinScriptHost();
    host.hooksDir = Optional.of(hooksDir.toString());
    return host;
  }
}

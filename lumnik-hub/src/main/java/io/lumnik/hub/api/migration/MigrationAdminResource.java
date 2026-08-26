package io.lumnik.hub.api.migration;

import io.lumnik.hub.flyway.PlatformFlywayRunner;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

@Path("/api/platform/migrations")
@Produces(MediaType.APPLICATION_JSON)
public class MigrationAdminResource {

  @Inject PlatformFlywayRunner runner;

  @GET
  public List<Map<String, Object>> list() {
    return runner.statusPerSchema().entrySet().stream()
        .map(e -> {
          var s = e.getValue();
          return Map.<String, Object>of(
              "schema", e.getKey(),
              "currentVersion", s.currentVersion(),
              "pending", s.pending(),
              "lastApplied", s.lastApplied() == null ? "" : s.lastApplied().toString());
        })
        .toList();
  }
}

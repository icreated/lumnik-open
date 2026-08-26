package io.lumnik.hub.api.meta;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/api/platform/version")
@Produces(MediaType.APPLICATION_JSON)
public class VersionResource {

  private static final String SERVER_VERSION = "0.1.0";
  private static final String API_VERSION = "v1";

  @GET
  public Map<String, String> get() {
    return Map.of("version", SERVER_VERSION, "apiVersion", API_VERSION);
  }
}

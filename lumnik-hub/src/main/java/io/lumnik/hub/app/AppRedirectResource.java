package io.lumnik.hub.app;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * The métier PWA is served as static resources under {@code /app/} only, so the bare, slash-less
 * path users actually type or share fell through to the API's not-found mapper. #572.
 *
 * <p>The redirect target is written as a bare, path-only string — never through
 * {@code Response.location(URI)}, which resolves a relative URI against the request's base URI
 * (built from the caller-supplied Host header) before writing it, reintroducing the very
 * reflection this guards against. A spoofed Host can't turn this into an open redirect.
 */
@Path("/app")
public class AppRedirectResource {

  @GET
  public Response redirectToTrailingSlash(@Context UriInfo uriInfo) {
    String query = uriInfo.getRequestUri().getRawQuery();
    String target = "/app/" + (query != null ? "?" + query : "");
    return Response.status(Response.Status.MOVED_PERMANENTLY)
        .header(HttpHeaders.LOCATION, target)
        .build();
  }
}

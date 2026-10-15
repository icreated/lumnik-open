package io.lumnik.hub.search.contract;

import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.List;
import java.util.Map;

/**
 * {@link NoBypassOidcProfile}'s real-JWT auth plus the #674 lane's search backend, for the
 * {@code RestHubSearch*IT} classes. The backend is declared here rather than with
 * {@code @QuarkusTestResource(restrictToAnnotatedClass = true)} on each class: a class-restricted
 * resource gives its class a Quarkus application of its own, and every application the test JVM
 * discovers stays in its heap until the run ends (#1072). A profile's resources are shared by the
 * classes of that profile, so the six classes are one application.
 */
public class SearchBackendOidcProfile implements QuarkusTestProfile {

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(SearchBackendContainerResource.class));
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return new NoBypassOidcProfile().getConfigOverrides();
    }
}

package io.lumnik.hub.api.connector.handler;

import io.lumnik.hub.validator.ConnectorValidator;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.Set;

@ApplicationScoped
public class HandlerUniquenessCheck {

    @Inject Instance<ConnectorApplyHandler> handlers;
    @Inject Instance<ConnectorValidator> validators;

    void onStartup(@Observes StartupEvent ev) {
        Set<String> handlerKinds = new HashSet<>();
        for (ConnectorApplyHandler h : handlers) {
            if (!handlerKinds.add(h.kind())) {
                throw new IllegalStateException("Duplicate ConnectorApplyHandler kind: " + h.kind());
            }
        }
        Set<String> validatorKinds = new HashSet<>();
        for (ConnectorValidator v : validators) {
            if (!validatorKinds.add(v.kind())) {
                throw new IllegalStateException("Duplicate ConnectorValidator kind: " + v.kind());
            }
        }
    }
}

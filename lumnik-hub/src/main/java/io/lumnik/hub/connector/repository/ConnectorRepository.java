package io.lumnik.hub.connector.repository;

import io.lumnik.hub.connector.domain.Connector;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class ConnectorRepository implements PanacheRepositoryBase<Connector, UUID> {}

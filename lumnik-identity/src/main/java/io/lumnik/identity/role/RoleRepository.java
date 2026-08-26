package io.lumnik.identity.role;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class RoleRepository implements PanacheRepository<Role> {

    public List<Role> listAll() {
        return list("archivedAt IS NULL order by name");
    }

    public Optional<Role> findByName(String name) {
        return find("name = ?1 and archivedAt IS NULL", name).firstResultOptional();
    }

    public boolean nameExists(String name) {
        return count("name = ?1", name) > 0;
    }
}

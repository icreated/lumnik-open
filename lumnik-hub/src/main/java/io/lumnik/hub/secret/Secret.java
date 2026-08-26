package io.lumnik.hub.secret;

import io.lumnik.common.domain.TenantedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Deliberately carries NO {@code @Auditable}. Every production write to this entity goes through
 * {@code SecretResource}'s native SQL (to keep {@code pgp_sym_encrypt}/{@code pgp_sym_decrypt}
 * inside PostgreSQL), which bypasses Hibernate's persistence context entirely — the global
 * {@code AuditEntityListener} (registered on {@code @PostPersist}/{@code @PostUpdate}) can never
 * fire on this route. An {@code @Auditable} annotation here previously implied Hibernate-driven
 * coverage that did not exist in production: it only ever fired in a test that persisted a
 * {@code Secret} through Hibernate directly, a route nothing in {@code src/main} takes (verified:
 * {@code SecretRepository} is never called for a write outside {@code SecretResource}). Real
 * coverage is now three explicit {@code recordAudit()} call sites in {@code SecretResource} —
 * CREATE, UPDATE (rotate), DELETE — the same pattern {@code EntityReadResource.read()} uses for
 * reads. See {@code AuditWiringTest} for the pin that a direct Hibernate write is NOT audited.
 */
@Entity
@Table(name = "secret", schema = "platform")
@Getter
@Setter
public class Secret extends TenantedEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid DEFAULT gen_random_uuid()")
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    /** Encrypted via pgcrypto pgp_sym_encrypt — never expose plaintext. */
    @Column(name = "value_encrypted", nullable = false)
    private byte[] valueEncrypted;

    @Column(name = "description")
    private String description;

    @Column(name = "rotated_at")
    private Instant rotatedAt;
}

package io.lumnik.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@MappedSuperclass
public abstract class AuditedEntity {

    // NULL = active/listable; SET = archived, hidden from pickers
    @Column(name = "archived_at")
    private Instant archivedAt;

    // NULL = live; SET = soft-deleted (GDPR erase or explicit delete)
    @Setter
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy = 0L;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Setter
    @Column(name = "updated_by", nullable = false)
    private Long updatedBy = 0L;

    public void archive() {
        this.archivedAt = Instant.now();
    }

    public void unarchive() {
        this.archivedAt = null;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public boolean isActive() {
        return archivedAt == null;
    }

    public void softDelete() {
        this.deletedAt = Instant.now();
    }

    public void undelete() {
        this.deletedAt = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    @PrePersist
    protected void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void preUpdate() {
        updatedAt = Instant.now();
    }
}

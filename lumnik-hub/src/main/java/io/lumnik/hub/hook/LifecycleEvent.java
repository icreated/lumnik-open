package io.lumnik.hub.hook;

import java.util.Set;

public enum LifecycleEvent {
    BEFORE_CREATE(Phase.BEFORE, Category.CRUD),
    AFTER_CREATE(Phase.AFTER,  Category.CRUD),
    BEFORE_UPDATE(Phase.BEFORE, Category.CRUD),
    AFTER_UPDATE(Phase.AFTER,  Category.CRUD),
    BEFORE_DELETE(Phase.BEFORE, Category.CRUD),
    AFTER_DELETE(Phase.AFTER,  Category.CRUD),
    BEFORE_ARCHIVE(Phase.BEFORE, Category.CRUD),
    AFTER_ARCHIVE(Phase.AFTER,  Category.CRUD),

    BEFORE_PREPARE(Phase.BEFORE, Category.WORKFLOW),
    AFTER_PREPARE(Phase.AFTER,  Category.WORKFLOW),
    BEFORE_COMPLETE(Phase.BEFORE, Category.WORKFLOW),
    AFTER_COMPLETE(Phase.AFTER,  Category.WORKFLOW),
    BEFORE_VOID(Phase.BEFORE, Category.WORKFLOW),
    AFTER_VOID(Phase.AFTER,  Category.WORKFLOW),
    BEFORE_CLOSE(Phase.BEFORE, Category.WORKFLOW),
    AFTER_CLOSE(Phase.AFTER,  Category.WORKFLOW),
    BEFORE_REACTIVATE(Phase.BEFORE, Category.WORKFLOW),
    AFTER_REACTIVATE(Phase.AFTER,  Category.WORKFLOW);

    public enum Phase { BEFORE, AFTER }
    public enum Category { CRUD, WORKFLOW }

    private final Phase phase;
    private final Category category;

    LifecycleEvent(Phase phase, Category category) {
        this.phase = phase;
        this.category = category;
    }

    public Phase phase() { return phase; }
    public Category category() { return category; }
    public boolean isBefore() { return phase == Phase.BEFORE; }
    public boolean isCrud() { return category == Category.CRUD; }
    public boolean isWorkflow() { return category == Category.WORKFLOW; }

    public static final Set<LifecycleEvent> CRUD_EVENTS = Set.of(
        BEFORE_CREATE, AFTER_CREATE, BEFORE_UPDATE, AFTER_UPDATE,
        BEFORE_DELETE, AFTER_DELETE, BEFORE_ARCHIVE, AFTER_ARCHIVE);
}

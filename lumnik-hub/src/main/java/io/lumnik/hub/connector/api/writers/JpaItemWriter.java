package io.lumnik.hub.connector.api.writers;

import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.api.types.WriteOutcome;
import io.lumnik.hub.connector.api.types.WritePerformanceProfile;

import jakarta.enterprise.inject.spi.CDI;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for single-entity writes via JPA/Hibernate.
 * <p>
 * Persists items one-by-one via EntityManager.persist(), with flush on completion.
 * Profile: {@link WritePerformanceProfile.Safe Safe}.
 * </p>
 */
public abstract class JpaItemWriter<T> implements ItemWriter<T> {

  /**
   * Returns Safe profile (single-row writes, transactional safety).
   */
  public WritePerformanceProfile profile() {
    return new WritePerformanceProfile.Safe();
  }

  /**
   * Converts an input item to a JPA entity ready to persist.
   *
   * @param item the input item
   * @param ctx  the connector context
   * @return the entity to persist
   */
  protected abstract Object toEntity(T item, ConnectorContext ctx);

  /**
   * Persists items one-by-one, collecting any write failures.
   * <p>
   * Flushes the EntityManager after all items are processed.
   * </p>
   */
  @Override
  public final WriteOutcome write(List<T> items, ConnectorContext ctx) {
    EntityManager em = CDI.current().select(EntityManager.class).get();
    var skipped = new ArrayList<WriteOutcome.SkippedRecord>();
    int written = 0;
    for (var item : items) {
      try {
        em.persist(toEntity(item, ctx));
        written++;
      } catch (PersistenceException e) {
        skipped.add(new WriteOutcome.SkippedRecord(item,
            new SkipReason.WriteFailed(item.getClass().getSimpleName(), e.getMessage())));
      }
    }
    em.flush();
    return skipped.isEmpty()
        ? new WriteOutcome.AllWritten(written)
        : new WriteOutcome.PartialWrite(written, skipped);
  }
}

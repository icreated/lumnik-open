package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Recycling the readers' pools is best-effort repair after a committed apply (#1338 review): a pool that fails to flush
 * is logged and skipped — the call never throws, and one pool's failure never keeps the next from being recycled.
 */
class EntityPlanCacheInvalidatorTest {

  @Test
  void aPoolThatFailsToFlushNeverFailsTheCall_andTheNextOneIsStillRecycled() {
    EntityPlanCacheInvalidator invalidator = new EntityPlanCacheInvalidator();
    invalidator.defaultDs = mock(AgroalDataSource.class);
    invalidator.readonlyDs = mock(AgroalDataSource.class);
    doThrow(new IllegalStateException("pool closed")).when(invalidator.defaultDs).flush(AgroalDataSource.FlushMode.GRACEFUL);

    assertThatCode(() -> invalidator.afterEntityApplied("PcOrder")).doesNotThrowAnyException();
    verify(invalidator.readonlyDs).flush(AgroalDataSource.FlushMode.GRACEFUL);
  }

  @Test
  void everyPoolFailingStillNeverFailsTheCall() {
    EntityPlanCacheInvalidator invalidator = new EntityPlanCacheInvalidator();
    invalidator.defaultDs = mock(AgroalDataSource.class);
    invalidator.readonlyDs = mock(AgroalDataSource.class);
    doThrow(new IllegalStateException("a")).when(invalidator.defaultDs).flush(AgroalDataSource.FlushMode.GRACEFUL);
    doThrow(new IllegalStateException("b")).when(invalidator.readonlyDs).flush(AgroalDataSource.FlushMode.GRACEFUL);

    assertThatCode(() -> invalidator.afterEntityApplied("PcOrder")).doesNotThrowAnyException();
  }
}

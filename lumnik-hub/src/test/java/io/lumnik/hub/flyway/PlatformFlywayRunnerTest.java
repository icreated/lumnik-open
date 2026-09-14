package io.lumnik.hub.flyway;

import io.lumnik.hub.manifest.ModuleManifestRegistry;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scheduler is started {@code halted} (application.properties) so that no {@code @Scheduled}
 * bean can race the migration loop on a fresh database — see the nightly-verify audit that found
 * WorkflowDormancySweep/ConnectorScheduler/AuditDispatcher querying tables Flyway hadn't created
 * yet. {@link PlatformFlywayRunner} is the only thing that knows migrations are done, so it is the
 * one that must call {@link Scheduler#resume()} — after the manifest loop, regardless of whether
 * any manifest actually had migrations to run.
 */
class PlatformFlywayRunnerTest {

    @Test
    void resumes_the_scheduler_after_the_manifest_loop_even_with_no_manifests() {
        ModuleManifestRegistry registry = mock(ModuleManifestRegistry.class);
        when(registry.all()).thenReturn(List.of());
        Scheduler scheduler = mock(Scheduler.class);

        PlatformFlywayRunner runner = new PlatformFlywayRunner();
        runner.registry = registry;
        runner.scheduler = scheduler;

        runner.onStart(mock(StartupEvent.class));

        verify(scheduler).resume();
    }

    /**
     * {@code quarkus.scheduler.enabled=false} (e.g. the IT-only {@code SchedulerPausedProfile})
     * swaps in a no-op {@code Scheduler} whose every method, {@code resume()} included, throws
     * {@link UnsupportedOperationException}("Scheduler was not started") — there is simply
     * nothing to un-halt. That is not a boot failure: the app must keep starting.
     */
    @Test
    void a_scheduler_with_nothing_to_resume_does_not_fail_the_boot() {
        ModuleManifestRegistry registry = mock(ModuleManifestRegistry.class);
        when(registry.all()).thenReturn(List.of());
        Scheduler scheduler = mock(Scheduler.class);
        doThrow(new UnsupportedOperationException("Scheduler was not started")).when(scheduler).resume();

        PlatformFlywayRunner runner = new PlatformFlywayRunner();
        runner.registry = registry;
        runner.scheduler = scheduler;

        assertThatCode(() -> runner.onStart(mock(StartupEvent.class))).doesNotThrowAnyException();
    }
}

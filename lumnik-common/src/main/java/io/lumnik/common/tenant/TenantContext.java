package io.lumnik.common.tenant;

import java.util.concurrent.Callable;

public final class TenantContext {

    private static final ThreadLocal<Boolean> SYSTEM_MODE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private TenantContext() {}

    public static boolean isSystemMode() {
        return SYSTEM_MODE.get();
    }

    public static void runAsSystem(Runnable work) {
        boolean previous = SYSTEM_MODE.get();
        if (previous) {
            work.run();
            return;
        }
        SYSTEM_MODE.set(Boolean.TRUE);
        try {
            work.run();
        } finally {
            SYSTEM_MODE.remove();
        }
    }

    public static <T> T callAsSystem(Callable<T> callable) {
        boolean previous = SYSTEM_MODE.get();
        if (previous) {
            try {
                return callable.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        SYSTEM_MODE.set(Boolean.TRUE);
        try {
            return callable.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            SYSTEM_MODE.remove();
        }
    }

    public static <T> T callAs(Long tenantId, Callable<T> callable) {
        Long prev = CurrentTenantHolder.get();
        CurrentTenantHolder.set(tenantId);
        try {
            return callable.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (prev == null) CurrentTenantHolder.clear();
            else CurrentTenantHolder.set(prev);
        }
    }
}

package io.lumnik.hub.hook;

import io.lumnik.bpartner.partner.Partner;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TestNoopHook implements LifecycleHook<Partner> {
    public static int beforeCreateCount = 0;
    @Override public void onBeforeCreate(Partner p, HookContext ctx) { beforeCreateCount++; }
}

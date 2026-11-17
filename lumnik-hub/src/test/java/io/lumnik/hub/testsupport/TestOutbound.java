package io.lumnik.hub.testsupport;

import io.lumnik.hub.outbound.OutboundAddress;

/** The outbound rule as {@code %test} sets it (#2002): loopback allowed, for the WireMock and the hub a test talks to. */
public final class TestOutbound {

    public static final OutboundAddress ADDRESSES = new OutboundAddress(true, true);

    private TestOutbound() {}
}

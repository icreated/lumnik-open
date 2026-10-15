package io.lumnik.hub.testsupport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GatedRelayTest {

    /** A server that accepts one connection and reads nothing until closed. */
    private static Socket sinkSide(ServerSocket sink) throws IOException {
        return sink.accept();
    }

    @Test
    void describeNamesTheHeldPumpAndCountsTheBytesEachWayAndSaysSoWhenNothingIsHeld() throws Exception {
        try (ServerSocket sink = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
             GatedRelay relay = new GatedRelay("localhost", sink.getLocalPort())) {
            assertThat(relay.describe()).contains("no connection");

            relay.holdAfter(10);
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), relay.port())) {
                Socket server = sinkSide(sink);
                client.getOutputStream().write(new byte[64]);
                client.getOutputStream().flush();

                assertThat(relay.awaitHeld(Duration.ofSeconds(10))).isTrue();
                assertThat(relay.describe())
                    .contains("held=true")
                    .contains("upstream=64")
                    .contains("downstream=0")
                    .contains("client->server: held");

                server.getOutputStream().write(new byte[5]);
                server.getOutputStream().flush();
                InputStream in = client.getInputStream();
                assertThat(in.readNBytes(5)).hasSize(5);
                assertThat(relay.describe()).contains("downstream=5").contains("server->client: reading");
                relay.release();
                server.close();
            }
        }
    }
}

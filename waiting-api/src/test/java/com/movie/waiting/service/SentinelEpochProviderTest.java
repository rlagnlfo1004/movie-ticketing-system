package com.movie.waiting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class SentinelEpochProviderTest {

    @Test
    void acceptsOnlyThePrimaryAndEpochReportedByAMajority() throws Exception {
        try (FakeSentinel first = new FakeSentinel("10.0.0.2", 6379, 4);
             FakeSentinel second = new FakeSentinel("10.0.0.2", 6379, 4);
             FakeSentinel stale = new FakeSentinel("10.0.0.1", 6379, 3)) {
            SentinelEpochProvider provider = new SentinelEpochProvider(nodes(first, second, stale), "movie-primary");

            assertThat(provider.enabled()).isTrue();
            assertThat(provider.currentEpoch()).isEqualTo(4);
        }
    }

    @Test
    void failsClosedWhenSentinelsDisagree() throws Exception {
        try (FakeSentinel first = new FakeSentinel("10.0.0.1", 6379, 1);
             FakeSentinel second = new FakeSentinel("10.0.0.2", 6379, 2);
             FakeSentinel third = new FakeSentinel("10.0.0.3", 6379, 3)) {
            SentinelEpochProvider provider = new SentinelEpochProvider(nodes(first, second, third), "movie-primary");

            assertThatThrownBy(provider::currentEpoch)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("majority");
        }
    }

    private static String nodes(FakeSentinel... sentinels) {
        return String.join(",", List.of(sentinels).stream()
                .map(sentinel -> "127.0.0.1:" + sentinel.port()).toList());
    }

    private static final class FakeSentinel implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        private FakeSentinel(String primary, int port, long epoch) throws IOException {
            server = new ServerSocket(0);
            executor.submit(() -> {
                try (var client = server.accept()) {
                    String request = "*3\r\n$8\r\nSENTINEL\r\n$6\r\nMASTER\r\n$13\r\nmovie-primary\r\n";
                    client.getInputStream().readNBytes(request.getBytes(StandardCharsets.US_ASCII).length);
                    String[] values = {"ip", primary, "port", Integer.toString(port),
                            "flags", "master", "config-epoch", Long.toString(epoch)};
                    StringBuilder response = new StringBuilder("*8\r\n");
                    for (String value : values) {
                        response.append('$').append(value.length()).append("\r\n")
                                .append(value).append("\r\n");
                    }
                    client.getOutputStream().write(response.toString().getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                } catch (IOException ignored) {
                    // Closing the server also ends the test listener.
                }
            });
        }

        private int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            server.close();
            executor.shutdownNow();
        }
    }
}

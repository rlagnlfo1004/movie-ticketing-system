package com.movie.waiting.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SentinelEpochProvider implements FencingEpochProvider {

    private static final int TIMEOUT_MILLIS = 500;

    private final List<InetSocketAddress> sentinels;
    private final String masterName;

    public SentinelEpochProvider(@Value("${waiting.fencing.sentinels:}") String nodes,
                                 @Value("${waiting.fencing.master:movie-primary}") String masterName) {
        this.sentinels = parseNodes(nodes);
        this.masterName = masterName;
    }

    @Override
    public boolean enabled() {
        return !sentinels.isEmpty();
    }

    @Override
    public long currentEpoch() {
        if (!enabled()) return 0;
        Map<Master, Integer> votes = new HashMap<>();
        for (InetSocketAddress node : sentinels) {
            try {
                Master master = readMaster(node);
                votes.merge(master, 1, Integer::sum);
            } catch (IOException | RuntimeException ignored) {
                // A single unavailable Sentinel must not decide the current primary.
            }
        }
        int quorum = sentinels.size() / 2 + 1;
        return votes.entrySet().stream()
                .filter(entry -> entry.getValue() >= quorum)
                .map(Map.Entry::getKey)
                .mapToLong(Master::epoch)
                .max()
                .orElseThrow(() -> new IllegalStateException("No Sentinel majority for fencing epoch"));
    }

    private Master readMaster(InetSocketAddress node) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(node, TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            byte[] name = masterName.getBytes(StandardCharsets.UTF_8);
            out.write(("*3\r\n$8\r\nSENTINEL\r\n$6\r\nMASTER\r\n$" + name.length + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(name);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            if (in.read() != '*') throw new IOException("Unexpected Sentinel response");
            int size = Integer.parseInt(readLine(in));
            if (size < 6 || size > 100 || size % 2 != 0) throw new IOException("Invalid Sentinel master fields");
            Map<String, String> fields = new HashMap<>();
            for (int index = 0; index < size; index += 2) {
                fields.put(readBulk(in), readBulk(in));
            }
            String flags = fields.getOrDefault("flags", "");
            if (flags.contains("s_down") || flags.contains("o_down") || !flags.contains("master")) {
                throw new IOException("Sentinel does not report a healthy primary");
            }
            return new Master(fields.get("ip"), Integer.parseInt(fields.get("port")),
                    Long.parseLong(fields.get("config-epoch")));
        }
    }

    private static String readBulk(InputStream in) throws IOException {
        if (in.read() != '$') throw new IOException("Expected Sentinel bulk string");
        int size = Integer.parseInt(readLine(in));
        if (size < 0 || size > 8192) throw new IOException("Invalid Sentinel bulk string length");
        byte[] data = in.readNBytes(size);
        if (data.length != size || in.read() != '\r' || in.read() != '\n') {
            throw new IOException("Incomplete Sentinel bulk string");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (result.size() < 64) {
            int next = in.read();
            if (next < 0) throw new IOException("Incomplete Sentinel line");
            if (next == '\r') {
                if (in.read() != '\n') throw new IOException("Invalid Sentinel line ending");
                return result.toString(StandardCharsets.US_ASCII);
            }
            result.write(next);
        }
        throw new IOException("Sentinel line is too long");
    }

    private static List<InetSocketAddress> parseNodes(String nodes) {
        if (nodes == null || nodes.isBlank()) return List.of();
        List<InetSocketAddress> parsed = new ArrayList<>();
        for (String value : nodes.split(",")) {
            String[] parts = value.trim().split(":", 2);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid Sentinel node: " + value);
            parsed.add(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
        }
        if (parsed.size() < 3 || parsed.size() % 2 == 0) {
            throw new IllegalArgumentException("Fencing requires an odd Sentinel majority of at least three");
        }
        return List.copyOf(parsed);
    }

    private record Master(String host, int port, long epoch) {
    }
}

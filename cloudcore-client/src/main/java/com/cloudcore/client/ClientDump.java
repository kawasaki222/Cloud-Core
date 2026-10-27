package com.cloudcore.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sanitised diagnostics package written by {@code F10} / {@code /cloudcore-client dump}.
 *
 * <p>Contains the client log tail, the client config, system properties, session state and
 * the recent frame headers. Addresses, user directories and bearer-like tokens are redacted
 * before anything is written. No document bodies, input text or item data are included.
 */
public final class ClientDump {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** A 10 MiB log would redact slowly; the tail is what matters for a report. */
    private static final int MAX_LOG_BYTES = 2 * 1024 * 1024;

    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern BEARER =
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern API_KEY = Pattern.compile("\\bsk-[A-Za-z0-9._-]{8,}\\b");
    private static final Pattern WINDOWS_HOME =
            Pattern.compile("(?i)([A-Z]:\\\\Users\\\\)([^\\\\\\s\"']+)");
    private static final Pattern UNIX_HOME =
            Pattern.compile("(/home/|/Users/)([^/\\s\"']+)");

    private ClientDump() {
    }

    /**
     * @param logsDir    directory that holds {@code cloudcore-client.log}
     * @param configFile client config JSON; may be absent
     * @param frames     recent frame header lines
     * @param state      session/system lines, already free of secrets
     * @param nowMillis  timestamp used for the file name
     * @return the written zip
     */
    public static Path write(Path logsDir, Path configFile, List<String> frames,
            Map<String, String> state, long nowMillis) throws IOException {
        Files.createDirectories(logsDir);
        String stamp = STAMP.format(Instant.ofEpochMilli(nowMillis)
                .atZone(ZoneId.systemDefault()));
        Path out = logsDir.resolve("cloudcore-dump-" + stamp + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            put(zip, "system.txt", systemText(state));
            addFile(zip, logsDir.resolve("cloudcore-client.log"), "cloudcore-client.log");
            addFile(zip, logsDir.resolve("cloudcore-client-1.log"), "cloudcore-client-1.log");
            addFile(zip, configFile, "config.json");
            StringBuilder session = new StringBuilder();
            for (String line : frames) {
                session.append(line).append('\n');
            }
            put(zip, "frames.txt", redact(session.toString()));
        }
        LOGGER.info("Wrote diagnostics dump {}", out.getFileName());
        return out;
    }

    private static String systemText(Map<String, String> state) {
        StringBuilder text = new StringBuilder();
        text.append("java.vendor=").append(System.getProperty("java.vendor", "?")).append('\n');
        text.append("os.name=").append(System.getProperty("os.name", "?")).append('\n');
        text.append("os.arch=").append(System.getProperty("os.arch", "?")).append('\n');
        text.append("processors=").append(Runtime.getRuntime().availableProcessors()).append('\n');
        text.append("maxMemory=").append(Runtime.getRuntime().maxMemory()).append('\n');
        text.append("time=").append(Instant.now()).append('\n');
        state.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        // "21.0.12.1" is a Java version, not an address; the IPv4 rule would eat it.
        // The version is a fixed system property, so it is safe to write unredacted.
        return "java.version=" + System.getProperty("java.version", "?") + "\n"
                + redact(text.toString());
    }

    private static void addFile(ZipOutputStream zip, Path file, String name) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        put(zip, name, redact(readTail(file, MAX_LOG_BYTES)));
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Reads the last {@code maxBytes} bytes so a rotated-away start does not matter. */
    private static String readTail(Path file, int maxBytes) throws IOException {
        long size = Files.size(file);
        try (InputStream input = Files.newInputStream(file)) {
            if (size > maxBytes) {
                input.skipNBytes(size - maxBytes);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String redact(String text) {
        String result = BEARER.matcher(text).replaceAll("$1<redacted>");
        result = API_KEY.matcher(result).replaceAll("<redacted-key>");
        result = IPV4.matcher(result).replaceAll("<ip>");
        result = WINDOWS_HOME.matcher(result).replaceAll("$1<user>");
        result = UNIX_HOME.matcher(result).replaceAll("$1<user>");
        return result;
    }
}

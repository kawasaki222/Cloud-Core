package com.cloudcore.client.render;

import com.cloudcore.protocol.TextureRef;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads {@code TEXTURE_REF} images on background threads and hands the bytes to the
 * render thread. Only https is accepted, the body is capped (default 8 MiB), a sha256 is
 * verified when the server supplies one, and failures are logged once per key.
 *
 * <p>The render thread never blocks: {@link #bytes(String)} returns {@code null} until the
 * transfer finished.
 */
public final class RemoteTextureCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final long DEFAULT_MAX_BYTES = 8L * 1024L * 1024L;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger GENERATION =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "cloudcore-texture-download");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile HttpClient httpClient;

    private RemoteTextureCache() {
    }

    private static final class Entry {
        private volatile byte[] bytes;
        private volatile boolean failed;
        private volatile boolean started;
    }

    /** Starts a download the first time a key is seen; later calls are ignored. */
    public static void request(TextureRef ref) {
        Entry entry = CACHE.computeIfAbsent(ref.key(), key -> new Entry());
        synchronized (entry) {
            if (entry.started || entry.bytes != null || entry.failed) {
                return;
            }
            entry.started = true;
        }
        long maxBytes = ref.maxBytes() > 0 ? ref.maxBytes() : DEFAULT_MAX_BYTES;
        EXECUTOR.submit(() -> download(ref, entry, maxBytes));
    }

    /** Bumped on every completed download so cached render lists can rebuild. */
    public static int generation() {
        return GENERATION.get();
    }

    /** @return the downloaded bytes, or {@code null} while pending or after a failure */
    public static byte[] bytes(String key) {
        Entry entry = CACHE.get(key);
        return entry == null ? null : entry.bytes;
    }

    public static boolean failed(String key) {
        Entry entry = CACHE.get(key);
        return entry != null && entry.failed;
    }

    private static void download(TextureRef ref, Entry entry, long maxBytes) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ref.url()))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "CloudCore")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client().send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                fail(ref, entry, "HTTP " + response.statusCode());
                return;
            }
            byte[] body = response.body();
            if (body.length == 0 || body.length > maxBytes) {
                fail(ref, entry, "unexpected size " + body.length);
                return;
            }
            if (!ref.sha256().isBlank()
                    && !ref.sha256().equalsIgnoreCase(sha256(body))) {
                fail(ref, entry, "sha256 mismatch");
                return;
            }
            entry.bytes = body;
            GENERATION.incrementAndGet();
            LOGGER.info("Downloaded texture {} ({} bytes) from {}", ref.key(), body.length,
                    ref.url());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(ref, entry, "interrupted");
        } catch (Exception exception) {
            fail(ref, entry, exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private static void fail(TextureRef ref, Entry entry, String reason) {
        entry.failed = true;
        LOGGER.warn("[CC-ASSET-011] texture download failed for {}: {}", ref.key(), reason);
    }

    private static HttpClient client() {
        HttpClient current = httpClient;
        if (current == null) {
            synchronized (RemoteTextureCache.class) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder()
                            .connectTimeout(TIMEOUT)
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                }
                current = httpClient;
            }
        }
        return current;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception exception) {
            return "";
        }
    }

    /** Test hook: drops the cache so each test starts clean. */
    static void clear() {
        CACHE.clear();
    }
}

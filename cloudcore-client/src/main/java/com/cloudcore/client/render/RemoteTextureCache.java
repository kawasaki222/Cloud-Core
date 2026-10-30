package com.cloudcore.client.render;

import com.cloudcore.protocol.TexturePut;
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
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads {@code TEXTURE_REF} images on background threads and hands the bytes to the
 * render thread. Only https is accepted, the body is capped (default 8 MiB), a sha256 is
 * verified when the server supplies one, and failures are logged once per key.
 *
 * <p>When direct download is disabled or fails, the key is sent to the server through
 * {@code TEXTURE_REQUEST}; its {@code TEXTURE_PUT} reply is stored with the sprite-sheet
 * metadata the renderer needs. The render thread never blocks: {@link #bytes(String)} returns
 * {@code null} until either path finishes.
 */
public final class RemoteTextureCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final long DEFAULT_MAX_BYTES = 8L * 1024L * 1024L;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Map<String, Payload> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Entry> DOWNLOADS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> PROXY_REQUESTED =
            ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicInteger GENERATION =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "cloudcore-texture-download");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile HttpClient httpClient;
    private static volatile TextureDiskCache diskCache;
    private static volatile boolean remoteImages = true;
    private static volatile Consumer<String> proxyRequester = key -> {
    };

    private RemoteTextureCache() {
    }

    /**
     * One decoded texture. {@code format} is 0 for a client-downloaded image (PNG or GIF) or
     * {@link TexturePut#FORMAT_STATIC_PNG}/{@link TexturePut#FORMAT_ANIMATED_SHEET} for a
     * server proxy reply.
     */
    public record Payload(String key, byte[] bytes, int format, int width, int height,
            int frameCount, int loop, int[] frameDelaysMs) {
        public Payload {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("texture key must not be blank");
            }
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("texture bytes must not be empty");
            }
            frameDelaysMs = frameDelaysMs == null ? new int[0] : frameDelaysMs.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public int[] frameDelaysMs() {
            return frameDelaysMs.clone();
        }
    }

    private static final class Entry {
        private volatile boolean failed;
        private volatile boolean started;
    }

    /** Applies the player's privacy setting and the callback that sends TEXTURE_REQUEST. */
    public static void configure(boolean allowRemoteImages, Consumer<String> requester) {
        configure(allowRemoteImages, requester, null);
    }

    /** Adds an optional disk cache; {@code cacheDir == null} keeps downloads memory-only. */
    public static void configure(boolean allowRemoteImages, Consumer<String> requester,
            java.nio.file.Path cacheDir) {
        remoteImages = allowRemoteImages;
        proxyRequester = requester == null ? key -> {
        } : requester;
        diskCache = cacheDir == null ? null : new TextureDiskCache(cacheDir,
                128L * 1024L * 1024L);
    }

    /**
     * Starts a download the first time a key is seen; later calls are ignored. A disabled or
     * failed direct download asks the server for a proxy copy instead.
     */
    public static void request(TextureRef ref) {
        if (CACHE.containsKey(ref.key())) {
            return;
        }
        if (!remoteImages) {
            requestProxy(ref.key());
            return;
        }
        Entry entry = DOWNLOADS.computeIfAbsent(ref.key(), key -> new Entry());
        synchronized (entry) {
            if (entry.started || entry.failed) {
                return;
            }
            entry.started = true;
        }
        long maxBytes = ref.maxBytes() > 0 ? ref.maxBytes() : DEFAULT_MAX_BYTES;
        EXECUTOR.submit(() -> download(ref, entry, maxBytes));
    }

    /** Stores a server proxy reply and lets cached render lists rebuild. */
    public static void put(TexturePut put) {
        CACHE.put(put.key(), new Payload(put.key(), put.data(), put.format(), put.width(),
                put.height(), put.frameCount(), put.loop(), put.frameDelaysMs()));
        PROXY_REQUESTED.remove(put.key());
        TextureDiskCache disk = diskCache;
        if (disk != null) {
            EXECUTOR.submit(() -> {
                try {
                    disk.write(put.key(), put.data());
                } catch (java.io.IOException exception) {
                    LOGGER.warn("[CC-ASSET-021] cannot cache texture {}: {}", put.key(),
                            exception.getMessage());
                }
            });
        }
        GENERATION.incrementAndGet();
        LOGGER.info("Received proxy texture {} ({} bytes, {}x{}, frames={})", put.key(),
                put.data().length, put.width(), put.height(), put.frameCount());
    }

    /** Bumped on every completed transfer so cached render lists can rebuild. */
    public static int generation() {
        return GENERATION.get();
    }

    /** @return the downloaded bytes, or {@code null} while pending or after a failure */
    public static byte[] bytes(String key) {
        Payload payload = CACHE.get(key);
        return payload == null ? null : payload.bytes();
    }

    /** @return the texture and its sprite-sheet metadata, or {@code null} while pending */
    public static Payload payload(String key) {
        return CACHE.get(key);
    }

    public static boolean failed(String key) {
        Entry entry = DOWNLOADS.get(key);
        return entry != null && entry.failed;
    }

    private static void download(TextureRef ref, Entry entry, long maxBytes) {
        try {
            TextureDiskCache disk = diskCache;
            byte[] cached = disk == null ? null : disk.read(ref.key());
            if (cached != null && cached.length > 0) {
                CACHE.put(ref.key(), new Payload(ref.key(), cached, 0, 0, 0, 0, 0,
                        new int[0]));
                GENERATION.incrementAndGet();
                LOGGER.info("Loaded texture {} from disk cache", ref.key());
                return;
            }
            if (!isSafeRemoteUrl(ref.url())) {
                fail(ref, entry, "unsafe remote URL");
                return;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(ref.url()))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "CloudCore")
                    .GET()
                    .build();
            HttpResponse<java.io.InputStream> response = client().send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (var ignored = response.body()) {
                    // Drain nothing: the status already decided the outcome.
                }
                fail(ref, entry, "HTTP " + response.statusCode());
                return;
            }
            byte[] body;
            try (var input = response.body()) {
                body = readLimited(input, Math.min(maxBytes, DEFAULT_MAX_BYTES));
            }
            if (body.length == 0) {
                fail(ref, entry, "empty body");
                return;
            }
            if (!ref.sha256().isBlank()
                    && !ref.sha256().equalsIgnoreCase(sha256(body))) {
                fail(ref, entry, "sha256 mismatch");
                return;
            }
            CACHE.put(ref.key(), new Payload(ref.key(), body, 0, 0, 0, 0, 0,
                    new int[0]));
            if (disk != null) {
                try {
                    disk.write(ref.key(), body);
                } catch (java.io.IOException exception) {
                    LOGGER.warn("[CC-ASSET-021] cannot cache texture {}: {}", ref.key(),
                            exception.getMessage());
                }
            }
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
        requestProxy(ref.key());
    }

    private static void requestProxy(String key) {
        if (key == null || key.isBlank() || !PROXY_REQUESTED.add(key)) {
            return;
        }
        try {
            proxyRequester.accept(key);
            LOGGER.info("Requested server proxy for texture {}", key);
        } catch (RuntimeException exception) {
            PROXY_REQUESTED.remove(key);
            LOGGER.warn("Cannot request a texture proxy for {}: {}", key,
                    exception.getMessage());
        }
    }

    /** Drops downloaded textures so the next draw re-requests them ({@code F9}). */
    public static void reload() {
        CACHE.clear();
        DOWNLOADS.clear();
        PROXY_REQUESTED.clear();
        GENERATION.incrementAndGet();
    }

    /** Test hook: drops the cache so each test starts clean. */
    static void clear() {
        CACHE.clear();
        DOWNLOADS.clear();
        PROXY_REQUESTED.clear();
        remoteImages = true;
        proxyRequester = key -> {
        };
        TextureDiskCache disk = diskCache;
        if (disk != null) {
            disk.clear();
        }
    }

    /**
     * Client-side SSRF guard: https only, no credentials, default port, and no loopback,
     * site-local, link-local or multicast destination after DNS resolution.
     */
    static boolean isSafeRemoteUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException exception) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getHost().isBlank() || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }
        try {
            for (java.net.InetAddress address : java.net.InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    return false;
                }
                byte[] raw = address.getAddress();
                if (raw.length == 16 && (raw[0] & 0xFE) == 0xFC) {
                    return false; // IPv6 unique local fc00::/7
                }
            }
        } catch (java.net.UnknownHostException exception) {
            return false;
        }
        return true;
    }

    private static byte[] readLimited(java.io.InputStream input, long maxBytes)
            throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maxBytes) {
                throw new java.io.IOException("body exceeds " + maxBytes + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception exception) {
            return "";
        }
    }

    private static HttpClient client() {
        HttpClient current = httpClient;
        if (current == null) {
            synchronized (RemoteTextureCache.class) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder()
                            .connectTimeout(TIMEOUT)
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
                }
                current = httpClient;
            }
        }
        return current;
    }
}

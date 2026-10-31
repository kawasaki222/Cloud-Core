package com.cloudcore.bukkit.texture;

import com.cloudcore.api.page.PageLoader;
import com.cloudcore.protocol.TexturePut;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

/**
 * Optional server-side URL texture proxy. The client normally downloads {@code TEXTURE_REF}
 * images itself; in {@code proxy} mode (or after a client {@code TEXTURE_REQUEST} in
 * {@code auto} mode) this class fetches the URL through an admin allowlist, decodes it into a
 * PNG (or a vertical sprite sheet for GIF), and sends {@code TEXTURE_PUT}.
 *
 * <p>No Bukkit API is referenced here so the fetch/decode/cache policy stays testable. The
 * executor is deliberately small: proxying trades server bandwidth for client privacy, and a
 * handful of concurrent downloads is enough for UI textures.
 */
public final class TextureProxy implements AutoCloseable {
    public enum Mode {
        CLIENT,
        PROXY,
        AUTO;

        public static Mode parse(String value) {
            if (value == null) {
                return CLIENT;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "proxy" -> PROXY;
                case "auto" -> AUTO;
                default -> CLIENT;
            };
        }
    }

    public record Settings(Mode mode, List<String> allowlist, long maxBytes, Duration timeout,
            int ttlSeconds, Path cacheDir, long cacheMaxBytes) {
        public Settings {
            mode = mode == null ? Mode.CLIENT : mode;
            allowlist = List.copyOf(allowlist == null ? List.of() : allowlist);
            maxBytes = maxBytes <= 0 ? 8L * 1024L * 1024L : maxBytes;
            timeout = timeout == null || timeout.isNegative() || timeout.isZero()
                    ? Duration.ofSeconds(10) : timeout;
            ttlSeconds = Math.max(0, ttlSeconds);
            cacheMaxBytes = cacheMaxBytes <= 0 ? 256L * 1024L * 1024L : cacheMaxBytes;
        }

        public static Settings client() {
            return new Settings(Mode.CLIENT, List.of(), 8L * 1024L * 1024L,
                    Duration.ofSeconds(10), 3600, null, 256L * 1024L * 1024L);
        }
    }

    private static final int MAX_DIMENSION = TexturePut.MAX_DIMENSION;
    private static final int MAX_FRAMES = TexturePut.MAX_FRAMES;
    private static final int MIN_DELAY_MS = TexturePut.MIN_DELAY_MS;
    private static final long REQUEST_INTERVAL_MS = 30_000L;

    private final Settings settings;
    private final Logger logger;
    private static final int MAX_QUEUED_DOWNLOADS = 32;

    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_QUEUED_DOWNLOADS),
            runnable -> {
                Thread thread = new Thread(runnable, "cloudcore-texture-proxy");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final HttpClient httpClient;
    private final Map<String, TexturePut> memory = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<TexturePut>> inFlight =
            new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> requestTimes = new ConcurrentHashMap<>();

    public TextureProxy(Settings settings, Logger logger) {
        this.settings = settings == null ? Settings.client() : settings;
        this.logger = logger == null ? Logger.getLogger("CloudCore") : logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.settings.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        if (this.settings.cacheDir() != null) {
            try {
                Files.createDirectories(this.settings.cacheDir());
            } catch (IOException exception) {
                this.logger.log(Level.WARNING, "Cannot create texture cache directory "
                        + this.settings.cacheDir(), exception);
            }
        }
    }

    public Mode mode() {
        return settings.mode();
    }

    /** Proxy mode sends the bytes on open; auto mode lets the client try direct first. */
    public boolean proxyOnOpen() {
        return settings.mode() == Mode.PROXY;
    }

    public boolean acceptsRequests() {
        return settings.mode() != Mode.CLIENT;
    }

    /**
     * Fetches every URL texture for a page. In proxy mode the sink receives the bytes even
     * when the client never asked; in auto mode the client's request path does the same.
     */
    public void provideForPage(UUID player, List<PageLoader.RemoteTexture> textures,
            Consumer<TexturePut> sink) {
        if (!acceptsRequests() || textures == null) {
            return;
        }
        for (PageLoader.RemoteTexture texture : textures) {
            request(player, texture.key(), texture.url(), sink);
        }
    }

    /**
     * Serves one client request. Returns {@code false} when the mode, allowlist, URL or rate
     * limit rejects it; the caller should stay silent in that case.
     */
    public boolean request(UUID player, String key, String url, Consumer<TexturePut> sink) {
        if (!acceptsRequests() || key == null || key.isBlank() || url == null) {
            return false;
        }
        if (!isAllowed(url)) {
            logger.warning("Texture proxy rejected non-allowlisted URL for key " + key);
            return false;
        }
        TexturePut cached = memory.get(key);
        if (cached == null) {
            cached = readDisk(key, url);
        }
        if (cached != null) {
            sink.accept(cached);
            return true;
        }
        if (!tryAcquire(player, key)) {
            logger.fine("Texture proxy rate-limited key " + key + " for " + player);
            return false;
        }
        String urlKey = sha256(url);
        CompletableFuture<TexturePut> future;
        try {
            future = inFlight.computeIfAbsent(urlKey,
                    ignored -> CompletableFuture.supplyAsync(() -> load(key, url), executor));
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            logger.fine("Texture proxy queue is full; dropping " + key);
            return false;
        }
        CompletableFuture<TexturePut> completed = future;
        completed.whenComplete((put, error) -> {
            inFlight.remove(urlKey, completed);
            if (error != null) {
                logger.log(Level.WARNING, "Texture proxy failed for " + key,
                        unwrap(error));
                return;
            }
            if (put != null) {
                sink.accept(put);
            }
        });
        return true;
    }

    /** True when {@code url} is https and starts with one of the configured prefixes. */
    public boolean isAllowed(String url) {
        return isAllowed(url, settings.allowlist());
    }

    static boolean isAllowed(String url, List<String> allowlist) {
        if (url == null || allowlist == null || allowlist.isEmpty()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getHost().isBlank() || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }
        for (String entry : allowlist) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String prefix = entry.trim();
            String withSlash = prefix.endsWith("/") ? prefix : prefix + "/";
            if (url.equals(prefix) || url.startsWith(withSlash)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryAcquire(UUID player, String key) {
        if (player == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Map<String, Long> keys = requestTimes.computeIfAbsent(player,
                ignored -> new ConcurrentHashMap<>());
        Long last = keys.get(key);
        if (last != null && now - last < REQUEST_INTERVAL_MS) {
            return false;
        }
        keys.put(key, now);
        if (keys.size() > 512) {
            keys.entrySet().removeIf(entry -> now - entry.getValue() > REQUEST_INTERVAL_MS);
        }
        return true;
    }

    private TexturePut load(String key, String url) {
        try {
            byte[] body = download(url);
            TexturePut put = decode(key, body);
            memory.put(key, put);
            writeDisk(key, url, put);
            logger.info("Proxied texture " + key + " (" + body.length + " bytes, "
                    + put.width() + "x" + put.height() + ", frames=" + put.frameCount() + ")");
            return put;
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Texture proxy cannot load " + url, exception);
            return null;
        }
    }

    private byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(settings.timeout())
                .header("User-Agent", "CloudCore")
                .header("Accept", "image/png,image/gif,image/jpeg,image/webp,image/*")
                .GET()
                .build();
        HttpResponse<java.io.InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (var ignored = response.body()) {
                // The status is enough; do not buffer an error page.
            }
            throw new IOException("HTTP " + response.statusCode());
        }
        try (var input = response.body()) {
            return readLimited(input, settings.maxBytes());
        }
    }

    private static byte[] readLimited(java.io.InputStream input, long maxBytes)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("texture body exceeds " + maxBytes + " bytes");
            }
            out.write(buffer, 0, read);
        }
        byte[] body = out.toByteArray();
        if (body.length == 0) {
            throw new IOException("empty texture body");
        }
        return body;
    }

    /** Decodes an image into a protocol payload, converting formats to PNG. */
    static TexturePut decode(String key, byte[] body) throws IOException {
        if (isGif(body)) {
            return decodeGif(key, body);
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(body));
        if (image == null) {
            throw new IOException("unsupported image format");
        }
        image = fit(image);
        byte[] png = encodePng(image);
        while (png.length > TexturePut.MAX_DATA_BYTES
                && image.getWidth() > 16 && image.getHeight() > 16) {
            image = scale(image, 0.75);
            png = encodePng(image);
        }
        if (png.length > TexturePut.MAX_DATA_BYTES) {
            throw new IOException("encoded texture too large: " + png.length);
        }
        return new TexturePut(key, image.getWidth(), image.getHeight(),
                TexturePut.FORMAT_STATIC_PNG, crc32(png), png, 1, 0, new int[0]);
    }

    private static TexturePut decodeGif(String key, byte[] body) throws IOException {
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(body))) {
            if (input == null) {
                throw new IOException("cannot open GIF stream");
            }
            reader.setInput(input, false, false);
            int count = Math.min(reader.getNumImages(true), MAX_FRAMES);
            if (count <= 0) {
                throw new IOException("GIF has no frames");
            }
            BufferedImage rawFirst = reader.read(0);
            int rawWidth = Math.max(rawFirst.getWidth(), screenDimension(reader, true));
            int rawHeight = Math.max(rawFirst.getHeight(), screenDimension(reader, false));
            if ((long) rawWidth * rawHeight * 4L * count > 64L * 1024L * 1024L) {
                throw new IOException("GIF decodes to too much memory");
            }
            // Optimised GIFs store only the changed rectangle per frame. Compose each frame
            // onto the logical screen so the sprite-sheet cells all have one size.
            BufferedImage canvas = new BufferedImage(rawWidth, rawHeight,
                    BufferedImage.TYPE_INT_ARGB);
            java.util.List<BufferedImage> frames = new ArrayList<>(count);
            java.util.List<Integer> delays = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                BufferedImage raw = index == 0 ? rawFirst : reader.read(index);
                int[] offset = frameOffset(reader, index);
                var graphics = canvas.createGraphics();
                try {
                    graphics.drawImage(raw, offset[0], offset[1], null);
                } finally {
                    graphics.dispose();
                }
                frames.add(fit(copy(canvas)));
                delays.add(Math.max(MIN_DELAY_MS, delayOf(reader, index)));
                if (index + 1 < count) {
                    applyDisposal(reader, index, canvas, offset, raw);
                }
            }
            int loop = loopOf(reader);
            while (!frames.isEmpty()) {
                BufferedImage first = frames.get(0);
                int width = first.getWidth();
                int height = first.getHeight();
                BufferedImage sheet = new BufferedImage(width, height * frames.size(),
                        BufferedImage.TYPE_INT_ARGB);
                var graphics = sheet.createGraphics();
                try {
                    for (int index = 0; index < frames.size(); index++) {
                        graphics.drawImage(frames.get(index), 0, index * height, null);
                    }
                } finally {
                    graphics.dispose();
                }
                byte[] png = encodePng(sheet);
                if (png.length <= TexturePut.MAX_DATA_BYTES) {
                    int[] keptDelays = delays.stream().mapToInt(Integer::intValue).toArray();
                    return new TexturePut(key, width, height * frames.size(),
                            TexturePut.FORMAT_ANIMATED_SHEET, crc32(png), png,
                            frames.size(), loop, keptDelays);
                }
                if (frames.size() > 1) {
                    // Halve the frame rate before touching resolution; motion stays readable.
                    java.util.List<BufferedImage> reduced = new ArrayList<>();
                    java.util.List<Integer> reducedDelays = new ArrayList<>();
                    for (int index = 0; index < frames.size(); index += 2) {
                        reduced.add(frames.get(index));
                        reducedDelays.add(delays.get(index) * 2);
                    }
                    frames = reduced;
                    delays = reducedDelays;
                } else {
                    BufferedImage scaled = scale(frames.get(0), 0.75);
                    if (scaled.getWidth() == frames.get(0).getWidth()
                            && scaled.getHeight() == frames.get(0).getHeight()) {
                        throw new IOException("encoded texture too large: " + png.length);
                    }
                    frames = java.util.List.of(scaled);
                }
            }
            throw new IOException("GIF has no usable frames");
        } finally {
            reader.dispose();
        }
    }

    /** Scales a large image down to the single-frame limit, preserving aspect ratio. */
    private static BufferedImage fit(BufferedImage image) {
        if (image.getWidth() <= MAX_DIMENSION && image.getHeight() <= MAX_DIMENSION) {
            return image;
        }
        double scale = Math.min(MAX_DIMENSION / (double) image.getWidth(),
                MAX_DIMENSION / (double) image.getHeight());
        return scale(image, scale);
    }

    private static BufferedImage scale(BufferedImage image, double factor) {
        int width = Math.max(1, (int) Math.round(image.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(image.getHeight() * factor));
        BufferedImage scaled = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        var graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("PNG encoder unavailable");
        }
        return out.toByteArray();
    }

    private static BufferedImage copy(BufferedImage image) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        var graphics = copy.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    private static int screenDimension(ImageReader reader, boolean width) {
        try {
            IIOMetadata metadata = reader.getStreamMetadata();
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(
                    "javax_imageio_gif_stream_1.0");
            var descriptors = root.getElementsByTagName("LogicalScreenDescriptor");
            if (descriptors.getLength() == 0) {
                return 0;
            }
            IIOMetadataNode descriptor = (IIOMetadataNode) descriptors.item(0);
            return Integer.parseInt(descriptor.getAttribute(
                    width ? "logicalScreenWidth" : "logicalScreenHeight"));
        } catch (Exception exception) {
            return 0;
        }
    }

    private static int[] frameOffset(ImageReader reader, int index) {
        try {
            IIOMetadata metadata = reader.getImageMetadata(index);
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(
                    "javax_imageio_gif_image_1.0");
            var descriptors = root.getElementsByTagName("ImageDescriptor");
            if (descriptors.getLength() == 0) {
                return new int[]{0, 0};
            }
            IIOMetadataNode descriptor = (IIOMetadataNode) descriptors.item(0);
            return new int[]{
                Integer.parseInt(descriptor.getAttribute("imageLeftPosition")),
                Integer.parseInt(descriptor.getAttribute("imageTopPosition"))};
        } catch (Exception exception) {
            return new int[]{0, 0};
        }
    }

    /** GIF disposal method 2 clears the frame rectangle before the next frame. */
    private static void applyDisposal(ImageReader reader, int index, BufferedImage canvas,
            int[] offset, BufferedImage raw) {
        try {
            IIOMetadata metadata = reader.getImageMetadata(index);
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(
                    "javax_imageio_gif_image_1.0");
            var controls = root.getElementsByTagName("GraphicControlExtension");
            if (controls.getLength() == 0) {
                return;
            }
            String disposal = ((IIOMetadataNode) controls.item(0))
                    .getAttribute("disposalMethod");
            if (!"restoreToBackgroundColor".equals(disposal)) {
                return;
            }
            var graphics = canvas.createGraphics();
            try {
                graphics.setComposite(java.awt.AlphaComposite.Clear);
                graphics.fillRect(offset[0], offset[1], raw.getWidth(), raw.getHeight());
            } finally {
                graphics.dispose();
            }
        } catch (Exception ignored) {
            // A malformed metadata block should not lose the frames we already decoded.
        }
    }

    private static int delayOf(ImageReader reader, int index) {
        try {
            IIOMetadata metadata = reader.getImageMetadata(index);
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(
                    "javax_imageio_gif_image_1.0");
            var controls = root.getElementsByTagName("GraphicControlExtension");
            if (controls.getLength() == 0) {
                return MIN_DELAY_MS;
            }
            String delay = ((IIOMetadataNode) controls.item(0)).getAttribute("delayTime");
            return Integer.parseInt(delay) * 10;
        } catch (Exception exception) {
            return MIN_DELAY_MS;
        }
    }

    private static int loopOf(ImageReader reader) {
        try {
            IIOMetadata metadata = reader.getStreamMetadata();
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(
                    "javax_imageio_gif_stream_1.0");
            var loops = root.getElementsByTagName("LoopExtension");
            if (loops.getLength() == 0) {
                return 0;
            }
            String iterations = ((IIOMetadataNode) loops.item(0))
                    .getAttribute("iterations");
            int value = Integer.parseInt(iterations);
            return value <= 0 ? 0 : Math.min(255, value);
        } catch (Exception exception) {
            return 0;
        }
    }

    private static boolean isGif(byte[] body) {
        return body != null && body.length >= 6 && body[0] == 'G' && body[1] == 'I'
                && body[2] == 'F' && body[3] == '8';
    }

    private static int crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }

    private TexturePut readDisk(String key, String url) {
        Path dir = settings.cacheDir();
        if (dir == null) {
            return null;
        }
        String hash = sha256(url);
        Path png = dir.resolve(hash + ".png");
        Path meta = dir.resolve(hash + ".properties");
        try {
            if (!Files.isRegularFile(png) || !Files.isRegularFile(meta)) {
                return null;
            }
            if (settings.ttlSeconds() > 0
                    && System.currentTimeMillis() - Files.getLastModifiedTime(png).toMillis()
                    > settings.ttlSeconds() * 1000L) {
                Files.deleteIfExists(png);
                Files.deleteIfExists(meta);
                return null;
            }
            Properties properties = new Properties();
            try (var input = Files.newInputStream(meta)) {
                properties.load(input);
            }
            int width = Integer.parseInt(properties.getProperty("width", "0"));
            int height = Integer.parseInt(properties.getProperty("height", "0"));
            int format = Integer.parseInt(properties.getProperty("format", "1"));
            int frameCount = Integer.parseInt(properties.getProperty("frameCount", "1"));
            int loop = Integer.parseInt(properties.getProperty("loop", "0"));
            int[] delays = parseDelays(properties.getProperty("delays", ""));
            byte[] data = Files.readAllBytes(png);
            TexturePut put = new TexturePut(key, width, height, format, crc32(data), data,
                    frameCount, loop, delays);
            memory.put(key, put);
            return put;
        } catch (Exception exception) {
            logger.log(Level.FINE, "Ignoring bad texture cache entry " + png, exception);
            return null;
        }
    }

    private void writeDisk(String key, String url, TexturePut put) {
        Path dir = settings.cacheDir();
        if (dir == null) {
            return;
        }
        String hash = sha256(url);
        Path png = dir.resolve(hash + ".png");
        Path meta = dir.resolve(hash + ".properties");
        try {
            Path tempPng = dir.resolve(hash + ".png.tmp");
            Files.write(tempPng, put.data());
            Files.move(tempPng, png, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            Properties properties = new Properties();
            properties.setProperty("width", Integer.toString(put.width()));
            properties.setProperty("height", Integer.toString(put.height()));
            properties.setProperty("format", Integer.toString(put.format()));
            properties.setProperty("frameCount", Integer.toString(put.frameCount()));
            properties.setProperty("loop", Integer.toString(put.loop()));
            properties.setProperty("delays", joinDelays(put.frameDelaysMs()));
            Path tempMeta = dir.resolve(hash + ".properties.tmp");
            try (var output = Files.newOutputStream(tempMeta)) {
                properties.store(output, "CloudCore texture cache");
            }
            Files.move(tempMeta, meta, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            trimDisk(dir, settings.cacheMaxBytes());
        } catch (IOException exception) {
            logger.log(Level.FINE, "Cannot write texture cache entry " + png, exception);
        }
    }

    /** Evicts the oldest cache entries until the directory fits its byte budget. */
    static void trimDisk(Path dir, long maxBytes) throws IOException {
        if (dir == null || maxBytes <= 0) {
            return;
        }
        java.util.List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        long total = 0;
        for (Path file : files) {
            total += Files.size(file);
        }
        if (total <= maxBytes) {
            return;
        }
        files.sort(java.util.Comparator.comparingLong(file -> {
            try {
                return Files.getLastModifiedTime(file).toMillis();
            } catch (IOException exception) {
                return 0L;
            }
        }));
        for (Path file : files) {
            if (total <= maxBytes) {
                break;
            }
            long size = Files.size(file);
            if (Files.deleteIfExists(file)) {
                total -= size;
            }
        }
    }

    private static int[] parseDelays(String value) {
        if (value == null || value.isBlank()) {
            return new int[0];
        }
        String[] parts = value.split(",");
        int[] delays = new int[parts.length];
        for (int index = 0; index < parts.length; index++) {
            delays[index] = Integer.parseInt(parts[index].trim());
        }
        return delays;
    }

    private static String joinDelays(int[] delays) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < delays.length; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(delays[index]);
        }
        return result.toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof java.util.concurrent.CompletionException completion
                && completion.getCause() != null) {
            return completion.getCause();
        }
        return error;
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        memory.clear();
        inFlight.clear();
        requestTimes.clear();
    }
}

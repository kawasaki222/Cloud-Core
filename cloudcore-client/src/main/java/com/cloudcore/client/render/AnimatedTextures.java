package com.cloudcore.client.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes animated GIFs into one texture per frame and picks the frame for the current time.
 *
 * <p>Limits follow docs/03-ui-dsl.md: at most 64 frames, frames up to 512x512, at most 16 MiB
 * decoded and a minimum delay of 50 ms. Render thread only: textures are registered here.
 */
public final class AnimatedTextures {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final int MAX_FRAMES = 64;
    private static final int MAX_DIMENSION = 512;
    private static final long MAX_DECODED_BYTES = 16L * 1024L * 1024L;
    private static final int MIN_DELAY_MS = 50;

    private static final Map<String, Animation> CACHE = new ConcurrentHashMap<>();

    private AnimatedTextures() {
    }

    private static final class Animation {
        private final List<Identifier> frames;
        private final int[] delays;
        private final int totalMs;
        private volatile long lastAccessMs;

        private Animation(List<Identifier> frames, int[] delays) {
            this.frames = frames;
            this.delays = delays;
            int sum = 0;
            for (int delay : delays) {
                sum += delay;
            }
            this.totalMs = Math.max(1, sum);
        }

        private Identifier frameAt(long nowMs, RenderCommand.Image.Animation options) {
            lastAccessMs = nowMs;
            if (!options.enabled()) {
                return frames.get(0);
            }
            long scaled = Math.max(0L, (long) (nowMs * (double) options.speed()));
            if (!options.loop() && scaled >= totalMs) {
                return frames.get(frames.size() - 1);
            }
            int offset = (int) (scaled % totalMs);
            for (int index = 0; index < delays.length; index++) {
                offset -= delays[index];
                if (offset < 0) {
                    return frames.get(index);
                }
            }
            return frames.get(frames.size() - 1);
        }
    }

    public static boolean isGif(byte[] bytes) {
        return bytes != null && bytes.length >= 6
                && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F';
    }

    /**
     * Uses a server-built vertical sprite sheet. The sheet is split on the render thread into
     * one texture per frame, so the normal native renderer can draw it.
     *
     * @return the frame to draw now, or {@code null} when the sheet is invalid
     */
    public static Identifier frameFromSheet(String key, byte[] bytes, int frameWidth,
            int frameHeight, int frameCount, int[] delays, long nowMs,
            RenderCommand.Image.Animation options) {
        Animation animation = CACHE.get(key);
        if (animation == null) {
            animation = decodeSheet(key, bytes, frameWidth, frameHeight, frameCount, delays);
            if (animation == null) {
                return null;
            }
            CACHE.put(key, animation);
        }
        return animation.frameAt(nowMs, options == null
                ? RenderCommand.Image.Animation.DEFAULT : options);
    }

    private static Animation decodeSheet(String key, byte[] bytes, int frameWidth,
            int frameHeight, int frameCount, int[] delays) {
        try {
            BufferedImage sheet = ImageIO.read(new ByteArrayInputStream(bytes));
            if (sheet == null) {
                LOGGER.warn("[CC-ASSET-016] cannot decode proxy sheet {}", key);
                return null;
            }
            if (frameWidth <= 0 || frameHeight <= 0 || frameCount <= 0
                    || frameCount > MAX_FRAMES || frameWidth > MAX_DIMENSION
                    || frameHeight > MAX_DIMENSION
                    || sheet.getWidth() != frameWidth
                    || sheet.getHeight() != frameHeight * frameCount) {
                LOGGER.warn("[CC-ASSET-017] invalid proxy sheet {} ({}x{}, frames={})",
                        key, sheet.getWidth(), sheet.getHeight(), frameCount);
                return null;
            }
            int[] effectiveDelays = new int[frameCount];
            for (int index = 0; index < frameCount; index++) {
                effectiveDelays[index] = delays != null && index < delays.length
                        ? Math.max(MIN_DELAY_MS, delays[index])
                        : MIN_DELAY_MS;
            }
            List<Identifier> frames = new ArrayList<>(frameCount);
            for (int index = 0; index < frameCount; index++) {
                BufferedImage frame = sheet.getSubimage(0, index * frameHeight,
                        frameWidth, frameHeight);
                frames.add(register(key + "/sheet", index, frame));
            }
            LOGGER.info("Decoded proxy sheet {} frames={} total={}ms", key, frameCount,
                    java.util.Arrays.stream(effectiveDelays).sum());
            return new Animation(frames, effectiveDelays);
        } catch (Exception exception) {
            LOGGER.warn("[CC-ASSET-018] cannot decode proxy sheet {}: {}", key,
                    exception.getMessage());
            return null;
        }
    }

    /**
     * @return the frame to draw now, or {@code null} when the bytes cannot be decoded
     */
    public static Identifier frame(String key, byte[] bytes, long nowMs) {
        return frame(key, bytes, nowMs, RenderCommand.Image.Animation.DEFAULT);
    }

    public static Identifier frame(String key, byte[] bytes, long nowMs,
            RenderCommand.Image.Animation options) {
        Animation animation = CACHE.get(key);
        if (animation == null) {
            animation = decode(key, bytes);
            if (animation == null) {
                return null;
            }
            CACHE.put(key, animation);
        }
        return animation.frameAt(nowMs, options == null
                ? RenderCommand.Image.Animation.DEFAULT : options);
    }

    /** Bumped after each decode so a render list holding an old frame can be rebuilt. */
    public static int generation() {
        return CACHE.size();
    }

    /**
     * True while an animated texture was resolved recently. Screens render lists and HUD
     * layers use this to rebuild on the animation clock, not only when the document changes.
     */
    public static boolean hasActiveAnimation() {
        long now = System.currentTimeMillis();
        for (Animation animation : CACHE.values()) {
            if (now - animation.lastAccessMs < 500L) {
                return true;
            }
        }
        return false;
    }

    /** Releases every frame texture and forgets the decodes so an edited GIF is re-read. */
    public static void reset() {
        var manager = Minecraft.getInstance().getTextureManager();
        for (Animation animation : CACHE.values()) {
            animation.frames.forEach(manager::release);
        }
        CACHE.clear();
    }

    private static Animation decode(String key, byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(bytes))) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(input, false, false);
            int frameCount = Math.min(reader.getNumImages(true), MAX_FRAMES);
            if (frameCount <= 0) {
                return null;
            }
            List<Identifier> frames = new ArrayList<>(frameCount);
            int[] delays = new int[frameCount];
            long decodedBytes = 0;
            for (int index = 0; index < frameCount; index++) {
                BufferedImage image = reader.read(index);
                if (image.getWidth() > MAX_DIMENSION || image.getHeight() > MAX_DIMENSION) {
                    LOGGER.warn("[CC-ASSET-013] GIF frame too large: {}x{} in {}",
                            image.getWidth(), image.getHeight(), key);
                    return null;
                }
                decodedBytes += (long) image.getWidth() * image.getHeight() * 4L;
                if (decodedBytes > MAX_DECODED_BYTES) {
                    LOGGER.warn("[CC-ASSET-014] GIF decoded size exceeds limit: {}", key);
                    return null;
                }
                delays[index] = Math.max(MIN_DELAY_MS, delayOf(reader, index));
                frames.add(register(key, index, image));
            }
            reader.dispose();
            LOGGER.info("Decoded GIF {} frames={} total={}ms", key, frames.size(),
                    java.util.Arrays.stream(delays).sum());
            return new Animation(frames, delays);
        } catch (Exception exception) {
            LOGGER.warn("[CC-ASSET-015] cannot decode GIF {}: {}", key, exception.getMessage());
            return null;
        }
    }

    /** Identifiers only accept {@code [a-z0-9/._-]}; local paths may contain anything. */
    static String sanitise(String key) {
        StringBuilder result = new StringBuilder(key.length());
        for (char character : key.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '/' || character == '.' || character == '_'
                    || character == '-';
            result.append(allowed ? character : '_');
        }
        return Integer.toHexString(key.hashCode()) + "_" + result;
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
            String delay = ((IIOMetadataNode) controls.item(0))
                    .getAttribute("delayTime");
            // GIF stores the delay in hundredths of a second.
            return Integer.parseInt(delay) * 10;
        } catch (Exception exception) {
            return MIN_DELAY_MS;
        }
    }

    private static Identifier register(String key, int frame, BufferedImage image) {
        Identifier identifier = Identifier.fromNamespaceAndPath("cloudcore",
                "inline/" + sanitise(key) + "/" + frame);
        DynamicTexture texture = new DynamicTexture(
                () -> "cloudcore-gif:" + key + ":" + frame, image.getWidth(),
                image.getHeight(), false);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                texture.getPixels().setPixel(x, y, image.getRGB(x, y));
            }
        }
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(identifier, texture);
        return identifier;
    }
}

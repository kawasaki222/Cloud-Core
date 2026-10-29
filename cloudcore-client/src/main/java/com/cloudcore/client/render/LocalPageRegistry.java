package com.cloudcore.client.render;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.page.PageLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pages that live on the client and attach themselves by name, with no server involved.
 *
 * <p>A player drops a normal Cloud Core page into {@code resourcepacks/CloudCore/页面/} and
 * gives it a {@code match} regex. Whenever a container screen or boss bar appears, the
 * client looks for a local page of the same surface whose {@code match} accepts that
 * screen's title / that bar's name and paints the page over it. The server never has to
 * send anything, so a skin works on any server and survives without one.
 *
 * <p>Server-sent pages still win: this registry is only consulted when nothing was sent for
 * that surface (see the adapters), so an explicit UI_OPEN is never overridden by a local file.
 *
 * <p>Render thread only; {@code F9} reloads it through {@link ClientResources}.
 */
public final class LocalPageRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    /** Sub-folder of the CloudCore root holding client-side pages. */
    private static final String FOLDER = "页面";
    private static final long MAX_PAGE_BYTES = 512L * 1024L;

    /**
     * @param id      page id, for logs
     * @param surface which surface this page skins
     * @param matcher applied to the container title / boss bar name
     * @param path    source file, so a reload can report where a page came from
     */
    private record Entry(String id, SurfaceType surface, Pattern matcher, Path path) {
    }

    private static volatile List<Entry> entries;

    /**
     * Compiled documents by source file. A local page has no session behind it, so its
     * document is stable and is compiled once per load; a failure is remembered as an empty
     * value so a broken file is reported once instead of on every frame.
     */
    private static final Map<Path, java.util.Optional<UiDocument>> COMPILED =
            new java.util.concurrent.ConcurrentHashMap<>();

    private LocalPageRegistry() {
    }

    /** Drops the cache so the next lookup re-reads the folder. */
    public static void reset() {
        entries = null;
        COMPILED.clear();
    }

    /**
     * Finds the local page that skins this surface for the given title.
     *
     * @param surface the surface asking (CONTAINER, BOSS_BAR, ...)
     * @param title   container title or boss bar name as shown to the player
     * @return the compiled document, or {@code null} when no local page matches
     */
    public static UiDocument find(SurfaceType surface, String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        for (Entry entry : load()) {
            if (entry.surface() != surface || !entry.matcher().matcher(title).find()) {
                continue;
            }
            UiDocument document = compile(entry);
            if (document != null) {
                return document;
            }
        }
        return null;
    }

    /**
     * @return true when a local page both matches the surface/title pair <em>and</em> compiles.
     *         A page that fails to compile must report false, otherwise the caller suppresses
     *         the vanilla rendering for a skin that can never be drawn.
     */
    public static boolean matches(SurfaceType surface, String title) {
        return find(surface, title) != null;
    }

    private static UiDocument compile(Entry entry) {
        java.util.Optional<UiDocument> cached = COMPILED.get(entry.path());
        if (cached != null) {
            return cached.orElse(null);
        }
        try {
            // State starts empty: a local page has no session behind it, so it renders its
            // own declared defaults.
            UiDocument document = PageLoader.loadDocument(pagesRoot(), entry.path(), Map.of());
            COMPILED.put(entry.path(), java.util.Optional.of(document));
            return document;
        } catch (Exception exception) {
            LOGGER.warn("[CC-LOCAL-002] local page {} failed to load: {}",
                    entry.path().getFileName(), exception.getMessage());
            COMPILED.put(entry.path(), java.util.Optional.empty());
            return null;
        }
    }

    private static List<Entry> load() {
        List<Entry> cached = entries;
        if (cached != null) {
            return cached;
        }
        List<Entry> loaded = new ArrayList<>();
        Path folder = root().resolve(FOLDER);
        if (!Files.isDirectory(folder)) {
            entries = loaded;
            return loaded;
        }
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(LocalPageRegistry::isPageFile).sorted().forEach(path -> {
                Entry entry = parse(path);
                if (entry != null) {
                    loaded.add(entry);
                }
            });
        } catch (IOException exception) {
            LOGGER.warn("[CC-LOCAL-001] cannot list local pages in {}: {}",
                    folder, exception.getMessage());
        }
        entries = loaded;
        LOGGER.info("Loaded {} client-side page(s) from {}", loaded.size(), folder.getFileName());
        return loaded;
    }

    private static boolean isPageFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    /** Reads just enough of the manifest to index it; the document is compiled on demand. */
    private static Entry parse(Path path) {
        String fileName = path.getFileName().toString();
        try {
            if (Files.size(path) > MAX_PAGE_BYTES) {
                LOGGER.warn("[CC-LOCAL-003] local page too large: {}", fileName);
                return null;
            }
            // A full @match in YAML would need the loader; the manifest keys we need are
            // top-level scalars, so a shallow scan keeps this independent of page internals.
            Map<String, String> head = readManifestHead(path);
            String surfaceName = head.get("surface");
            String match = head.get("match");
            if (surfaceName == null || match == null) {
                return null;
            }
            SurfaceType surface = surfaceOf(surfaceName);
            if (surface == null) {
                LOGGER.warn("[CC-LOCAL-004] local page {} has unknown surface '{}'",
                        fileName, surfaceName);
                return null;
            }
            Pattern matcher;
            try {
                matcher = Pattern.compile(match);
            } catch (PatternSyntaxException exception) {
                LOGGER.warn("[CC-LOCAL-005] local page {} has an invalid match: {}",
                        fileName, exception.getMessage());
                return null;
            }
            String id = head.getOrDefault("id", fileName);
            return new Entry(id, surface, matcher, path);
        } catch (IOException exception) {
            LOGGER.warn("[CC-LOCAL-006] cannot read local page {}: {}",
                    fileName, exception.getMessage());
            return null;
        }
    }

    /**
     * Pulls top-level {@code key: value} lines. Only the manifest scalars are needed, and the
     * real parse happens in {@link PageLoader}, so this stays deliberately shallow.
     */
    private static Map<String, String> readManifestHead(Path path) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        String text = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        for (String rawLine : text.split("\\R")) {
            if (rawLine.isEmpty() || rawLine.charAt(0) == '#' || rawLine.charAt(0) == ' '
                    || rawLine.charAt(0) == '\t') {
                continue;
            }
            int colon = rawLine.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = rawLine.substring(0, colon).trim();
            String value = rawLine.substring(colon + 1).trim();
            if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                    && value.charAt(value.length() - 1) == value.charAt(0)) {
                value = value.substring(1, value.length() - 1);
            }
            result.putIfAbsent(key, value);
        }
        return result;
    }

    /** Same names the page manifests use for {@code surface}. */
    private static SurfaceType surfaceOf(String name) {
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "container" -> SurfaceType.CONTAINER;
            case "boss_bar" -> SurfaceType.BOSS_BAR;
            case "inventory" -> SurfaceType.PLAYER_INVENTORY;
            case "hud" -> SurfaceType.HUD;
            case "screen", "custom_screen" -> SurfaceType.CUSTOM_SCREEN;
            case "tooltip" -> SurfaceType.TOOLTIP;
            default -> null;
        };
    }

    private static Path pagesRoot() {
        return root().resolve(FOLDER);
    }

    private static Path root() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("resourcepacks").resolve("CloudCore").toAbsolutePath().normalize();
    }
}

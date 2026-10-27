package com.cloudcore.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client settings from {@code config/cloudcore-client.json}. A launch property still wins, so
 * {@code -Dcloudcore.nanovg=true} keeps working; the file is there so a player or a launcher
 * profile can pick a backend without touching JVM arguments.
 *
 * <p>Parsing is pure (a path in, values out) and unit-tested; the game calls {@link #load}
 * once at startup, before the render backend is chosen.
 */
public record ClientConfig(String renderer, boolean debug) {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    public static final String DEFAULT_RENDERER = "native";
    private static final String DEFAULT_JSON = """
            {
              "renderer": "native",
              "debug": false
            }
            """;

    /** Requests the NanoVG backend; {@link #apply} only sets it when the launch property is absent. */
    public boolean wantsNanoVg() {
        return "nanovg".equalsIgnoreCase(renderer);
    }

    /**
     * Reads the file, writing the defaults when it is missing. A malformed file is reported and
     * replaced by the defaults in memory — a bad config must never stop the client from starting.
     */
    public static ClientConfig load(Path file) {
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, DEFAULT_JSON, StandardCharsets.UTF_8);
                LOGGER.info("Wrote default client config to {}", file);
                return defaults();
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return parse(json);
        } catch (IOException exception) {
            LOGGER.warn("[CC-CFG-001] Cannot read {}: {}; using defaults", file,
                    exception.getMessage());
            return defaults();
        }
    }

    /** Parses the JSON body; unknown values fall back instead of failing. */
    public static ClientConfig parse(String json) {
        String text = json == null ? "" : json;
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            // Editors on Windows may save UTF-8 with a BOM; Gson would reject the document.
            text = text.substring(1);
        }
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            String renderer = DEFAULT_RENDERER;
            if (root.has("renderer") && root.get("renderer").isJsonPrimitive()) {
                String value = root.get("renderer").getAsString().trim().toLowerCase();
                if (value.equals("nanovg") || value.equals("native")) {
                    renderer = value;
                } else {
                    LOGGER.warn("[CC-CFG-002] Unknown renderer '{}'; using {}", value,
                            DEFAULT_RENDERER);
                }
            }
            boolean debug = root.has("debug") && root.get("debug").isJsonPrimitive()
                    && root.get("debug").getAsBoolean();
            return new ClientConfig(renderer, debug);
        } catch (RuntimeException exception) {
            LOGGER.warn("[CC-CFG-001] Malformed client config ({}); using defaults",
                    exception.getMessage());
            return defaults();
        }
    }

    public static ClientConfig defaults() {
        return new ClientConfig(DEFAULT_RENDERER, false);
    }

    /**
     * Applies the file to the backend selector. An explicit launch property always wins so a
     * profile that sets {@code -Dcloudcore.nanovg} is not overridden by a stale file.
     */
    public void apply() {
        if (!wantsNanoVg()) {
            return;
        }
        if (System.getProperty("cloudcore.nanovg") == null) {
            System.setProperty("cloudcore.nanovg", "true");
            LOGGER.info("Client config requests the NanoVG renderer");
        }
    }
}

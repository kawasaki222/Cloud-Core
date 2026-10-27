package com.cloudcore.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientConfigTest {

    @TempDir
    Path dir;

    @Test
    void missingFileWritesDefaults() throws Exception {
        Path file = dir.resolve("cloudcore-client.json");

        ClientConfig config = ClientConfig.load(file);

        assertEquals("native", config.renderer());
        assertFalse(config.debug());
        assertTrue(Files.exists(file), "the defaults are written for the player to edit");
    }

    @Test
    void readsRendererAndDebug() {
        ClientConfig config = ClientConfig.parse("{\"renderer\": \"NanoVG\", \"debug\": true}");

        assertEquals("nanovg", config.renderer());
        assertTrue(config.wantsNanoVg());
        assertTrue(config.debug());
    }

    @Test
    void toleratesABomFromWindowsEditors() {
        ClientConfig config = ClientConfig.parse("﻿{\"renderer\": \"nanovg\"}");
        assertEquals("nanovg", config.renderer());
    }

    @Test
    void unknownRendererFallsBackToNative() {
        ClientConfig config = ClientConfig.parse("{\"renderer\": \"vulkan\"}");
        assertEquals("native", config.renderer());
    }

    @Test
    void malformedJsonFallsBackInsteadOfFailingStartup() throws Exception {
        Path file = dir.resolve("cloudcore-client.json");
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);

        ClientConfig config = ClientConfig.load(file);

        assertEquals("native", config.renderer());
        assertFalse(config.debug());
    }

    @Test
    void launchPropertyWinsOverTheFile() {
        ClientConfig config = new ClientConfig("nanovg", false);
        String previous = System.getProperty("cloudcore.nanovg");
        try {
            System.setProperty("cloudcore.nanovg", "false");
            config.apply();
            assertEquals("false", System.getProperty("cloudcore.nanovg"),
                    "an explicit launch property is not overridden");
        } finally {
            if (previous == null) {
                System.clearProperty("cloudcore.nanovg");
            } else {
                System.setProperty("cloudcore.nanovg", previous);
            }
        }
    }

    @Test
    void nanovgFileSetsTheProperty() {
        ClientConfig config = new ClientConfig("nanovg", false);
        String previous = System.getProperty("cloudcore.nanovg");
        try {
            System.clearProperty("cloudcore.nanovg");
            config.apply();
            assertEquals("true", System.getProperty("cloudcore.nanovg"));
        } finally {
            if (previous == null) {
                System.clearProperty("cloudcore.nanovg");
            } else {
                System.setProperty("cloudcore.nanovg", previous);
            }
        }
    }
}

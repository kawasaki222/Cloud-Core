package com.cloudcore.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Machine-readable results for scripted client checks, written to {@code logs/cloudcore-check.log}.
 *
 * <p>Verifying rendering by screenshotting the window and comparing pixels is slow and cannot
 * tell "one pixel off" from "correct". A scenario instead states what it expects — a page
 * composed into N lines, an element at a position, a backend in use — and records
 * {@code PASS}/{@code FAIL} lines here. The harness greps this file, so a check is a string
 * comparison rather than a screenshot the model has to interpret.
 *
 * <p>Only active when {@code -Dcloudcore.check=true}; in a normal client every call is a
 * no-op. Markers are replaced with {@code ?} so the file stays readable in any viewer.
 */
public final class CloudCheckLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final boolean ENABLED = Boolean.getBoolean("cloudcore.check");
    private static final Path FILE = Path.of("logs", "cloudcore-check.log");
    /** One entry per check name; the first result is kept so a loop cannot flood the file. */
    private static final Map<String, String> SEEN = new ConcurrentHashMap<>();
    private static int passed;
    private static int failed;

    private CloudCheckLog() {
    }

    /**
     * Records one check.
     *
     * @param name  stable identifier the harness greps for, e.g. {@code wrap.zh.lines}
     * @param ok    whether the expectation held
     * @param detail what was expected and what was observed
     */
    public static synchronized void check(String name, boolean ok, String detail) {
        if (!ENABLED) {
            return;
        }
        String verdict = ok ? "PASS" : "FAIL";
        if (SEEN.putIfAbsent(name, verdict + " " + detail) != null) {
            return;
        }
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        write(verdict + " " + name + " " + detail);
    }

    /** Records a value for the harness to compare, without judging it. */
    public static synchronized void report(String name, Object value) {
        if (!ENABLED) {
            return;
        }
        if (SEEN.putIfAbsent(name, String.valueOf(value)) != null) {
            return;
        }
        write("INFO " + name + " " + value);
    }

    /** Writes the totals; a scenario calls this once it is done. */
    public static synchronized void summary(String scenario) {
        if (!ENABLED) {
            return;
        }
        write("SUMMARY " + scenario + " passed=" + passed + " failed=" + failed
                + (failed == 0 ? " RESULT=OK" : " RESULT=FAILED"));
        LOGGER.info("Cloud check '{}' finished: passed={} failed={}", scenario, passed, failed);
    }

    /** Forgets results between scenarios in one process. */
    public static synchronized void reset() {
        SEEN.clear();
        passed = 0;
        failed = 0;
    }

    public static boolean enabled() {
        return ENABLED;
    }

    private static void write(String line) {
        String cleaned = line.replace('\u0000', '?');
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, Instant.now() + " " + cleaned + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            LOGGER.warn("Cannot write {}: {}", FILE, exception.getMessage());
        }
        LOGGER.info("[CC-CHECK] {}", cleaned);
    }
}

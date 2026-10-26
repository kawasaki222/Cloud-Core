package com.cloudcore.bukkit;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watches the pages directory and calls {@code onChange} once per quiet period.
 *
 * <p>The callback runs on the watcher thread; callers must schedule their own main-thread
 * work. Only {@code .yml}/{@code .yaml} changes count.
 */
final class PageWatcher implements AutoCloseable {
    private static final long QUIET_MS = 400;

    private final Path root;
    private final Runnable onChange;
    private final Logger logger;
    private final WatchService service;
    private final Map<WatchKey, Path> directories = new HashMap<>();
    private final Thread thread;
    private volatile boolean running = true;

    PageWatcher(Path root, Runnable onChange, Logger logger) throws IOException {
        this.root = root;
        this.onChange = onChange;
        this.logger = logger;
        this.service = FileSystems.getDefault().newWatchService();
        registerTree(root);
        this.thread = new Thread(this::loop, "cloudcore-page-watcher");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void registerTree(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        WatchKey key = directory.register(service,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        directories.put(key, directory);
        try (var children = Files.list(directory)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child)) {
                    registerTree(child);
                }
            }
        }
    }

    private void loop() {
        while (running) {
            try {
                WatchKey key = service.poll(QUIET_MS, TimeUnit.MILLISECONDS);
                boolean relevant = false;
                while (key != null) {
                    Path directory = directories.get(key);
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (isRelevant(event)) {
                            relevant = true;
                            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                                    && directory != null) {
                                Path created = directory.resolve((Path) event.context());
                                if (Files.isDirectory(created)) {
                                    registerTree(created);
                                }
                            }
                        }
                    }
                    key.reset();
                    key = service.poll();
                }
                if (relevant) {
                    // Coalesce rapid saves into one reload.
                    Thread.sleep(QUIET_MS);
                    onChange.run();
                }
            } catch (ClosedWatchServiceException exception) {
                return;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException exception) {
                logger.log(Level.WARNING, "Page watcher could not register a directory",
                        exception);
            }
        }
    }

    private static boolean isRelevant(WatchEvent<?> event) {
        if (!(event.context() instanceof Path path)) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    @Override
    public void close() {
        running = false;
        try {
            service.close();
        } catch (IOException exception) {
            logger.log(Level.FINE, "Closing page watcher failed", exception);
        }
    }
}

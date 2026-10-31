package com.cloudcore.server.surface;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters behind {@code /cloudcore stats}.
 *
 * <p>Only aggregate numbers: never a player name, a document body or an element id. The
 * server reads them on the main thread and the counters are atomic, so a diagnostics read
 * never needs a lock. Counter names are stable; {@link #snapshot()} returns them in a fixed
 * order so the command output is greppable.
 */
public final class ServerMetrics {

    /** S2C frames accepted by the transport and how many fragments they took. */
    private final AtomicLong s2cFrames = new AtomicLong();
    private final AtomicLong s2cFragments = new AtomicLong();
    private final AtomicLong s2cBytes = new AtomicLong();
    /** C2S frames accepted by the transport. */
    private final AtomicLong c2sFrames = new AtomicLong();
    private final AtomicLong c2sBytes = new AtomicLong();

    private final AtomicLong rateLimited = new AtomicLong();
    private final AtomicLong unknownElement = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong surfaceUnsupported = new AtomicLong();
    private final AtomicLong malformedFrames = new AtomicLong();

    private final AtomicLong texturesPut = new AtomicLong();
    private final AtomicLong texturesBytes = new AtomicLong();
    private final AtomicLong texturesEvicted = new AtomicLong();

    private final AtomicLong bindPre = new AtomicLong();
    private final AtomicLong bindLegacy = new AtomicLong();
    private final AtomicLong bindTimeout = new AtomicLong();

    private final AtomicLong repeatExpanded = new AtomicLong();
    private final AtomicLong refreshIncremental = new AtomicLong();
    private final AtomicLong refreshFull = new AtomicLong();

    private final AtomicLong pagesCompiled = new AtomicLong();
    private final AtomicLong pagesFailed = new AtomicLong();

    public void s2cFrame(int bytes, int fragments) {
        s2cFrames.incrementAndGet();
        s2cFragments.addAndGet(Math.max(1, fragments));
        s2cBytes.addAndGet(Math.max(0, bytes));
    }

    public void c2sFrame(int bytes) {
        c2sFrames.incrementAndGet();
        c2sBytes.addAndGet(Math.max(0, bytes));
    }

    public void rateLimited() {
        rateLimited.incrementAndGet();
    }

    public void unknownElement() {
        unknownElement.incrementAndGet();
    }

    public void rejected() {
        rejected.incrementAndGet();
    }

    public void surfaceUnsupported() {
        surfaceUnsupported.incrementAndGet();
    }

    public void malformedFrame() {
        malformedFrames.incrementAndGet();
    }

    public void texturePut(int bytes) {
        texturesPut.incrementAndGet();
        texturesBytes.addAndGet(Math.max(0, bytes));
    }

    public void textureEvicted() {
        texturesEvicted.incrementAndGet();
    }

    public void bindPre() {
        bindPre.incrementAndGet();
    }

    public void bindLegacy() {
        bindLegacy.incrementAndGet();
    }

    public void bindTimeout() {
        bindTimeout.incrementAndGet();
    }

    public void repeatExpanded(int instances) {
        repeatExpanded.addAndGet(Math.max(0, instances));
    }

    public void refresh(boolean incremental) {
        if (incremental) {
            refreshIncremental.incrementAndGet();
        } else {
            refreshFull.incrementAndGet();
        }
    }

    public void pageCompiled() {
        pagesCompiled.incrementAndGet();
    }

    public void pageFailed() {
        pagesFailed.incrementAndGet();
    }

    /** One line per counter, in a fixed order, for the {@code stats} command and logs. */
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("s2c_frames", s2cFrames.get());
        out.put("s2c_fragments", s2cFragments.get());
        out.put("s2c_bytes", s2cBytes.get());
        out.put("c2s_frames", c2sFrames.get());
        out.put("c2s_bytes", c2sBytes.get());
        out.put("rate_limited", rateLimited.get());
        out.put("unknown_element", unknownElement.get());
        out.put("rejected", rejected.get());
        out.put("surface_unsupported", surfaceUnsupported.get());
        out.put("malformed_frames", malformedFrames.get());
        out.put("textures_put", texturesPut.get());
        out.put("textures_bytes", texturesBytes.get());
        out.put("textures_evicted", texturesEvicted.get());
        out.put("bind_pre", bindPre.get());
        out.put("bind_legacy", bindLegacy.get());
        out.put("bind_timeout", bindTimeout.get());
        out.put("repeat_expanded", repeatExpanded.get());
        out.put("refresh_incremental", refreshIncremental.get());
        out.put("refresh_full", refreshFull.get());
        out.put("pages_compiled", pagesCompiled.get());
        out.put("pages_failed", pagesFailed.get());
        return out;
    }

    /** Forgets every counter; used by tests and by an operator after a change window. */
    public void reset() {
        for (AtomicLong counter : new AtomicLong[]{s2cFrames, s2cFragments, s2cBytes, c2sFrames,
                c2sBytes, rateLimited, unknownElement, rejected, surfaceUnsupported,
                malformedFrames, texturesPut, texturesBytes, texturesEvicted, bindPre, bindLegacy,
                bindTimeout, repeatExpanded, refreshIncremental, refreshFull, pagesCompiled,
                pagesFailed}) {
            counter.set(0L);
        }
    }
}

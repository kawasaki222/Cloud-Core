package com.cloudcore.client.render;

/**
 * NanoVG backend skeleton. Native library loading, NVGContext creation, fonts and FBO
 * composition are intentionally not faked here; until that spike lands this backend
 * reports unavailable and the selector uses {@link NativeBackend}.
 */
public final class NanoVgBackend implements RenderBackend {
    private final boolean available;

    public NanoVgBackend() {
        this(false);
    }

    public NanoVgBackend(boolean available) {
        this.available = available;
    }

    @Override
    public BackendId id() {
        return BackendId.NANOVG;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public void beginFrame(FrameContext context) {
        requireAvailable();
    }

    @Override
    public void draw(RenderList list, Layer layer) {
        requireAvailable();
    }

    @Override
    public void endFrame() {
        requireAvailable();
    }

    private void requireAvailable() {
        if (!available) {
            throw new IllegalStateException("NanoVG backend is not initialized");
        }
    }
}

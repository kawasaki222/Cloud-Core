package com.cloudcore.client.render;

public interface RenderBackend {

    BackendId id();

    boolean available();

    void beginFrame(FrameContext context);

    void draw(RenderList list, Layer layer);

    void endFrame();
}

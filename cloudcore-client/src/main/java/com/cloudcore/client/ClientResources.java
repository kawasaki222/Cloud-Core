package com.cloudcore.client;

import com.cloudcore.client.render.AnimatedTextures;
import com.cloudcore.client.render.LocalTextureRegistry;
import com.cloudcore.client.render.NanoVgBackend;
import com.cloudcore.client.render.RemoteTextureCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drops every cache that holds data read from the client's CloudCore folder, so a texture or
 * font replaced on disk is picked up without restarting. Called from {@code F9} and
 * {@code /cloudcore-client reloadassets}.
 *
 * <p>Client thread only: texture registration and NanoVG handles are not thread safe.
 */
public final class ClientResources {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");

    private ClientResources() {
    }

    public static void reload() {
        LocalTextureRegistry.reset();
        AnimatedTextures.reset();
        RemoteTextureCache.reload();
        NanoVgBackend.invalidateAssets();
        LOGGER.info("Local CloudCore assets reloaded (textures, GIF frames, fonts, remote cache)");
    }
}

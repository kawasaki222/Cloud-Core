package com.cloudcore.bukkit.api;

import com.cloudcore.api.page.PageLoader.CompiledPage;
import org.bukkit.entity.Player;

/**
 * Builds a page on demand for {@link CloudCoreApi#open(Player, String)}. Use it for pages
 * whose content depends on the player (a quest log, a level-filtered shop) instead of
 * shipping one YAML per player.
 *
 * <p>Called on the main thread per open; build with
 * {@link com.cloudcore.api.document.PageBuilder} and return the result.
 */
@FunctionalInterface
public interface PageProvider {

    CompiledPage build(Player player) throws Exception;
}

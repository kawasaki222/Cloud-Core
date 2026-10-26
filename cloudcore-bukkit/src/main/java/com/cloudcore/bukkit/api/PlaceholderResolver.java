package com.cloudcore.bukkit.api;

import org.bukkit.entity.Player;

/**
 * Resolves {@code %namespace_params%} tokens in page text. Register it under a namespace and
 * any page text containing {@code %rpg_level%} gets the value back.
 *
 * <p>Called on the main thread while a page is being sent or refreshed, so keep it cheap.
 * Returning {@code null} means "not mine": the token is left for PlaceholderAPI or shown
 * as written. Custom resolvers run before PlaceholderAPI.
 */
@FunctionalInterface
public interface PlaceholderResolver {

    /** @param params the token text after {@code namespace_}, possibly empty */
    String resolve(Player player, String params) throws Exception;
}

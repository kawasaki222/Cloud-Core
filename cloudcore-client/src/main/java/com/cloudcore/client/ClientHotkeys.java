package com.cloudcore.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side operations from docs/17: {@code F9} reloads the local CloudCore folder,
 * {@code F10} writes a sanitised diagnostics zip. Both are also available as
 * {@code /cloudcore-client reloadassets} and {@code /cloudcore-client dump}, so a server
 * operator can put them on a macro or a sign.
 */
public final class ClientHotkeys {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("cloudcore", "main"));

    private ClientHotkeys() {
    }

    public static void register() {
        KeyMapping reload = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.cloudcore.reload_assets", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F9, CATEGORY));
        KeyMapping dump = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.cloudcore.dump", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F10, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (reload.consumeClick()) {
                reloadAssets();
            }
            while (dump.consumeClick()) {
                writeDump();
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("cloudcore-client")
                        .then(ClientCommandManager.literal("reloadassets")
                                .executes(context -> {
                                    context.getSource().sendFeedback(
                                            Component.literal("Cloud Core: 本地资源已重载"));
                                    reloadAssets();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("dump")
                                .executes(context -> {
                                    Path out = CloudCoreClient.writeDiagnostics();
                                    context.getSource().sendFeedback(Component.literal(out == null
                                            ? "Cloud Core: 诊断包生成失败，见客户端日志"
                                            : "Cloud Core: 已生成 " + out.getFileName()));
                                    return 1;
                                }))));
    }

    private static void reloadAssets() {
        try {
            ClientResources.reload();
        } catch (RuntimeException exception) {
            LOGGER.error("[CC-ASSET-005] reloading local assets failed", exception);
        }
    }

    private static void writeDump() {
        Path out = CloudCoreClient.writeDiagnostics();
        if (out != null) {
            LOGGER.info("Diagnostics dump written: {}", out.getFileName());
        }
        // Local chat only: nothing is sent to the server, and the relative path keeps the
        // user's home directory out of the message.
        net.minecraft.client.Minecraft.getInstance().gui.getChat().addMessage(
                Component.literal(out == null
                        ? "Cloud Core: 诊断包生成失败，见客户端日志"
                        : "Cloud Core: 已生成 logs/" + out.getFileName()));
    }
}

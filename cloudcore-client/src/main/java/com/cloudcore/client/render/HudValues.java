package com.cloudcore.client.render;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Binds HUD elements to live player values, so a server-authored HUD can replace the vanilla
 * health/hunger/experience bars without a document update every tick.
 *
 * <p>{@code value_from} on a {@code progress}/{@code slider} sets {@code value} to a 0..1
 * fraction; {@code text_from} on a label sets its text. Supported sources:
 * {@code health}, {@code food}, {@code xp}, {@code air}, {@code armor}, {@code level},
 * {@code health_full}, {@code food_full}, {@code name}.
 *
 * <p>Render thread only: reads the client player and returns a rewritten document.
 */
public final class HudValues {
    private HudValues() {
    }

    /** @return a copy of the document with live values filled in (same instance when unused) */
    public static UiDocument apply(UiDocument document) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !usesLiveValues(document)) {
            return document;
        }
        List<UiElement> elements = new ArrayList<>(document.elements().size());
        for (UiElement element : document.elements()) {
            elements.add(rewrite(element, player));
        }
        return new UiDocument(document.schema(), document.id(), document.surface(),
                document.title(), document.theme(), document.width(), document.height(),
                document.scale(), document.container(), document.surfaceOptions(),
                document.fonts(), elements);
    }

    public static boolean usesLiveValues(UiDocument document) {
        for (UiElement element : document.elements()) {
            if (usesLiveValues(element)) {
                return true;
            }
        }
        return false;
    }

    private static boolean usesLiveValues(UiElement element) {
        if (element.attributes().containsKey("value_from")
                || element.attributes().containsKey("text_from")) {
            return true;
        }
        for (UiElement child : element.children()) {
            if (usesLiveValues(child)) {
                return true;
            }
        }
        return false;
    }

    private static UiElement rewrite(UiElement element, LocalPlayer player) {
        Map<String, Object> attributes = new LinkedHashMap<>(element.attributes());
        if (attributes.get("value_from") instanceof String source) {
            Double fraction = fraction(source, player);
            if (fraction != null) {
                attributes.put("value", fraction);
            }
        }
        if (attributes.get("text_from") instanceof String source) {
            String text = text(source, player);
            if (text != null) {
                attributes.put("text", text);
            }
        }
        List<UiElement> children = new ArrayList<>(element.children().size());
        for (UiElement child : element.children()) {
            children.add(rewrite(child, player));
        }
        if (children.equals(element.children()) && attributes.equals(element.attributes())) {
            return element;
        }
        return new UiElement(element.id(), element.type(), element.x(), element.y(),
                element.width(), element.height(), element.visible(), element.enabled(),
                element.z(), element.events(), children, attributes);
    }

    private static Double fraction(String source, LocalPlayer player) {
        return switch (source.toLowerCase(Locale.ROOT)) {
            case "health" -> clamp(player.getHealth() / Math.max(1.0f, player.getMaxHealth()));
            case "food" -> clamp(player.getFoodData().getFoodLevel() / 20.0);
            case "xp", "experience" -> clamp(player.experienceProgress);
            case "air", "oxygen" -> clamp(player.getAirSupply()
                    / (double) Math.max(1, player.getMaxAirSupply()));
            case "armor" -> clamp(player.getArmorValue() / 20.0);
            default -> null;
        };
    }

    private static String text(String source, LocalPlayer player) {
        return switch (source.toLowerCase(Locale.ROOT)) {
            case "health", "health_full" -> Math.max(0, (int) Math.ceil(player.getHealth()))
                    + "/" + (int) Math.ceil(player.getMaxHealth());
            case "food", "food_full" -> player.getFoodData().getFoodLevel() + "/20";
            case "level" -> String.valueOf(player.experienceLevel);
            case "xp" -> Math.round(player.experienceProgress * 100) + "%";
            case "name" -> Minecraft.getInstance().getUser().getName();
            default -> null;
        };
    }

    private static Double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}

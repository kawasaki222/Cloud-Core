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

    /** Live values, extracted so the rewrite can be tested without a client player. */
    interface Values {
        Double fraction(String source);

        String text(String source);
    }

    /** @return a copy of the document with live values filled in (same instance when unused) */
    public static UiDocument apply(UiDocument document) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !usesLiveValues(document)) {
            return document;
        }
        return apply(document, new PlayerValues(player));
    }

    /**
     * Rewrites elements whose live value changed, sharing untouched elements and attributes.
     * A HUD is rendered every frame, so the steady state (health bar full, XP unchanged)
     * must not allocate a new document.
     */
    static UiDocument apply(UiDocument document, Values values) {
        List<UiElement> original = document.elements();
        List<UiElement> elements = null;
        for (int index = 0; index < original.size(); index++) {
            UiElement rewritten = rewrite(original.get(index), values);
            if (rewritten != original.get(index)) {
                if (elements == null) {
                    elements = new ArrayList<>(original);
                }
                elements.set(index, rewritten);
            }
        }
        if (elements == null) {
            return document;
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

    private static UiElement rewrite(UiElement element, Values values) {
        List<UiElement> originalChildren = element.children();
        List<UiElement> children = null;
        for (int index = 0; index < originalChildren.size(); index++) {
            UiElement child = originalChildren.get(index);
            UiElement rewritten = rewrite(child, values);
            if (rewritten != child) {
                if (children == null) {
                    children = new ArrayList<>(originalChildren);
                }
                children.set(index, rewritten);
            }
        }

        Map<String, Object> originalAttributes = element.attributes();
        Map<String, Object> attributes = null;
        if (originalAttributes.get("value_from") instanceof String source) {
            Double fraction = values.fraction(source);
            if (fraction != null && !fraction.equals(originalAttributes.get("value"))) {
                attributes = new LinkedHashMap<>(originalAttributes);
                attributes.put("value", fraction);
            }
        }
        if (originalAttributes.get("text_from") instanceof String source) {
            String text = values.text(source);
            if (text != null && !text.equals(originalAttributes.get("text"))) {
                if (attributes == null) {
                    attributes = new LinkedHashMap<>(originalAttributes);
                }
                attributes.put("text", text);
            }
        }

        if (children == null && attributes == null) {
            return element;
        }
        return new UiElement(element.id(), element.type(), element.x(), element.y(),
                element.width(), element.height(), element.visible(), element.enabled(),
                element.z(), element.events(),
                children != null ? children : originalChildren,
                attributes != null ? attributes : originalAttributes);
    }

    private static final class PlayerValues implements Values {
        private final LocalPlayer player;

        private PlayerValues(LocalPlayer player) {
            this.player = player;
        }

        @Override
        public Double fraction(String source) {
            return HudValues.fraction(source, player);
        }

        @Override
        public String text(String source) {
            return HudValues.text(source, player);
        }
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

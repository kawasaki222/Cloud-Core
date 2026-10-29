package com.cloudcore.client.screen;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

/**
 * A vanilla {@link EditBox} that paints the caret and selection in page colours.
 *
 * <p>{@code EditBox} keeps those colours in private state and hard-codes them inside
 * {@code renderWidget}, so a mixin would be the obvious alternative. We stay out of the mixin
 * set (four mixins, each needing a compatibility review) and re-implement the widget draw here
 * instead: all editing behaviour — IME, selection, clipboard, keyboard repeat — is still the
 * vanilla superclass. Only {@code renderWidget} is replaced.
 *
 * <p>Private fields reachable to a subclass are read through cached {@link VarHandle}s:
 * {@code displayPos} (horizontal scroll), {@code highlightPos} (selection anchor) and the
 * already-computed text origin. Everything else is tracked by overriding the public setters,
 * so a future Minecraft field rename only breaks this class at startup, loudly.
 */
final class CloudEditBox extends EditBox {

    private static final VarHandle DISPLAY_POS;
    private static final VarHandle HIGHLIGHT_POS;
    private static final VarHandle TEXT_X;
    private static final VarHandle TEXT_Y;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(EditBox.class,
                    MethodHandles.lookup());
            DISPLAY_POS = lookup.findVarHandle(EditBox.class, "displayPos", int.class);
            HIGHLIGHT_POS = lookup.findVarHandle(EditBox.class, "highlightPos", int.class);
            TEXT_X = lookup.findVarHandle(EditBox.class, "textX", int.class);
            TEXT_Y = lookup.findVarHandle(EditBox.class, "textY", int.class);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private final Font font;
    private final List<EditBox.TextFormatter> formatters = new ArrayList<>();
    private boolean editable = true;
    private boolean textShadow = true;
    private boolean invertHighlightedTextColor = true;
    private int maxLength = 32;
    private int textColor = EditBox.DEFAULT_TEXT_COLOR;
    private int textColorUneditable = 0xFF707070;
    private Component hint;
    private long focusedSince = Util.getMillis();
    /** ARGB caret colour; {@code null} keeps the text colour, like vanilla. */
    private Integer cursorColor;
    /** ARGB selection fill; {@code null} keeps vanilla's highlight pipeline. */
    private Integer selectionColor;

    CloudEditBox(Font font, int x, int y, int width, int height, Component hint) {
        super(font, x, y, width, height, Component.empty());
        this.font = font;
        this.hint = hint;
        setBordered(false);
    }

    void setCursorColor(Integer cursorColor) {
        this.cursorColor = cursorColor;
    }

    void setSelectionColor(Integer selectionColor) {
        this.selectionColor = selectionColor;
    }

    @Override
    public void setTextColor(int color) {
        this.textColor = color;
        super.setTextColor(color);
    }

    @Override
    public void setTextColorUneditable(int color) {
        this.textColorUneditable = color;
        super.setTextColorUneditable(color);
    }

    @Override
    public void setEditable(boolean editable) {
        this.editable = editable;
        super.setEditable(editable);
    }

    @Override
    public void setTextShadow(boolean shadow) {
        this.textShadow = shadow;
        super.setTextShadow(shadow);
    }

    @Override
    public void setInvertHighlightedTextColor(boolean invert) {
        this.invertHighlightedTextColor = invert;
        super.setInvertHighlightedTextColor(invert);
    }

    @Override
    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
        super.setMaxLength(maxLength);
    }

    @Override
    public void setHint(Component hint) {
        this.hint = hint;
        super.setHint(hint);
    }

    @Override
    public void addFormatter(EditBox.TextFormatter formatter) {
        formatters.add(formatter);
        super.addFormatter(formatter);
    }

    @Override
    public void setFocused(boolean focused) {
        if (focused && !isFocused()) {
            focusedSince = Util.getMillis();
        }
        super.setFocused(focused);
    }

    /**
     * Mirrors {@code EditBox#renderWidget}. Keep it line-for-line with the vanilla method when
     * updating Minecraft: the caret position rules (end-of-text, scrolled text) are subtle.
     */
    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!isVisible()) {
            return;
        }
        // The document draws the border and background; a bordered EditBox sprite would
        // double-paint them.
        int color = editable ? textColor : textColorUneditable;
        int displayPos = (int) DISPLAY_POS.get(this);
        int cursorPos = getCursorPosition();
        String value = getValue();
        String visible = font.plainSubstrByWidth(value.substring(displayPos), getInnerWidth());
        boolean cursorInView = cursorPos - displayPos >= 0
                && cursorPos - displayPos <= visible.length();
        boolean caretVisible = isFocused()
                && (Util.getMillis() - focusedSince) / 300L % 2L == 0L
                && cursorInView;
        int drawX = (int) TEXT_X.get(this);
        int textY = (int) TEXT_Y.get(this);
        int highlight = Mth.clamp((int) HIGHLIGHT_POS.get(this) - displayPos, 0,
                visible.length());

        if (!visible.isEmpty()) {
            String head = cursorInView ? visible.substring(0, cursorPos - displayPos) : visible;
            FormattedCharSequence sequence = applyFormat(head, displayPos);
            graphics.drawString(font, sequence, drawX, textY, color, textShadow);
            drawX += font.width(sequence) + 1;
        }
        boolean atEnd = cursorPos >= value.length() || value.length() >= maxLength;
        int cursorX = drawX;
        if (!cursorInView) {
            cursorX = cursorPos > 0 ? (int) TEXT_X.get(this) + width : (int) TEXT_X.get(this);
        } else if (atEnd) {
            cursorX--;
            drawX--;
        }
        if (!visible.isEmpty() && cursorInView && cursorPos - displayPos < visible.length()) {
            // The tail after the caret is drawn separately so formatting offsets stay correct.
            graphics.drawString(font,
                    applyFormat(visible.substring(cursorPos - displayPos), cursorPos),
                    drawX, textY, color, textShadow);
        }
        if (hint != null && visible.isEmpty() && !isFocused()) {
            graphics.drawString(font, hint, drawX, textY, color);
        }
        if (highlight != cursorPos - displayPos) {
            int highlightX = (int) TEXT_X.get(this) + font.width(visible.substring(0, highlight));
            int right = getX() + width;
            int fromX = Math.min(cursorX, right);
            int toX = Math.min(highlightX - 1, right);
            if (selectionColor != null) {
                graphics.fill(fromX, textY - 1, toX, textY + 9, selectionColor);
            } else {
                graphics.textHighlight(fromX, textY - 1, toX, textY + 9,
                        invertHighlightedTextColor);
            }
        }
        if (caretVisible) {
            int caret = cursorColor != null ? cursorColor : color;
            if (atEnd) {
                graphics.fill(cursorX, textY - 1, cursorX + 1, textY + 9, caret);
            } else {
                // Vanilla draws an underscore at the caret while text follows it.
                graphics.drawString(font, "_", cursorX, textY, caret, textShadow);
            }
        }
        if (isHovered()) {
            graphics.requestCursor(editable ? CursorTypes.IBEAM : CursorTypes.NOT_ALLOWED);
        }
    }

    private FormattedCharSequence applyFormat(String text, int offset) {
        for (EditBox.TextFormatter formatter : formatters) {
            FormattedCharSequence formatted = formatter.format(text, offset);
            if (formatted != null) {
                return formatted;
            }
        }
        return FormattedCharSequence.forward(text, Style.EMPTY);
    }
}

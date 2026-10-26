package com.cloudcore.client.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a nine-slice image into up to nine draws: corners keep their source size, edges
 * stretch along one axis and the centre stretches on both. Borders are given in texture
 * pixels, matching {@code nine_slice: [l, t, r, b]} in the UI DSL.
 */
public final class NineSlice {
    private NineSlice() {
    }

    /** One draw: source rectangle in texture pixels and destination rectangle in GUI units. */
    public record Piece(int srcX, int srcY, int srcWidth, int srcHeight,
            int dstX, int dstY, int dstWidth, int dstHeight) {
    }

    public static List<Piece> split(int x, int y, int width, int height,
            int textureWidth, int textureHeight, int[] borders) {
        List<Piece> pieces = new ArrayList<>(9);
        if (borders == null || borders.length != 4 || textureWidth <= 0 || textureHeight <= 0) {
            pieces.add(new Piece(0, 0, textureWidth, textureHeight, x, y, width, height));
            return pieces;
        }
        int left = Math.max(0, borders[0]);
        int top = Math.max(0, borders[1]);
        int right = Math.max(0, borders[2]);
        int bottom = Math.max(0, borders[3]);
        if (left + right >= textureWidth || top + bottom >= textureHeight
                || left + right >= width || top + bottom >= height) {
            pieces.add(new Piece(0, 0, textureWidth, textureHeight, x, y, width, height));
            return pieces;
        }

        int[] srcX = {0, left, textureWidth - right};
        int[] srcW = {left, textureWidth - left - right, right};
        int[] dstX = {x, x + left, x + width - right};
        int[] dstW = {left, width - left - right, right};
        int[] srcY = {0, top, textureHeight - bottom};
        int[] srcH = {top, textureHeight - top - bottom, bottom};
        int[] dstY = {y, y + top, y + height - bottom};
        int[] dstH = {top, height - top - bottom, bottom};

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                pieces.add(new Piece(srcX[column], srcY[row], srcW[column], srcH[row],
                        dstX[column], dstY[row], dstW[column], dstH[row]));
            }
        }
        return pieces;
    }
}

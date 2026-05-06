package com.bsxu.carlyrics.ui;

import android.graphics.Bitmap;

public final class ArtworkBackdropFactory {

    private ArtworkBackdropFactory() {
    }

    public static Bitmap createBlurredBackdrop(Bitmap source) {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return null;
        }

        Bitmap scaled = scaleBitmap(source, 96);
        if (scaled == null) {
            return null;
        }

        Bitmap mutable = scaled.copy(Bitmap.Config.ARGB_8888, true);
        if (mutable == null) {
            return null;
        }

        blur(mutable, 5, 3);
        return mutable;
    }

    private static Bitmap scaleBitmap(Bitmap source, int maxEdge) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longest = Math.max(width, height);
        if (longest <= maxEdge) {
            return source;
        }

        float scale = maxEdge / (float) longest;
        int scaledWidth = Math.max(1, Math.round(width * scale));
        int scaledHeight = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true);
    }

    // A small two-pass box blur keeps the background soft without heavy GPU work.
    private static void blur(Bitmap bitmap, int radius, int iterations) {
        if (radius <= 0 || iterations <= 0) {
            return;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        int[] buffer = new int[pixels.length];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int i = 0; i < iterations; i++) {
            blurHorizontal(pixels, buffer, width, height, radius);
            blurVertical(buffer, pixels, width, height, radius);
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    private static void blurHorizontal(int[] src, int[] dst, int width, int height, int radius) {
        int windowSize = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            int aSum = 0;
            int rSum = 0;
            int gSum = 0;
            int bSum = 0;

            for (int i = -radius; i <= radius; i++) {
                int x = clamp(i, 0, width - 1);
                int color = src[rowStart + x];
                aSum += (color >>> 24);
                rSum += (color >> 16) & 0xFF;
                gSum += (color >> 8) & 0xFF;
                bSum += color & 0xFF;
            }

            for (int x = 0; x < width; x++) {
                dst[rowStart + x] = ((aSum / windowSize) << 24)
                        | ((rSum / windowSize) << 16)
                        | ((gSum / windowSize) << 8)
                        | (bSum / windowSize);

                int removeX = clamp(x - radius, 0, width - 1);
                int addX = clamp(x + radius + 1, 0, width - 1);

                int removeColor = src[rowStart + removeX];
                int addColor = src[rowStart + addX];

                aSum += (addColor >>> 24) - (removeColor >>> 24);
                rSum += ((addColor >> 16) & 0xFF) - ((removeColor >> 16) & 0xFF);
                gSum += ((addColor >> 8) & 0xFF) - ((removeColor >> 8) & 0xFF);
                bSum += (addColor & 0xFF) - (removeColor & 0xFF);
            }
        }
    }

    private static void blurVertical(int[] src, int[] dst, int width, int height, int radius) {
        int windowSize = radius * 2 + 1;
        for (int x = 0; x < width; x++) {
            int aSum = 0;
            int rSum = 0;
            int gSum = 0;
            int bSum = 0;

            for (int i = -radius; i <= radius; i++) {
                int y = clamp(i, 0, height - 1);
                int color = src[y * width + x];
                aSum += (color >>> 24);
                rSum += (color >> 16) & 0xFF;
                gSum += (color >> 8) & 0xFF;
                bSum += color & 0xFF;
            }

            for (int y = 0; y < height; y++) {
                dst[y * width + x] = ((aSum / windowSize) << 24)
                        | ((rSum / windowSize) << 16)
                        | ((gSum / windowSize) << 8)
                        | (bSum / windowSize);

                int removeY = clamp(y - radius, 0, height - 1);
                int addY = clamp(y + radius + 1, 0, height - 1);

                int removeColor = src[removeY * width + x];
                int addColor = src[addY * width + x];

                aSum += (addColor >>> 24) - (removeColor >>> 24);
                rSum += ((addColor >> 16) & 0xFF) - ((removeColor >> 16) & 0xFF);
                gSum += ((addColor >> 8) & 0xFF) - ((removeColor >> 8) & 0xFF);
                bSum += (addColor & 0xFF) - (removeColor & 0xFF);
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}


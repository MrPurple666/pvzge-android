package com.pvzge.gardendless;

final class RenderSize {
    private RenderSize() {}

    static int[] fit(int width, int height, int maxEdge) {
        if (width <= 0 || height <= 0) return new int[] {0, 0};
        int longest = Math.max(width, height);
        if (maxEdge <= 0 || longest <= maxEdge) return new int[] {width, height};
        return new int[] {
            Math.max(1, (int) ((long) width * maxEdge / longest)),
            Math.max(1, (int) ((long) height * maxEdge / longest))
        };
    }
}

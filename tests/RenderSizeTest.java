package com.pvzge.gardendless;

import java.util.Arrays;

public final class RenderSizeTest {
    public static void main(String[] args) {
        check(2400, 1080, 960, 960, 432);
        check(2400, 1080, 1280, 1280, 576);
        check(2400, 1080, 0, 2400, 1080);
        check(800, 450, 960, 800, 450);
        check(1080, 2400, 960, 432, 960);
        check(2052, 1080, 960, 960, 505);
        check(0, 0, 960, 0, 0);
        check(1, Integer.MAX_VALUE, 960, 1, 960);
        System.out.println("8 render size cases passed");
    }

    private static void check(int w, int h, int max, int expectedW, int expectedH) {
        int[] size = RenderSize.fit(w, h, max);
        if (!Arrays.equals(size, new int[] {expectedW, expectedH})) {
            throw new AssertionError(Arrays.toString(size));
        }
    }
}

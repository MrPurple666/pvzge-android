package com.pvzge.gardendless;

public final class UpdateVersionTest {
    public static void main(String[] args) {
        check("0.14.0", "0.13.9", true);
        check("0.9.99", "0.10.0", false);
        check("1.0.0", "0.99.99", true);
        check("1.99.99", "2.0.0", false);
        check("v0.14.0", "0.13.0", true);
        check("0.14", "0.14.0", false);
        check("0.14.0+build.4", "0.14.0", false);
        check("0.14.0-rc.1", "0.13.0", false);
        check("0.14.bad", "0.13.0", false);
        check("", "0.13.0", false);
        check(null, "0.13.0", false);
        check("0.14.0", null, false);
        check("0.99999999999999999999", "0.13.0", true);
        System.out.println("13 version comparison cases passed");
    }

    private static void check(String candidate, String installed, boolean expected) {
        boolean actual = UpdateVersion.isNewer(candidate, installed);
        if (actual != expected) {
            throw new AssertionError(candidate + " vs " + installed + ": " + actual);
        }
    }
}

package com.pvzge.gardendless;

import java.math.BigInteger;

final class UpdateVersion {
    private UpdateVersion() {}

    static boolean isNewer(String candidate, String installed) {
        String[] remote = parts(candidate);
        String[] local = parts(installed);
        if (remote == null || local == null) return false;
        for (int i = 0; i < Math.max(remote.length, local.length); i++) {
            BigInteger r = i < remote.length ? new BigInteger(remote[i]) : BigInteger.ZERO;
            BigInteger l = i < local.length ? new BigInteger(local[i]) : BigInteger.ZERO;
            int comparison = r.compareTo(l);
            if (comparison != 0) return comparison > 0;
        }
        return false;
    }

    private static String[] parts(String version) {
        if (version == null) return null;
        String value = version.trim().replaceFirst("^[vV]", "");
        if (!value.matches("[0-9]+(?:\\.[0-9]+)*(?:\\+[0-9A-Za-z.-]+)?")) return null;
        return value.split("\\+", 2)[0].split("\\.");
    }
}

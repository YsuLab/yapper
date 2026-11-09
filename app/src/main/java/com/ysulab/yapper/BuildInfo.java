package com.ysulab.yapper;

/** Formats the build line shown on the main screen. Pure Java, so it is unit-testable. */
public final class BuildInfo {

    private BuildInfo() {}

    public static String describe(String versionName, int versionCode, String buildType) {
        String name = (versionName == null || versionName.isEmpty()) ? "?" : versionName;
        String type = (buildType == null || buildType.isEmpty()) ? "unknown" : buildType;
        return "v" + name + " (build " + versionCode + ", " + type + ")";
    }
}

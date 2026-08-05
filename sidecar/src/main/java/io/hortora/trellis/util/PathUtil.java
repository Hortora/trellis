package io.hortora.trellis.util;

public final class PathUtil {

    private PathUtil() {}

    public static String expandTilde(String path) {
        if (path == null) return null;
        if (path.equals("~")) return System.getProperty("user.home");
        if (path.startsWith("~/")) return System.getProperty("user.home") + path.substring(1);
        return path;
    }

    public static java.nio.file.Path resolveRoot(String root) {
        return java.nio.file.Path.of(expandTilde(root)).toAbsolutePath().normalize();
    }
}

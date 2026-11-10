package dev.trakr.api.attachment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The one piece of an object key that comes from the user, made harmless (09-security §4): no directories,
 * no control characters, at most {@value #MAX} characters with the extension kept.
 */
final class FileNames {

    static final int MAX = 120;

    private FileNames() {
    }

    static String safe(String fileName) {
        // BLOCK 1: the last path segment only — "../../etc/passwd" and "C:\x\report.pdf" lose their directories
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        // BLOCK 2: no control characters (newlines in a header, NUL), no leading dots (hidden files, "..")
        name = name.replaceAll("\\p{Cntrl}", "").strip().replaceFirst("^\\.+", "");
        if (name.isEmpty()) {
            return "file";
        }
        // BLOCK 3: shorten in the middle, so "a-very-long-name….png" keeps its ".png" (code points, not chars:
        // an emoji is two chars, and cutting between them leaves half a character)
        if (name.codePointCount(0, name.length()) <= MAX) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
        String stem = name.substring(0, name.length() - extension.length());
        int keep = MAX - extension.codePointCount(0, extension.length());
        return stem.substring(0, stem.offsetByCodePoints(0, keep)) + extension;
    }

    /**
     * {@code Content-Disposition} for a download (RFC 6266): an ASCII fallback for old clients, and the real
     * name percent-encoded as UTF-8 in {@code filename*}.
     */
    static String disposition(boolean inline, String fileName) {
        String ascii = fileName.replaceAll("[^\\x20-\\x7e]", "_").replace("\"", "'");
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return (inline ? "inline" : "attachment") + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}

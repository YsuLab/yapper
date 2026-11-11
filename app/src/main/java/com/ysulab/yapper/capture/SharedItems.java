package com.ysulab.yapper.capture;

import java.util.Locale;

/**
 * Naming and limits for things shared into Yapper from another app (WhatsApp text, images,
 * timetables, PDFs). The share is stored in the capture bundle as {@code shared.txt} plus
 * {@code shared-01.jpg}, {@code shared-02.pdf} … Pure Java, testable.
 */
public final class SharedItems {

    /** Text that came with the share (message text, caption, link, subject). */
    public static final String TEXT_FILE = "shared.txt";
    /** At most this many files from one share. WhatsApp allows more; the rest is noted, not copied. */
    public static final int MAX_FILES = 10;
    /** Per file. A long video would fill the phone's private storage and the vault. */
    public static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    /** Images sent to the sorter per share (each one downscaled). */
    public static final int MAX_IMAGES_TO_SORTER = 4;
    /** PDFs sent to the sorter per share, and their size limit. */
    public static final int MAX_PDFS_TO_SORTER = 1;
    public static final long MAX_PDF_TO_SORTER_BYTES = 5L * 1024 * 1024;

    private SharedItems() {}

    /** Bundle file name of the {@code index}-th shared file (0-based): {@code shared-01.jpg}. */
    public static String fileName(int index, String mimeType, String displayName) {
        return String.format(Locale.ROOT, "shared-%02d", index + 1) + extension(mimeType, displayName);
    }

    /** Vault file name: {@code <id>-shared-01.jpg}, unique across captures. */
    public static String vaultName(String captureId, String bundleName) {
        return captureId + "-" + bundleName;
    }

    /** ".jpg", ".pdf" … from the display name if it has a sane one, else from the MIME type. */
    static String extension(String mimeType, String displayName) {
        if (displayName != null) {
            int dot = displayName.lastIndexOf('.');
            if (dot > 0 && dot < displayName.length() - 1) {
                String ext = displayName.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (ext.matches("[a-z0-9]{1,5}")) {
                    return "." + (ext.equals("jpeg") ? "jpg" : ext);
                }
            }
        }
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT).trim();
        switch (mime) {
            case "image/jpeg":
            case "image/jpg": return ".jpg";
            case "image/png": return ".png";
            case "image/webp": return ".webp";
            case "image/gif": return ".gif";
            case "image/heic": return ".heic";
            case "image/heif": return ".heif";
            case "application/pdf": return ".pdf";
            case "text/plain": return ".txt";
            case "text/x-vcard":
            case "text/vcard": return ".vcf";
            case "audio/ogg":
            case "audio/opus": return ".ogg";
            case "audio/mpeg": return ".mp3";
            case "audio/mp4":
            case "audio/aac": return ".m4a";
            case "video/mp4": return ".mp4";
            default: return ".bin";
        }
    }

    /** Images Obsidian shows inline and a vision model can read. */
    public static boolean isImage(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
                || n.endsWith(".gif") || n.endsWith(".heic") || n.endsWith(".heif");
    }

    public static boolean isPdf(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    /**
     * The text part of a share. Subject first when it adds something (e-mail subject, page title),
     * then the text. Trimmed; never null.
     */
    public static String text(String subject, String text) {
        String s = subject == null ? "" : subject.trim();
        String t = text == null ? "" : text.trim();
        if (s.isEmpty() || t.contains(s)) {
            return t;
        }
        return t.isEmpty() ? s : s + "\n\n" + t;
    }

    /**
     * Package name of the app that shared, from {@code Activity.getReferrer()} ("android-app://com.whatsapp"
     * or "android-app://com.whatsapp/…"). Web referrers and junk give "".
     */
    public static String sourceApp(String referrer) {
        if (referrer == null || !referrer.startsWith("android-app://")) {
            return "";
        }
        String rest = referrer.substring("android-app://".length());
        int slash = rest.indexOf('/');
        String pkg = slash >= 0 ? rest.substring(0, slash) : rest;
        return pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") ? pkg : "";
    }

    /** "1 image", "2 images · text", "text", "1 file" — for the sheet's readout. */
    public static String describe(int images, int otherFiles, boolean hasText) {
        StringBuilder sb = new StringBuilder();
        if (images > 0) {
            sb.append(images).append(images == 1 ? " image" : " images");
        }
        if (otherFiles > 0) {
            sb.append(sb.length() == 0 ? "" : " · ").append(otherFiles).append(otherFiles == 1 ? " file" : " files");
        }
        if (hasText) {
            sb.append(sb.length() == 0 ? "" : " · ").append("text");
        }
        return sb.length() == 0 ? "nothing" : sb.toString();
    }
}

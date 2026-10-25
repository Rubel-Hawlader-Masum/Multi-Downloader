package com.downloadhub.app;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UrlRules {

    private UrlRules() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty() || raw.trim().length() > 8192) {
            throw new IllegalArgumentException("লিংক খালি বা খুব বড়।");
        }

        try {
            URI uri = new URI(raw.trim());

            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isEmpty()) {
                throw new IllegalArgumentException("সম্পূর্ণ https:// লিংক দিন। এই সংস্করণে HTTP সমর্থিত নয়।");
            }

            if (uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("লিংকের মধ্যে লগইন বা পাসওয়ার্ড গ্রহণ করা হবে না।");
            }

            if (uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("পোর্ট নম্বর সঠিক নয়।");
            }

            String value = uri.toASCIIString();
            int hash = value.indexOf('#');

            return hash >= 0 ? value.substring(0, hash) : value;

        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("লিংকের গঠন সঠিক নয়।", e);
        }
    }

    public static String extract(String text) {
        if (text == null || text.length() > 32768) {
            return null;
        }

        Matcher matcher = Pattern.compile(
                "https://[^\\s<>\\\"]+",
                Pattern.CASE_INSENSITIVE
        ).matcher(text);

        if (matcher.find()) {
            try {
                return normalize(matcher.group());
            } catch (IllegalArgumentException ignored) {
            }
        }

        return null;
    }

    public static String safeName(String raw) {
        String name = raw == null ? "" : raw;

        name = name
                .replaceAll("[\\p{Cntrl}\\p{Cf}/\\\\:*?\"<>|]", "_")
                .trim()
                .replaceAll("^\\.+", "");

        if (name.isEmpty()) {
            name = "download.bin";
        }

        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot < 12 ? name.substring(dot) : "";
        String stem = extension.isEmpty() ? name : name.substring(0, dot);

        if (stem.length() + extension.length() > 100) {
            int cut = 100 - extension.length();

            if (cut > 0 && Character.isHighSurrogate(stem.charAt(cut - 1))) {
                cut--;
            }

            stem = stem.substring(0, cut);
        }

        while ((stem + extension).getBytes(StandardCharsets.UTF_8).length > 180 && !stem.isEmpty()) {
            stem = stem.substring(0, stem.offsetByCodePoints(stem.length(), -1));
        }

        return stem + extension;
    }

    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String nameFromUrl(String url) {
        try {
            String path = new URI(url).getRawPath();

            if (path == null || path.endsWith("/")) {
                return "download.bin";
            }

            String last = path.substring(path.lastIndexOf('/') + 1);

            return safeName(decode(last.replace("+", "%2B")));

        } catch (Exception e) {
            return "download.bin";
        }
    }

    public static String nameFromHeaders(String header, String url) {
        if (header != null) {
            Matcher extended = Pattern.compile(
                    "filename\\*\\s*=\\s*UTF-8''([^;]+)",
                    Pattern.CASE_INSENSITIVE
            ).matcher(header);

            if (extended.find()) {
                try {
                    return safeName(decode(extended.group(1).trim().replace("+", "%2B")));
                } catch (IllegalArgumentException ignored) {
                }
            }

            Matcher simple = Pattern.compile(
                    "filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))",
                    Pattern.CASE_INSENSITIVE
            ).matcher(header);

            if (simple.find()) {
                return safeName(simple.group(1) != null ? simple.group(1) : simple.group(2));
            }
        }

        return nameFromUrl(url);
    }

    public static String category(String name, String mime) {
        String fileName = name.toLowerCase(Locale.ROOT);
        String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT);

        if (fileName.endsWith(".apk")) {
            return "অ্যাপ";
        }

        if (type.startsWith("video/") || fileName.matches(".*\\.(mp4|webm|mkv|mov)$")) {
            return "ভিডিও";
        }

        if (type.startsWith("audio/") || fileName.matches(".*\\.(mp3|m4a|ogg|wav|flac)$")) {
            return "অডিও";
        }

        if (fileName.matches(".*\\.(zip|rar|7z|tar|gz)$")) {
            return "আর্কাইভ";
        }

        if (fileName.matches(".*\\.(pdf|docx?|xlsx?|pptx?|txt|csv)$")) {
            return "ডকুমেন্ট";
        }

        if (type.startsWith("image/") || fileName.matches(".*\\.(png|jpe?g|gif|webp|svg)$")) {
            return "ছবি";
        }

        return "অন্যান্য";
    }
}

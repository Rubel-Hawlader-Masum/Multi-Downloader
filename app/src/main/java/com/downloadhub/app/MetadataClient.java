package com.downloadhub.app;

import java.net.HttpURLConnection;
import java.net.URL;

public final class MetadataClient {

    public static final class Info {
        public final String url;
        public final String name;
        public final String mime;
        public final long bytes;
        public final String note;

        public Info(String url, String name, String mime, long bytes, String note) {
            this.url = url;
            this.name = name;
            this.mime = mime;
            this.bytes = bytes;
            this.note = note;
        }
    }

    public Info inspect(String raw) throws Exception {
        String next = UrlRules.normalize(raw);

        for (int redirects = 0; redirects <= 5; redirects++) {
            HttpURLConnection connection =
                    (HttpURLConnection) new URL(next).openConnection();

            connection.setRequestMethod("HEAD");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("User-Agent", "DownloadHub/0.1");

            try {
                int code = connection.getResponseCode();

                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");

                    if (location == null) {
                        throw new IllegalArgumentException("Redirect ঠিকানা পাওয়া যায়নি।");
                    }

                    next = UrlRules.normalize(new URL(new URL(next), location).toString());
                    continue;
                }

                if (code == 405 || code == 501) {
                    return new Info(
                            raw,
                            UrlRules.nameFromUrl(next),
                            "",
                            -1,
                            "সার্ভার HEAD অনুরোধ সমর্থন করে না; আকার ও ধরন অজানা।"
                    );
                }

                if (code < 200 || code >= 300) {
                    throw new IllegalArgumentException("সার্ভারের উত্তর: HTTP " + code);
                }

                String mime = connection.getContentType();

                if (mime == null) {
                    mime = "";
                }

                return new Info(
                        raw,
                        UrlRules.nameFromHeaders(
                                connection.getHeaderField("Content-Disposition"),
                                next
                        ),
                        mime.split(";")[0].trim(),
                        connection.getContentLengthLong(),
                        "HEAD তথ্য; ডাউনলোডের সময় বদলাতে পারে।"
                );

            } finally {
                connection.disconnect();
            }
        }

        throw new IllegalArgumentException("অতিরিক্ত Redirect; লিংকটি গ্রহণ করা হয়নি।");
    }
}

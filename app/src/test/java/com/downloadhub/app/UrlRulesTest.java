package com.downloadhub.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class UrlRulesTest {

    @Test
    public void acceptsHttps() {
        assertEquals(
                "https://example.com/file.pdf",
                UrlRules.normalize(" https://example.com/file.pdf ")
        );
    }

    @Test
    public void removesFragment() {
        assertEquals(
                "https://example.com/a",
                UrlRules.normalize("https://example.com/a#part")
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsHttp() {
        UrlRules.normalize("http://example.com/a");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsScript() {
        UrlRules.normalize("javascript:alert(1)");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsFile() {
        UrlRules.normalize("file:///sdcard/a");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsCredentials() {
        UrlRules.normalize("https://user:password@example.com/a");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingHost() {
        UrlRules.normalize("https:///a");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBadPort() {
        UrlRules.normalize("https://example.com:99999/a");
    }

    @Test
    public void extractsSharedText() {
        assertEquals(
                "https://example.com/a.mp4",
                UrlRules.extract("ভিডিও দেখুন https://example.com/a.mp4")
        );
    }

    @Test
    public void rejectsNonUrlShare() {
        assertNull(UrlRules.extract("একটি সাধারণ লেখা"));
    }

    @Test
    public void blocksPathTraversal() {
        String name = UrlRules.safeName("../../unsafe.apk");

        assertFalse(name.contains("/"));
        assertFalse(name.contains("\\"));
        assertFalse(name.startsWith("."));
    }

    @Test
    public void preventsControlCharacters() {
        assertFalse(UrlRules.safeName("a\nb.pdf").contains("\n"));
    }

    @Test
    public void preventsBidiSpoofing() {
        assertFalse(UrlRules.safeName("photo\u202Egnp.apk").contains("\u202E"));
    }

    @Test
    public void limitsNameLength() {
        assertTrue(UrlRules.safeName("a".repeat(200) + ".pdf").length() <= 100);
    }

    @Test
    public void unicodeNamesFitByteBudget() {
        String name = UrlRules.safeName("বাংলা".repeat(100) + ".pdf");

        assertTrue(name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 180);
        assertTrue(name.endsWith(".pdf"));
    }

    @Test
    public void preservesPlusInPath() {
        assertEquals(
                "a+b.pdf",
                UrlRules.nameFromUrl("https://example.com/a+b.pdf")
        );
    }

    @Test
    public void decodesHeaders() {
        assertEquals(
                "report one.pdf",
                UrlRules.nameFromHeaders(
                        "attachment; filename*=UTF-8''report%20one.pdf",
                        "https://example.com/a"
                )
        );
    }

    @Test
    public void plainHeader() {
        assertEquals(
                "test.pdf",
                UrlRules.nameFromHeaders(
                        "attachment; filename=\"test.pdf\"",
                        "https://example.com/a"
                )
        );
    }

    @Test
    public void categoryChecks() {
        assertEquals("অ্যাপ", UrlRules.category("test.apk", "application/octet-stream"));
        assertEquals("ভিডিও", UrlRules.category("test.mp4", ""));
        assertEquals("ছবি", UrlRules.category("image.png", ""));
        assertEquals("অন্যান্য", UrlRules.category("data.bin", ""));
    }
}

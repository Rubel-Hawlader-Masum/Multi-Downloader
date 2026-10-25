package com.downloadhub.app;

import org.json.JSONException;
import org.json.JSONObject;

public final class DownloadRecord {

    public final long id;
    public final String url;
    public final String name;
    public final String mime;

    public DownloadRecord(long id, String url, String name, String mime) {
        this.id = id;
        this.url = url;
        this.name = name;
        this.mime = mime;
    }

    public JSONObject json() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("url", url)
                .put("name", name)
                .put("mime", mime);
    }

    public static DownloadRecord from(JSONObject object) throws JSONException {
        return new DownloadRecord(
                object.getLong("id"),
                object.getString("url"),
                object.getString("name"),
                object.optString("mime", "")
        );
    }
}

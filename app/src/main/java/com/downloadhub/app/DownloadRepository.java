package com.downloadhub.app;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DownloadRepository {

    public static final class State {
        public final DownloadRecord record;

        public int status = 0;
        public int reason = 0;
        public long bytes = 0;
        public long total = -1;

        public State(DownloadRecord record) {
            this.record = record;
        }
    }

    private final DownloadManager manager;
    private final RecordStore store;

    public DownloadRepository(Context context) {
        manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        store = new RecordStore(context);
    }

    public DownloadRecord enqueue(
            String raw,
            String proposedName,
            String mime,
            boolean wifiOnly
    ) {
        String url = UrlRules.normalize(raw);
        String name = UrlRules.safeName(proposedName);

        int dot = name.lastIndexOf('.');
        String suffix = "_" + UUID.randomUUID().toString().substring(0, 8);

        name = dot > 0
                ? name.substring(0, dot) + suffix + name.substring(dot)
                : name + suffix;

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setDescription("DownloadHub • " + Uri.parse(url).getHost())
                .setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        "DownloadHub/" + name
                )
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setAllowedOverMetered(!wifiOnly)
                .setAllowedOverRoaming(false);

        if (wifiOnly) {
            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI);
        }

        if (mime != null && !mime.trim().isEmpty()) {
            request.setMimeType(mime);
        }

        long id = manager.enqueue(request);
        DownloadRecord record = new DownloadRecord(id, url, name, mime == null ? "" : mime);

        try {
            store.add(record);
        } catch (RuntimeException e) {
            manager.remove(id);
            throw e;
        }

        return record;
    }

    public List<State> states() {
        List<State> states = new ArrayList<>();

        for (DownloadRecord record : store.all()) {
            State state = new State(record);

            try (Cursor cursor = manager.query(
                    new DownloadManager.Query().setFilterById(record.id)
            )) {
                if (cursor != null && cursor.moveToFirst()) {
                    state.status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    );

                    state.reason = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                    );

                    state.bytes = cursor.getLong(
                            cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                            )
                    );

                    state.total = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    );
                }
            }

            states.add(state);
        }

        return states;
    }

    public Uri uri(long id) {
        return manager.getUriForDownloadedFile(id);
    }

    public void remove(long id) {
        manager.remove(id);
        store.remove(id);
    }

    public void forget(long id) {
        store.remove(id);
    }
}

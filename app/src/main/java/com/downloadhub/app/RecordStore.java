package com.downloadhub.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public final class RecordStore {

    private final SharedPreferences preferences;

    public RecordStore(Context context) {
        preferences = context.getSharedPreferences("download_records", Context.MODE_PRIVATE);
    }

    public synchronized List<DownloadRecord> all() {
        List<DownloadRecord> records = new ArrayList<>();

        try {
            JSONArray array = new JSONArray(preferences.getString("items", "[]"));

            for (int index = 0; index < array.length(); index++) {
                records.add(DownloadRecord.from(array.getJSONObject(index)));
            }

        } catch (JSONException e) {
            throw new IllegalStateException("ডাউনলোড তালিকা পড়া যায়নি। অ্যাপের তথ্য মুছবেন না।", e);
        }

        return records;
    }

    public synchronized void add(DownloadRecord record) {
        List<DownloadRecord> records = all();
        records.add(0, record);
        save(records);
    }

    public synchronized void remove(long id) {
        List<DownloadRecord> records = all();
        records.removeIf(record -> record.id == id);
        save(records);
    }

    private void save(List<DownloadRecord> records) {
        JSONArray array = new JSONArray();

        try {
            for (DownloadRecord record : records) {
                array.put(record.json());
            }
        } catch (JSONException e) {
            throw new IllegalStateException("ডাউনলোড তালিকা তৈরি করা যায়নি।", e);
        }

        if (!preferences.edit().putString("items", array.toString()).commit()) {
            throw new IllegalStateException("ডাউনলোড তালিকা সংরক্ষণ করা যায়নি।");
        }
    }
}

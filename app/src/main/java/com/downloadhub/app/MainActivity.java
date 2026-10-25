package com.downloadhub.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {

    private static final int BG = 0xff0d1424;
    private static final int PANEL = 0xff192438;
    private static final int TEXT = 0xfff2f6ff;
    private static final int MUTED = 0xffb6c4da;
    private static final int ACCENT = 0xff48dbc0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService network = Executors.newSingleThreadExecutor();

    private final Map<Long, long[]> samples = new HashMap<>();
    private final Map<Long, Long> speeds = new HashMap<>();

    private List<DownloadRepository.State> snapshot = new ArrayList<>();

    private DownloadRepository repository;
    private SharedPreferences settings;

    private LinearLayout root;
    private LinearLayout content;
    private LinearLayout listBox;
    private ScrollView scroll;

    private EditText linkInput;
    private TextView metadataLabel;

    private MetadataClient.Info preview;
    private Future<?> inspection;

    private String draft = "";
    private String lastClipboard = "";
    private String category = "সব";

    private int tab = 0;
    private int metadataVersion = 0;

    private boolean resumed = false;
    private boolean querying = false;

    private final Runnable metadataTask = this::inspectLink;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();

            if (resumed) {
                handler.postDelayed(this, 1500);
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        settings = getSharedPreferences("settings", MODE_PRIVATE);
        repository = new DownloadRepository(this);

        if (savedInstanceState != null) {
            draft = savedInstanceState.getString("draft", "");
            tab = savedInstanceState.getInt("tab", 0);
        }

        showPage();
        receive(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receive(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("draft", draft);
        outState.putInt("tab", tab);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        metadataVersion++;
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        network.shutdownNow();
        super.onDestroy();
    }

    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }

    private Button button(String title, Runnable action) {
        Button view = new Button(this);
        view.setText(title);
        view.setAllCaps(false);
        view.setTextSize(14);
        view.setTextColor(BG);
        view.setMinHeight(dp(48));
        view.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private EditText field(String hint) {
        EditText view = new EditText(this);
        view.setTextColor(TEXT);
        view.setHintTextColor(MUTED);
        view.setHint(hint);
        view.setTextSize(16);
        view.setSingleLine(true);
        view.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_URI
        );
        view.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        view.setMinHeight(dp(56));
        return view;
    }

    private void showPage() {
        handler.removeCallbacks(metadataTask);
        metadataVersion++;

        linkInput = null;
        metadataLabel = null;
        listBox = null;

        root = column();
        root.setBackgroundColor(BG);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars()
                                | WindowInsets.Type.displayCutout()
                                | WindowInsets.Type.ime()
                );

                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);

            } else {
                view.setPadding(
                        insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom()
                );
            }

            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();

        LinearLayout heading = new LinearLayout(this);
        heading.setPadding(dp(18), dp(8), dp(12), dp(8));
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView brand = text("DownloadHub", 24, TEXT);
        brand.setTypeface(null, Typeface.BOLD);

        heading.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(button("সেটিংস", () -> {
            tab = 3;
            showPage();
        }));

        root.addView(heading);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        content = column();
        content.setPadding(dp(18), dp(8), dp(18), dp(20));
        scroll.addView(content);

        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        if (tab == 0) {
            home();
        } else if (tab == 1) {
            downloads();
        } else if (tab == 2) {
            browser();
        } else {
            preferences();
        }

        HorizontalScrollView navigationScroll = new HorizontalScrollView(this);
        navigationScroll.setHorizontalScrollBarEnabled(false);
        navigationScroll.setFillViewport(true);

        LinearLayout navigation = new LinearLayout(this);
        navigation.setPadding(dp(6), dp(4), dp(6), dp(4));

        String[] labels = {"হোম", "ডাউনলোড", "ব্রাউজার", "সেটিংস"};

        for (int index = 0; index < labels.length; index++) {
            final int destination = index;

            Button item = button(labels[index], () -> {
                tab = destination;
                showPage();
            });

            if (index != tab) {
                item.setTextColor(TEXT);
                item.setBackgroundTintList(ColorStateList.valueOf(PANEL));
            }

            navigation.addView(item, new LinearLayout.LayoutParams(dp(100), dp(52)));
        }

        navigationScroll.addView(navigation);
        root.addView(navigationScroll);
    }

    private void home() {
        content.addView(text("আপনার লিংক, আপনার ডাউনলোড", 20, TEXT));
        content.addView(text("অনুমোদিত সরাসরি HTTPS ফাইল লিংক দিন।", 14, MUTED));

        linkInput = field("https://… সরাসরি ফাইল লিংক");
        linkInput.setContentDescription("ডাউনলোডের HTTPS লিংক");
        linkInput.setText(draft);
        content.addView(linkInput);

        linkInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void afterTextChanged(Editable s) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                draft = s.toString();
                preview = null;
                metadataVersion++;

                handler.removeCallbacks(metadataTask);

                if (metadataLabel != null) {
                    metadataLabel.setText("ফাইলের তথ্য এখনো যাচাই করা হয়নি।");
                }

                if (draft.trim().startsWith("https://")) {
                    handler.postDelayed(metadataTask, 700);
                }
            }
        });

        LinearLayout actions = new LinearLayout(this);

        actions.addView(button("পেস্ট", this::paste), new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(button("তথ্য দেখুন", this::inspectLink), new LinearLayout.LayoutParams(0, -2, 1));

        content.addView(actions);

        metadataLabel = text("ফাইলের তথ্য জানতে ‘তথ্য দেখুন’ চাপুন।", 14, MUTED);
        content.addView(metadataLabel);

        content.addView(button("ডাউনলোড করুন", this::requestDownload));
        content.addView(text("চলমান ও অসম্পূর্ণ ডাউনলোড", 18, TEXT));

        listBox = column();
        content.addView(listBox);

        renderList();
    }

    private void downloads() {
        content.addView(text("ফাইল ও ডাউনলোড তালিকা", 20, TEXT));
        content.addView(text("সংরক্ষণ: Downloads/DownloadHub • নিজের ডাউনলোডগুলো দেখানো হয়।", 14, MUTED));

        HorizontalScrollView categoryScroll = new HorizontalScrollView(this);
        categoryScroll.setHorizontalScrollBarEnabled(false);

        LinearLayout categories = new LinearLayout(this);

        for (String item : new String[]{
                "সব", "ভিডিও", "অডিও", "ডকুমেন্ট", "ছবি", "আর্কাইভ", "অ্যাপ", "অন্যান্য"
        }) {
            Button button = button(item, () -> {
                category = item;
                showPage();
            });

            if (!category.equals(item)) {
                button.setTextColor(TEXT);
                button.setBackgroundTintList(ColorStateList.valueOf(PANEL));
            }

            categories.addView(button);
        }

        categoryScroll.addView(categories);
        content.addView(categoryScroll);

        listBox = column();
        content.addView(listBox);

        renderList();
    }

    private void browser() {
        content.addView(text("বাহ্যিক ব্রাউজার", 20, TEXT));
        content.addView(text(
                "এই সংস্করণে অ্যাপের ভেতরে ব্রাউজার নেই। ফোনের ব্রাউজারে পেজ খুলে সরাসরি ফাইল লিংক Share → DownloadHub করুন।",
                16,
                MUTED
        ));

        EditText site = field("https://… ওয়েবসাইট");
        content.addView(site);

        content.addView(button("ফোনের ব্রাউজারে খুলুন", () -> {
            try {
                startActivity(new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(UrlRules.normalize(site.getText().toString()))
                ).addCategory(Intent.CATEGORY_BROWSABLE));

            } catch (RuntimeException e) {
                toast("লিংক বা ফোনের ব্রাউজার পরীক্ষা করুন।");
            }
        }));

        content.addView(text(
                "YouTube/Facebook পেজ থেকে ভিডিও বের করা, লগইন বা DRM পাশ কাটানো হয় না।",
                15,
                MUTED
        ));
    }

    @SuppressWarnings("deprecation")
    private void preferences() {
        content.addView(text("আপনার নিয়ন্ত্রণে", 20, TEXT));

        Switch wifi = new Switch(this);
        wifi.setText("নতুন ডাউনলোড শুধু Wi-Fi দিয়ে");
        wifi.setTextColor(TEXT);
        wifi.setMinHeight(dp(56));
        wifi.setChecked(settings.getBoolean("wifi", false));

        wifi.setOnCheckedChangeListener((buttonView, isChecked) ->
                settings.edit().putBoolean("wifi", isChecked).apply());

        content.addView(wifi);

        content.addView(text(
                "পরিবর্তনটি শুধু পরের ডাউনলোডে প্রযোজ্য। Roaming বন্ধ রাখা হয়েছে।",
                14,
                MUTED
        ));

        Switch clipboard = new Switch(this);
        clipboard.setText("অ্যাপ সামনে এলে ক্লিপবোর্ড লিংকের পরামর্শ");
        clipboard.setTextColor(TEXT);
        clipboard.setMinHeight(dp(64));
        clipboard.setChecked(settings.getBoolean("clipboard", false));

        clipboard.setOnCheckedChangeListener((buttonView, isChecked) ->
                settings.edit().putBoolean("clipboard", isChecked).apply());

        content.addView(clipboard);

        content.addView(text(
                "শুরুতে বন্ধ। চালু করলে শুধু অ্যাপ সামনে ও ফোকাসে থাকা অবস্থায় পড়বে। নিজে থেকে কোনো ডাউনলোড শুরু হবে না।",
                14,
                MUTED
        ));

        content.addView(text("গোপনীয়তা ও সীমাবদ্ধতা", 19, TEXT));

        content.addView(text(
                "• কোনো অ্যাকাউন্ট, বিজ্ঞাপন বা analytics SDK যোগ করা হয়নি।\n"
                        + "• লিংক ও ডাউনলোড তালিকা ফোনে সংরক্ষিত থাকে।\n"
                        + "• signed লিংকে গোপন token থাকতে পারে।\n"
                        + "• ফাইলের সার্ভার ও Redirect সার্ভার আপনার IP এবং অনুরোধ দেখতে পারে।\n"
                        + "• তালিকার cloud backup বন্ধ।\n"
                        + "• এই অ্যাপ ভাইরাস পরীক্ষা করে না।\n"
                        + "• HTTP, Torrent, manual Pause/Resume, rename, priority বা concurrency control নেই।\n"
                        + "• ব্যর্থ ডাউনলোড আবার চালালে শুরু থেকে নামবে।",
                15,
                MUTED
        ));

        content.addView(text("DownloadHub 0.1.0 • প্রথম ধাপের সোর্স সংস্করণ", 14, ACCENT));
    }

    private void setLink(String link) {
        draft = link;
        preview = null;

        if (tab != 0) {
            tab = 0;
            showPage();
        }

        if (linkInput != null) {
            linkInput.setText(link);
        }
    }

    private void paste() {
        try {
            ClipboardManager manager =
                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

            if (!manager.hasPrimaryClip() || manager.getPrimaryClip() == null) {
                toast("ক্লিপবোর্ড খালি।");
                return;
            }

            String link = UrlRules.extract(
                    manager.getPrimaryClip().getItemAt(0).coerceToText(this).toString()
            );

            if (link == null) {
                toast("ক্লিপবোর্ডে HTTPS লিংক পাওয়া যায়নি।");
            } else {
                setLink(link);
            }

        } catch (RuntimeException e) {
            toast("ক্লিপবোর্ড পড়া যায়নি; ঘরে চেপে ধরে Paste করুন।");
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (!hasFocus || settings == null || !settings.getBoolean("clipboard", false)) {
            return;
        }

        try {
            ClipboardManager manager =
                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

            if (!manager.hasPrimaryClip() || manager.getPrimaryClip() == null) {
                return;
            }

            String link = UrlRules.extract(
                    manager.getPrimaryClip().getItemAt(0).coerceToText(this).toString()
            );

            if (link == null || link.equals(lastClipboard) || link.equals(draft)) {
                return;
            }

            lastClipboard = link;

            new AlertDialog.Builder(this)
                    .setTitle("কপি করা লিংক পাওয়া গেছে")
                    .setMessage("হোস্ট: " + Uri.parse(link).getHost()
                            + "\nডাউনলোডের ঘরে বসাবেন?")
                    .setPositiveButton("বসান", (dialog, which) -> setLink(link))
                    .setNegativeButton("না", null)
                    .show();

        } catch (RuntimeException ignored) {
        }
    }

    private void receive(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            return;
        }

        String link = UrlRules.extract(intent.getStringExtra(Intent.EXTRA_TEXT));

        intent.setAction(null);
        intent.removeExtra(Intent.EXTRA_TEXT);

        if (link == null) {
            toast("শেয়ার করা লেখায় HTTPS লিংক পাওয়া যায়নি।");
        } else {
            setLink(link);
        }
    }

    private void inspectLink() {
        final String url;

        try {
            url = UrlRules.normalize(draft);
        } catch (IllegalArgumentException e) {
            if (metadataLabel != null) {
                metadataLabel.setText(e.getMessage());
            }

            return;
        }

        final int version = ++metadataVersion;

        if (metadataLabel != null) {
            metadataLabel.setText("সার্ভার থেকে ফাইলের তথ্য দেখা হচ্ছে…");
        }

        if (inspection != null) {
            inspection.cancel(true);
        }

        inspection = network.submit(() -> {
            try {
                MetadataClient.Info info = new MetadataClient().inspect(url);

                handler.post(() -> {
                    if (!alive() || version != metadataVersion) {
                        return;
                    }

                    preview = info;

                    if (metadataLabel != null) {
                        metadataLabel.setText(
                                info.name + "\n"
                                        + size(info.bytes) + " • "
                                        + (info.mime.isEmpty() ? "ধরন অজানা" : info.mime)
                                        + "\n" + info.note
                        );
                    }
                });

            } catch (Exception e) {
                handler.post(() -> {
                    if (alive() && version == metadataVersion && metadataLabel != null) {
                        metadataLabel.setText(
                                "তথ্য পাওয়া যায়নি: " + friendly(e)
                                        + "\nআকার অজানা অবস্থায় চেষ্টা করা যাবে।"
                        );
                    }
                });
            }
        });
    }

    private void requestDownload() {
        final String url;

        try {
            url = UrlRules.normalize(draft);
        } catch (IllegalArgumentException e) {
            toast(e.getMessage());
            return;
        }

        MetadataClient.Info info =
                preview != null && preview.url.equals(url) ? preview : null;

        if (info != null && (info.mime.equalsIgnoreCase("text/html")
                || info.mime.equalsIgnoreCase("application/xhtml+xml"))) {
            toast("এটি ওয়েবপেজ। সরাসরি ফাইল বা ভিডিওর লিংক দিন।");
            return;
        }

        final String name = info == null
                ? UrlRules.nameFromUrl(url)
                : info.name;

        final String mime = info == null ? "" : info.mime;

        new AlertDialog.Builder(this)
                .setTitle("ডাউনলোড শুরু করবেন?")
                .setMessage(
                        "ফাইল: " + name + "\n"
                                + "সার্ভার: " + Uri.parse(url).getHost() + "\n"
                                + "আকার: " + size(info == null ? -1 : info.bytes) + "\n"
                                + (info == null
                                ? "ফাইলের ধরন যাচাই হয়নি; ওয়েবপেজও হতে পারে।\n"
                                : "")
                                + "শুধু অনুমোদিত ফাইল নামান। ফাইল নিরাপদ কি না যাচাই হয়নি।"
                )
                .setNegativeButton("বাতিল", null)
                .setPositiveButton("শুরু করুন", (dialog, which) ->
                        enqueue(url, name, mime, null))
                .show();
    }

    private void enqueue(
            String url,
            String name,
            String mime,
            DownloadRecord oldRecord
    ) {
        boolean wifiOnly = settings.getBoolean("wifi", false);

        io.execute(() -> {
            try {
                repository.enqueue(url, name, mime, wifiOnly);

                boolean removed = true;

                if (oldRecord != null) {
                    try {
                        repository.remove(oldRecord.id);
                    } catch (RuntimeException e) {
                        removed = false;
                    }
                }

                final boolean clean = removed;

                handler.post(() -> {
                    if (alive()) {
                        toast(clean
                                ? "ডাউনলোড তালিকায় যোগ হয়েছে।"
                                : "নতুন ডাউনলোড যোগ হয়েছে; পুরোনো রেকর্ড আলাদা করে মুছুন।");

                        refresh();
                    }
                });

            } catch (RuntimeException e) {
                handler.post(() -> {
                    if (alive()) {
                        toast("শুরু করা যায়নি: " + friendly(e));
                    }
                });
            }
        });
    }

    private void refresh() {
        if (querying || !alive() || io.isShutdown()) {
            return;
        }

        querying = true;

        io.execute(() -> {
            try {
                List<DownloadRepository.State> result = repository.states();

                handler.post(() -> {
                    querying = false;

                    if (!alive()) {
                        return;
                    }

                    long now = SystemClock.elapsedRealtime();

                    for (DownloadRepository.State state : result) {
                        long[] previous = samples.put(
                                state.record.id,
                                new long[]{now, state.bytes}
                        );

                        long speed = 0;

                        if (previous != null
                                && now > previous[0]
                                && state.status == DownloadManager.STATUS_RUNNING
                                && state.bytes >= previous[1]) {
                            speed = (state.bytes - previous[1]) * 1000 / (now - previous[0]);
                        }

                        speeds.put(state.record.id, speed);
                    }

                    snapshot = result;
                    renderList();
                });

            } catch (RuntimeException e) {
                handler.post(() -> {
                    querying = false;

                    if (alive()) {
                        toast(friendly(e));
                    }
                });
            }
        });
    }

    private void renderList() {
        if (listBox == null) {
            return;
        }

        int oldScroll = scroll.getScrollY();
        listBox.removeAllViews();

        int count = 0;

        for (DownloadRepository.State state : snapshot) {
            if (tab == 0 && state.status == DownloadManager.STATUS_SUCCESSFUL) {
                continue;
            }

            if (tab == 1 && !category.equals("সব") && !category.equals(
                    UrlRules.category(state.record.name, state.record.mime)
            )) {
                continue;
            }

            count++;

            LinearLayout card = column();
            card.setPadding(dp(14), dp(12), dp(14), dp(12));

            GradientDrawable background = new GradientDrawable();
            background.setColor(PANEL);
            background.setCornerRadius(dp(18));
            card.setBackground(background);

            LinearLayout.LayoutParams cardLayout =
                    new LinearLayout.LayoutParams(-1, -2);
            cardLayout.setMargins(0, dp(8), 0, dp(8));

            listBox.addView(card, cardLayout);

            TextView title = text(state.record.name, 16, TEXT);
            title.setTypeface(null, Typeface.BOLD);
            card.addView(title);

            card.addView(text(
                    UrlRules.category(state.record.name, state.record.mime)
                            + " • " + status(state),
                    13,
                    ACCENT
            ));

            long speed = speeds.getOrDefault(state.record.id, 0L);
            String details = size(state.bytes) + " / " + size(state.total);

            if (state.status == DownloadManager.STATUS_RUNNING) {
                details += " • " + size(speed) + "/s";

                if (speed > 0 && state.total > state.bytes) {
                    details += " • বাকি ~" + ((state.total - state.bytes) / speed) + " সেকেন্ড";
                }
            }

            card.addView(text(details, 13, MUTED));

            if (state.status == DownloadManager.STATUS_RUNNING
                    || state.status == DownloadManager.STATUS_PENDING
                    || state.status == DownloadManager.STATUS_PAUSED) {

                ProgressBar progress = new ProgressBar(
                        this,
                        null,
                        android.R.attr.progressBarStyleHorizontal
                );

                progress.setMax(1000);
                progress.setIndeterminate(state.total <= 0);

                if (state.total > 0) {
                    progress.setProgress(
                            (int) Math.min(1000, state.bytes * 1000.0 / state.total)
                    );
                }

                progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
                progress.setContentDescription("ডাউনলোডের অগ্রগতি");

                card.addView(progress);
            }

            if (state.status == DownloadManager.STATUS_SUCCESSFUL) {
                card.addView(text(
                        "Downloads/DownloadHub/" + state.record.name,
                        12,
                        MUTED
                ));

                LinearLayout actions = new LinearLayout(this);

                actions.addView(
                        button("খুলুন", () -> openFile(state.record, false)),
                        new LinearLayout.LayoutParams(0, -2, 1)
                );

                actions.addView(
                        button("শেয়ার", () -> openFile(state.record, true)),
                        new LinearLayout.LayoutParams(0, -2, 1)
                );

                card.addView(actions);

            } else if (state.status == DownloadManager.STATUS_FAILED) {
                card.addView(button("শুরু থেকে আবার চেষ্টা", () ->
                        new AlertDialog.Builder(this)
                                .setTitle("আবার নামাবেন?")
                                .setMessage(
                                        "আগের অগ্রগতি রাখা হবে না। লিংকটি এখনো কার্যকর থাকতে হবে।"
                                )
                                .setPositiveButton("আবার শুরু", (dialog, which) ->
                                        enqueue(
                                                state.record.url,
                                                UrlRules.nameFromUrl(state.record.url),
                                                state.record.mime,
                                                state.record
                                        ))
                                .setNegativeButton("বাতিল", null)
                                .show()));
            }

            card.addView(button(
                    state.status == 0
                            ? "শুধু তালিকা থেকে সরান"
                            : state.status == DownloadManager.STATUS_SUCCESSFUL
                            ? "ফাইল ও রেকর্ড মুছুন"
                            : "বাতিল ও মুছুন",
                    () -> confirmRemove(state)
            ));
        }

        if (count == 0) {
            listBox.addView(text(
                    tab == 0
                            ? "এখন কোনো চলমান বা অসম্পূর্ণ ডাউনলোড নেই।"
                            : "এই বিভাগে কোনো ফাইল নেই।",
                    15,
                    MUTED
            ));
        }

        scroll.post(() -> {
            if (alive()) {
                scroll.scrollTo(0, oldScroll);
            }
        });
    }

    private String status(DownloadRepository.State state) {
        switch (state.status) {
            case DownloadManager.STATUS_PENDING:
                return "অপেক্ষায়";

            case DownloadManager.STATUS_RUNNING:
                return "ডাউনলোড চলছে";

            case DownloadManager.STATUS_SUCCESSFUL:
                return "সম্পন্ন";

            case DownloadManager.STATUS_FAILED:
                return "ব্যর্থ • " + reason(state.reason);

            case DownloadManager.STATUS_PAUSED:
                return "সিস্টেমের অপেক্ষায় • " + reason(state.reason);

            default:
                return "সিস্টেমে রেকর্ড নেই; ফাইল থাকতে পারে";
        }
    }

    private String reason(int code) {
        if (code >= 400 && code <= 599) {
            return "HTTP " + code;
        }

        switch (code) {
            case DownloadManager.PAUSED_WAITING_FOR_NETWORK:
                return "নেটওয়ার্ক নেই";

            case DownloadManager.PAUSED_QUEUED_FOR_WIFI:
                return "Wi-Fi প্রয়োজন";

            case DownloadManager.PAUSED_WAITING_TO_RETRY:
                return "আবার চেষ্টা করবে";

            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "স্টোরেজে জায়গা কম";

            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "একই নামের ফাইল আছে";

            case DownloadManager.ERROR_CANNOT_RESUME:
                return "সার্ভার Resume করতে দেয়নি";

            default:
                return "সিস্টেম কোড " + code;
        }
    }

    private void confirmRemove(DownloadRepository.State state) {
        new AlertDialog.Builder(this)
                .setTitle(state.status == 0
                        ? "তালিকা থেকে সরাবেন?"
                        : "মুছে ফেলবেন?")
                .setMessage(state.status == 0
                        ? "শুধু অ্যাপের রেকর্ড সরবে। Downloads-এ ফাইল থাকলে সেটি থাকবে।"
                        : "ডাউনলোড বাতিল হবে এবং এই ডাউনলোডের ফাইলও স্থায়ীভাবে মুছে যেতে পারে।\n"
                        + state.record.name)
                .setNegativeButton("না", null)
                .setPositiveButton("হ্যাঁ, সরান", (dialog, which) -> io.execute(() -> {
                    try {
                        if (state.status == 0) {
                            repository.forget(state.record.id);
                        } else {
                            repository.remove(state.record.id);
                        }

                        handler.post(() -> {
                            if (alive()) {
                                refresh();
                            }
                        });

                    } catch (RuntimeException e) {
                        handler.post(() -> {
                            if (alive()) {
                                toast("মুছতে সমস্যা: " + friendly(e));
                            }
                        });
                    }
                }))
                .show();
    }

    private void openFile(DownloadRecord record, boolean share) {
        try {
            Uri uri = repository.uri(record.id);

            if (uri == null) {
                toast("ফাইল পাওয়া যায়নি; ফোনের Files অ্যাপে দেখুন।");
                return;
            }

            String mime = getContentResolver().getType(uri);

            if (mime == null || mime.trim().isEmpty()) {
                mime = record.mime.isEmpty()
                        ? "application/octet-stream"
                        : record.mime;
            }

            Intent intent = share
                    ? new Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    : new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime);

            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("DownloadHub file", uri));

            startActivity(Intent.createChooser(
                    intent,
                    share ? "ফাইল শেয়ার" : "ফাইল খুলুন"
            ));

        } catch (RuntimeException e) {
            toast("ফাইল নেই বা খোলার উপযুক্ত অ্যাপ পাওয়া যায়নি।");
        }
    }

    private static String size(long bytes) {
        if (bytes < 0) {
            return "অজানা";
        }

        if (bytes < 1024) {
            return bytes + " B";
        }

        if (bytes < 1024L * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }

        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        }

        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String friendly(Exception e) {
        // Network exception messages may contain signed URLs; do not show them.
        return e instanceof IllegalArgumentException
                || e instanceof IllegalStateException
                ? e.getMessage() == null ? "অজানা সমস্যা" : e.getMessage()
                : "সংযোগ বা সার্ভার পরীক্ষা করুন।";
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}

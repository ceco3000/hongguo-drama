package io.github.jackotom.hongguotv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(245, 243, 238), INK = Color.rgb(36, 45, 50), MUTED = Color.rgb(88, 101, 107);
    private static final int ACCENT = Color.rgb(49, 93, 82), FOCUS = Color.rgb(220, 234, 227);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newFixedThreadPool(2);
    private final CatalogClient client = new CatalogClient();
    private HistoryStore history;
    private LinearLayout body;
    private EditText searchInput;
    private Future<?> pending;
    private int generation;
    private String screen = "home", homeTab = "browse", query = "", selectedSeries = "";
    private CatalogClient.Page cachedPage;
    private CatalogClient.Detail detail;
    private int pageNumber = 1, episodeIndex = -1, savedPosition, duration;
    private boolean autoplay = true, prepared, isPlaying, foreground, completed, playWhenReady, playerNeedsRestore, seeking;
    private String currentUrl = "", playbackError = "";
    private long controlsUntil;
    private VideoView video;
    private LinearLayout controls;
    private TextView playerStatus, timeLabel;
    private Button toggle;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        history = new HistoryStore(this);
        autoplay = getPreferences(MODE_PRIVATE).getBoolean("autoplay", true);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (saved != null) { query = saved.getString("query", ""); selectedSeries = saved.getString("series", ""); }
        loadPage(false, 1);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(4), 0, dp(4)); return view;
    }
    private GradientDrawable shape(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(10));
        drawable.setStroke(dp(stroke == 0 ? 1 : 3), stroke == 0 ? Color.rgb(216, 220, 214) : stroke); return drawable;
    }
    private StateListDrawable focusBackground() {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_pressed}, shape(FOCUS, ACCENT));
        drawable.addState(new int[]{android.R.attr.state_focused}, shape(FOCUS, ACCENT));
        drawable.addState(new int[]{}, shape(Color.WHITE, 0)); return drawable;
    }
    private Button button(String label, View.OnClickListener action) {
        Button view = new Button(this); view.setId(View.generateViewId()); view.setText(label); view.setTextSize(18);
        view.setTextColor(INK); view.setAllCaps(false); view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48));
        view.setPadding(dp(16), dp(6), dp(16), dp(6)); view.setBackground(focusBackground());
        view.setOnClickListener(action); view.setOnFocusChangeListener((v, focus) -> { if (focus && "player".equals(screen)) controlsUntil = System.currentTimeMillis() + 7000; });
        return view;
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private void addButton(LinearLayout row, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        params.setMargins(0, 0, dp(10), 0); row.addView(button, params);
    }
    private LinearLayout base(String title) {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setPadding(dp(36), dp(20), dp(36), dp(20));
        TextView heading = text(title, 30, INK); heading.setTypeface(null, Typeface.BOLD); root.addView(heading);
        setContentView(root); return root;
    }
    private void homeShell() {
        screen = "home";
        LinearLayout root = base("红果短剧");
        LinearLayout nav = row();
        addButton(nav, button("剧集", v -> { homeTab = "browse"; query = ""; cachedPage = null; loadPage(false, 1); }));
        addButton(nav, button("观看记录", v -> showHistory()));
        addButton(nav, button("使用说明", v -> help()));
        TextView badge = text("TV 测试版 1.0", 16, MUTED); nav.addView(badge); root.addView(nav);
        LinearLayout search = row(); search.setPadding(0, dp(14), 0, dp(10));
        searchInput = new EditText(this); searchInput.setSingleLine(true); searchInput.setTextSize(20); searchInput.setTextColor(INK);
        searchInput.setHintTextColor(MUTED); searchInput.setHint("输入剧名或关键词"); searchInput.setText(query);
        searchInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT); searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setId(View.generateViewId()); searchInput.setBackground(focusBackground()); searchInput.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, dp(50), 1); inputParams.setMargins(0, 0, dp(12), 0);
        search.addView(searchInput, inputParams); addButton(search, button("搜索", v -> search())); root.addView(search);
        searchInput.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) { search(); return true; }
            return false;
        });
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(text("方向键选择 · 确定打开 · 返回上一级", 16, MUTED));
        nav.getChildAt(0).requestFocus();
    }
    private void search() {
        String term = searchInput.getText().toString().trim();
        if (term.isEmpty()) { searchInput.setError("请输入剧名或关键词"); searchInput.requestFocus(); return; }
        query = term; homeTab = "search"; cachedPage = null;
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        loadPage(true, 1);
    }
    private void loadPage(boolean searching, int page) {
        stopPlayback(); homeTab = searching ? "search" : "browse"; pageNumber = page; homeShell();
        loading(searching ? "正在搜索…" : "正在加载剧集…");
        String requestedQuery = query;
        task(() -> searching ? client.search(requestedQuery, page) : client.browse(page), result -> {
            cachedPage = result; renderPage(result, searching);
        }, () -> loadPage(searching, page));
    }
    private void renderPage(CatalogClient.Page result, boolean searching) {
        body.removeAllViews();
        body.addView(text(searching ? "搜索：" + query : "短剧目录 · 第 " + result.page + " 页", 20, INK));
        if (result.items.isEmpty()) { body.addView(text("没有找到剧集，换个关键词试试。", 20, MUTED)); return; }
        GridLayout grid = grid(3); ScrollView scroll = scroll(grid); body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        View first = null, previous = null;
        for (CatalogClient.Series series : result.items) {
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(16), dp(10), dp(16), dp(10)); card.setBackground(focusBackground()); card.setFocusable(true); card.setClickable(true); card.setId(View.generateViewId());
            TextView name = text(series.title, 21, INK); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); card.addView(name);
            card.addView(text(series.episodeCount > 0 ? series.episodeCount + " 集 · 查看可看范围" : "查看分集", 16, MUTED));
            card.setContentDescription(series.title + "，打开分集列表"); card.setOnClickListener(v -> openSeries(series.id));
            cell(grid, card, 3, 112); if (first == null) first = card; if (series.id.equals(selectedSeries)) previous = card;
        }
        LinearLayout paging = row();
        if (result.page > 1) addButton(paging, button("上一页", v -> loadPage(searching, result.page - 1)));
        if (result.hasMore) addButton(paging, button("下一页", v -> loadPage(searching, result.page + 1)));
        if (searching) paging.addView(text("来源当前返回的搜索结果", 16, MUTED));
        body.addView(paging);
        View focus = previous == null ? first : previous; if (focus != null) focus.requestFocus();
    }
    private GridLayout grid(int columns) { GridLayout grid = new GridLayout(this); grid.setColumnCount(columns); return grid; }
    private ScrollView scroll(View content) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.setClipToPadding(false);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2)); return scroll;
    }
    private void cell(GridLayout grid, View child, int columns, int height) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams(); params.width = 0; params.height = dp(height);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); params.setMargins(dp(4), dp(5), dp(4), dp(5)); grid.addView(child, params);
    }
    private void showHistory() {
        cancelTask(); stopPlayback(); homeTab = "history"; homeShell(); body.removeAllViews();
        body.addView(text("继续观看", 22, INK)); List<HistoryStore.Entry> entries = history.all();
        if (entries.isEmpty()) { body.addView(text("播放后，进度会保存在这台电视上。", 20, MUTED)); return; }
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); View first = null;
        for (HistoryStore.Entry entry : entries) {
            Button item = button(entry.title + "   第 " + entry.episodeNumber + " 集 · " + clock(entry.position), v -> openSeries(entry.seriesId));
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); item.setMaxLines(2); item.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(72)); params.bottomMargin = dp(10); list.addView(item, params);
            if (first == null) first = item;
        }
        body.addView(scroll(list), new LinearLayout.LayoutParams(-1, 0, 1)); if (first != null) first.requestFocus();
    }
    private void returnHome() {
        cancelTask(); stopPlayback(); detail = null;
        if ("history".equals(homeTab)) showHistory();
        else if (cachedPage != null) { homeShell(); renderPage(cachedPage, "search".equals(homeTab)); }
        else loadPage("search".equals(homeTab), pageNumber);
    }
    private void openSeries(String id) {
        selectedSeries = id; cancelTask(); stopPlayback(); detail = null; screen = "detail";
        LinearLayout root = base("分集列表"); addButton(root, button("返回", v -> returnHome()));
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        loading("正在读取分集…");
        task(() -> client.detail(id), result -> { detail = result; renderDetail(); }, () -> openSeries(id));
    }
    private void renderDetail() {
        screen = "detail"; LinearLayout root = base(detail.series.title);
        LinearLayout nav = row(); addButton(nav, button("返回", v -> returnHome()));
        HistoryStore.Entry entry = history.get(detail.series.id);
        int resume = findEpisode(entry == null ? "" : entry.episodeId);
        int available = 0; for (CatalogClient.Episode episode : detail.episodes) if (episode.available) available++;
        if (resume >= 0 && detail.episodes.get(resume).available) {
            final int index = resume;
            addButton(nav, button("继续第 " + detail.episodes.get(index).number + " 集", v -> play(index, true)));
        }
        CheckBox auto = new CheckBox(this); auto.setText("自动连播"); auto.setTextSize(18); auto.setTextColor(INK); auto.setMinHeight(dp(48)); auto.setChecked(autoplay);
        auto.setOnCheckedChangeListener((v, checked) -> { autoplay = checked; getPreferences(MODE_PRIVATE).edit().putBoolean("autoplay", checked).apply(); }); nav.addView(auto); root.addView(nav);
        root.addView(text("共 " + detail.episodes.size() + " 集 · 来源当前公开可看 " + available + " 集", 18, MUTED));
        GridLayout episodes = grid(6); View chosen = null;
        for (int i = 0; i < detail.episodes.size(); i++) {
            final int index = i; CatalogClient.Episode episode = detail.episodes.get(i);
            Button item = button("第 " + episode.number + " 集" + (episode.available ? "" : "\n暂不可看"), v -> {
                if (episode.available) play(index, true); else new AlertDialog.Builder(this).setMessage("来源尚未公开这集，请到来源平台确认可看范围。").setPositiveButton("知道了", null).show();
            });
            item.setTextSize(17); if (!episode.available) item.setTextColor(MUTED); cell(episodes, item, 6, 72);
            if (i == Math.max(0, resume)) chosen = item;
        }
        root.addView(scroll(episodes), new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(text("公开可看范围可能变化。受限集数不会自动跳过。", 16, MUTED));
        if (nav.getChildCount() > 2) nav.getChildAt(1).requestFocus(); else if (chosen != null) chosen.requestFocus();
    }
    private int findEpisode(String id) {
        if (detail == null) return -1;
        for (int i = 0; i < detail.episodes.size(); i++) if (detail.episodes.get(i).id.equals(id)) return i; return -1;
    }
    private void play(int index, boolean resume) {
        if (detail == null || index < 0 || index >= detail.episodes.size()) return;
        if (!detail.episodes.get(index).available) { message("下一集尚未公开，已停止连播。"); return; }
        cancelTask(); stopPlayback(); episodeIndex = index; savedPosition = 0; duration = 0; completed = false; playWhenReady = true; playbackError = "";
        CatalogClient.Episode episode = detail.episodes.get(index);
        HistoryStore.Entry old = history.get(detail.series.id);
        if (resume && old != null && old.episodeId.equals(episode.id)) savedPosition = PlaybackState.resumePosition(old.position, old.duration);
        screen = "player"; playerShell();
        String sid = detail.series.id;
        task(() -> client.playUrl(sid, episode.id), url -> startVideo(url), () -> play(index, true));
    }
    private void playerShell() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this); video.setFocusable(false); video.setFocusableInTouchMode(false);
        final VideoView playerView = video;
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.setOnPreparedListener(player -> {
            if (video != playerView || !"player".equals(screen)) return;
            prepared = true; duration = video.getDuration(); savedPosition = PlaybackState.resumePosition(savedPosition, duration);
            player.setOnSeekCompleteListener(mp -> { if (video == playerView) seeking = false; });
            if (savedPosition > 0) { seeking = true; video.seekTo(savedPosition); }
            player.setOnInfoListener((mp, what, extra) -> {
                if (video != playerView || playerStatus == null) return false;
                if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START) playerStatus.setText("正在缓冲…");
                if (what == android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END || what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) playerStatus.setText("");
                return false;
            });
            if (foreground && playWhenReady) { video.start(); isPlaying = true; }
            playerStatus.setText(""); showControls(); saveProgress();
        });
        video.setOnCompletionListener(player -> {
            if (video != playerView || !"player".equals(screen)) return;
            completed = true; seeking = false; isPlaying = false; playWhenReady = false; savedPosition = 0; saveProgress();
            int next = PlaybackState.nextEpisode(episodeIndex, detail.episodes.size(), autoplay);
            if (next >= 0 && detail.episodes.get(next).available) play(next, false);
            else { showControls(); playerStatus.setText(next >= 0 ? "下一集尚未公开，已停止连播。" : "本集播放结束。"); updateTime(); }
        });
        video.setOnErrorListener((player, what, extra) -> {
            if (video != playerView || !"player".equals(screen)) return true;
            isPlaying = false; playWhenReady = false; prepared = false; showPlayerError("播放失败（" + what + "/" + extra + "）。可重试或返回选集。"); return true;
        });
        playerStatus = text("正在获取播放地址…", 20, Color.WHITE); playerStatus.setGravity(Gravity.CENTER); playerStatus.setPadding(dp(48), dp(16), dp(48), dp(16));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); statusParams.topMargin = dp(30); root.addView(playerStatus, statusParams);
        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); controls.setBackgroundColor(0xED202826); controls.setPadding(dp(30), dp(10), dp(30), dp(18));
        TextView title = text(detail.series.title + " · 第 " + detail.episodes.get(episodeIndex).number + " 集", 22, Color.WHITE);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END); controls.addView(title);
        timeLabel = text("准备播放", 16, 0xFFDCEAE3); controls.addView(timeLabel);
        LinearLayout actions = row();
        addPlayerButton(actions, button("选集", v -> leavePlayer()));
        addPlayerButton(actions, button("上一集", v -> adjacent(-1)));
        addPlayerButton(actions, button("后退10秒", v -> seek(-10000)));
        toggle = button("暂停", v -> togglePlayback()); addPlayerButton(actions, toggle);
        addPlayerButton(actions, button("前进10秒", v -> seek(10000)));
        addPlayerButton(actions, button("下一集", v -> adjacent(1)));
        addPlayerButton(actions, button("重试", v -> play(episodeIndex, true)));
        controls.addView(actions); controls.addView(text("隐藏时：左右快进退 · 确定暂停/继续 · 上下显示菜单 · 返回选集", 14, 0xFFDCEAE3));
        root.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root); showControls(); handler.removeCallbacks(ticker); handler.postDelayed(ticker, 1000);
    }
    private void addPlayerButton(LinearLayout row, Button item) {
        item.setTextSize(16); item.setPadding(dp(4), dp(4), dp(4), dp(4)); item.setMinWidth(0); item.setMinimumWidth(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1); params.setMargins(0, 0, dp(6), 0); row.addView(item, params);
    }
    private void startVideo(String url) {
        if (url == null || !url.startsWith("https://")) { showPlayerError("来源没有提供可用播放地址，请返回选集或稍后重试。"); return; }
        currentUrl = url;
        if (!foreground || video == null) return;
        try {
            Map<String, String> headers = new HashMap<>(); headers.put("User-Agent", CatalogClient.USER_AGENT); headers.put("Referer", CatalogClient.REFERER);
            video.setVideoURI(Uri.parse(url), headers);
        } catch (Exception error) { showPlayerError("无法打开播放器，请重试。"); }
    }
    private void togglePlayback() {
        if (!prepared || video == null) return;
        if (video.isPlaying()) { video.pause(); isPlaying = false; saveProgress(); }
        else { if (completed) { savedPosition = 0; seeking = true; video.seekTo(0); completed = false; } video.start(); isPlaying = true; }
        playWhenReady = isPlaying;
        showControls(); updateTime();
    }
    private void seek(int delta) {
        if (!prepared || video == null) return;
        savedPosition = PlaybackState.seekPosition(seeking ? savedPosition : video.getCurrentPosition(), delta, video.getDuration()); seeking = true; video.seekTo(savedPosition);
        completed = false; showControls(); updateTime();
    }
    private void adjacent(int delta) {
        int next = episodeIndex + delta;
        if (next < 0) { message("已经是第一集。"); return; }
        if (detail == null || next >= detail.episodes.size()) { message("已经是最后一集。"); return; }
        if (!detail.episodes.get(next).available) { message("这集尚未公开，请返回选集查看可看范围。"); return; }
        play(next, false);
    }
    private void showControls() {
        if (controls == null) return; boolean wasHidden = controls.getVisibility() != View.VISIBLE;
        controls.setVisibility(View.VISIBLE); controlsUntil = System.currentTimeMillis() + 7000;
        if (toggle != null && (wasHidden || getCurrentFocus() == null)) toggle.requestFocus();
    }
    private void updateTime() {
        if (timeLabel == null) return;
        int position = prepared && video != null && !completed && !seeking ? video.getCurrentPosition() : savedPosition;
        timeLabel.setText(clock(position) + " / " + clock(duration) + (autoplay ? "   自动连播已开启" : "   自动连播已关闭"));
        if (toggle != null) toggle.setText(isPlaying ? "暂停" : "播放");
    }
    private static String clock(int millis) {
        int seconds = Math.max(0, millis) / 1000; return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
    private int ticks;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!"player".equals(screen) || !foreground) return;
            if (prepared && video != null) { isPlaying = video.isPlaying(); updateTime(); if (++ticks % 5 == 0) saveProgress(); }
            if (prepared && isPlaying && System.currentTimeMillis() > controlsUntil) controls.setVisibility(View.GONE);
            handler.postDelayed(this, 1000);
        }
    };
    private void saveProgress() {
        if (detail == null || episodeIndex < 0 || episodeIndex >= detail.episodes.size() || !prepared) return;
        if (video != null) { duration = Math.max(0, video.getDuration()); if (!seeking) savedPosition = completed ? 0 : Math.max(0, video.getCurrentPosition()); }
        CatalogClient.Episode episode = detail.episodes.get(episodeIndex);
        history.save(new HistoryStore.Entry(detail.series.id, detail.series.title, episode.id, episode.number, savedPosition, duration));
    }
    private void stopPlayback() {
        handler.removeCallbacks(ticker); saveProgress();
        if (video != null) { video.stopPlayback(); video = null; }
        prepared = false; seeking = false; isPlaying = false; controls = null; playerStatus = null; toggle = null; timeLabel = null;
        playerNeedsRestore = false; currentUrl = "";
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void leavePlayer() { cancelTask(); stopPlayback(); if (detail != null) renderDetail(); else returnHome(); }
    private void message(String message) {
        if ("player".equals(screen) && playerStatus != null) { playerStatus.setText(message); showControls(); }
        else new AlertDialog.Builder(this).setMessage(message).setPositiveButton("知道了", null).show();
    }
    private void showPlayerError(String message) { playbackError = message; if (playerStatus != null) playerStatus.setText(message); showControls(); }
    private void loading(String message) { body.removeAllViews(); body.addView(text(message, 20, MUTED)); }
    private interface Result<T> { void accept(T value); }
    private <T> void task(Callable<T> work, Result<T> success, Runnable retry) {
        cancelTask(); final int token = generation;
        pending = network.submit(() -> {
            try {
                T result = work.call(); handler.post(() -> { if (token == generation && !isFinishing()) success.accept(result); });
            } catch (Exception error) {
                handler.post(() -> {
                    if (token != generation || isFinishing()) return;
                    String message = error.getMessage(); if (message == null || message.length() > 160) message = "加载失败，请检查网络后重试。";
                    if ("player".equals(screen)) showPlayerError(message);
                    else { body.removeAllViews(); body.addView(text(message, 20, MUTED)); Button again = button("重试", v -> retry.run()); body.addView(again); again.requestFocus(); }
                });
            }
        });
    }
    private void cancelTask() { generation++; if (pending != null) pending.cancel(true); pending = null; }
    private void help() {
        String system = "Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + "\n设备：" + Build.MANUFACTURER + " " + Build.MODEL;
        new AlertDialog.Builder(this).setTitle("红果短剧 TV · 测试版 1.0")
            .setMessage("方向键移动焦点，确定打开，返回上一级。\n播放中左右快进退，上下显示菜单。\n观看记录仅保存在本机，不登录、不上传。\n\n内容来自红果短剧公开网页，部分剧集仅开放试看。来源变更可能导致加载失败。\n\n" + system + "\n\n反馈时请附这个页面和具体错误。")
            .setPositiveButton("知道了", null).show();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if ("player".equals(screen) && event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY || key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (event.getRepeatCount() == 0 && (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || (key == KeyEvent.KEYCODE_MEDIA_PLAY && !isPlaying) || (key == KeyEvent.KEYCODE_MEDIA_PAUSE && isPlaying))) togglePlayback();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) { seek(10000); return true; }
            if (key == KeyEvent.KEYCODE_MEDIA_REWIND) { seek(-10000); return true; }
            if (controls != null && controls.getVisibility() != View.VISIBLE) {
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) { seek(key == KeyEvent.KEYCODE_DPAD_LEFT ? -10000 : 10000); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) { if (event.getRepeatCount() == 0) togglePlayback(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_MENU) { showControls(); return true; }
            }
            controlsUntil = System.currentTimeMillis() + 7000;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() {
        if ("player".equals(screen)) leavePlayer();
        else if ("detail".equals(screen)) returnHome();
        else if (!"browse".equals(homeTab)) { homeTab = "browse"; query = ""; cachedPage = null; loadPage(false, 1); }
        else new AlertDialog.Builder(this).setMessage("退出红果短剧？").setNegativeButton("继续看", null).setPositiveButton("退出", (d, w) -> finish()).show();
    }
    @Override protected void onPause() {
        foreground = false; handler.removeCallbacks(ticker); saveProgress();
        if ("player".equals(screen)) {
            if (prepared && video != null) playWhenReady = video.isPlaying();
            // Release before Surface teardown so its zero position cannot overwrite the saved progress.
            VideoView previous = video; video = null; prepared = false; seeking = false; isPlaying = false;
            if (previous != null) previous.stopPlayback();
            playerNeedsRestore = true; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume(); foreground = true;
        if ("player".equals(screen)) {
            if (playerNeedsRestore) {
                playerNeedsRestore = false; playerShell();
                if (!playbackError.isEmpty()) showPlayerError(playbackError);
                else if (!currentUrl.isEmpty()) startVideo(currentUrl);
            }
            handler.removeCallbacks(ticker); handler.post(ticker);
        }
    }
    @Override protected void onSaveInstanceState(Bundle out) { saveProgress(); out.putString("query", query); out.putString("series", selectedSeries); super.onSaveInstanceState(out); }
    @Override protected void onDestroy() { cancelTask(); stopPlayback(); network.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}

package io.github.jackotom.hongguotv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads only the source website's public HTML and explicitly linked preview videos. */
public final class CatalogClient {
    public static final String REFERER = "https://hongguoduanju.com/";
    public static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36";
    private static final String SITE = "https://hongguoduanju.com";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Pattern ROUTER = Pattern.compile("(?:window\\.)?_ROUTER_DATA\\s*=\\s*(\\{)");
    private static final Pattern LD = Pattern.compile("<script\\b[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public static final class Series {
        public final String id, title, cover;
        public final int episodeCount;
        public Series(String id, String title, String cover, int episodeCount) {
            this.id = id; this.title = title; this.cover = cover; this.episodeCount = episodeCount;
        }
    }
    public static final class Episode {
        public final String id;
        public final int number;
        public final boolean available;
        public Episode(String id, int number, boolean available) { this.id = id; this.number = number; this.available = available; }
    }
    public static final class Detail {
        public final Series series;
        public final List<Episode> episodes;
        public Detail(Series series, List<Episode> episodes) { this.series = series; this.episodes = Collections.unmodifiableList(new ArrayList<>(episodes)); }
    }
    public static final class Page {
        public final List<Series> items;
        public final int page;
        public final boolean hasMore;
        public Page(List<Series> items, int page, boolean hasMore) { this.items = Collections.unmodifiableList(new ArrayList<>(items)); this.page = page; this.hasMore = hasMore; }
    }

    public Page browse(int page) throws Exception {
        requirePage(page);
        return parseBrowse(fetch(SITE + "/category/real-drama?page=" + page), page);
    }
    public Page search(String keyword, int page) throws Exception {
        requirePage(page);
        String query = keyword == null ? "" : keyword.trim();
        if (query.isEmpty() || query.length() > 100) throw error("请输入 1 至 100 字的剧名");
        // shortcut: the public search SSR ignores page; enable pagination only after a verified public route exists.
        if (page != 1) throw error("来源网站搜索目前仅返回前 10 条，请换用更完整的剧名");
        return parseSearch(fetch(SITE + "/search/" + URLEncoder.encode(query, "UTF-8").replace("+", "%20")), query, page);
    }
    public Detail detail(String seriesId) throws Exception {
        requireId(seriesId);
        return parseDetail(fetch(SITE + "/player/" + seriesId), seriesId);
    }
    public String playUrl(String seriesId, String episodeId) throws Exception {
        requireId(seriesId); requireId(episodeId);
        requireAvailable(detail(seriesId), episodeId);
        return parsePlayUrl(fetch(SITE + "/player/" + seriesId + "/" + episodeId), seriesId, episodeId);
    }

    static Page parseBrowse(String html, int page) throws IOException {
        requirePage(page);
        JSONObject data = route(html, "category_");
        JSONObject category = data.optJSONObject("categoryRoute");
        JSONArray items = data.optJSONArray("recommendList");
        JSONObject pagination = data.optJSONObject("pagination");
        if (category == null || !"real-drama".equals(category.optString("contentType")) || items == null || pagination == null)
            throw error("来源网站目录暂不可用");
        if (pagination.optInt("pageNum", 0) != page) throw error("来源网站未返回请求的目录页，请重试");
        return new Page(seriesList(items, false), page, page < pagination.optInt("totalPages", page));
    }
    static Page parseSearch(String html, String query, int page) throws IOException {
        JSONObject data = route(html, "search_");
        JSONArray items = data.optJSONArray("searchList");
        if (page != 1 || !query.equals(data.optString("query")) || items == null) throw error("来源网站搜索结果不匹配，请重试");
        return new Page(seriesList(items, true), 1, false);
    }
    static Detail parseDetail(String html, String seriesId) throws IOException {
        requireId(seriesId);
        return detailData(route(html, "player_"), seriesId);
    }
    private static Detail detailData(JSONObject data, String seriesId) throws IOException {
        JSONObject series = data.optJSONObject("seriesDetail");
        if (!seriesId.equals(data.optString("series_id")) || series == null || !seriesId.equals(series.optString("series_id")))
            throw error("来源网站返回了其他短剧，已停止加载");
        JSONArray ids = series.optJSONArray("vid_list");
        if (ids == null || ids.length() == 0 || ids.length() > 3000) throw error("来源网站未提供有效分集列表");
        int accessible = series.optInt("accessible_episode_cnt", -1);
        JSONObject seo = data.optJSONObject("publicVideoSeo");
        String verified = seo != null && seo.optBoolean("available") && seriesId.equals(seo.optString("seriesId"))
                && data.optString("vid").equals(seo.optString("itemId")) ? seo.optString("itemId") : "";
        List<Episode> episodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i);
            requireId(id);
            if (!seen.add(id)) throw error("来源网站分集列表重复，请重试");
            episodes.add(new Episode(id, i + 1, accessible >= 0 ? i < accessible : id.equals(verified)));
        }
        Series model = series(series);
        return new Detail(new Series(model.id, model.title, model.cover, ids.length()), episodes);
    }
    static String parsePlayUrl(String html, String seriesId, String episodeId) throws IOException {
        requireId(seriesId); requireId(episodeId);
        JSONObject data = route(html, "player_");
        Detail detail = detailData(data, seriesId);
        requireAvailable(detail, episodeId);
        if (!episodeId.equals(data.optString("vid"))) throw error("来源网站返回了其他集，已停止播放");
        JSONObject seo = data.optJSONObject("publicVideoSeo");
        if (seo == null || !seo.optBoolean("available") || !seriesId.equals(seo.optString("seriesId")) || !episodeId.equals(seo.optString("itemId")))
            throw error("来源网站暂未开放本集播放");
        Matcher matcher = LD.matcher(html);
        while (matcher.find()) {
            JSONObject video;
            try { video = new JSONObject(matcher.group(1)); } catch (Exception ignored) { continue; }
            if (!"VideoObject".equals(video.optString("@type"))) continue;
            if (!(SITE + "/player/" + seriesId + "/" + episodeId).equals(video.optString("url"))) continue;
            String url = video.optString("contentUrl");
            URI uri = siteUri(url);
            if (!uri.getRawPath().equals("/video/public/" + episodeId + "/360p.mp4") || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw error("来源网站播放地址与本集不匹配");
            return url;
        }
        throw error("来源网站未提供公开播放地址，请稍后重试");
    }
    private static void requireAvailable(Detail detail, String episodeId) throws IOException {
        for (Episode episode : detail.episodes) if (episode.id.equals(episodeId) && episode.available) return;
        throw error("本集未在来源网站公开试看，无法播放");
    }

    private static List<Series> seriesList(JSONArray array, boolean search) throws IOException {
        if (array.length() > 1000) throw error("来源网站列表过大");
        List<Series> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            if (search) item = item.optJSONObject("video_data");
            if (item == null || !validId(item.optString("series_id"))) continue;
            String title = item.optString("series_name", item.optString("series_title")).trim();
            if (title.isEmpty() || !seen.add(item.optString("series_id"))) continue;
            result.add(series(item));
        }
        return result;
    }
    private static Series series(JSONObject item) throws IOException {
        String id = item.optString("series_id");
        requireId(id);
        String title = item.optString("series_name", item.optString("series_title")).trim();
        if (title.isEmpty()) throw error("来源网站未提供剧名");
        String cover = item.optString("series_cover");
        try {
            URI uri = new URI(cover);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) cover = "";
        } catch (Exception ignored) { cover = ""; }
        return new Series(id, title, cover, Math.max(0, Math.min(3000, item.optInt("episode_cnt", 0))));
    }
    private static JSONObject route(String html, String prefix) throws IOException {
        JSONObject router = readRouter(html);
        JSONObject loader = router.optJSONObject("loaderData");
        if (loader != null) {
            Iterator<?> keys = loader.keys();
            while (keys.hasNext()) {
                String key = String.valueOf(keys.next());
                JSONObject data = loader.optJSONObject(key);
                if (key.startsWith(prefix) && data != null && data.has("isSuccess")) {
                    if (!data.optBoolean("isSuccess")) throw error("来源网站暂未返回数据，请稍后重试");
                    return data;
                }
            }
        }
        throw error("来源网站数据格式已变化，请稍后重试");
    }
    private static JSONObject readRouter(String html) throws IOException {
        if (html == null || html.length() > MAX_BYTES) throw error("来源网站数据过大或为空");
        Matcher matcher = ROUTER.matcher(html);
        if (!matcher.find()) throw error("来源网站暂未返回公开数据");
        int start = matcher.end() - 1, depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) {
                try { return new JSONObject(html.substring(start, i + 1)); }
                catch (Exception ignored) { throw error("来源网站数据无法解析"); }
            }
        }
        throw error("来源网站数据不完整，请重试");
    }
    private static URI siteUri(String url) throws IOException {
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"hongguoduanju.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) throw error("来源网站地址不安全，已停止加载");
            return uri;
        } catch (java.net.URISyntaxException ignored) { throw error("来源网站地址无效"); }
    }
    private static String fetch(String url) throws IOException {
        URI uri = siteUri(url);
        long deadline = System.nanoTime() + 25_000_000_000L;
        try {
            for (int redirects = 0; redirects <= 2; redirects++) {
                if (Thread.currentThread().isInterrupted()) throw error("加载已取消");
                HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Referer", REFERER);
                connection.setRequestProperty("Accept", "text/html");
                connection.setRequestProperty("Accept-Encoding", "identity");
                try {
                    int status = connection.getResponseCode();
                    if (status >= 300 && status < 400) {
                        String target = connection.getHeaderField("Location");
                        if (target == null || redirects == 2) throw error("来源网站跳转过多，请重试");
                        uri = siteUri(uri.resolve(target).toString());
                        continue;
                    }
                    if (status != 200) throw error("来源网站请求失败（" + status + "），请稍后重试");
                    if (connection.getContentLength() > MAX_BYTES) throw error("来源网站数据超过大小限制");
                    try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int size;
                        while ((size = input.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) throw error("加载已取消");
                            if (System.nanoTime() > deadline) throw error("来源网站响应超时，请重试");
                            if (output.size() + size > MAX_BYTES) throw error("来源网站数据超过大小限制");
                            output.write(buffer, 0, size);
                        }
                        return new String(output.toByteArray(), "UTF-8");
                    }
                } finally { connection.disconnect(); }
            }
        } catch (java.net.SocketTimeoutException ignored) { throw error("来源网站连接超时，请检查网络后重试"); }
        catch (javax.net.ssl.SSLException ignored) { throw error("安全连接失败，请检查电视时间和网络"); }
        catch (IOException failure) {
            if (failure.getMessage() != null && (failure.getMessage().startsWith("来源网站") || failure.getMessage().startsWith("加载"))) throw failure;
            throw error("无法连接来源网站，请检查网络后重试");
        }
        throw error("来源网站跳转失败");
    }
    private static boolean validId(String id) { return id != null && id.matches("[1-9][0-9]{5,29}"); }
    private static void requireId(String id) throws IOException { if (!validId(id)) throw error("剧目或分集编号无效"); }
    private static void requirePage(int page) throws IOException { if (page < 1 || page > 1000) throw error("目录页码无效"); }
    private static IOException error(String message) { return new IOException(message); }
}

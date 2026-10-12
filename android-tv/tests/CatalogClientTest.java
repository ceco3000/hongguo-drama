package io.github.jackotom.hongguotv;

import org.json.JSONArray;
import org.json.JSONObject;

public final class CatalogClientTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    interface Attempt { void run() throws Exception; }
    private static void rejected(Attempt attempt, String message) throws Exception {
        checks++;
        try { attempt.run(); } catch (java.io.IOException expected) { return; }
        throw new AssertionError(message);
    }
    private static void locked(CatalogClient client, String series, String episode) throws Exception {
        try { client.playUrl(series, episode); }
        catch (java.io.IOException failure) {
            check("本集未在来源网站公开试看，无法播放".equals(failure.getMessage()), "locked boundary must not be a network error: " + failure.getMessage());
            return;
        }
        throw new AssertionError("locked episode returned a URL");
    }
    private static String html(String route, JSONObject data) throws Exception {
        return "<script>window._ROUTER_DATA = " + new JSONObject().put("loaderData",
                new JSONObject().put(route, data)) + ";</script>";
    }
    private static JSONObject series() throws Exception {
        return new JSONObject().put("series_id", "123456789").put("series_name", "引号\"、反斜线\\、花括号}与中文")
                .put("series_cover", "https://p6-novel.byteimg.com/cover.image").put("episode_cnt", 4)
                .put("accessible_episode_cnt", 3).put("vid_list", new JSONArray("[\"111111111\",\"222222222\",\"333333333\",\"444444444\"]"));
    }
    private static JSONObject player() throws Exception {
        return new JSONObject().put("isSuccess", true).put("protectedPlayback", true)
                .put("series_id", "123456789").put("vid", "111111111").put("seriesDetail", series())
                .put("publicVideoSeo", new JSONObject().put("available", true).put("seriesId", "123456789").put("itemId", "111111111"));
    }
    private static String ld(String content) throws Exception {
        return "<script type=\"application/ld+json\">" + new JSONObject().put("@type", "VideoObject")
                .put("url", "https://hongguoduanju.com/player/123456789/111111111").put("contentUrl", content) + "</script>";
    }
    public static void main(String[] args) throws Exception {
        String detailHtml = html("player_(series_id)/(vid)/page", player());
        CatalogClient.Detail detail = CatalogClient.parseDetail(detailHtml, "123456789");
        check(detail.series.title.equals("引号\"、反斜线\\、花括号}与中文"), "JSON escaping preserved");
        check(detail.episodes.size() == 4 && detail.episodes.get(2).available && !detail.episodes.get(3).available, "public access boundary");
        rejected(() -> CatalogClient.parseDetail(detailHtml, "987654321"), "reject series mismatch");
        JSONObject unknown = player(); unknown.getJSONObject("seriesDetail").remove("accessible_episode_cnt");
        CatalogClient.Detail unknownDetail = CatalogClient.parseDetail(html("player_(series_id)/(vid)/page", unknown), "123456789");
        check(unknownDetail.episodes.get(0).available && !unknownDetail.episodes.get(1).available, "only explicitly verified current episode available when count absent");
        JSONObject zero = player(); zero.getJSONObject("seriesDetail").put("accessible_episode_cnt", 0);
        check(!CatalogClient.parseDetail(html("player_(series_id)/(vid)/page", zero), "123456789").episodes.get(0).available, "explicit zero access wins");
        JSONObject invalid = player(); invalid.getJSONObject("seriesDetail").put("vid_list", new JSONArray("[\"111111111\",\"oops\",\"333333333\"]"));
        rejected(() -> CatalogClient.parseDetail(html("player_(series_id)/(vid)/page", invalid), "123456789"), "invalid episode must not renumber");
        String publicUrl = "https://hongguoduanju.com/video/public/111111111/360p.mp4";
        check(publicUrl.equals(CatalogClient.parsePlayUrl(detailHtml + ld(publicUrl), "123456789", "111111111")), "protected page public URL is usable");
        rejected(() -> CatalogClient.parsePlayUrl(detailHtml + ld(publicUrl), "123456789", "222222222"), "current episode mismatch");
        rejected(() -> CatalogClient.parsePlayUrl(detailHtml + ld(publicUrl), "123456789", "444444444"), "locked episode rejected");
        JSONObject wrongSeo = player(); wrongSeo.getJSONObject("publicVideoSeo").put("seriesId", "987654321");
        rejected(() -> CatalogClient.parsePlayUrl(html("player_(series_id)/(vid)/page", wrongSeo) + ld(publicUrl), "123456789", "111111111"), "SEO series must match");
        for (String url : new String[]{"http://hongguoduanju.com/video/public/111111111/360p.mp4", "https://hongguoduanju.com.evil.test/video/public/111111111/360p.mp4", "https://user@hongguoduanju.com/video/public/111111111/360p.mp4", "https://hongguoduanju.com/video/public/222222222/360p.mp4", "https://hongguoduanju.com:444/video/public/111111111/360p.mp4"}) {
            rejected(() -> CatalogClient.parsePlayUrl(detailHtml + ld(url), "123456789", "111111111"), "unsafe or unrelated URL rejected");
        }
        rejected(() -> CatalogClient.parsePlayUrl(detailHtml, "123456789", "111111111"), "missing public URL is explicit error");
        JSONObject browse = new JSONObject().put("isSuccess", true).put("categoryRoute", new JSONObject().put("contentType", "real-drama"))
                .put("recommendList", new JSONArray().put(series()).put(series()))
                .put("pagination", new JSONObject().put("pageNum", 1).put("totalPages", 2));
        CatalogClient.Page page = CatalogClient.parseBrowse(html("category_$", browse), 1);
        check(page.items.size() == 1 && page.hasMore, "catalog dedup and pagination");
        rejected(() -> CatalogClient.parseBrowse(html("category_$", browse), 2), "reject repeated page");
        JSONObject search = new JSONObject().put("isSuccess", true).put("query", "关键词").put("totalCount", 99)
                .put("searchList", new JSONArray().put(new JSONObject().put("video_data", new JSONObject().put("series_id", "123456789").put("series_title", "找到短剧").put("episode_cnt", 4))));
        CatalogClient.Page found = CatalogClient.parseSearch(html("search_(keyword)/page", search), "关键词", 1);
        check(found.items.size() == 1 && found.items.get(0).title.equals("找到短剧") && !found.hasMore, "search first public page only");
        rejected(() -> CatalogClient.parseSearch(html("search_(keyword)/page", search), "其他", 1), "search query mismatch");
        rejected(() -> CatalogClient.parseDetail("<script>window._ROUTER_DATA={broken", "123456789"), "malformed payload");
        System.out.println("CatalogClientTest: " + checks + " checks passed");
        if (args.length > 0 && args[0].equals("--boundary")) {
            locked(new CatalogClient(), "7686713195246930968", "7686715407662582846");
            System.out.println("Live first locked episode: confirmed public-preview restriction");
        }
        if (args.length > 0 && args[0].equals("--live")) {
            CatalogClient client = new CatalogClient();
            CatalogClient.Page liveBrowse = client.browse(1);
            check(!liveBrowse.items.isEmpty(), "live catalog");
            CatalogClient.Series first = liveBrowse.items.get(0);
            CatalogClient.Page liveSearch = client.search(first.title, 1);
            check(!liveSearch.items.isEmpty(), "live search");
            CatalogClient.Detail liveDetail = client.detail(first.id);
            int available = 0;
            for (CatalogClient.Episode episode : liveDetail.episodes) {
                if (episode.available) { client.playUrl(first.id, episode.id); available++; }
                else { locked(client, first.id, episode.id); break; }
                if (available >= 3) {
                    for (CatalogClient.Episode next : liveDetail.episodes) {
                        if (!next.available) { locked(client, first.id, next.id); break; }
                    }
                    break;
                }
            }
            check(available > 0, "live public playback metadata");
            System.out.println("Live metadata: catalog=" + liveBrowse.items.size() + ", search=" + liveSearch.items.size() + ", episodes=" + liveDetail.episodes.size() + ", verified public URLs=" + available);
        }
    }
}

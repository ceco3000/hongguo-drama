package io.github.jackotom.hongguotv;
import java.util.ArrayList;
import java.util.List;
public final class HistoryStoreTest {
    public static void main(String[] args) {
        check(HistoryStore.decode("bad").isEmpty(), "corrupt history survives");
        List<HistoryStore.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 45; i++) entries.add(new HistoryStore.Entry("123456" + i, "剧" + i, "700" + i, i + 1, 9000, 100000));
        HistoryStore.Entry current = new HistoryStore.Entry("1234561", "更新剧名", "7001", 2, 40000, 100000);
        List<HistoryStore.Entry> result = HistoryStore.decode(HistoryStore.encode(current, entries));
        check(result.size() == 40, "history bounded");
        check(result.get(0).title.equals("更新剧名") && result.get(0).position == 40000, "latest progress first");
        check(result.stream().filter(e -> e.seriesId.equals("1234561")).count() == 1, "deduplicate series");
        check(HistoryStore.decode("[{\"seriesId\":\"javascript:x\",\"episodeId\":\"1\"}]").isEmpty(), "reject corrupt ids");
        System.out.println("HistoryStore: 5 assertions passed");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}

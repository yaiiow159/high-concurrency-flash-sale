package com.flashsale.infrastructure.adapter.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Comparator;
import java.util.Set;
import java.util.List;

/** 索引的建立與 alias 切換（ADR-0012 決策 5）。 */
@Component
public class ProductIndexAdmin {

    private static final Logger log = LoggerFactory.getLogger(ProductIndexAdmin.class);

    private final ElasticsearchClient client;
    private final Clock clock;

    public ProductIndexAdmin(ElasticsearchClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    /** 確保 alias 存在，不存在就建一個空索引掛上去。 */
    public boolean ensureAliasExists() {
        try {
            if (aliasExists()) {
                return false;
            }
            switchAliasTo(createNextVersion());
            log.info("搜尋 alias 不存在，已建立空索引並掛上——"
                    + "避免第一個寫入事件把 alias 名稱自動建成實體索引");
            return true;
        } catch (RuntimeException e) {
            log.warn("建立搜尋 alias 失敗；搜尋會降級為資料庫查詢，索引寫入會重試", e);
            return false;
        }
    }

    private boolean aliasExists() {
        try {
            return client.indices()
                    .existsAlias(request -> request.name(ElasticsearchProductSearchIndex.ALIAS))
                    .value();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("查詢搜尋 alias 是否存在時失敗", e);
        }
    }

    /** 建立下一個版本的索引。 */
    String createNextVersion() {
        String name = ALIAS_PREFIX + clock.millis() + "_"
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000));
        try {
            client.indices().create(request -> request
                    .index(name)
                    .mappings(mapping -> mapping
                            .properties("productId", p -> p.long_(l -> l))
                            // name 用 text 並套 cjk 分析器。
                            //
                            // standard 對中文是<b>逐字切</b>：「幽靈商品」→ 幽/靈/商/品，
                            // 於是它會命中「測試用商品」——只因為共用了「商」「品」兩個字。
                            // 實測過那個假命中。
                            //
                            // cjk 產生雙字組（幽靈/靈商/商品），精準度高一個量級，
                            // 而且是 ES 內建、不需要安裝外掛。ADR-0012 說的
                            // 「IK 或內建 CJK 分析器」指的就是它——先前寫成 standard
                            // 等於把選 ES 的三個理由之一打了折。
                            .properties("name", p -> p.text(t -> t.analyzer("cjk")))
                            // brand 同時要能全文搜尋與做精確分面，
                            // 因此 text 之下再掛一個 keyword 子欄位
                            // brand 要同時支援「部分關鍵字搜尋」與「精確分面」：
                            // 純 keyword 不分詞，搜「App」找不到品牌「Apple」，
                            // 而 buildQuery 對 brand 的加權會完全失效。
                            // 因此主欄位是 text，底下掛一個 keyword 子欄位給 filter 與 aggregation
                            .properties("brand", p -> p.text(t -> t
                                    .analyzer("cjk")
                                    .fields("keyword", f -> f.keyword(k -> k))))
                            .properties("description", p -> p.text(t -> t.analyzer("cjk")))
                            .properties("categoryId", p -> p.long_(l -> l))
                            .properties("lowestPrice", p -> p.double_(d -> d))));
            log.info("已建立搜尋索引 {}", name);
            return name;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("建立搜尋索引失敗: " + name, e);
        }
    }

    /** 把 alias 指到新索引。 */
    void switchAliasTo(String target) {
        try {
            List<String> previous = currentIndices();
            client.indices().updateAliases(request -> {
                previous.forEach(old -> request.actions(action -> action
                        .remove(remove -> remove.index(old)
                                .alias(ElasticsearchProductSearchIndex.ALIAS))));
                return request.actions(action -> action
                        .add(add -> add.index(target)
                                .alias(ElasticsearchProductSearchIndex.ALIAS)));
            });
            log.info("搜尋索引 alias 已切換到 {}（舊索引 {} 保留供回退）", target, previous);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("切換搜尋索引 alias 失敗: " + target, e);
        }
    }

    /** alias 目前指向哪些索引；尚未建立時回空清單。 */
    private List<String> currentIndices() {
        if (!aliasExists()) {
            return List.of();
        }
        try {
            return client.indices()
                    .getAlias(request -> request.name(ElasticsearchProductSearchIndex.ALIAS))
                    .result().keySet().stream().toList();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("查詢搜尋 alias 指向哪些索引時失敗", e);
        }
    }


    /** 清掉過舊的索引版本，只留最近幾代。 */
    void pruneOldVersions(int keepGenerations) {
        try {
            Set<String> live = Set.copyOf(currentIndices());
            Set<String> all = client.indices()
                    .get(request -> request.index(ALIAS_PREFIX + "*"))
                    .result().keySet();

            List<String> obsolete = selectObsolete(all, live, keepGenerations);
            for (String index : obsolete) {
                client.indices().delete(request -> request.index(index));
                log.info("已刪除過舊的搜尋索引 {}", index);
            }
            if (obsolete.isEmpty()) {
                log.debug("沒有需要清理的舊搜尋索引");
            }
        } catch (IOException | RuntimeException e) {
            // 刪不掉舊索引不該讓重建變成失敗——重建已經成功了
            log.warn("清理舊搜尋索引失敗，索引仍在但不影響服務", e);
        }
    }

    /** 挑出該刪的索引。 */
    static List<String> selectObsolete(Set<String> all, Set<String> live, int keepGenerations) {
        return all.stream()
                // alias 指向的一律不動
                .filter(name -> !live.contains(name))
                // 版本號是建立當下的毫秒數，數字大的是新的。
                // 用數值比較而不是字串：毫秒數的位數在未來會增加，
                // 而那一天字串排序會把新索引排到舊索引前面
                .sorted(Comparator.comparingLong(ProductIndexAdmin::versionOf).reversed())
                .skip(Math.max(keepGenerations, 0))
                .toList();
    }

    /** 從索引名解出版本號（建立當下的毫秒數）。 */
    private static long versionOf(String indexName) {
        String suffix = indexName.substring(ALIAS_PREFIX.length());
        int separator = suffix.indexOf('_');
        String millis = separator < 0 ? suffix : suffix.substring(0, separator);
        try {
            return Long.parseLong(millis);
        } catch (NumberFormatException notOurs) {
            return 0L;
        }
    }

    private static final String ALIAS_PREFIX = ElasticsearchProductSearchIndex.ALIAS + "_v";
}

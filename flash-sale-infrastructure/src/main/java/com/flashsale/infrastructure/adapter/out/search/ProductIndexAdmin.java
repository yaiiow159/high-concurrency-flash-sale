package com.flashsale.infrastructure.adapter.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import java.util.List;

/**
 * 索引的建立與 alias 切換（ADR-0012 決策 5）。
 *
 * <p>ES 不允許改既有欄位的型別，因此任何 mapping 變更都必須重建整份索引。
 * 對外固定用 alias {@code products}，實際索引是 {@code products_vN}——
 * 重建寫進新版本，完成後<b>原子切換</b> alias，舊索引留著可以立刻切回去。
 *
 * <p>少了這一層，改一次 mapping 就是一次停機。
 *
 * <h2>alias 必須先存在，否則會被自動建成一個實體索引</h2>
 *
 * <p>ES 預設 {@code action.auto_create_index=true}：寫入一個不存在的名稱時，
 * 它會<b>自動建立一個同名的實體索引</b>並用 dynamic mapping 猜欄位型別。
 *
 * <p>實測過那條路徑的完整後果：全新環境還沒重建過索引，第一個上架事件
 * 就把 {@code products} 建成實體索引；之後 {@code reindexAll} 永遠失敗
 * （不能建立與既有索引同名的 alias），而搜尋靜靜地降級成資料庫查詢——
 * HTTP 200、沒有告警。<b>那個狀態沒有任何 API 救得回來</b>，
 * 只能人工 {@code DELETE /products}。
 *
 * <p>因此 {@link #ensureAliasExists()} 在啟動時與每次寫入失敗時都會跑，
 * 且它是冪等的。
 */
@Component
public class ProductIndexAdmin {

    private static final Logger log = LoggerFactory.getLogger(ProductIndexAdmin.class);

    private final ElasticsearchClient client;
    private final Clock clock;

    public ProductIndexAdmin(ElasticsearchClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    /**
     * 確保 alias 存在，不存在就建一個空索引掛上去。
     *
     * <p><b>冪等</b>：alias 已存在時什麼都不做，因此可以在啟動時、
     * 也可以在寫入失敗後重複呼叫。
     *
     * <p>失敗時只記錄不拋出——搜尋是可降級的功能，
     * 不該因為 ES 沒起來就讓整個應用啟動不了。
     *
     * @return 這次是否真的建立了
     */
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

    /**
     * 建立下一個版本的索引。
     *
     * <p>版本號用時間戳加隨機碼。只用時間戳的話，同一毫秒內的兩次重建會撞名——
     * 時間戳把碰撞窗口縮到 1 毫秒，但沒有消除它。
     */
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

    /**
     * 把 alias 指到新索引。
     *
     * <p>移除舊綁定與新增新綁定放在<b>同一個請求</b>裡，ES 保證原子生效——
     * 分成兩次呼叫的話，中間那個瞬間 alias 不存在，所有搜尋都會失敗。
     */
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

    /**
     * alias 目前指向哪些索引；尚未建立時回空清單。
     *
     * <p><b>只有「alias 不存在」才回空清單，其餘一律往上拋。</b>
     * 先前這裡吞掉所有例外，而那會製造一個很難查的狀態：
     * 若 {@code getAlias} 因為逾時或權限不足失敗、而後續的 {@code updateAliases}
     * 成功了，移除舊綁定的動作就一個都沒產生——<b>alias 會同時指向新舊兩個索引</b>。
     * 之後每筆文件被搜出兩次，而且所有單筆寫入都會失敗
     * （ES 拒絕對解析到多個索引的 alias 寫入），索引更新徹底停擺。
     */
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

    private static final String ALIAS_PREFIX = ElasticsearchProductSearchIndex.ALIAS + "_v";
}

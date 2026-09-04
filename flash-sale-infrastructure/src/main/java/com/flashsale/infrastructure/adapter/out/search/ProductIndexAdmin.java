package com.flashsale.infrastructure.adapter.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 索引的建立與 alias 切換（ADR-0012 決策 5）。
 *
 * <p>ES 不允許改既有欄位的型別，因此任何 mapping 變更都必須重建整份索引。
 * 對外固定用 alias {@code products}，實際索引是 {@code products_vN}——
 * 重建寫進新版本，完成後<b>原子切換</b> alias，舊索引留著可以立刻切回去。
 *
 * <p>少了這一層，改一次 mapping 就是一次停機。
 */
@Component
public class ProductIndexAdmin {

    private static final Logger log = LoggerFactory.getLogger(ProductIndexAdmin.class);

    private final ElasticsearchClient client;

    public ProductIndexAdmin(ElasticsearchClient client) {
        this.client = client;
    }

    /**
     * 建立下一個版本的索引。
     *
     * <p>版本號用時間戳而不是遞增數字：遞增要先讀出目前最大值，
     * 而兩個同時執行的重建會拿到同一個號碼並互相覆寫。
     */
    String createNextVersion() {
        String name = ALIAS_PREFIX + System.currentTimeMillis();
        try {
            client.indices().create(request -> request
                    .index(name)
                    .mappings(mapping -> mapping
                            .properties("productId", p -> p.long_(l -> l))
                            // name 用 text（要分詞才搜得到「手機」）
                            .properties("name", p -> p.text(t -> t.analyzer("standard")))
                            // brand 同時要能全文搜尋與做精確分面，
                            // 因此 text 之下再掛一個 keyword 子欄位
                            .properties("brand", p -> p.keyword(k -> k))
                            .properties("description", p -> p.text(t -> t.analyzer("standard")))
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

    /** alias 目前指向哪些索引；尚未建立時回空清單。 */
    private List<String> currentIndices() {
        try {
            return client.indices()
                    .getAlias(request -> request.name(ElasticsearchProductSearchIndex.ALIAS))
                    .result().keySet().stream().toList();
        } catch (IOException | RuntimeException e) {
            // alias 還不存在（第一次重建）是正常情況，不是錯誤
            return List.of();
        }
    }

    private static final String ALIAS_PREFIX = ElasticsearchProductSearchIndex.ALIAS + "_v";
}

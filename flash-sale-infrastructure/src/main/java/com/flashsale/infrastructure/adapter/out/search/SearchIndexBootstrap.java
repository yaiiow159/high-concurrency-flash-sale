package com.flashsale.infrastructure.adapter.out.search;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 啟動時確保搜尋 alias 存在。
 *
 * <h2>為什麼一定要在第一個寫入之前建好</h2>
 *
 * <p>ES 預設 {@code action.auto_create_index=true}：寫入一個不存在的名稱時，
 * 它會自動建立一個<b>同名的實體索引</b>。一旦 {@code products} 變成實體索引
 * 而不是 alias，{@code reindexAll} 就永遠失敗（不能建立與既有索引同名的 alias），
 * 而搜尋會安靜地降級成資料庫查詢——HTTP 200、沒有告警。
 *
 * <p>實測過那條路徑：全新環境沒重建過索引，第一個上架事件就讓系統進入
 * <b>沒有任何 API 救得回來</b>的狀態，只能人工 {@code DELETE /products}。
 *
 * <h2>失敗不阻擋啟動</h2>
 *
 * <p>搜尋是可降級的功能（ADR-0012 決策 4）。ES 沒起來就讓整個應用啟動不了，
 * 等於把一個「壞了也不影響下單」的依賴變成硬相依——
 * 那與 readiness 探針不含 ES 是同一個判斷。
 *
 * <p>啟動時建不起來也還有第二道：索引寫入遇到「索引不存在」時會自己補建再試。
 */
@Configuration
public class SearchIndexBootstrap {

    @Bean
    public ApplicationRunner ensureSearchAlias(ProductIndexAdmin indexAdmin) {
        return args -> indexAdmin.ensureAliasExists();
    }
}

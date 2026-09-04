package com.flashsale.application.port.in.dto;

import java.util.List;

/**
 * 商品列表的一頁（ADR-0021）。
 *
 * <p>回信封而不是裸陣列，是因為 keyset 分頁的下一頁起點<b>只有伺服器知道</b>。
 * 讓前端自己拿最後一筆的 id 當游標，等於把「排序鍵是什麼、方向是哪邊」
 * 洩漏成對外契約——之後想改排序規則就再也改不動了。
 *
 * @param nextCursor 下一頁的起點；{@code null} 代表沒有下一頁。
 *                   前端只負責原樣送回，不需要理解它的內容
 * @param hasMore    還有沒有下一頁。用 {@code LIMIT size + 1} 多取一筆判斷，
 *                   而不是再打一次 {@code COUNT(*)}——在 5 萬列上那個 count
 *                   比查詢本身還貴，而它只是為了決定一個布林值
 */
public record ProductPage(
        List<ProductView> items,
        Long nextCursor,
        boolean hasMore
) {

    public static ProductPage of(List<ProductView> items, Long nextCursor) {
        return new ProductPage(items, nextCursor, nextCursor != null);
    }

    public static ProductPage empty() {
        return new ProductPage(List.of(), null, false);
    }
}

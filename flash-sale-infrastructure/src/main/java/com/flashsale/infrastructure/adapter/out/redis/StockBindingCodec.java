package com.flashsale.infrastructure.adapter.out.redis;

import com.flashsale.domain.stock.StockBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 扣減憑證的編解碼：{@code orderNo|userId|quantity}。
 *
 * <p><b>為什麼不用 JSON？</b> 這個字串會被寫進 Redis 數百萬次，
 * 每一個位元組都乘上訂單量。JSON 的鍵名會讓每筆多出約 40 個位元組，
 * 百萬訂單就是 40MB 的純浪費。分隔字串在這裡是正確的取捨——
 * 欄位固定三個且都不含分隔符（{@code OrderNo} 的格式已排除 {@code |}）。
 *
 * <p>解析失敗時<b>不拋例外</b>：Redis 中可能殘留升級前的舊格式資料，
 * 讓對帳排程因為一筆舊資料就整個崩掉，代價遠大於略過它。
 */
final class StockBindingCodec {

    private static final Logger log = LoggerFactory.getLogger(StockBindingCodec.class);
    private static final char SEPARATOR = '|';
    private static final int FIELD_COUNT = 3;

    private StockBindingCodec() {
    }

    /**
     * 從憑證字串取出訂單號。
     *
     * <p>相容舊格式（純訂單號，不含分隔符）：找不到分隔符就整串視為訂單號。
     */
    static String extractOrderNo(String binding) {
        if (binding == null || binding.isEmpty()) {
            return null;
        }
        int separatorIndex = binding.indexOf(SEPARATOR);
        return separatorIndex < 0 ? binding : binding.substring(0, separatorIndex);
    }

    /**
     * 解析完整憑證。
     *
     * @return 舊格式或格式損毀時，回傳 {@code quantity = 0} 的憑證——
     *         呼叫端可用 {@link StockBinding#isReversible()} 判斷資訊是否足以退庫
     */
    static StockBinding decode(String requestId, String binding) {
        String[] parts = binding.split("\\" + SEPARATOR, -1);
        if (parts.length != FIELD_COUNT) {
            // 舊格式：只有訂單號，缺少退庫所需的數量資訊。
            return new StockBinding(requestId, binding, 0L, 0);
        }
        try {
            return new StockBinding(requestId, parts[0], Long.parseLong(parts[1]), Integer.parseInt(parts[2]));
        } catch (IllegalArgumentException e) {
            // 涵蓋 NumberFormatException（欄位非數字）與 StockBinding 建構期的數量檢查
            log.warn("扣減憑證格式無法解析，將視為不可退庫 requestId={}, binding={}", requestId, binding);
            return new StockBinding(requestId, parts[0], 0L, 0);
        }
    }
}

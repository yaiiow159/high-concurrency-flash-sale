package com.flashsale.infrastructure.adapter.out.redis;

import com.flashsale.domain.stock.StockBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 扣減憑證的編解碼：{@code orderNo|userId|quantity}。 */
final class StockBindingCodec {

    private static final Logger log = LoggerFactory.getLogger(StockBindingCodec.class);
    private static final char SEPARATOR = '|';
    private static final int FIELD_COUNT = 3;

    private StockBindingCodec() {
    }

    /** 從憑證字串取出訂單號。 */
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
     * 呼叫端可用 {@link StockBinding#isReversible()} 判斷資訊是否足以退庫
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

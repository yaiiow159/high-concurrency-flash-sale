package com.flashsale.domain.stock;

import java.util.Objects;

/**
 * 一筆「已扣減庫存」的憑證。
 *
 * <p>這是 Redis 中冪等映射的領域表述。對帳時它的用途是找出<b>孤兒扣減</b>——
 * 庫存扣了、訂單卻不存在的紀錄。
 *
 * <p>孤兒扣減是最危險的一種庫存洩漏：資料庫裡沒有任何一筆紀錄會提醒你
 * 「這裡有庫存被鎖住了」。若不主動掃描比對，這些庫存會一直消失到活動結束。
 *
 * <p><b>為什麼要記 {@code userId} 與 {@code quantity}，而不只是訂單號？</b>
 * 因為一筆扣減紀錄必須攜帶「足以反轉它自己」的完整資訊。
 * 正常的補償路徑能從訂單事件拿到數量，但孤兒扣減<b>沒有訂單</b>——
 * 若綁定裡不記數量，發現孤兒時就無從得知該退幾件，
 * 這個洩漏會變成偵測得到卻修不掉的死結。
 *
 * @param quantity 扣減數量；為 {@code 0} 代表這筆綁定是舊格式，資訊不足以安全退庫
 */
public record StockBinding(String requestId, String orderNo, long userId, int quantity) {

    public StockBinding {
        Objects.requireNonNull(requestId, "requestId 不可為 null");
        Objects.requireNonNull(orderNo, "orderNo 不可為 null");
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity 不可為負數");
        }
    }

    /** 資訊是否完整到足以安全地退回這筆扣減。 */
    public boolean isReversible() {
        return quantity > 0;
    }
}

package com.flashsale.application.port.out;

import com.flashsale.domain.order.OrderNo;

import java.time.Instant;
import java.util.Optional;

/**
 * 訂單編號產生器埠（出站）。
 *
 * <p>必須是<b>本地產生、無遠端呼叫</b>：秒殺鏈路容不下一次為了取號的網路往返。
 * 預設實作為 Snowflake 變形（時間戳 + 節點 + 序號）。
 */
public interface OrderNoGenerator {

    OrderNo next();

    /**
     * 解出訂單號內嵌的產生時間。
     *
     * <p>由這個埠同時負責產生與解析，是因為兩者共用同一份格式契約——
     * 若解析邏輯散落在別處，改了位元配置就會有人忘了同步。
     *
     * <p>對帳用它判斷一筆孤兒扣減是否「已經舊到不可能還在處理中」：
     * 剛建立幾秒的訂單很可能只是還在 MQ 佇列裡，貿然退庫反而會造成超賣。
     *
     * @return 無法解析時回傳 {@code Optional.empty()}（例如訂單號來自其他系統或格式已變更）
     */
    Optional<Instant> issuedAt(OrderNo orderNo);
}

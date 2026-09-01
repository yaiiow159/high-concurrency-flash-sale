package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.List;
import java.util.Objects;

/**
 * 一般下單（同步）。
 *
 * <p>與秒殺通道的差別只有兩個（ADR-0006）：庫存扣減機制、訂單建立路徑。
 * 訂單聚合根、狀態機、付款、履約、事件全部共用。
 *
 * <table border="1">
 *   <caption>兩條通道的行為差異</caption>
 *   <tr><th></th><th>一般</th><th>秒殺</th></tr>
 *   <tr><td>回應</td><td>201 + 完整訂單</td><td>202 + 受理憑證，之後輪詢</td></tr>
 *   <tr><td>一致性</td><td>單一交易，失敗全回滾</td><td>最終一致，靠補償</td></tr>
 *   <tr><td>庫存</td><td>MySQL 條件式 UPDATE</td><td>Redis Lua</td></tr>
 *   <tr><td>品項數</td><td>多品項</td><td>單品項</td></tr>
 * </table>
 *
 * <p><b>一般通道刻意做成同步。</b>它沒有削峰的需求——把它也推進 MQ，
 * 換來的是「為什麼買一本書也要輪詢」，而且失去了交易帶來的免費正確性。
 */
public interface PlaceOrderUseCase {

    OrderView place(PlaceOrderCommand command);

    /**
     * @param requestId 端到端冪等鍵。重送同一個 requestId 會拿回同一張訂單，
     *                  而不是一個「重複請求」的錯誤——使用者連點兩次不該被懲罰
     * @param lines     要買什麼、各買幾件。<b>不含價格</b>：價格一律由目錄決定，
     *                  呼叫端若能指定價格，那就不叫價格了
     */
    record PlaceOrderCommand(Long userId, String requestId, List<OrderItem> lines) {

        /** 單筆訂單的品項數上限。沒有上限的話，一次請求就能讓資料庫做上萬次扣減。 */
        public static final int MAX_LINES = 50;

        public PlaceOrderCommand {
            Objects.requireNonNull(userId, "userId 不可為 null");
            if (requestId == null || requestId.isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "requestId 不可為空");
            }
            if (lines == null || lines.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "訂單至少要有一個品項");
            }
            if (lines.size() > MAX_LINES) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                        "單筆訂單最多 %d 個品項".formatted(MAX_LINES));
            }
            long distinct = lines.stream().map(OrderItem::skuId).distinct().count();
            if (distinct != lines.size()) {
                // 同一個 SKU 拆成兩行會讓庫存流水的唯一鍵 (訂單, SKU, DEDUCT) 撞在一起，
                // 第二行扣不下去。與其讓它在深處失敗，不如在入口就說清楚。
                throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                        "同一個規格請合併為一行，不要重複出現");
            }
            lines = List.copyOf(lines);
        }
    }

    /** @param quantity 上限交由 SKU 的可售量決定，這裡只擋明顯不合理的輸入 */
    record OrderItem(Long skuId, int quantity) {

        public static final int MAX_QUANTITY_PER_LINE = 999;

        public OrderItem {
            Objects.requireNonNull(skuId, "skuId 不可為 null");
            if (quantity <= 0 || quantity > MAX_QUANTITY_PER_LINE) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                        "購買數量必須介於 1 與 %d 之間".formatted(MAX_QUANTITY_PER_LINE));
            }
        }
    }
}

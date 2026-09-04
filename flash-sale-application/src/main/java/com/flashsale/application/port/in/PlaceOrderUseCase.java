package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.CheckoutPreview;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.domain.shipping.ShippingMethod;
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
     * 結帳試算：不建訂單、不扣庫存、不核銷券。
     *
     * <p>使用者在按下「送出訂單」之前就該看到這張券折多少。
     * 讓前端自己算是錯的——兩邊算出不同答案時，使用者只會相信他先看到的那一個。
     *
     * <p><b>試算通過不代表下單會成功。</b> 庫存、券的狀態都可能在兩次呼叫之間變化，
     * 這是任何「先看再做」的介面都躲不掉的，也是為什麼真正的防線都在
     * {@link #place} 那條路徑上，而不是這裡。
     */
    CheckoutPreview preview(PreviewCommand command);

    /**
     * 試算的輸入。
     *
     * <p><b>刻意不重用 {@code PlaceOrderCommand}。</b> 那個型別要求 {@code addressId}
     * 與 {@code requestId} 不可為空，而那兩個約束是為了「建立訂單」存在的：
     * 寄不出去的訂單不該被建立、沒有冪等鍵就沒有冪等。
     * 試算什麼都不建立，硬塞兩個假值進去只會讓那些約束變成裝飾。
     */
    record PreviewCommand(Long userId, List<OrderItem> lines, Long couponId,
                          String postalCode, ShippingMethod shippingMethod) {

        /** 還沒選地址的試算：運費算不出來，回 0 並由畫面說明「選了地址才知道」。 */
        public PreviewCommand(Long userId, List<OrderItem> lines, Long couponId) {
            this(userId, lines, couponId, null, ShippingMethod.HOME_DELIVERY);
        }

        public PreviewCommand {
            Objects.requireNonNull(userId, "userId 不可為 null");
            if (lines == null || lines.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "試算至少要有一個品項");
            }
            if (lines.size() > PlaceOrderCommand.MAX_LINES) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                        "單筆訂單最多 %d 個品項".formatted(PlaceOrderCommand.MAX_LINES));
            }
            lines = List.copyOf(lines);
            // 沒指定就宅配。省略這一行的話，直接呼叫正規建構子的路徑
            // （例如 API 的 DTO 轉換）會把 null 一路傳到費率查詢，
            // 而那裡的錯誤訊息是一個 NullPointerException
            shippingMethod = shippingMethod == null
                    ? ShippingMethod.HOME_DELIVERY : shippingMethod;
        }
    }

    /**
     * @param requestId 端到端冪等鍵。重送同一個 requestId 會拿回同一張訂單，
     *                  而不是一個「重複請求」的錯誤——使用者連點兩次不該被懲罰
     * @param addressId 收貨地址簿的 ID。<b>訂單存的是它的快照而非這個 ID</b>——
     *                  使用者日後搬家改了地址簿，這張訂單要寄到哪裡不能跟著變
     * @param lines     要買什麼、各買幾件。<b>不含價格</b>：價格一律由目錄決定，
     *                  呼叫端若能指定價格，那就不叫價格了
     * @param couponId  要使用的優惠券；不用券時為 {@code null}。
     *                  <b>只傳 ID，不傳折抵金額</b>——與價格同一個道理，
     *                  呼叫端若能指定折多少，那就不叫折扣了。
     *                  滿減這類不需券的優惠由伺服器自行判定，不必也不該由呼叫端指定
     */
    record PlaceOrderCommand(Long userId, String requestId, Long addressId,
                             List<OrderItem> lines, Long couponId,
                             ShippingMethod shippingMethod) {

        /** 不用券、宅配的下單。 */
        public PlaceOrderCommand(Long userId, String requestId, Long addressId,
                                 List<OrderItem> lines) {
            this(userId, requestId, addressId, lines, null, ShippingMethod.HOME_DELIVERY);
        }

        public PlaceOrderCommand(Long userId, String requestId, Long addressId,
                                 List<OrderItem> lines, Long couponId) {
            this(userId, requestId, addressId, lines, couponId, ShippingMethod.HOME_DELIVERY);
        }

        /** 單筆訂單的品項數上限。沒有上限的話，一次請求就能讓資料庫做上萬次扣減。 */
        public static final int MAX_LINES = 50;

        public PlaceOrderCommand {
            Objects.requireNonNull(userId, "userId 不可為 null");
            // 寄不出去的訂單不該被建立。少了這道檢查，
            // 缺地址的訂單會一路走到出貨環節才卡住，而那時錢已經收了
            Objects.requireNonNull(addressId, "addressId 不可為 null");
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
            // 沒指定就宅配。這個預設值讓既有的呼叫端不必全部改，
            // 而配送方式是「有預設值才合理」的欄位——多數人不會特別選
            shippingMethod = shippingMethod == null
                    ? ShippingMethod.HOME_DELIVERY : shippingMethod;
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

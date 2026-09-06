package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.CheckoutPreview;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.domain.shipping.ShippingMethod;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.List;
import java.util.Objects;

/** 一般下單（同步）。 */
public interface PlaceOrderUseCase {

    OrderView place(PlaceOrderCommand command);

    /** 結帳試算：不建訂單、不扣庫存、不核銷券。 */
    CheckoutPreview preview(PreviewCommand command);

    /** 試算的輸入。 */
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
     * 而不是一個「重複請求」的錯誤——使用者連點兩次不該被懲罰
     * @param addressId 收貨地址簿的 ID。<b>訂單存的是它的快照而非這個 ID</b>——
     * 使用者日後搬家改了地址簿，這張訂單要寄到哪裡不能跟著變
     * @param lines     要買什麼、各買幾件。<b>不含價格</b>：價格一律由目錄決定，
     * 呼叫端若能指定價格，那就不叫價格了
     * @param couponId  要使用的優惠券；不用券時為 {@code null}。
     * <b>只傳 ID，不傳折抵金額</b>——與價格同一個道理，
     * 呼叫端若能指定折多少，那就不叫折扣了。
     * 滿減這類不需券的優惠由伺服器自行判定，不必也不該由呼叫端指定
     */
    record PlaceOrderCommand(Long userId, String requestId, Long addressId,
                             List<OrderItem> lines, Long couponId,
                             ShippingMethod shippingMethod, String buyerNote) {

        /** 沒有備註的下單。多載而不是逼所有呼叫端補一個 null。 */
        public PlaceOrderCommand(Long userId, String requestId, Long addressId,
                                 List<OrderItem> lines, Long couponId,
                                 ShippingMethod shippingMethod) {
            this(userId, requestId, addressId, lines, couponId, shippingMethod, null);
        }

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

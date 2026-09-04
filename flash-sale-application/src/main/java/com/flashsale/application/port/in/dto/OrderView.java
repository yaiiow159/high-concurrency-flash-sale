package com.flashsale.application.port.in.dto;

import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.ShippingInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 訂單查詢結果。
 *
 * <p>{@code processing=true} 代表庫存已扣、訂單尚在 MQ 佇列中（DB 還查不到），
 * 前端應繼續輪詢而非顯示「訂單不存在」。
 */
public record OrderView(
        String orderNo,
        Long userId,
        String channel,
        List<Line> lines,
        /** 各行小計的加總，<b>折扣前</b>。 */
        BigDecimal subtotal,
        /**
         * 折扣明細。
         *
         * <p>回明細而不是一個總額：客服要回答的是「為什麼折了 320」，
         * 而那需要知道是哪幾個優惠、各折了多少（ADR-0013 決策 3）。
         */
        List<Discount> discounts,
        /** <b>商品</b>折後應付。<b>不含運費</b>——付款金額是 {@code payableAmount}。 */
        BigDecimal totalAmount,
        /** 已扣掉免運折抵的實收運費。秒殺訂單為 0（那條通道不收地址）。 */
        BigDecimal shippingFee,
        /** 這張訂單總共付了多少 = totalAmount + shippingFee。付款與退款上限以它為準。 */
        BigDecimal payableAmount,
        String shippingMethod,
        Shipping shipping,
        String status,
        String closeReason,
        Instant createdAt,
        Instant paidAt,
        boolean processing,

        /** 仍在佇列中時的排隊資訊；訂單已建立時為 {@code null}。 */
        Queue queue
) {

    /**
     * 排隊資訊（ADR-0023）。
     *
     * <p>訂單號發出去了、訂單還不存在，這段期間使用者只看得到「處理中」。
     * 不知道是三秒還是四十分鐘，而那會讓人以為系統壞了。
     *
     * @param ahead                 前面大約還有幾筆。取自已經算好的佇列積壓，
     *                              是<b>整個佇列</b>的近似值而非精確的個人名次——
     *                              要精確就得記錄每則訊息的位移並回查，
     *                              而那是為了一個顯示欄位在熱路徑上加負擔
     * @param estimatedWaitSeconds  預估還要多久；{@code -1} 代表<b>算不出來</b>。
     *                              回 0 會被讀成「不用等」，而那不是同一件事
     */
    public record Queue(long ahead, long estimatedWaitSeconds) {
    }

    /**
     * 訂單行。刻意不含 sourceActivityId——那是內部追溯用的，前端不需要。
     *
     * @param subtotal      定價小計（單價 × 數量）
     * @param paidAmount    整單折扣分攤後<b>這一行實際付了多少</b>。
     *                      退貨頁要顯示的是這個數字，不是 {@code subtotal}——
     *                      使用者退一件商品拿回的錢，是他當初為那一件付的錢
     */
    public record Line(Long skuId, String skuSnapshot, BigDecimal unitPrice,
                       int quantity, BigDecimal subtotal, BigDecimal paidAmount) {
    }

    /** 一筆折扣的快照。{@code sourceType} 是字串而非列舉，歷史訂單才不會被規則改動影響。 */
    public record Discount(String sourceType, Long sourceId, String name, BigDecimal amount) {
    }

    /**
     * 收貨資訊快照。秒殺訂單為 {@code null}——那條通道下單當下不收集地址。
     *
     * <p>電話<b>不遮蔽</b>：這是使用者自己的訂單，遮了他就沒辦法核對收件資訊。
     * 遮蔽用在日誌那種「看的人不是資料主人」的場合。
     */
    public record Shipping(String recipientName, String phone, String postalCode,
                           String region, String district, String streetAddress,
                           String fullAddress) {

        static Shipping from(ShippingInfo info) {
            return info == null ? null : new Shipping(info.recipientName(), info.phone(),
                    info.postalCode(), info.region(), info.district(),
                    info.streetAddress(), info.fullAddress());
        }
    }

    public static OrderView from(Order order) {
        return new OrderView(
                order.orderNo().value(),
                order.userId(),
                order.channel().name(),
                order.lines().stream()
                        .map(line -> new Line(line.skuId(), line.skuSnapshot(),
                                line.unitPrice(), line.quantity(), line.subtotal(),
                                line.allocatedAmount()))
                        .toList(),
                order.lines().stream()
                        .map(OrderLine::subtotal)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                order.discounts().stream()
                        .map(discount -> new Discount(discount.sourceType(), discount.sourceId(),
                                discount.name(), discount.amount()))
                        .toList(),
                order.totalAmount(),
                order.shippingFee(),
                order.payableAmount(),
                order.shippingMethod().name(),
                Shipping.from(order.shippingInfo()),
                order.status().name(),
                order.closeReason(),
                order.createdAt(),
                order.paidAt(),
                false,
                null);
    }

    /** 庫存已扣減、訂單仍在非同步建立中。 */
    public static OrderView processing(String orderNo, Queue queue) {
        return new OrderView(orderNo, null, null, List.of(), null, List.of(), null,
                null, null, null, null,
                "PROCESSING", null, null, null, true, queue);
    }
}

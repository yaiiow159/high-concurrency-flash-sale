package com.flashsale.domain.order;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 訂單狀態機。
 *
 * <p>合法轉移集中宣告在此處，而非散落在各個 service 的 if-else 中——
 * 新增狀態時只需改這張表，編譯器與單元測試會找出所有受影響處。
 *
 * <pre>
 *   PENDING_PAYMENT ──pay()──▶ PAID ──ship()──▶ SHIPPED ──complete()──▶ COMPLETED (終態)
 *          │
 *          ├────────cancel()───────▶ CANCELLED   (終態，觸發庫存補償)
 *          └────────markFailed()──▶ FAILED       (終態，觸發庫存補償)
 *
 *   付款之後就沒有回頭路：PAID / SHIPPED / COMPLETED 都不可轉為 CANCELLED，
 *   因為取消只退庫存不退錢。退款是另一條流程，會有自己的狀態。
 * </pre>
 *
 * <h2>這裡只記里程碑，不記物流細節</h2>
 *
 * <p>「運送中」「派送中」「配送失敗」這些屬於 {@code Shipment} 聚合，
 * 不會出現在這張表上。判準是：<b>訂單狀態只收錄「會改變買家能做什麼」的轉折</b>。
 *
 * <ul>
 *   <li>{@code PAID → SHIPPED}：出貨前可自由取消，出貨後必須走退貨流程。
 *       這確實改變了買家能做的事，所以它是訂單的里程碑</li>
 *   <li>「運送中 → 派送中」：買家能做的事完全沒變，
 *       Ordering 也沒有任何邏輯分支在這上面。放進來只是把
 *       Fulfillment 的細節漏進共用聚合根</li>
 * </ul>
 *
 * <p>這條線一旦鬆掉，訂單狀態機會慢慢長成一份物流狀態的副本，
 * 而那份副本永遠會比真正的物流資料慢一步。
 */
public enum OrderStatus {

    /** 已建立、待付款。庫存已於 Redis 預扣，等待使用者付款。 */
    PENDING_PAYMENT,

    /**
     * 已付款，等待出貨。
     *
     * <p><b>不可轉為 {@code CANCELLED}</b>：錢已經收了，
     * 而取消會觸發庫存補償卻不會退錢。要退錢必須走退款流程。
     */
    PAID,

    /** 已出貨。買家不能再直接取消，要退錢必須走退貨流程。 */
    SHIPPED,

    /** 已送達，訂單完成。 */
    COMPLETED,

    /** 已取消（逾時未付款或使用者主動取消），需補償預扣庫存。 */
    CANCELLED,

    /** 建單流程異常終止（例如重試耗盡後進入 DLQ），需補償預扣庫存。 */
    FAILED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = Map.of(
            PENDING_PAYMENT, EnumSet.of(PAID, CANCELLED, FAILED),
            // 已付款「只能」往出貨走，不可取消。
            //
            // 取消會發出 OrderCancelledEvent，補償服務據此把庫存退回可售池——
            // 但錢已經收了。允許 PAID → CANCELLED 等於製造出
            // 「庫存退了、錢沒退」的路徑，而逾時關單排程隨時可能踩到它
            // （那正是 OrderTest.paidOrderCannotBeCancelled 守住的規則）。
            //
            // 已付款的訂單要退錢必須走退款流程，那會有自己的狀態，
            // 不能借用 CANCELLED——兩者對庫存與金流的意義完全不同。
            PAID, EnumSet.of(SHIPPED),
            // 出貨後不可取消。要退錢必須走退貨（P3 的退款 Saga），
            // 因為此時貨在路上，庫存不能直接退回可售池
            SHIPPED, EnumSet.of(COMPLETED),
            COMPLETED, Collections.emptySet(),
            CANCELLED, Collections.emptySet(),
            FAILED, Collections.emptySet()
    );

    public boolean canTransitionTo(OrderStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /** 終態不可再變更，且不會重複觸發補償。 */
    public boolean isFinal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }

    /** 此狀態是否代表「庫存需要退回」。 */
    public boolean requiresStockCompensation() {
        return this == CANCELLED || this == FAILED;
    }

    /**
     * 此狀態下的訂單是否還佔用著庫存。
     *
     * <p>對帳用這個判斷「哪些訂單的數量該算進已售出」。
     * <b>出貨與完成也算佔用</b>——貨已經離開倉庫，那批庫存確實不在了。
     * 少算它們會讓對帳把正常出貨誤判成庫存洩漏。
     */
    public boolean holdsStock() {
        return this == PENDING_PAYMENT || this == PAID
                || this == SHIPPED || this == COMPLETED;
    }
}

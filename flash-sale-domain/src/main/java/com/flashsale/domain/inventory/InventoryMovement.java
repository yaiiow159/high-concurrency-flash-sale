package com.flashsale.domain.inventory;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/**
 * 庫存異動流水。
 *
 * <p><b>這不是加分項，是必要的。</b>{@code available} 這個數字本身說明不了任何事：
 * 庫存出問題時，只有流水能回答「這 37 件是怎麼消失的」。
 *
 * <p>與秒殺 Redis 的扣減憑證（{@code orderNo|userId|quantity}）解決的是同一個問題，
 * 只是換到關聯式資料庫的表述。
 *
 * <h2>為什麼記兩個增減量而不是一個數量</h2>
 *
 * <p>庫存有兩個欄位（可售、已劃撥），而異動對兩者的影響<b>不是同一個數字</b>：
 *
 * <pre>
 *   ALLOCATE 30 件  →  available −30, allocated +30
 *   RELEASE（劃撥 30，剩 12）→  available +12, allocated −30
 * </pre>
 *
 * <p>釋放時「回到可售池的量」與「從劃撥中扣掉的量」根本是兩個值，
 * 兩者之差就是實際銷量。若流水只記一個 quantity，
 * 對帳就無法從流水重建出現在的庫存——而那正是流水存在的唯一理由。
 * 一份重建不出結果的流水，只是一堆看起來很像稽核紀錄的字串。
 *
 * <p>因此增減量帶正負號。這裡的正負沒有歧義：每個欄位的意義是固定的，
 * 「{@code availableDelta = −30}」只能解讀成可售量少了 30。
 */
public record InventoryMovement(
        Long skuId,
        InventoryMovementType type,
        int availableDelta,
        int allocatedDelta,
        String refType,
        String refNo,
        Instant occurredAt) {

    public InventoryMovement {
        Objects.requireNonNull(skuId, "skuId 不可為 null");
        Objects.requireNonNull(type, "type 不可為 null");
        Objects.requireNonNull(occurredAt, "occurredAt 不可為 null");
        if (availableDelta == 0 && allocatedDelta == 0) {
            // 兩邊都沒動的流水記了也沒用，只會讓稽核紀錄裡多出雜訊
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "流水至少要有一項增減");
        }
        if (refNo == null || refNo.isBlank()) {
            // 沒有來源單號的異動無法被追溯，等於沒記
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "流水必須有來源單號");
        }
    }

    /** 一般下單扣減可售量。 */
    public static InventoryMovement deduct(Long skuId, int quantity, String orderNo, Instant at) {
        return new InventoryMovement(skuId, InventoryMovementType.DEDUCT,
                -requirePositive(quantity), 0, RefType.ORDER, orderNo, at);
    }

    /** 取消或退貨退回可售量。 */
    public static InventoryMovement restore(Long skuId, int quantity, String orderNo, Instant at) {
        return new InventoryMovement(skuId, InventoryMovementType.RESTORE,
                requirePositive(quantity), 0, RefType.ORDER, orderNo, at);
    }

    /** 劃撥給活動：可售量搬到劃撥量，總量不變。 */
    public static InventoryMovement allocate(Long skuId, int quantity, Long activityId, Instant at) {
        int amount = requirePositive(quantity);
        return new InventoryMovement(skuId, InventoryMovementType.ALLOCATE,
                -amount, amount, RefType.ACTIVITY, String.valueOf(activityId), at);
    }

    /**
     * 活動結束釋放。
     *
     * @param allocatedQuantity 當初劃撥的量，會從 {@code allocated} 扣掉
     * @param unsoldQuantity    未售出的量，回到可售池。<b>允許為 0</b>——
     *                          全部賣光是正常結果，而這筆流水仍必須記，
     *                          否則 {@code allocated} 的減少就沒有任何憑據
     */
    public static InventoryMovement release(Long skuId, int allocatedQuantity,
                                            int unsoldQuantity, Long activityId, Instant at) {
        if (unsoldQuantity < 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "未售量不可為負");
        }
        return new InventoryMovement(skuId, InventoryMovementType.RELEASE,
                unsoldQuantity, -requirePositive(allocatedQuantity),
                RefType.ACTIVITY, String.valueOf(activityId), at);
    }

    /** 人工調整。{@code delta} 為負代表下修。 */
    public static InventoryMovement adjust(Long skuId, int delta, String reference, Instant at) {
        if (delta == 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "調整量不可為 0");
        }
        return new InventoryMovement(skuId, InventoryMovementType.ADJUST,
                delta, 0, RefType.MANUAL, reference, at);
    }

    /** 供顯示與告警使用的絕對數量。 */
    public int magnitude() {
        return Math.max(Math.abs(availableDelta), Math.abs(allocatedDelta));
    }

    private static int requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "數量必須大於 0");
        }
        return quantity;
    }

    /** 異動來源的種類。 */
    public static final class RefType {
        public static final String ORDER = "ORDER";
        public static final String ACTIVITY = "ACTIVITY";
        public static final String MANUAL = "MANUAL";

        private RefType() {
        }
    }
}

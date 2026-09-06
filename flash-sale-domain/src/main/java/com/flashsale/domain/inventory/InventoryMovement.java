package com.flashsale.domain.inventory;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/** 庫存異動流水。 */
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

    /** 訂單取消或建單失敗，退回可售量。 */
    public static InventoryMovement restore(Long skuId, int quantity, String orderNo, Instant at) {
        return new InventoryMovement(skuId, InventoryMovementType.RESTORE,
                requirePositive(quantity), 0, RefType.ORDER, orderNo, at);
    }

    /** 退貨驗收後退回可售量（ADR-0011）。 */
    public static InventoryMovement restoreFromReturn(Long skuId, int quantity,
                                                      String returnNo, Instant at) {
        return new InventoryMovement(skuId, InventoryMovementType.RESTORE,
                requirePositive(quantity), 0, RefType.RETURN, returnNo, at);
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
     * 全部賣光是正常結果，而這筆流水仍必須記，
     * 否則 {@code allocated} 的減少就沒有任何憑據
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
        /** 退貨單。與 ORDER 分開，因為一張訂單可以有多張退貨單。 */
        public static final String RETURN = "RETURN";

        private RefType() {
        }
    }
}

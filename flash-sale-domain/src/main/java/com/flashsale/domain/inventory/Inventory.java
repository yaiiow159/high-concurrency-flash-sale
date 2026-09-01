package com.flashsale.domain.inventory;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.Objects;

/**
 * SKU 庫存聚合根。
 *
 * <p>持有兩個數字，而不是一個：
 *
 * <pre>
 *   available   可自由販售的量
 *   allocated   已劃撥給秒殺活動、由 Redis 代管的量
 * </pre>
 *
 * <p><b>為什麼要分開：</b>秒殺與一般銷售用的是兩套完全不同的扣減機制
 * （Redis Lua 與 MySQL 樂觀鎖，見 ADR-0008）。若兩者對著同一個數字扣，
 * 就有兩個真實來源，而兩個真實來源必然導致超賣。
 *
 * <p>劃撥把庫存「切出去」：秒殺開賣期間 {@code allocated} 那部分完全由 Redis 管，
 * MySQL 這邊不再碰它；一般銷售只動 {@code available}。兩邊各自有唯一的真實來源。
 *
 * <p><b>守恆恆等式</b>（對帳依據）：
 *
 * <pre>
 *   available + allocated + 已售出 = 期初總量
 * </pre>
 *
 * <p>劃撥 N 件：{@code available -= N}、{@code allocated += N}，總量不變。
 * 活動結束時 Redis 剩 R 件：{@code allocated -= N}、{@code available += R}，
 * 總量減少 {@code N − R}，正好等於秒殺實際賣出的數量。
 */
public final class Inventory {

    private final Long skuId;
    private final long version;

    private int available;
    private int allocated;

    private Inventory(Long skuId, int available, int allocated, long version) {
        this.skuId = Objects.requireNonNull(skuId, "skuId 不可為 null");
        this.available = requireNotNegative(available, "available");
        this.allocated = requireNotNegative(allocated, "allocated");
        this.version = version;
    }

    public static Inventory create(Long skuId, int initialQuantity) {
        return new Inventory(skuId, initialQuantity, 0, 0L);
    }

    public static Inventory restore(Long skuId, int available, int allocated, long version) {
        return new Inventory(skuId, available, allocated, version);
    }

    /**
     * 一般銷售扣減。
     *
     * <p>只扣 {@code available}——劃撥出去的量不屬於這條通道，
     * 動了它就等於把秒殺的貨賣掉兩次。
     */
    public void deduct(int quantity) {
        requirePositive(quantity);
        if (available < quantity) {
            throw new BusinessException(ErrorCode.SOLD_OUT,
                    "SKU %d 可售量不足：剩 %d，需要 %d".formatted(skuId, available, quantity));
        }
        available -= quantity;
    }

    /** 取消或退貨時退回可售量。 */
    public void restoreQuantity(int quantity) {
        requirePositive(quantity);
        available += quantity;
    }

    /**
     * 劃撥給秒殺活動。
     *
     * <p>條件是 {@code available >= quantity}。這個檢查<b>不是</b>靠外層的分散式鎖
     * 保證的——鎖只降低衝突頻率，正確性由這裡與資料庫的
     * {@code UPDATE ... WHERE available >= ?} 共同守住。
     * 鎖從來不該是唯一的正確性依據（ADR-0003）。
     */
    public void allocate(int quantity) {
        requirePositive(quantity);
        if (available < quantity) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_INVENTORY_TO_ALLOCATE,
                    "SKU %d 可售量不足以劃撥：剩 %d，需要 %d".formatted(skuId, available, quantity));
        }
        available -= quantity;
        allocated += quantity;
    }

    /**
     * 活動結束後歸還未售出的部分。
     *
     * @param allocatedQuantity 當初劃撥出去的量
     * @param unsoldQuantity    Redis 上剩餘的量，會回到可售池
     *
     * <p>兩個參數都要，不能只給一個：{@code allocated} 要減掉的是<b>當初劃撥的量</b>，
     * 而回到可售池的是<b>沒賣掉的量</b>，兩者之差就是實際銷量。
     * 若只用剩餘量去減 {@code allocated}，賣掉的部分會永遠掛在 {@code allocated} 上，
     * 那個數字會隨每場活動累積，最後沒有人知道它代表什麼。
     */
    public void release(int allocatedQuantity, int unsoldQuantity) {
        requirePositive(allocatedQuantity);
        if (unsoldQuantity < 0 || unsoldQuantity > allocatedQuantity) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "未售量 %d 不合法：必須介於 0 與劃撥量 %d 之間"
                            .formatted(unsoldQuantity, allocatedQuantity));
        }
        if (allocated < allocatedQuantity) {
            // 釋放超過劃撥中的量，代表帳已經不平了。此時繼續執行會把錯誤寫進資料，
            // 讓後續對帳再也追不出原因——寧可失敗，留下可查的現場。
            throw new BusinessException(ErrorCode.INVENTORY_RELEASE_EXCEEDS_ALLOCATION,
                    "SKU %d 釋放量 %d 超過劃撥中的 %d".formatted(skuId, allocatedQuantity, allocated));
        }
        allocated -= allocatedQuantity;
        available += unsoldQuantity;
    }

    /** 手動調整可售量（盤點、補貨）。負數代表下修。 */
    public void adjust(int delta) {
        if (delta == 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "調整量不可為 0");
        }
        if (available + delta < 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "SKU %d 調整後可售量會變成負數".formatted(skuId));
        }
        available += delta;
    }

    /** 帳面總量：可售 + 已劃撥。對帳的左邊。 */
    public int totalOnHand() {
        return available + allocated;
    }

    public boolean canFulfil(int quantity) {
        return quantity > 0 && available >= quantity;
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "數量必須大於 0");
        }
    }

    private static int requireNotNegative(int value, String field) {
        if (value < 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, field + " 不可為負數");
        }
        return value;
    }

    public Long skuId() {
        return skuId;
    }

    public int available() {
        return available;
    }

    public int allocated() {
        return allocated;
    }

    public long version() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Inventory other && Objects.equals(skuId, other.skuId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(skuId);
    }

    @Override
    public String toString() {
        return "Inventory{skuId=%d, available=%d, allocated=%d, v=%d}"
                .formatted(skuId, available, allocated, version);
    }
}

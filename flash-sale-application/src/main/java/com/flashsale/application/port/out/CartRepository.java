package com.flashsale.application.port.out;

import com.flashsale.domain.cart.Cart;

import java.time.Instant;
import java.util.Optional;

/**
 * 購物車持久化埠（出站）。
 *
 * <p>沒有「建立購物車」這個操作：購物車就是某個使用者名下的品項集合，
 * {@code userId} 就是它的識別。多一張只有 id 與 user_id 的表頭，
 * 只會多出「使用者存在但購物車列不存在」這種要處理的中間態。
 */
public interface CartRepository {

    /** 查無資料時回空車而非 {@code Optional.empty()}——沒加過東西的人也有購物車，只是空的。 */
    Cart findByUserId(Long userId);

    /** 全量覆寫該使用者的購物車。品項數最多 50，整批寫回比逐筆 diff 簡單且不會漏。 */
    void save(Cart cart);

    /** 清空。結帳成功後呼叫。 */
    void clear(Long userId);

    /**
     * 刪除長期未異動的購物車列，供清理排程使用。
     *
     * @return 刪除的列數
     */
    int deleteStale(Instant updatedBefore, int limit);

    /** 供合併流程判斷伺服器端是否已有購物車。 */
    Optional<Instant> lastUpdatedAt(Long userId);
}

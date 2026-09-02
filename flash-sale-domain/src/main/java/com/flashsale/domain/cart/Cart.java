package com.flashsale.domain.cart;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 購物車聚合根。
 *
 * <h2>購物車只存 SKU 與數量，<b>不存價格</b></h2>
 *
 * <p>這一點與訂單正好相反，而且是刻意的：
 *
 * <table border="1">
 *   <caption>快照與引用的分工</caption>
 *   <tr><th></th><th>購物車</th><th>訂單</th></tr>
 *   <tr><td>價格</td><td><b>引用</b>：每次顯示都重新取</td><td><b>快照</b>：建立時凍結</td></tr>
 *   <tr><td>問的問題</td><td>「現在買要多少錢」</td><td>「當初成交是多少錢」</td></tr>
 *   <tr><td>商家調價後</td><td>必須跟著變</td><td>絕不可變</td></tr>
 * </table>
 *
 * <p>若購物車也存價格快照，商家調價之後，使用者會看到舊價格、
 * 結帳時卻被收新價格——那是最糟的一種驚訝。
 * 反過來說，訂單若用引用，歷史訂單會在調價當下集體變動。
 * <b>兩者的正確答案剛好相反，把同一套規則套到兩邊就一定有一邊是錯的。</b>
 *
 * <h2>購物車不鎖庫存</h2>
 *
 * <p>加入購物車不預扣、不預留任何庫存。否則任何人都能靠塞滿購物車
 * 把全站庫存凍結——而那不需要任何攻擊技巧，只要一個迴圈。
 *
 * <p>庫存只在<b>結帳當下</b>檢查與扣減。代價是「加到購物車時還有貨、
 * 結帳時卻沒了」，那是正確且誠實的行為：貨本來就是先到先得。
 */
public final class Cart {

    /** 品項種類上限，與訂單的 {@code MAX_LINES} 對齊——購物車裝得下卻結不了帳只會更難解釋。 */
    public static final int MAX_ITEMS = 50;

    /** 單一品項的數量上限。真正的上限由庫存決定，這裡只擋明顯不合理的輸入。 */
    public static final int MAX_QUANTITY_PER_ITEM = 999;

    private final Long userId;
    private final Map<Long, CartItem> items;

    private Cart(Long userId, List<CartItem> items) {
        this.userId = Objects.requireNonNull(userId, "userId 不可為 null");
        // LinkedHashMap 保序：使用者看到的順序應該與加入順序一致，
        // 每次重新整理都換一個順序會讓人以為東西不見了
        this.items = new LinkedHashMap<>();
        items.forEach(item -> this.items.put(item.skuId(), item));
    }

    public static Cart empty(Long userId) {
        return new Cart(userId, List.of());
    }

    public static Cart restore(Long userId, List<CartItem> items) {
        return new Cart(userId, items);
    }

    /**
     * 加入品項。同一個 SKU 會累加數量，而不是新增一行。
     *
     * <p>累加是使用者的預期：在商品頁按兩次「加入購物車」，
     * 得到的應該是數量 2，不是兩行各 1。
     */
    public void addItem(Long skuId, int quantity, Instant now) {
        requireValidQuantity(quantity);
        CartItem existing = items.get(skuId);

        if (existing == null) {
            if (items.size() >= MAX_ITEMS) {
                throw new BusinessException(ErrorCode.CART_ITEM_LIMIT_EXCEEDED,
                        "購物車最多放 %d 種商品".formatted(MAX_ITEMS));
            }
            items.put(skuId, new CartItem(skuId, quantity, now));
            return;
        }
        items.put(skuId, existing.withQuantity(
                capQuantity(existing.quantity() + quantity), now));
    }

    /** 直接設定數量（購物車頁的加減按鈕）。設為 0 等同移除。 */
    public void changeQuantity(Long skuId, int quantity, Instant now) {
        if (quantity == 0) {
            removeItem(skuId);
            return;
        }
        requireValidQuantity(quantity);
        CartItem existing = requireItem(skuId);
        items.put(skuId, existing.withQuantity(quantity, now));
    }

    public void removeItem(Long skuId) {
        if (items.remove(skuId) == null) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
    }

    /** 結帳成功後清空。 */
    public void clear() {
        items.clear();
    }

    /**
     * 把另一台裝置（或未登入時的本地）購物車併進來。
     *
     * <p><b>同一個 SKU 取兩邊較大值，而不是相加。</b>
     * 在手機上加了 2 件、在電腦上也加了 2 件的人，想要的幾乎一定是 2 件；
     * 相加會讓他在結帳頁看到 4 件，而那是他從沒按過的數字。
     *
     * <p>取大值的代價是「真的想要 4 件」的人得再調一次數量——
     * 那只是一次多餘的操作；相加的代價是買錯數量，兩者不對等。
     *
     * <p>超過品項上限時<b>保留已有的、丟棄多出來的</b>，而不是整個合併失敗：
     * 登入這個動作不該因為購物車太滿而失敗。
     */
    public void mergeFrom(Cart other, Instant now) {
        for (CartItem incoming : other.items()) {
            CartItem existing = items.get(incoming.skuId());
            if (existing != null) {
                items.put(incoming.skuId(), existing.withQuantity(
                        Math.max(existing.quantity(), incoming.quantity()), now));
            } else if (items.size() < MAX_ITEMS) {
                items.put(incoming.skuId(), new CartItem(incoming.skuId(),
                        capQuantity(incoming.quantity()), now));
            }
        }
    }

    /**
     * 移除指定的 SKU（下架、刪除等原因）。
     *
     * <p>回傳實際被移除的數量，讓呼叫端能明確告訴使用者「有 N 件已下架被移除」。
     * <b>靜默移除是不可接受的</b>——東西自己消失，使用者只會以為系統壞了。
     */
    public int removeUnavailable(List<Long> unavailableSkuIds) {
        int before = items.size();
        unavailableSkuIds.forEach(items::remove);
        return before - items.size();
    }

    public List<CartItem> items() {
        return List.copyOf(items.values());
    }

    public List<Long> skuIds() {
        return List.copyOf(items.keySet());
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int totalQuantity() {
        return items.values().stream().mapToInt(CartItem::quantity).sum();
    }

    public Long userId() {
        return userId;
    }

    private CartItem requireItem(Long skuId) {
        CartItem item = items.get(skuId);
        if (item == null) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
        return item;
    }

    private static void requireValidQuantity(int quantity) {
        if (quantity <= 0 || quantity > MAX_QUANTITY_PER_ITEM) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "數量必須介於 1 與 %d 之間".formatted(MAX_QUANTITY_PER_ITEM));
        }
    }

    /** 累加後可能超過上限；夾住而非拋錯——按了第三次「加入購物車」不該收到錯誤訊息。 */
    private static int capQuantity(int quantity) {
        return Math.min(quantity, MAX_QUANTITY_PER_ITEM);
    }

    @Override
    public String toString() {
        return "Cart{userId=%d, items=%d}".formatted(userId, items.size());
    }
}

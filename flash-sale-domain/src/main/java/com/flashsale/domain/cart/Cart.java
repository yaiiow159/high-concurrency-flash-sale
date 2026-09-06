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
 * <p><b>只存 SKU 與數量，不存價格</b>——購物車問「現在買要多少錢」，
 * 存快照的話商家調價後使用者會看到舊價格卻被收新價格。訂單剛好相反。
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

    /** 加入品項。同一個 SKU 會累加數量，而不是新增一行。 */
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

    /** 把另一台裝置（或未登入時的本地）購物車併進來。 */
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

    /** 移除指定的 SKU（下架、刪除等原因）。 */
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

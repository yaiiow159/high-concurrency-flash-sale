package com.flashsale.application.service;

import com.flashsale.application.port.in.CartUseCase;
import com.flashsale.application.port.in.dto.CartView;
import com.flashsale.application.port.out.CartRepository;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.domain.cart.Cart;
import com.flashsale.domain.cart.CartItem;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 購物車。
 *
 * <p><b>價格在每次讀取時從 Catalog 重新取，不存進購物車。</b>
 * 這是購物車與訂單最重要的差異：訂單存快照（當初成交多少錢），
 * 購物車用引用（現在買多少錢）。存錯邊的後果是使用者看到一個價格、
 * 結帳時被收另一個價格。
 *
 * <p><b>購物車完全不碰庫存。</b>加入購物車不預扣也不預留——
 * 否則任何人都能靠一個迴圈塞滿購物車，把全站庫存凍結。
 * 庫存只在結帳當下檢查與扣減。
 */
@Service
public class CartService implements CartUseCase {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final Clock clock;

    public CartService(CartRepository cartRepository,
                       ProductRepository productRepository,
                       Clock clock) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CartView view(Long userId) {
        return enrich(cartRepository.findByUserId(userId), true);
    }

    @Override
    @Transactional
    public CartView addItem(Long userId, Long skuId, int quantity) {
        // 先確認這個 SKU 真的存在且可買，再放進購物車。
        // 少了這一步，購物車會累積一堆結帳時才會失敗的品項。
        requirePurchasable(skuId);

        Cart cart = cartRepository.findByUserId(userId);
        cart.addItem(skuId, quantity, clock.instant());
        cartRepository.save(cart);
        return enrich(cart, false);
    }

    @Override
    @Transactional
    public CartView changeQuantity(Long userId, Long skuId, int quantity) {
        Cart cart = cartRepository.findByUserId(userId);
        cart.changeQuantity(skuId, quantity, clock.instant());
        cartRepository.save(cart);
        return enrich(cart, false);
    }

    @Override
    @Transactional
    public CartView removeItem(Long userId, Long skuId) {
        Cart cart = cartRepository.findByUserId(userId);
        cart.removeItem(skuId);
        cartRepository.save(cart);
        return enrich(cart, false);
    }

    @Override
    @Transactional
    public void clear(Long userId) {
        cartRepository.clear(userId);
    }

    /**
     * 合併未登入期間的本地購物車。
     *
     * <p>不存在的 SKU 直接略過而不是報錯：本地購物車可能放了好幾天，
     * 期間商品被下架是完全正常的。讓登入這個動作因為購物車裡有一件下架商品
     * 而失敗，是把系統的內部狀態變成使用者的問題。
     */
    @Override
    @Transactional
    public CartView merge(Long userId, List<LocalItem> localItems) {
        Cart server = cartRepository.findByUserId(userId);
        Cart local = Cart.empty(userId);

        int skipped = 0;
        for (LocalItem item : localItems) {
            if (!isPurchasable(item.skuId())) {
                skipped++;
                continue;
            }
            try {
                local.addItem(item.skuId(), item.quantity(), clock.instant());
            } catch (BusinessException e) {
                // 本地購物車的內容是前端送來的，格式不可信；
                // 單一品項不合法不該讓整次合併失敗
                log.debug("合併時略過不合法的品項 skuId={}, 原因={}", item.skuId(), e.getMessage());
                skipped++;
            }
        }

        server.mergeFrom(local, clock.instant());
        cartRepository.save(server);

        if (skipped > 0) {
            log.info("購物車合併完成 userId={}，略過 {} 個不可購買的品項", userId, skipped);
        }
        return enrich(server, false);
    }

    /**
     * 補上 Catalog 的資料，並處理已下架的品項。
     *
     * @param persistUnavailableRemoval 是否把「移除已下架品項」的結果寫回資料庫。
     *                                  只有單純讀取時才寫回——寫入操作已經有自己的 save，
     *                                  在那裡再存一次只是多一次往返
     */
    private CartView enrich(Cart cart, boolean persistUnavailableRemoval) {
        if (cart.isEmpty()) {
            return CartView.empty();
        }

        Map<Long, Priced> catalog = loadCatalog(cart.skuIds());

        // 查不到的 SKU 代表商品已被刪除，直接移除；下架的則留著並標記，
        // 讓使用者知道為什麼不能結帳，而不是東西默默消失
        List<Long> vanished = cart.skuIds().stream()
                .filter(skuId -> !catalog.containsKey(skuId))
                .toList();
        int removed = vanished.isEmpty() ? 0 : cart.removeUnavailable(vanished);
        if (removed > 0 && persistUnavailableRemoval) {
            cartRepository.save(cart);
            log.info("購物車移除了 {} 個已刪除的商品 userId={}", removed, cart.userId());
        }

        List<CartView.Item> items = new ArrayList<>(cart.items().size());
        BigDecimal total = BigDecimal.ZERO;
        for (CartItem item : cart.items()) {
            Priced priced = catalog.get(item.skuId());
            BigDecimal subtotal = priced.sku().price()
                    .multiply(BigDecimal.valueOf(item.quantity()));

            items.add(new CartView.Item(
                    item.skuId(),
                    priced.product().id(),
                    priced.product().name(),
                    priced.sku().spec().display(),
                    priced.sku().price(),
                    item.quantity(),
                    subtotal,
                    priced.isPurchasable()));

            // 已下架的品項不計入總額——顯示一個結不了帳的金額只會造成誤解
            if (priced.isPurchasable()) {
                total = total.add(subtotal);
            }
        }
        return new CartView(items, total, cart.totalQuantity(), removed);
    }

    /**
     * 批次載入購物車裡所有 SKU 的商品資料。
     *
     * <p><b>刻意批次而非逐筆。</b>購物車最多 50 個品項，
     * 逐筆查就是 50 次資料庫往返，而購物車頁是使用者反覆重整的頁面。
     */
    private Map<Long, Priced> loadCatalog(List<Long> skuIds) {
        Map<Long, Priced> result = new HashMap<>();
        for (Product product : productRepository.findBySkuIds(skuIds)) {
            for (Sku sku : product.skus()) {
                if (skuIds.contains(sku.id())) {
                    result.put(sku.id(), new Priced(product, sku));
                }
            }
        }
        return result;
    }

    private void requirePurchasable(Long skuId) {
        Product product = productRepository.findBySkuId(skuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SKU_NOT_FOUND));
        product.requirePurchasableSku(skuId);
    }

    private boolean isPurchasable(Long skuId) {
        return productRepository.findBySkuId(skuId)
                .map(product -> {
                    try {
                        product.requirePurchasableSku(skuId);
                        return true;
                    } catch (BusinessException e) {
                        return false;
                    }
                })
                .orElse(false);
    }

    /** 商品與其中一個 SKU 的配對，避免在迴圈裡重複查詢。 */
    private record Priced(Product product, Sku sku) {

        boolean isPurchasable() {
            return product.status().isPurchasable() && sku.isPurchasable();
        }
    }
}

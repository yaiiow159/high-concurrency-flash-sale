package com.flashsale.application.service;

import com.flashsale.application.port.in.CatalogQueryUseCase;
import com.flashsale.application.port.in.EngagementUseCase;
import com.flashsale.application.port.in.dto.ProductView;
import com.flashsale.application.port.out.EngagementRepository;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 收藏、瀏覽紀錄與「看了又看」。 */
@Service
public class EngagementService implements EngagementUseCase {

    private static final Logger log = LoggerFactory.getLogger(EngagementService.class);

    /** 收藏一頁最多這麼多，與其他列表一致。 */
    private static final int MAX_PAGE_SIZE = 50;

    /** 最近瀏覽保留幾筆。再多沒有人會往下捲，而它每一頁都要查。 */
    private static final int MAX_RECENT = 20;

    private final EngagementRepository engagementRepository;
    private final CatalogQueryUseCase catalogQuery;
    private final Clock clock;

    public EngagementService(EngagementRepository engagementRepository,
                             CatalogQueryUseCase catalogQuery,
                             Clock clock) {
        this.engagementRepository = engagementRepository;
        this.catalogQuery = catalogQuery;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void addToWishlist(Long userId, Long productId) {
        // 先確認商品存在且上架。少了這一步，收藏頁會列出點進去 404 的東西，
        // 而使用者不會知道是自己收藏了一個已下架的商品
        if (catalogQuery.findProductsByIds(List.of(productId)).isEmpty()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        engagementRepository.addToWishlist(userId, productId, clock.instant());
    }

    @Override
    @Transactional
    public void removeFromWishlist(Long userId, Long productId) {
        engagementRepository.removeFromWishlist(userId, productId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductView> wishlist(Long userId, int page, int size) {
        int capped = Math.clamp(size, 1, MAX_PAGE_SIZE);
        List<Long> ids = engagementRepository.findWishlistProductIds(
                userId, capped, Math.max(page, 0) * capped);
        return inGivenOrder(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public long wishlistCount(Long userId) {
        return engagementRepository.countWishlist(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> wishlistedAmong(Long userId, List<Long> productIds) {
        if (userId == null || productIds == null || productIds.isEmpty()) {
            return Set.of();
        }
        return engagementRepository.findWishlistedAmong(userId, productIds);
    }

    /**
     * 記一次瀏覽。
     *
     * <p>失敗不往外拋：瀏覽紀錄是附加價值，寫不進去不該讓商品頁打不開。
     *
     * <p><b>這裡刻意沒有 {@code @Transactional}。</b> 有的話這個 catch 是假的——
     * 例外會讓外層交易被標成 rollback-only，吞掉之後仍然在提交時炸成
     * {@code UnexpectedRollbackException}，使用者一樣拿到 500。
     * 交易邊界在倉庫那一層，例外傳到這裡時已經回滾完畢，catch 才真的有效。
     */
    @Override
    public void recordView(Long userId, Long productId) {
        if (userId == null) {
            return;
        }
        try {
            engagementRepository.recordView(userId, productId, clock.instant());
        } catch (RuntimeException e) {
            log.warn("記錄瀏覽失敗 userId={}, productId={}", userId, productId, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductView> recentlyViewed(Long userId, int limit) {
        if (userId == null) {
            return List.of();
        }
        return inGivenOrder(engagementRepository.findRecentlyViewed(
                userId, Math.clamp(limit, 1, MAX_RECENT)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductView> alsoViewed(Long productId, int limit) {
        return inGivenOrder(engagementRepository.findAlsoViewed(
                productId, Math.clamp(limit, 1, MAX_PAGE_SIZE)));
    }

    /**
     * 依給定的 id 順序取商品。
     *
     * <p>順序是這幾個清單的全部意義——「最近看過」與「最多人一起看」
     * 都是排出來的。已下架的查不到，直接跳過。
     */
    private List<ProductView> inGivenOrder(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductView> found = new LinkedHashMap<>();
        for (ProductView product : catalogQuery.findProductsByIds(ids)) {
            found.put(product.productId(), product);
        }
        return ids.stream().map(found::get).filter(Objects::nonNull).toList();
    }
}

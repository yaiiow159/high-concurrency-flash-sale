package com.flashsale.domain.home;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 首頁的一個版位。
 *
 * @param productIds 只有 {@link ProductSource#CURATED} 用得到，順序即顯示順序
 * @param categoryId 只有 {@link ProductSource#CATEGORY} 用得到
 */
public record HomeSection(
        Long id,
        SectionType type,
        String title,
        String subtitle,
        ProductSource source,
        Long categoryId,
        List<Long> productIds,
        int itemLimit,
        int sortOrder,
        Visibility visibility
) {

    /** 一個版位最多放幾件商品。再多首頁會變成商品列表，而那已經有專門的頁面。 */
    public static final int MAX_ITEMS = 20;

    public HomeSection {
        Objects.requireNonNull(type, "type 不可為 null");
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "版位標題不可為空");
        }
        if (itemLimit < 1 || itemLimit > MAX_ITEMS) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "版位商品數必須介於 1 與 " + MAX_ITEMS + " 之間");
        }
        productIds = productIds == null ? List.of() : List.copyOf(productIds);
        if (type == SectionType.PRODUCT_RAIL) {
            requireUsableSource(source, categoryId, productIds);
        }
    }

    /**
     * 內容來源要能真的取出東西。
     *
     * <p>少了這個檢查，設錯的版位會在首頁上變成一個空白區塊——
     * 而空白區塊看起來像壞掉，不像設定沒填完。
     */
    private static void requireUsableSource(ProductSource source, Long categoryId,
                                            List<Long> productIds) {
        if (source == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "商品版位必須指定內容來源");
        }
        if (source.needsCategory() && categoryId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "依類目取商品時必須指定類目");
        }
        if (source.isCurated() && productIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "人工選品的版位至少要選一件商品");
        }
    }

    public boolean isVisibleAt(Instant now) {
        return visibility.isVisibleAt(now);
    }
}

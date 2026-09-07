package com.flashsale.application.service;

import com.flashsale.application.port.in.CatalogQueryUseCase;
import com.flashsale.application.port.in.ProductRankingUseCase;
import com.flashsale.application.port.in.SalesReportUseCase;
import com.flashsale.application.port.in.dto.ProductView;
import com.flashsale.application.port.out.SalesReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 前台排行榜。 */
@Service
public class ProductRankingService implements ProductRankingUseCase {

    private static final int MAX_DAYS = 90;
    private static final int MAX_LIMIT = 50;

    private final SalesReportRepository salesReport;
    private final CatalogQueryUseCase catalogQuery;
    private final Clock clock;

    public ProductRankingService(SalesReportRepository salesReport,
                                 CatalogQueryUseCase catalogQuery, Clock clock) {
        this.salesReport = salesReport;
        this.catalogQuery = catalogQuery;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RankedProduct> bestSellers(int days, int limit) {
        Instant now = clock.instant();
        int window = Math.clamp(days, 1, MAX_DAYS);
        int wanted = Math.clamp(limit, 1, MAX_LIMIT);
        // 多撈一倍再過濾：榜上的商品可能剛下架，補位要從後面拿
        List<SalesReportUseCase.TopProduct> top = salesReport.topProducts(
                now.minus(Duration.ofDays(window)), now, wanted * 2);
        if (top.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductView> purchasable = new HashMap<>();
        for (ProductView product : catalogQuery.findProductsByIds(
                top.stream().map(SalesReportUseCase.TopProduct::productId).toList())) {
            purchasable.put(product.productId(), product);
        }
        List<RankedProduct> ranked = new ArrayList<>();
        for (SalesReportUseCase.TopProduct entry : top) {
            ProductView product = purchasable.get(entry.productId());
            if (product == null) {
                continue;
            }
            ranked.add(new RankedProduct(ranked.size() + 1, product, entry.quantity()));
            if (ranked.size() >= wanted) {
                break;
            }
        }
        return ranked;
    }
}

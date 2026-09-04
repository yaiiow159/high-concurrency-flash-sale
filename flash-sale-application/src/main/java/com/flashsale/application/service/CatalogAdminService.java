package com.flashsale.application.service;

import com.flashsale.application.port.in.CatalogAdminUseCase;
import com.flashsale.application.port.in.dto.ProductView;
import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.DomainEvent;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * 商品上下架。
 *
 * <p>狀態變更與索引事件在<b>同一個交易</b>裡落庫（Outbox，ADR-0004）。
 * 直接呼叫 Elasticsearch 是最直覺也最錯的做法：兩個資源無法原子提交，
 * ES 那一半失敗時資料庫已經 commit，兩邊從此分岔且沒有任何東西會發現。
 */
@Service
public class CatalogAdminService implements CatalogAdminUseCase {

    private static final Logger log = LoggerFactory.getLogger(CatalogAdminService.class);

    private final ProductRepository productRepository;
    private final EventOutbox eventOutbox;
    private final Clock clock;

    public CatalogAdminService(ProductRepository productRepository,
                               EventOutbox eventOutbox,
                               Clock clock) {
        this.productRepository = productRepository;
        this.eventOutbox = eventOutbox;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ProductView putOnShelf(Long productId) {
        Product product = require(productId);
        product.putOnShelf(clock.instant());
        return persist(product, "上架");
    }

    @Override
    @Transactional
    public ProductView takeOffShelf(Long productId) {
        Product product = require(productId);
        product.takeOffShelf(clock.instant());
        return persist(product, "下架");
    }

    private ProductView persist(Product product, String action) {
        // 事件先取出來：update() 回傳的是從 entity 重建的新聚合根，身上沒有剛註冊的事件
        List<DomainEvent> events = product.pullDomainEvents();
        Product saved = productRepository.updateStatus(product);
        eventOutbox.append(events);
        log.info("商品 {} 已{}", product.id(), action);
        return ProductView.from(saved);
    }

    private Product require(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,
                        "商品不存在: " + productId));
    }
}

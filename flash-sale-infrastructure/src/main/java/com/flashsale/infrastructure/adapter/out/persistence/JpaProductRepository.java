package com.flashsale.infrastructure.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.ProductCursor;
import com.flashsale.domain.catalog.ProductSort;
import com.flashsale.domain.catalog.ProductStatus;
import com.flashsale.domain.catalog.ProductSummary;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.catalog.SkuSpec;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.infrastructure.adapter.out.persistence.entity.ProductEntity;
import com.flashsale.infrastructure.adapter.out.persistence.entity.SkuEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.ProductJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 商品持久化埠的 JPA 實作。 */
@Repository
public class JpaProductRepository implements ProductRepository {

    /** 規格屬性以 LinkedHashMap 反序列化，保住營運設定的顯示順序。 */
    private static final TypeReference<LinkedHashMap<String, String>> SPEC_TYPE =
            new TypeReference<>() {
            };

    private final ProductJpaRepository jpaRepository;

    /** 列表查詢是動態拼裝的原生 SQL（見 ProductListingQuery），走不了具名查詢。 */
    @PersistenceContext
    private EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public JpaProductRepository(ProductJpaRepository jpaRepository, ObjectMapper objectMapper) {
        this.jpaRepository = jpaRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Product save(Product product) {
        ProductEntity entity = product.id() == null
                ? newEntity(product)
                : updateEntity(product);
        return toDomain(jpaRepository.save(entity));
    }

    private ProductEntity newEntity(Product product) {
        ProductEntity entity = new ProductEntity(product.categoryId(), product.name(),
                product.brand(), product.description(), product.status().name(), product.createdAt());
        product.skus().forEach(sku -> entity.addSku(new SkuEntity(
                writeSpec(sku.spec()), sku.price(), sku.barcode(), sku.status().name(),
                sku.weightGrams())));
        return entity;
    }

    private ProductEntity updateEntity(Product product) {
        ProductEntity entity = jpaRepository.findWithSkusById(product.id())
                .orElseThrow(() -> new IllegalStateException("更新時找不到商品 " + product.id()));
        entity.applyChanges(product.name(), product.brand(), product.description(),
                product.status().name());
        // SKU 的新增／移除屬於營運後台的範圍（P4），此處只同步狀態與價格
        Map<Long, Sku> incoming = product.skus().stream()
                .filter(sku -> sku.id() != null)
                .collect(java.util.stream.Collectors.toMap(Sku::id, sku -> sku));
        entity.getSkus().forEach(skuEntity -> {
            Sku sku = incoming.get(skuEntity.getId());
            if (sku != null) {
                skuEntity.applyChanges(writeSpec(sku.spec()), sku.price(),
                        sku.barcode(), sku.status().name());
            }
        });
        return entity;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Product> findById(Long productId) {
        return jpaRepository.findWithSkusById(productId).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> findOnShelfIds() {
        return Set.copyOf(jpaRepository.findOnShelfIds());
    }

    @Override
    @Transactional
    public Product updateStatus(Product product) {
        ProductEntity entity = jpaRepository.findById(product.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,
                        "商品不存在: " + product.id()));
        entity.applyStatus(product.status().name());
        return toDomain(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> searchByKeyword(String keyword, Long categoryId, String brand,
                                         int limit, int offset) {
        return jpaRepository
                .searchByKeyword(keyword == null ? "" : keyword, categoryId,
                        brand == null || brand.isBlank() ? null : brand,
                        Pageables.of(limit, offset))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> findAllByStatus(String status, int limit, int offset) {
        // 空字串正規化成 null。前端的「全部」選項送出的是空字串，
        // 直接拿去比對會得到零筆——症狀是「後台一片空白」而不是任何錯誤
        String normalized = status == null || status.isBlank() ? null : status;
        return jpaRepository
                .findAllByStatus(normalized, Pageables.of(limit, offset))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> findOnShelf(Long categoryId, int limit, int offset) {
        return jpaRepository
                .findOnShelf(categoryId, Pageables.of(limit, offset))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * 商店列表查詢：<b>固定兩次</b>查詢，與頁大小、與翻到第幾頁都無關。
     *
     * <p>第一次取整頁（keyset，ADR-0021），第二次用一次 {@code in (...)}
     * 把最低價全部帶回來。
     *
     * <p>最低價<b>顯示</b>用批次查詢，<b>排序</b>用 product 上反正規化的
     * {@code lowest_price} 欄位——排序要在資料庫裡對 5 萬列做，
     * 而相關子查詢會重建剛消掉的那個懸崖。
     */
    @Override
    @Transactional(readOnly = true)
    public List<ProductSummary> findOnShelfSummaries(Collection<Long> categoryIds,
                                                     ProductSort sort, ProductCursor cursor,
                                                     int limit) {
        String sql = ProductListingQuery.build(sort, categoryIds, cursor);
        var query = entityManager.createNativeQuery(sql).setParameter("limit", Math.max(limit, 1));
        if (categoryIds != null && !categoryIds.isEmpty()) {
            query.setParameter("categoryIds", categoryIds);
        }
        if (cursor != null) {
            query.setParameter("cursorId", cursor.id());
            if (ProductListingQuery.sortExpression(sort) != null) {
                query.setParameter("cursorSort", cursor.sortValue());
            }
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        if (rows.isEmpty()) {
            return List.of();
        }

        List<ProductSummary> page = rows.stream()
                .map(row -> new ProductSummary(
                        ((Number) row[0]).longValue(),
                        ((Number) row[1]).longValue(),
                        (String) row[2],
                        (String) row[3],
                        ProductStatus.ON_SHELF,
                        null,
                        // 游標直接用資料庫回來的排序值組，不在 Java 端重算——
                        // 重算就有兩份定義，而它們遲早會不一致
                        cursorOf(row[4], ((Number) row[0]).longValue())))
                .toList();

        Map<Long, BigDecimal> lowestPrices = jpaRepository
                .findLowestPrices(page.stream().map(ProductSummary::id).toList()).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (BigDecimal) row[1]));

        return page.stream()
                // 沒有任何可購買 SKU 的商品最低價為 null，前端顯示成「—」。
                // 補一個 0 會讓它排到價格排序的最前面，那是錯的
                .map(summary -> summary.withLowestPrice(lowestPrices.get(summary.id())))
                .toList();
    }

    /**
     * 用資料庫回來的排序值組游標。
     *
     * <p>排序值為 {@code null} 代表這次是依 id 排序，游標只需要 id。
     */
    private static String cursorOf(Object sortValue, Long id) {
        if (sortValue == null) {
            return ProductCursor.ofId(id).encode();
        }
        BigDecimal value = sortValue instanceof BigDecimal decimal
                ? decimal
                : BigDecimal.valueOf(((Number) sortValue).doubleValue());
        return ProductCursor.of(value, id).encode();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Product> findBySkuId(Long skuId) {
        return jpaRepository.findBySkuId(skuId).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> findBySkuIds(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return List.of();
        }
        return jpaRepository.findBySkuIds(skuIds).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Sku> findSkusByIds(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            // 空集合會產生 `in ()` 這種在部分資料庫上非法的 SQL
            return List.of();
        }
        return jpaRepository.findSkusByIds(skuIds).stream().map(this::toSku).toList();
    }

    private Product toDomain(ProductEntity entity) {
        return Product.restore(
                entity.getId(),
                entity.getCategoryId(),
                entity.getName(),
                entity.getBrand(),
                entity.getDescription(),
                ProductStatus.valueOf(entity.getStatus()),
                entity.getSkus().stream().map(this::toSku).toList(),
                entity.getCreatedAt());
    }

    private Sku toSku(SkuEntity entity) {
        return Sku.restore(
                entity.getId(),
                entity.getProduct().getId(),
                SkuSpec.of(readSpec(entity.getSpecJson())),
                entity.getPrice(),
                entity.getBarcode(),
                ProductStatus.valueOf(entity.getStatus()),
                entity.getWeightGrams());
    }

    private String writeSpec(SkuSpec spec) {
        try {
            return objectMapper.writeValueAsString(spec.attributes());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SKU 規格序列化失敗", e);
        }
    }

    private Map<String, String> readSpec(String json) {
        try {
            return objectMapper.readValue(json, SPEC_TYPE);
        } catch (JsonProcessingException e) {
            // 規格損毀不該讓整個商品查詢失敗——回一個可辨識的佔位值，
            // 讓問題在畫面上可見而非讓整頁 500
            throw new IllegalStateException("SKU 規格無法解析: " + json, e);
        }
    }
}

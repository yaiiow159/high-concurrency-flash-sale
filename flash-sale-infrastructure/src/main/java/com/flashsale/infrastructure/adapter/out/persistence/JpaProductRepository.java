package com.flashsale.infrastructure.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.domain.catalog.ProductStatus;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.catalog.SkuSpec;
import com.flashsale.infrastructure.adapter.out.persistence.entity.ProductEntity;
import com.flashsale.infrastructure.adapter.out.persistence.entity.SkuEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.ProductJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 商品持久化埠的 JPA 實作。 */
@Repository
public class JpaProductRepository implements ProductRepository {

    /** 規格屬性以 LinkedHashMap 反序列化，保住營運設定的顯示順序。 */
    private static final TypeReference<LinkedHashMap<String, String>> SPEC_TYPE =
            new TypeReference<>() {
            };

    private final ProductJpaRepository jpaRepository;
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
                writeSpec(sku.spec()), sku.price(), sku.barcode(), sku.status().name())));
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
                        PageRequest.of(offset / Math.max(limit, 1), limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> findOnShelf(Long categoryId, int limit, int offset) {
        return jpaRepository
                .findOnShelf(categoryId, PageRequest.of(offset / Math.max(limit, 1), limit))
                .stream()
                .map(this::toDomain)
                .toList();
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
                ProductStatus.valueOf(entity.getStatus()));
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

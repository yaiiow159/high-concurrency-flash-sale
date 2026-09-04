package com.flashsale.domain.catalog;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("商品目錄")
class ProductTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");

    @Nested
    @DisplayName("SPU / SKU 分離")
    class SpuSkuSeparation {

        @Test
        @DisplayName("同一商品的不同規格可有不同價格——這正是分離的理由")
        void skusCarryTheirOwnPrice() {
            Product product = phoneWithTwoSkus();

            assertThat(product.skus())
                    .extracting(Sku::price)
                    .containsExactly(new BigDecimal("29900.00"), new BigDecimal("35900.00"));
        }

        @Test
        @DisplayName("列表顯示的「起」價取最低的 SKU")
        void lowestPriceIsMinimumAcrossSkus() {
            assertThat(phoneWithTwoSkus().lowestPrice())
                    .isEqualByComparingTo(new BigDecimal("29900.00"));
        }

        @Test
        @DisplayName("沒有 SKU 的商品無法被購買也無法定價——那不是商品")
        void rejectsProductWithoutSku() {
            assertThatThrownBy(() -> Product.create(2L, "空商品", "Test", null, List.of(), NOW))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("規格屬性")
    class Specs {

        @Test
        @DisplayName("保序——「256G 黑」與「黑 256G」讀起來不同")
        void preservesAttributeOrder() {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("容量", "256G");
            attributes.put("顏色", "黑");

            assertThat(SkuSpec.of(attributes).display()).isEqualTo("256G / 黑");
        }

        @Test
        @DisplayName("建構後改動原 Map 不影響已建立的規格")
        void isImmutableAfterConstruction() {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("容量", "256G");
            SkuSpec spec = SkuSpec.of(attributes);

            attributes.put("顏色", "黑");

            assertThat(spec.attributes()).hasSize(1);
        }

        @Test
        @DisplayName("空規格與空值都拒絕")
        void rejectsEmptyOrBlank() {
            assertThatThrownBy(() -> SkuSpec.of(Map.of())).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> SkuSpec.of(Map.of("容量", " ")))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("上下架")
    class ShelfStatus {

        @Test
        @DisplayName("新建商品為草稿，不會意外對外開賣")
        void newProductIsDraft() {
            assertThat(phoneWithTwoSkus().status()).isEqualTo(ProductStatus.DRAFT);
        }

        @Test
        @DisplayName("未上架的商品不可購買，且錯誤訊息要能區分原因")
        void draftProductIsNotPurchasable() {
            Product product = phoneWithTwoSkus();

            assertThatThrownBy(() -> product.requirePurchasableSku(1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.PRODUCT_NOT_PURCHASABLE);
        }

        @Test
        @DisplayName("上架後可購買")
        void onShelfProductIsPurchasable() {
            Product product = persistedPhone(ProductStatus.ON_SHELF);

            assertThatCode(() -> product.requirePurchasableSku(2001L)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("不存在的規格：回 SKU_NOT_FOUND 而非籠統的錯誤")
        void reportsMissingSkuPrecisely() {
            Product product = persistedPhone(ProductStatus.ON_SHELF);

            assertThatThrownBy(() -> product.requirePurchasableSku(9999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.SKU_NOT_FOUND);
        }

        @Test
        @DisplayName("下架不刪除資料——歷史訂單仍需追溯這是哪個商品")
        void takeOffShelfKeepsData() {
            Product product = persistedPhone(ProductStatus.ON_SHELF);

            product.takeOffShelf(NOW);

            assertThat(product.status()).isEqualTo(ProductStatus.OFF_SHELF);
            assertThat(product.skus()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("類目樹")
    class Categories {

        @Test
        @DisplayName("根類目層級為 1 且無父")
        void rootHasNoParent() {
            Category root = Category.root(1L, "3C 產品", 1);

            assertThat(root.isRoot()).isTrue();
            assertThat(root.level()).isEqualTo(Category.ROOT_LEVEL);
        }

        @Test
        @DisplayName("子類目層級為父加一——樹的一致性在建構時就強制")
        void childLevelDerivesFromParent() {
            Category root = Category.root(1L, "3C 產品", 1);

            Category child = Category.child(2L, root, "手機", 1);

            assertThat(child.level()).isEqualTo(2);
            assertThat(child.parentId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("層級與父關係不一致：拒絕重建，避免產生無法渲染的樹")
        void rejectsInconsistentLevel() {
            // 宣稱是根卻給了 level 3
            assertThatThrownBy(() -> Category.restore(1L, null, "壞掉的類目", 3, 0))
                    .isInstanceOf(BusinessException.class);
            // 宣稱有父卻給了根層級
            assertThatThrownBy(() -> Category.restore(2L, 1L, "壞掉的子類目", 1, 0))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("層級過深：拒絕。沒有上限的類目樹遲早沒人維護得動")
        void rejectsTooDeepTree() {
            assertThatThrownBy(() -> Category.restore(9L, 8L, "太深了", 5, 0))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ---- fixtures ----

    private static Product phoneWithTwoSkus() {
        return Product.create(2L, "iPhone 16 Pro", "Apple", "旗艦機種", List.of(
                Sku.create(1L, spec("256G"), new BigDecimal("29900.00"), "IP16P-256"),
                Sku.create(1L, spec("512G"), new BigDecimal("35900.00"), "IP16P-512")
        ), NOW);
    }

    private static Product persistedPhone(ProductStatus status) {
        return Product.restore(1L, 2L, "iPhone 16 Pro", "Apple", "旗艦機種", status, List.of(
                Sku.restore(2001L, 1L, spec("256G"), new BigDecimal("29900.00"), "IP16P-256", status)
        ), NOW);
    }

    private static SkuSpec spec(String capacity) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("容量", capacity);
        return SkuSpec.of(attributes);
    }
}

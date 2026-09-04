package com.flashsale.application.service;

import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.ProductStatus;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.catalog.SkuSpec;
import com.flashsale.domain.catalog.event.ProductIndexChangedEvent;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.DomainEvent;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品上下架。
 *
 * <h2>要守的是「狀態變更與索引事件同生共死」</h2>
 *
 * <p>直接呼叫 Elasticsearch 是最直覺也最錯的做法：兩個資源無法原子提交，
 * ES 那一半失敗時資料庫已經 commit，<b>兩邊從此分岔且沒有任何東西會發現</b>。
 * 因此事件必須寫進 outbox 而不是直接發出去。
 *
 * <p>另一條容易寫錯的是取事件的時機：{@code updateStatus} 回傳的是從 entity
 * 重建的新聚合根，身上沒有剛剛註冊的事件。先 update 再 pull 會拿到空清單，
 * 而症狀是「商品下架了但搜尋還找得到」——沒有任何錯誤訊息。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("商品上下架")
class CatalogAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");
    private static final long PRODUCT_ID = 1L;

    @Mock
    private ProductRepository productRepository;
    @Mock
    private EventOutbox eventOutbox;

    private CatalogAdminService service;

    @BeforeEach
    void setUp() {
        service = new CatalogAdminService(productRepository, eventOutbox,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Product product(ProductStatus status) {
        Sku sku = Sku.restore(100L, PRODUCT_ID, SkuSpec.of(Map.of("容量", "256G")),
                new BigDecimal("29900"), "BARCODE-1", status);
        return Product.restore(PRODUCT_ID, 10L, "測試商品", "測試品牌", "說明",
                status, List.of(sku), NOW);
    }

    /** 模擬真實的 repository：回傳一個從 entity 重建、身上沒有事件的新聚合根。 */
    private void stubRepository(ProductStatus current) {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(current)));
        when(productRepository.updateStatus(any()))
                .thenAnswer(call -> product(((Product) call.getArgument(0)).status()));
    }

    @Test
    @DisplayName("下架會寫出索引事件——沒寫的話商品下架了但搜尋還找得到")
    void takeOffShelfEmitsIndexEvent() {
        stubRepository(ProductStatus.ON_SHELF);

        service.takeOffShelf(PRODUCT_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DomainEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventOutbox).append(captor.capture());
        assertThat(captor.getValue())
                .as("update() 回傳的是重建的新物件，先 update 再 pull 會拿到空清單")
                .singleElement()
                .isInstanceOf(ProductIndexChangedEvent.class);
    }

    @Test
    @DisplayName("上架同樣寫出索引事件")
    void putOnShelfEmitsIndexEvent() {
        stubRepository(ProductStatus.OFF_SHELF);

        service.putOnShelf(PRODUCT_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DomainEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventOutbox).append(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("事件走 outbox 而不是直接打搜尋引擎——兩個資源無法原子提交")
    void eventGoesThroughOutbox() {
        stubRepository(ProductStatus.ON_SHELF);

        service.takeOffShelf(PRODUCT_ID);

        // 這條的意義是結構性的：這個服務不該認得任何搜尋相關的埠。
        // 它的建構子只有 repository、outbox、clock——多一個就是把
        // 「同一個交易內完成兩件事」的保證拆掉了
        verify(eventOutbox).append(any());
    }

    @Test
    @DisplayName("商品不存在時不寫任何事件")
    void missingProductEmitsNothing() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.takeOffShelf(PRODUCT_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_NOT_FOUND);

        verify(eventOutbox, never()).append(any());
        verify(productRepository, never()).updateStatus(any());
    }
}

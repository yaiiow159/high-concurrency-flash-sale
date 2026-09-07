package com.flashsale.application.service;

import com.flashsale.application.port.in.dto.PromotionAdminView;
import com.flashsale.application.port.in.dto.PromotionCommand;
import com.flashsale.application.port.out.PromotionRepository;
import com.flashsale.application.port.out.PromotionRepository.CouponStats;
import com.flashsale.domain.promotion.DiscountType;
import com.flashsale.domain.promotion.Promotion;
import com.flashsale.domain.promotion.PromotionRule;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("後台優惠管理")
class PromotionAdminServiceTest {

    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-12-31T00:00:00Z");

    private PromotionRepository promotionRepository;
    private PromotionAdminService service;

    @BeforeEach
    void setUp() {
        promotionRepository = mock(PromotionRepository.class);
        when(promotionRepository.save(any())).thenAnswer(invocation -> {
            Promotion p = invocation.getArgument(0);
            return p.id() == null ? p.withEnabled(p.enabled()) : p;
        });
        service = new PromotionAdminService(promotionRepository);
    }

    private static PromotionCommand coupon(String name, DiscountType type) {
        return new PromotionCommand(name, type, PromotionRule.FIXED_AMOUNT,
                new BigDecimal("1000"), new BigDecimal("100"), null, null, START, END, true);
    }

    private static Promotion existing(long id, DiscountType type) {
        return Promotion.of(id, "既有", type, PromotionRule.FIXED_AMOUNT,
                new BigDecimal("1000"), new BigDecimal("100"), null, START, END, true);
    }

    @Test
    @DisplayName("列表：把發券與核銷數併進每一筆；沒發過券的顯示 0")
    void listMergesCouponStats() {
        when(promotionRepository.findAll(any(), anyInt(), anyInt()))
                .thenReturn(List.of(existing(1L, DiscountType.COUPON), existing(2L, DiscountType.SHIPPING)));
        when(promotionRepository.count(any())).thenReturn(2L);
        when(promotionRepository.couponStats(List.of(1L, 2L))).thenReturn(Map.of(1L, new CouponStats(10, 4)));

        var page = service.list(null, 0, 20);

        assertThat(page.total()).isEqualTo(2L);
        assertThat(page.items()).extracting(PromotionAdminView::issuedCoupons).containsExactly(10L, 0L);
        assertThat(page.items()).extracting(PromotionAdminView::usedCoupons).containsExactly(4L, 0L);
    }

    @Test
    @DisplayName("建立：比例折扣沒有上限被領域擋下，不會存進去")
    void createRejectsPercentageWithoutCap() {
        PromotionCommand command = new PromotionCommand("九折", DiscountType.COUPON, PromotionRule.PERCENTAGE,
                BigDecimal.ZERO, new BigDecimal("0.1"), null, null, START, END, true);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PARAMETER);

        verify(promotionRepository, never()).save(any());
    }

    @Test
    @DisplayName("修改：type 建立後不可改——已發出的券換了 type 就不知道自己是什麼了")
    void updateRejectsTypeChange() {
        when(promotionRepository.findPromotionById(1L)).thenReturn(Optional.of(existing(1L, DiscountType.COUPON)));

        assertThatThrownBy(() -> service.update(1L, coupon("改名", DiscountType.SHIPPING)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PARAMETER);

        verify(promotionRepository, never()).save(any());
    }

    @Test
    @DisplayName("停用：其餘欄位原封不動")
    void disableKeepsEverythingElse() {
        when(promotionRepository.findPromotionById(1L)).thenReturn(Optional.of(existing(1L, DiscountType.COUPON)));
        when(promotionRepository.couponStats(any())).thenReturn(Map.of());

        PromotionAdminView view = service.setEnabled(1L, false);

        assertThat(view.enabled()).isFalse();
        assertThat(view.name()).isEqualTo("既有");
        assertThat(view.threshold()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("不存在：PROMOTION_NOT_FOUND")
    void notFound() {
        when(promotionRepository.findPromotionById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setEnabled(99L, true))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PROMOTION_NOT_FOUND);
    }
}

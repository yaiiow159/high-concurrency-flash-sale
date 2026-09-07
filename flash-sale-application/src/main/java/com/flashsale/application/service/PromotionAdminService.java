package com.flashsale.application.service;

import com.flashsale.application.port.in.PromotionAdminUseCase;
import com.flashsale.application.port.in.dto.PageView;
import com.flashsale.application.port.in.dto.PromotionAdminView;
import com.flashsale.application.port.in.dto.PromotionCommand;
import com.flashsale.application.port.out.PromotionRepository;
import com.flashsale.application.port.out.PromotionRepository.CouponStats;
import com.flashsale.domain.promotion.DiscountType;
import com.flashsale.domain.promotion.Promotion;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.domain.shared.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class PromotionAdminService implements PromotionAdminUseCase {

    private static final int MAX_PAGE_SIZE = 100;

    private final PromotionRepository promotionRepository;

    public PromotionAdminService(PromotionRepository promotionRepository) {
        this.promotionRepository = promotionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<PromotionAdminView> list(String type, int page, int size) {
        Page paging = Page.of(page, size, MAX_PAGE_SIZE);
        DiscountType typeFilter = parseType(type);
        List<Promotion> promotions = promotionRepository.findAll(typeFilter, paging.size(), paging.offset());
        Map<Long, CouponStats> stats = promotionRepository.couponStats(
                promotions.stream().map(Promotion::id).toList());
        List<PromotionAdminView> items = promotions.stream()
                .map(promotion -> PromotionAdminView.from(promotion, stats.get(promotion.id())))
                .toList();
        return PageView.of(items, promotionRepository.count(typeFilter), paging.number(), paging.size());
    }

    @Override
    @Transactional
    public PromotionAdminView create(PromotionCommand command) {
        Promotion saved = promotionRepository.save(command.toPromotion(null));
        return PromotionAdminView.from(saved, null);
    }

    @Override
    @Transactional
    public PromotionAdminView update(Long promotionId, PromotionCommand command) {
        Promotion existing = load(promotionId);
        if (existing.type() != command.type()) {
            // type 決定折扣的計算順序與券要不要發；已經發出去的券換了 type 就不知道自己是什麼了
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "優惠類型建立後不可更改");
        }
        Promotion saved = promotionRepository.save(command.toPromotion(promotionId));
        return withStats(saved);
    }

    @Override
    @Transactional
    public PromotionAdminView setEnabled(Long promotionId, boolean enabled) {
        Promotion saved = promotionRepository.save(load(promotionId).withEnabled(enabled));
        return withStats(saved);
    }

    private PromotionAdminView withStats(Promotion promotion) {
        return PromotionAdminView.from(promotion,
                promotionRepository.couponStats(List.of(promotion.id())).get(promotion.id()));
    }

    private Promotion load(Long promotionId) {
        return promotionRepository.findPromotionById(promotionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROMOTION_NOT_FOUND));
    }

    private static DiscountType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return DiscountType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "參數「type」的值不正確");
        }
    }
}

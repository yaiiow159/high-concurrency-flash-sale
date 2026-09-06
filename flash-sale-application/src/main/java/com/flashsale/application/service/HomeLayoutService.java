package com.flashsale.application.service;

import com.flashsale.application.port.in.CatalogQueryUseCase;
import com.flashsale.application.port.in.HomeLayoutUseCase;
import com.flashsale.application.port.in.dto.HomeLayoutView;
import com.flashsale.application.port.in.dto.ProductView;
import com.flashsale.application.port.out.HomeLayoutRepository;
import com.flashsale.application.port.out.MediaStorage;
import com.flashsale.domain.home.CarouselSlide;
import com.flashsale.domain.home.HomeSection;
import com.flashsale.domain.home.ProductSource;
import com.flashsale.domain.home.SectionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 組出首頁內容。
 *
 * <p>回應不含任何身分相關的資料，這是首頁能被 CDN 與 ISR 快取的前提。
 */
@Service
public class HomeLayoutService implements HomeLayoutUseCase {

    private static final Logger log = LoggerFactory.getLogger(HomeLayoutService.class);

    private final HomeLayoutRepository layoutRepository;
    private final CatalogQueryUseCase catalogQuery;
    private final MediaStorage mediaStorage;
    private final Clock clock;

    public HomeLayoutService(HomeLayoutRepository layoutRepository,
                             CatalogQueryUseCase catalogQuery,
                             MediaStorage mediaStorage,
                             Clock clock) {
        this.layoutRepository = layoutRepository;
        this.catalogQuery = catalogQuery;
        this.mediaStorage = mediaStorage;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public HomeLayoutView currentLayout() {
        Instant now = clock.instant();
        List<CarouselSlide> slides = layoutRepository.findAllSlides().stream()
                .filter(slide -> slide.isVisibleAt(now))
                .sorted(Comparator.comparingInt(CarouselSlide::sortOrder))
                .toList();

        List<HomeLayoutView.SectionView> sections = layoutRepository.findAllSections().stream()
                .filter(section -> section.isVisibleAt(now))
                .sorted(Comparator.comparingInt(HomeSection::sortOrder))
                .map(section -> toView(section, slides))
                .filter(view -> !isEmptyRail(view))
                .toList();

        return new HomeLayoutView(sections);
    }

    /**
     * 空的商品版位不輸出。
     *
     * <p>「當季限定」選的商品全部下架之後，留著會在首頁上變成一塊空白，
     * 而空白看起來像壞掉。整個版位消失才是對的。
     */
    private static boolean isEmptyRail(HomeLayoutView.SectionView view) {
        return SectionType.PRODUCT_RAIL.name().equals(view.type()) && view.products().isEmpty();
    }

    private HomeLayoutView.SectionView toView(HomeSection section, List<CarouselSlide> slides) {
        return new HomeLayoutView.SectionView(
                section.id(),
                section.type().name(),
                section.title(),
                section.subtitle(),
                section.type() == SectionType.CAROUSEL ? toSlideViews(slides) : List.of(),
                section.type() == SectionType.PRODUCT_RAIL ? resolveProducts(section) : List.of());
    }

    private List<HomeLayoutView.SlideView> toSlideViews(List<CarouselSlide> slides) {
        return slides.stream()
                .map(slide -> new HomeLayoutView.SlideView(slide.id(),
                        mediaStorage.publicUrl(slide.objectKey()), slide.title(), slide.linkUrl()))
                .toList();
    }

    private List<ProductView> resolveProducts(HomeSection section) {
        try {
            return section.source().isCurated()
                    ? curated(section)
                    : byRule(section);
        } catch (RuntimeException e) {
            // fail-open：一個版位取不到商品不該讓整個首頁掛掉
            log.warn("版位 {} 取商品失敗，本次略過", section.id(), e);
            return List.of();
        }
    }

    private List<ProductView> byRule(HomeSection section) {
        return catalogQuery.listProducts(section.categoryId(), sortNameOf(section.source()),
                null, (BigDecimal) null, null, section.itemLimit()).items();
    }

    /**
     * 版位來源對應到目錄的排序名稱。
     *
     * <p>直接傳 {@code source.name()} 會讓兩個列舉被綁死——
     * 領域層的「熱門商品」不必知道目錄那邊把它叫做 BEST_SELLING。
     */
    private static String sortNameOf(ProductSource source) {
        return switch (source) {
            case BEST_SELLING -> "BEST_SELLING";
            case TOP_RATED -> "RATING";
            case CATEGORY, CURATED, NEWEST -> "NEWEST";
        };
    }

    /**
     * 人工選品。
     *
     * <p>照設定的順序輸出，而不是資料庫回來的順序——選品的重點就是那個順序。
     * 中途下架的商品會查不到，直接跳過。
     */
    private List<ProductView> curated(HomeSection section) {
        Map<Long, ProductView> found = new LinkedHashMap<>();
        for (ProductView product : catalogQuery.findProductsByIds(section.productIds())) {
            found.put(product.productId(), product);
        }
        return section.productIds().stream()
                .map(found::get)
                .filter(java.util.Objects::nonNull)
                .limit(section.itemLimit())
                .toList();
    }
}

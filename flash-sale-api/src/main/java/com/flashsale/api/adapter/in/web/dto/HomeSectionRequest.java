package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.domain.home.HomeSection;
import com.flashsale.domain.home.ProductSource;
import com.flashsale.domain.home.SectionType;
import com.flashsale.domain.home.Visibility;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 新增／修改版位。內容規則的檢查在 {@link HomeSection} 的建構子，這裡只擋格式。 */
public record HomeSectionRequest(
        @NotNull(message = "請選擇版位類型") SectionType type,
        @NotBlank(message = "標題不可為空") @Size(max = 64) String title,
        @Size(max = 128) String subtitle,
        ProductSource source,
        Long categoryId,
        @Size(max = HomeSection.MAX_ITEMS, message = "選品數量過多") List<Long> productIds,
        @Min(1) @Max(HomeSection.MAX_ITEMS) int itemLimit,
        int sortOrder,
        boolean enabled,
        Instant visibleFrom,
        Instant visibleTo
) {

    public HomeSection toDomain(Long id) {
        return new HomeSection(id, type, title, subtitle, source, categoryId,
                productIds == null ? List.of() : productIds,
                itemLimit, sortOrder, new Visibility(enabled, visibleFrom, visibleTo));
    }

    public record View(Long sectionId, String type, String title, String subtitle, String source,
                       Long categoryId, List<Long> productIds, int itemLimit, int sortOrder,
                       boolean enabled, Instant visibleFrom, Instant visibleTo) {

        public static View from(HomeSection section) {
            return new View(section.id(), section.type().name(), section.title(),
                    section.subtitle(),
                    section.source() == null ? null : section.source().name(),
                    section.categoryId(), section.productIds(), section.itemLimit(),
                    section.sortOrder(), section.visibility().enabled(),
                    section.visibility().from(), section.visibility().to());
        }
    }
}

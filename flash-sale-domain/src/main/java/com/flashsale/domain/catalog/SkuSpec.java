package com.flashsale.domain.catalog;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SKU 的規格屬性，例如 {@code {容量: 256G, 顏色: 黑}}。
 *
 * <p>用 {@link LinkedHashMap} 保序：規格的顯示順序是有意義的——
 * 「256G 黑」與「黑 256G」讀起來不同，而營運設定的順序就是他們想要的順序。
 * 用一般的 HashMap 會讓同一個 SKU 在不同機器上顯示出不同的字串。
 *
 * <p>存成鍵值對而非單一字串，是為了讓「依顏色篩選」這類查詢日後成為可能；
 * 若只存 "256G 黑"，那就只能做字串比對。
 */
public record SkuSpec(Map<String, String> attributes) {

    private static final int MAX_ATTRIBUTES = 10;

    public SkuSpec {
        if (attributes == null || attributes.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "SKU 規格不可為空");
        }
        if (attributes.size() > MAX_ATTRIBUTES) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "SKU 規格屬性不可超過 " + MAX_ATTRIBUTES + " 項");
        }
        attributes.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "SKU 規格的鍵與值都不可為空");
            }
        });
        // 複製並保序，避免呼叫端在建構後改動內容
        attributes = new LinkedHashMap<>(attributes);
    }

    public static SkuSpec of(Map<String, String> attributes) {
        return new SkuSpec(attributes);
    }

    /** 供顯示的字串，例如 {@code 256G / 黑}。 */
    public String display() {
        return attributes.values().stream().collect(Collectors.joining(" / "));
    }

    @Override
    public Map<String, String> attributes() {
        return Map.copyOf(attributes);
    }
}

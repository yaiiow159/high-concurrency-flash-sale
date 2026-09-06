package com.flashsale.domain.catalog;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** SKU 的規格屬性，例如 {@code {容量: 256G, 顏色: 黑}}。 */
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

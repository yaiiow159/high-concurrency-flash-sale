package com.flashsale.application.port.in.dto;

import java.util.List;

/** 後台列表的一頁：帶總筆數，維運要能直接跳到第 N 頁核對。 */
public record PageView<T>(List<T> items, long total, int page, int size) {

    public static <T> PageView<T> of(List<T> items, long total, int page, int size) {
        return new PageView<>(List.copyOf(items), total, page, size);
    }
}

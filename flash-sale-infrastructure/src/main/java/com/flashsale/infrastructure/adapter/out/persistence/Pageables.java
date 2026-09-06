package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.domain.shared.Page;
import org.springframework.data.domain.PageRequest;

/** 把埠的 {@code (limit, offset)} 換算成 Spring Data 的 {@link PageRequest}。 */
public final class Pageables {

    private Pageables() {
    }

    public static PageRequest of(int limit, int offset) {
        Page page = Page.fromOffset(limit, offset);
        return PageRequest.of(page.number(), page.size());
    }
}

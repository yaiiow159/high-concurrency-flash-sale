package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.domain.shared.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 把埠的 {@code (limit, offset)} 換算成 Spring Data 的 {@link PageRequest}。
 *
 * <p>這段換算先前在 <b>11 個倉庫各寫一次</b>，而其中兩處
 * （{@code JpaReviewRepository}）漏了 {@code Math.max(limit, 1)}——
 * {@code limit=0} 會除以零。那正是「同一段程式複製十一次」的典型後果：
 * 不是所有副本都會跟著修。
 *
 * <p>換算邏輯本身放在領域層的 {@link Page}，這裡只負責轉成 Spring 的型別——
 * 領域層不該認得 {@code org.springframework.data}。
 */
public final class Pageables {

    private Pageables() {
    }

    public static PageRequest of(int limit, int offset) {
        Page page = Page.fromOffset(limit, offset);
        return PageRequest.of(page.number(), page.size());
    }
}

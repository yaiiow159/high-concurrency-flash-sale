package com.flashsale.domain.review;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** 一則商品評價。 */
public final class Review {

    /** 可修改的窗口。 */
    public static final Duration EDIT_WINDOW = Duration.ofDays(7);

    /** 評價內容長度上限。沒有上限的話，一則評價就能塞爆商品頁。 */
    public static final int MAX_CONTENT_LENGTH = 1000;

    private final Long id;
    private final Long productId;
    private final Long skuId;
    private final String orderNo;
    private final Long userId;
    private final String authorName;
    private final Rating rating;
    private final String content;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Review(Long id, Long productId, Long skuId, String orderNo, Long userId,
                   String authorName, Rating rating, String content,
                   Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.productId = Objects.requireNonNull(productId, "productId 不可為 null");
        this.skuId = Objects.requireNonNull(skuId, "skuId 不可為 null");
        this.orderNo = Objects.requireNonNull(orderNo, "orderNo 不可為 null");
        this.userId = Objects.requireNonNull(userId, "userId 不可為 null");
        this.authorName = Objects.requireNonNull(authorName, "authorName 不可為 null");
        this.rating = Objects.requireNonNull(rating, "rating 不可為 null");
        this.content = requireContent(content);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不可為 null");
        this.updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public static Review create(Long productId, Long skuId, String orderNo, Long userId,
                                String authorName, Rating rating, String content, Instant now) {
        return new Review(null, productId, skuId, orderNo, userId,
                authorName, rating, content, now, now);
    }

    public static Review restore(Long id, Long productId, Long skuId, String orderNo, Long userId,
                                 String authorName, Rating rating, String content,
                                 Instant createdAt, Instant updatedAt) {
        return new Review(id, productId, skuId, orderNo, userId,
                authorName, rating, content, createdAt, updatedAt);
    }

    /** 改評分與內容。 */
    public Review edit(Rating newRating, String newContent, Instant now) {
        if (!isEditableAt(now)) {
            throw new BusinessException(ErrorCode.REVIEW_EDIT_WINDOW_CLOSED,
                    "評價僅能在發表後 %d 天內修改".formatted(EDIT_WINDOW.toDays()));
        }
        return new Review(id, productId, skuId, orderNo, userId,
                authorName, newRating, newContent, createdAt, now);
    }

    /** 修改窗口是從<b>發表</b>算起，不是從上次修改算起——否則改一次就能無限續期。 */
    public boolean isEditableAt(Instant now) {
        return now.isBefore(createdAt.plus(EDIT_WINDOW));
    }

    public void requireOwnedBy(Long candidateUserId) {
        if (!userId.equals(candidateUserId)) {
            // 回「不存在」而非「不是你的」：後者等於確認這個 ID 有效
            throw new BusinessException(ErrorCode.REVIEW_NOT_FOUND, "評價不存在");
        }
    }

    private static String requireContent(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "評價內容不可為空");
        }
        if (candidate.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "評價內容不可超過 %d 字".formatted(MAX_CONTENT_LENGTH));
        }
        return candidate;
    }

    public Long id() {
        return id;
    }

    public Long productId() {
        return productId;
    }

    public Long skuId() {
        return skuId;
    }

    public String orderNo() {
        return orderNo;
    }

    public Long userId() {
        return userId;
    }

    public String authorName() {
        return authorName;
    }

    public Rating rating() {
        return rating;
    }

    public String content() {
        return content;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** 是否被改過。畫面要標「已編輯」——讀者有權知道這則評價不是原始版本。 */
    public boolean isEdited() {
        return updatedAt.isAfter(createdAt);
    }
}

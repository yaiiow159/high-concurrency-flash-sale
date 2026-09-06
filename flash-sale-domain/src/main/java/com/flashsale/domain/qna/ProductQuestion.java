package com.flashsale.domain.qna;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/**
 * 商品問答。
 *
 * <p>與評價的差別是<b>誰能發言</b>：評價只有買過的人能寫（那是它可信的原因），
 * 問答任何登入者都能問——還沒買的人才有問題要問。
 */
public final class ProductQuestion {

    private static final int MIN_CONTENT = 5;
    private static final int MAX_CONTENT = 500;
    private static final int MAX_ANSWER = 1000;

    private final Long id;
    private final Long productId;
    private final Long userId;
    private final String content;
    private final Instant createdAt;

    private String answer;
    private Long answeredBy;
    private Instant answeredAt;
    private boolean published;

    private ProductQuestion(Long id, Long productId, Long userId, String content,
                            String answer, Long answeredBy, Instant answeredAt,
                            boolean published, Instant createdAt) {
        this.id = id;
        this.productId = Objects.requireNonNull(productId, "productId 不可為 null");
        this.userId = Objects.requireNonNull(userId, "userId 不可為 null");
        this.content = requireValidContent(content);
        this.answer = answer;
        this.answeredBy = answeredBy;
        this.answeredAt = answeredAt;
        this.published = published;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不可為 null");
    }

    public static ProductQuestion ask(Long productId, Long userId, String content, Instant now) {
        return new ProductQuestion(null, productId, userId, content, null, null, null, false, now);
    }

    public static ProductQuestion restore(Long id, Long productId, Long userId, String content,
                                          String answer, Long answeredBy, Instant answeredAt,
                                          boolean published, Instant createdAt) {
        return new ProductQuestion(Objects.requireNonNull(id, "重建時 id 不可為 null"),
                productId, userId, content, answer, answeredBy, answeredAt, published, createdAt);
    }

    /**
     * 回答並公開。
     *
     * <p>回答與公開是同一個動作：只回答不公開等於白回答，
     * 而公開一個沒有答案的問題只會在商品頁上留下一個沒人理的問題。
     */
    public void answerWith(String answerText, Long adminUserId, Instant now) {
        if (answerText == null || answerText.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "回答內容不可為空");
        }
        if (answerText.length() > MAX_ANSWER) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "回答不可超過 " + MAX_ANSWER + " 字");
        }
        this.answer = answerText.trim();
        this.answeredBy = Objects.requireNonNull(adminUserId, "adminUserId 不可為 null");
        this.answeredAt = now;
        this.published = true;
    }

    /** 下架。用於不當提問——已回答的問題也可能因為提問內容不妥而需要隱藏。 */
    public void hide() {
        this.published = false;
    }

    public boolean isAnswered() {
        return answeredAt != null;
    }

    private static String requireValidContent(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() < MIN_CONTENT) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "問題至少需要 " + MIN_CONTENT + " 個字");
        }
        if (trimmed.length() > MAX_CONTENT) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "問題不可超過 " + MAX_CONTENT + " 字");
        }
        return trimmed;
    }

    public Long id() {
        return id;
    }

    public Long productId() {
        return productId;
    }

    public Long userId() {
        return userId;
    }

    public String content() {
        return content;
    }

    public String answer() {
        return answer;
    }

    public Long answeredBy() {
        return answeredBy;
    }

    public Instant answeredAt() {
        return answeredAt;
    }

    public boolean isPublished() {
        return published;
    }

    public Instant createdAt() {
        return createdAt;
    }
}

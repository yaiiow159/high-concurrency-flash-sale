package com.flashsale.application.port.in.dto;

import com.flashsale.domain.qna.ProductQuestion;

import java.time.Instant;

/**
 * 問答。
 *
 * @param askedBy 提問者的遮罩暱稱。問答是公開的，不該把完整身分露出來
 */
public record QuestionView(
        Long questionId,
        Long productId,
        String askedBy,
        String content,
        String answer,
        Instant answeredAt,
        boolean published,
        Instant createdAt
) {

    public static QuestionView from(ProductQuestion question, String askedBy) {
        return new QuestionView(question.id(), question.productId(), askedBy,
                question.content(), question.answer(), question.answeredAt(),
                question.isPublished(), question.createdAt());
    }
}

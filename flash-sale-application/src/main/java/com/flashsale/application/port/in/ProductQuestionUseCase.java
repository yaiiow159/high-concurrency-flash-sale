package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.QuestionView;

import java.util.List;

/** 商品問答。 */
public interface ProductQuestionUseCase {

    QuestionView ask(Long userId, Long productId, String content);

    /** 商品頁的問答，只有已公開的。 */
    List<QuestionView> publishedFor(Long productId, int page, int size);

    long countPublishedFor(Long productId);

    List<QuestionView> myQuestions(Long userId, int page, int size);

    /** 後台：待回覆清單。 */
    List<QuestionView> unanswered(int page, int size);

    long countUnanswered();

    QuestionView answer(Long adminUserId, Long questionId, String answer);

    void hide(Long questionId);
}

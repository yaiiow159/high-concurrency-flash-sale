package com.flashsale.application.service;

import com.flashsale.application.port.in.CatalogQueryUseCase;
import com.flashsale.application.port.in.ProductQuestionUseCase;
import com.flashsale.application.port.in.dto.QuestionView;
import com.flashsale.application.port.out.ProductQuestionRepository;
import com.flashsale.application.port.out.UserRepository;
import com.flashsale.domain.identity.User;
import com.flashsale.domain.qna.ProductQuestion;
import com.flashsale.domain.review.DisplayNameMask;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** 商品問答。 */
@Service
public class ProductQuestionService implements ProductQuestionUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProductQuestionService.class);

    private static final int MAX_PAGE_SIZE = 50;

    /**
     * 一天最多問幾題。
     *
     * <p>問答是公開的，而且任何登入者都能發——沒有上限的話它就是一個
     * 免費的留言板，而清理成本會落在營運身上。
     */
    private static final int MAX_QUESTIONS_PER_DAY = 10;

    private final ProductQuestionRepository questionRepository;
    private final CatalogQueryUseCase catalogQuery;
    private final UserRepository userRepository;
    private final Clock clock;

    public ProductQuestionService(ProductQuestionRepository questionRepository,
                                  CatalogQueryUseCase catalogQuery,
                                  UserRepository userRepository,
                                  Clock clock) {
        this.questionRepository = questionRepository;
        this.catalogQuery = catalogQuery;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public QuestionView ask(Long userId, Long productId, String content) {
        if (catalogQuery.findProductsByIds(List.of(productId)).isEmpty()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        long asked = questionRepository.countAskedSince(
                userId, clock.instant().minus(Duration.ofDays(1)));
        if (asked >= MAX_QUESTIONS_PER_DAY) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "今天的提問次數已達上限，請明天再試");
        }

        ProductQuestion saved = questionRepository.save(
                ProductQuestion.ask(productId, userId, content, clock.instant()));
        log.info("新問題 questionId={}, productId={}", saved.id(), productId);
        return toView(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuestionView> publishedFor(Long productId, int page, int size) {
        int capped = capped(size);
        return questionRepository
                .findPublishedByProduct(productId, capped, Math.max(page, 0) * capped)
                .stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countPublishedFor(Long productId) {
        return questionRepository.countPublishedByProduct(productId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuestionView> myQuestions(Long userId, int page, int size) {
        int capped = capped(size);
        return questionRepository.findByUser(userId, capped, Math.max(page, 0) * capped)
                .stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuestionView> unanswered(int page, int size) {
        int capped = capped(size);
        return questionRepository.findUnanswered(capped, Math.max(page, 0) * capped)
                .stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnanswered() {
        return questionRepository.countUnanswered();
    }

    /** 回答並公開。兩件事是同一個動作——只回答不公開等於白回答。 */
    @Override
    @Transactional
    public QuestionView answer(Long adminUserId, Long questionId, String answer) {
        ProductQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));
        question.answerWith(answer, adminUserId, clock.instant());
        return toView(questionRepository.save(question));
    }

    @Override
    @Transactional
    public void hide(Long questionId) {
        ProductQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));
        question.hide();
        questionRepository.save(question);
        log.info("問題已下架 questionId={}", questionId);
    }

    private static int capped(int size) {
        return Math.clamp(size, 1, MAX_PAGE_SIZE);
    }

    /** 提問者只露遮罩後的暱稱：問答是公開的，與評價同一個判準。 */
    private QuestionView toView(ProductQuestion question) {
        String askedBy = DisplayNameMask.apply(userRepository.findById(question.userId())
                .map(User::displayName)
                .orElse(null));
        return QuestionView.from(question, askedBy);
    }
}

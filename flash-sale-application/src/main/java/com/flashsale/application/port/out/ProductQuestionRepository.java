package com.flashsale.application.port.out;

import com.flashsale.domain.qna.ProductQuestion;

import java.util.List;
import java.util.Optional;

/** 商品問答的持久化埠（出站）。 */
public interface ProductQuestionRepository {

    ProductQuestion save(ProductQuestion question);

    /**
     * 回答並公開，<b>僅限還沒被回答的題目</b>。
     *
     * <p>回傳 0 代表別人先回答了。整列盲寫的話後到的人會靜默覆蓋先到的回答，
     * 而兩邊的畫面都顯示成功。
     */
    int answerIfUnanswered(Long questionId, String answer, Long answeredBy,
                           java.time.Instant answeredAt);

    /** 只改公開旗標，不碰回答欄位——用過期快照整列寫回會把別人剛存的回答抹掉。 */
    void setPublished(Long questionId, boolean published);

    Optional<ProductQuestion> findById(Long questionId);

    /** 商品頁看到的：只有已公開的，新到舊。 */
    List<ProductQuestion> findPublishedByProduct(Long productId, int limit, int offset);

    long countPublishedByProduct(Long productId);

    /** 我問過的，含還沒公開的——問的人自己看得到自己的問題。 */
    List<ProductQuestion> findByUser(Long userId, int limit, int offset);

    /** 後台待回覆清單，最舊的排前面。 */
    List<ProductQuestion> findUnanswered(int limit, int offset);

    long countUnanswered();

    /** 某使用者今天問了幾題，用於防灌水。 */
    long countAskedSince(Long userId, java.time.Instant since);
}

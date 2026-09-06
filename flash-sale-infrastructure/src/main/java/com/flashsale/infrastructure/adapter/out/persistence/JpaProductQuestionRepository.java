package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.ProductQuestionRepository;
import com.flashsale.domain.qna.ProductQuestion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 商品問答的持久化。 */
@Repository
public class JpaProductQuestionRepository implements ProductQuestionRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public ProductQuestion save(ProductQuestion question) {
        if (question.id() == null) {
            entityManager.createNativeQuery("""
                            insert into product_question
                                (product_id, user_id, content, published, created_at)
                            values (:productId, :userId, :content, 0, :createdAt)
                            """)
                    .setParameter("productId", question.productId())
                    .setParameter("userId", question.userId())
                    .setParameter("content", question.content())
                    .setParameter("createdAt", Timestamp.from(question.createdAt()))
                    .executeUpdate();
            Long id = ((Number) entityManager.createNativeQuery("select last_insert_id()")
                    .getSingleResult()).longValue();
            return ProductQuestion.restore(id, question.productId(), question.userId(),
                    question.content(), null, null, null, false, question.createdAt());
        }

        entityManager.createNativeQuery("""
                        update product_question set answer = :answer, answered_by = :answeredBy,
                            answered_at = :answeredAt, published = :published
                        where id = :id
                        """)
                .setParameter("id", question.id())
                .setParameter("answer", question.answer())
                .setParameter("answeredBy", question.answeredBy())
                .setParameter("answeredAt", question.answeredAt() == null
                        ? null : Timestamp.from(question.answeredAt()))
                .setParameter("published", question.isPublished() ? 1 : 0)
                .executeUpdate();
        return question;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductQuestion> findById(Long questionId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(selectColumns()
                        + " from product_question where id = :id")
                .setParameter("id", questionId)
                .getResultList();
        return rows.stream().findFirst().map(JpaProductQuestionRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<ProductQuestion> findPublishedByProduct(Long productId, int limit, int offset) {
        List<Object[]> rows = entityManager.createNativeQuery(selectColumns()
                        + """
                         from product_question
                         where product_id = :productId and published = 1
                         order by created_at desc, id desc limit :limit offset :offset
                        """)
                .setParameter("productId", productId)
                .setParameter("limit", limit)
                .setParameter("offset", offset)
                .getResultList();
        return rows.stream().map(JpaProductQuestionRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countPublishedByProduct(Long productId) {
        return ((Number) entityManager.createNativeQuery("""
                        select count(*) from product_question
                        where product_id = :productId and published = 1
                        """)
                .setParameter("productId", productId)
                .getSingleResult()).longValue();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<ProductQuestion> findByUser(Long userId, int limit, int offset) {
        List<Object[]> rows = entityManager.createNativeQuery(selectColumns()
                        + """
                         from product_question where user_id = :userId
                         order by created_at desc limit :limit offset :offset
                        """)
                .setParameter("userId", userId)
                .setParameter("limit", limit)
                .setParameter("offset", offset)
                .getResultList();
        return rows.stream().map(JpaProductQuestionRepository::toDomain).toList();
    }

    /** 最舊的排前面：等最久的人應該最先被回答。 */
    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<ProductQuestion> findUnanswered(int limit, int offset) {
        List<Object[]> rows = entityManager.createNativeQuery(selectColumns()
                        + """
                         from product_question where answered_at is null
                         order by created_at asc limit :limit offset :offset
                        """)
                .setParameter("limit", limit)
                .setParameter("offset", offset)
                .getResultList();
        return rows.stream().map(JpaProductQuestionRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnanswered() {
        return ((Number) entityManager.createNativeQuery(
                        "select count(*) from product_question where answered_at is null")
                .getSingleResult()).longValue();
    }

    @Override
    @Transactional(readOnly = true)
    public long countAskedSince(Long userId, Instant since) {
        return ((Number) entityManager.createNativeQuery("""
                        select count(*) from product_question
                        where user_id = :userId and created_at >= :since
                        """)
                .setParameter("userId", userId)
                .setParameter("since", Timestamp.from(since))
                .getSingleResult()).longValue();
    }

    private static String selectColumns() {
        return """
                select id, product_id, user_id, content, answer, answered_by,
                       answered_at, published, created_at
                """;
    }

    private static ProductQuestion toDomain(Object[] row) {
        return ProductQuestion.restore(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(),
                (String) row[3],
                (String) row[4],
                row[5] == null ? null : ((Number) row[5]).longValue(),
                row[6] == null ? null : ((Timestamp) row[6]).toInstant(),
                flag(row[7]),
                ((Timestamp) row[8]).toInstant());
    }

    /** {@code TINYINT(1)} 不可直接轉 Number：Connector/J 預設回的是 Boolean。 */
    private static boolean flag(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value instanceof Number number && number.intValue() == 1;
    }
}

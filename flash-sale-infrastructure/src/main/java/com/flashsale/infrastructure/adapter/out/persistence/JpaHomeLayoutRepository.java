package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.HomeLayoutRepository;
import com.flashsale.domain.home.CarouselSlide;
import com.flashsale.domain.home.HomeSection;
import com.flashsale.domain.home.ProductSource;
import com.flashsale.domain.home.SectionType;
import com.flashsale.domain.home.Visibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 首頁版型的持久化。用原生 SQL：欄位固定、查詢單純，加一層實體對映沒有換到東西。 */
@Repository
public class JpaHomeLayoutRepository implements HomeLayoutRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public List<HomeSection> findAllSections() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                select id, type, title, subtitle, source, category_id, item_limit,
                       sort_order, enabled, visible_from, visible_to
                from home_section order by sort_order asc, id asc
                """).getResultList();
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Long, List<Long>> curated = curatedProducts(
                rows.stream().map(r -> ((Number) r[0]).longValue()).toList());
        return rows.stream().map(row -> toSection(row, curated)).toList();
    }

    /** 一次撈完所有版位的選品，而不是逐個版位查——版位數量不多，但那仍然是 N+1。 */
    private Map<Long, List<Long>> curatedProducts(List<Long> sectionIds) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select section_id, product_id from home_section_product
                        where section_id in (:ids) order by sort_order asc
                        """)
                .setParameter("ids", sectionIds)
                .getResultList();
        Map<Long, List<Long>> bySection = new HashMap<>();
        for (Object[] row : rows) {
            bySection.computeIfAbsent(((Number) row[0]).longValue(), k -> new ArrayList<>())
                    .add(((Number) row[1]).longValue());
        }
        return bySection;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HomeSection> findSection(Long sectionId) {
        return findAllSections().stream().filter(s -> s.id().equals(sectionId)).findFirst();
    }

    @Override
    @Transactional
    public HomeSection saveSection(HomeSection section) {
        Long id = section.id();
        if (id == null) {
            entityManager.createNativeQuery("""
                            insert into home_section
                                (type, title, subtitle, source, category_id, item_limit,
                                 sort_order, enabled, visible_from, visible_to)
                            values (:type, :title, :subtitle, :source, :categoryId, :itemLimit,
                                    :sortOrder, :enabled, :from, :to)
                            """)
                    .setParameter("type", section.type().name())
                    .setParameter("title", section.title())
                    .setParameter("subtitle", section.subtitle())
                    .setParameter("source", section.source() == null ? null : section.source().name())
                    .setParameter("categoryId", section.categoryId())
                    .setParameter("itemLimit", section.itemLimit())
                    .setParameter("sortOrder", section.sortOrder())
                    .setParameter("enabled", section.visibility().enabled() ? 1 : 0)
                    .setParameter("from", timestamp(section.visibility().from()))
                    .setParameter("to", timestamp(section.visibility().to()))
                    .executeUpdate();
            id = ((Number) entityManager.createNativeQuery("select last_insert_id()")
                    .getSingleResult()).longValue();
        } else {
            entityManager.createNativeQuery("""
                            update home_section set type = :type, title = :title,
                                subtitle = :subtitle, source = :source, category_id = :categoryId,
                                item_limit = :itemLimit, sort_order = :sortOrder,
                                enabled = :enabled, visible_from = :from, visible_to = :to
                            where id = :id
                            """)
                    .setParameter("id", id)
                    .setParameter("type", section.type().name())
                    .setParameter("title", section.title())
                    .setParameter("subtitle", section.subtitle())
                    .setParameter("source", section.source() == null ? null : section.source().name())
                    .setParameter("categoryId", section.categoryId())
                    .setParameter("itemLimit", section.itemLimit())
                    .setParameter("sortOrder", section.sortOrder())
                    .setParameter("enabled", section.visibility().enabled() ? 1 : 0)
                    .setParameter("from", timestamp(section.visibility().from()))
                    .setParameter("to", timestamp(section.visibility().to()))
                    .executeUpdate();
        }
        replaceCurated(id, section.productIds());
        return new HomeSection(id, section.type(), section.title(), section.subtitle(),
                section.source(), section.categoryId(), section.productIds(),
                section.itemLimit(), section.sortOrder(), section.visibility());
    }

    /** 整批換掉而不是逐筆比對差異：選品是一個有序清單，「改」的語意就是「換成這一份」。 */
    private void replaceCurated(Long sectionId, List<Long> productIds) {
        entityManager.createNativeQuery("delete from home_section_product where section_id = :id")
                .setParameter("id", sectionId)
                .executeUpdate();
        for (int i = 0; i < productIds.size(); i++) {
            entityManager.createNativeQuery("""
                            insert into home_section_product (section_id, product_id, sort_order)
                            values (:sectionId, :productId, :sortOrder)
                            """)
                    .setParameter("sectionId", sectionId)
                    .setParameter("productId", productIds.get(i))
                    .setParameter("sortOrder", i)
                    .executeUpdate();
        }
    }

    @Override
    @Transactional
    public void deleteSection(Long sectionId) {
        entityManager.createNativeQuery("delete from home_section where id = :id")
                .setParameter("id", sectionId)
                .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CarouselSlide> findAllSlides() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                select id, object_key, title, link_url, sort_order, enabled,
                       visible_from, visible_to
                from carousel_slide order by sort_order asc, id asc
                """).getResultList();
        return rows.stream().map(JpaHomeLayoutRepository::toSlide).toList();
    }

    @Override
    @Transactional
    public CarouselSlide saveSlide(CarouselSlide slide) {
        Long id = slide.id();
        if (id == null) {
            entityManager.createNativeQuery("""
                            insert into carousel_slide
                                (object_key, title, link_url, sort_order, enabled,
                                 visible_from, visible_to)
                            values (:key, :title, :link, :sortOrder, :enabled, :from, :to)
                            """)
                    .setParameter("key", slide.objectKey())
                    .setParameter("title", slide.title())
                    .setParameter("link", slide.linkUrl())
                    .setParameter("sortOrder", slide.sortOrder())
                    .setParameter("enabled", slide.visibility().enabled() ? 1 : 0)
                    .setParameter("from", timestamp(slide.visibility().from()))
                    .setParameter("to", timestamp(slide.visibility().to()))
                    .executeUpdate();
            id = ((Number) entityManager.createNativeQuery("select last_insert_id()")
                    .getSingleResult()).longValue();
        } else {
            entityManager.createNativeQuery("""
                            update carousel_slide set object_key = :key, title = :title,
                                link_url = :link, sort_order = :sortOrder, enabled = :enabled,
                                visible_from = :from, visible_to = :to
                            where id = :id
                            """)
                    .setParameter("id", id)
                    .setParameter("key", slide.objectKey())
                    .setParameter("title", slide.title())
                    .setParameter("link", slide.linkUrl())
                    .setParameter("sortOrder", slide.sortOrder())
                    .setParameter("enabled", slide.visibility().enabled() ? 1 : 0)
                    .setParameter("from", timestamp(slide.visibility().from()))
                    .setParameter("to", timestamp(slide.visibility().to()))
                    .executeUpdate();
        }
        return new CarouselSlide(id, slide.objectKey(), slide.title(), slide.linkUrl(),
                slide.sortOrder(), slide.visibility());
    }

    @Override
    @Transactional
    public void deleteSlide(Long slideId) {
        entityManager.createNativeQuery("delete from carousel_slide where id = :id")
                .setParameter("id", slideId)
                .executeUpdate();
    }

    private static HomeSection toSection(Object[] row, Map<Long, List<Long>> curated) {
        Long id = ((Number) row[0]).longValue();
        return new HomeSection(id,
                SectionType.valueOf((String) row[1]),
                (String) row[2],
                (String) row[3],
                row[4] == null ? null : ProductSource.valueOf((String) row[4]),
                row[5] == null ? null : ((Number) row[5]).longValue(),
                curated.getOrDefault(id, List.of()),
                ((Number) row[6]).intValue(),
                ((Number) row[7]).intValue(),
                new Visibility(flag(row[8]), instant(row[9]), instant(row[10])));
    }

    private static CarouselSlide toSlide(Object[] row) {
        return new CarouselSlide(((Number) row[0]).longValue(),
                (String) row[1], (String) row[2], (String) row[3],
                ((Number) row[4]).intValue(),
                new Visibility(flag(row[5]), instant(row[6]), instant(row[7])));
    }

    /** {@code TINYINT(1)} 不可直接轉 Number：Connector/J 預設回的是 Boolean。 */
    private static boolean flag(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value instanceof Number number && number.intValue() == 1;
    }

    private static Instant instant(Object value) {
        return value == null ? null : ((Timestamp) value).toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}

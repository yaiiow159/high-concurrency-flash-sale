package com.flashsale.infrastructure.adapter.out.persistence;

import com.flashsale.application.port.out.CategoryRepository;
import com.flashsale.domain.catalog.Category;
import com.flashsale.infrastructure.adapter.out.persistence.entity.CategoryEntity;
import com.flashsale.infrastructure.adapter.out.persistence.jpa.CategoryJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 類目持久化埠的 JPA 實作。 */
@Repository
public class JpaCategoryRepository implements CategoryRepository {

    private final CategoryJpaRepository jpaRepository;

    public JpaCategoryRepository(CategoryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Category> findById(Long categoryId) {
        return jpaRepository.findById(categoryId).map(JpaCategoryRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category> findAll() {
        return jpaRepository.findAll().stream().map(JpaCategoryRepository::toDomain).toList();
    }

    private static Category toDomain(CategoryEntity entity) {
        return Category.restore(entity.getId(), entity.getParentId(),
                entity.getName(), entity.getLevel(), entity.getSortOrder());
    }
}

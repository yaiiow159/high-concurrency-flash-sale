package com.flashsale.infrastructure.adapter.out.persistence.jpa;

import com.flashsale.infrastructure.adapter.out.persistence.entity.CategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 類目的 Spring Data 介面。 */
public interface CategoryJpaRepository extends JpaRepository<CategoryEntity, Long> {
}

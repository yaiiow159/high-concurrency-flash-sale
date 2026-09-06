package com.flashsale.application.port.out;

import com.flashsale.domain.catalog.Category;

import java.util.List;
import java.util.Optional;

/** 類目持久化埠（出站）。 */
public interface CategoryRepository {

    Optional<Category> findById(Long categoryId);

    /** 全部類目。 */
    List<Category> findAll();
}

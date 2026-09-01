package com.flashsale.application.port.out;

import com.flashsale.domain.catalog.Category;

import java.util.List;
import java.util.Optional;

/** 類目持久化埠（出站）。 */
public interface CategoryRepository {

    Optional<Category> findById(Long categoryId);

    /**
     * 全部類目。
     *
     * <p>類目樹極少變動卻在每次商品查詢時被讀取——典型的「讀多寫極少」，
     * 一次全撈再由呼叫端組樹，比逐層查詢快得多。
     * 類目總數以千為上限，記憶體不是問題。
     */
    List<Category> findAll();
}

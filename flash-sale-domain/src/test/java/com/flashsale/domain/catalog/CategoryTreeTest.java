package com.flashsale.domain.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 類目子樹展開（ADR-0022）。
 *
 * <p>這裡壞掉的方式不會拋錯，只會讓某些商品從列表上消失——
 * 而使用者不會回報「我看不到本來就看不到的東西」。
 */
@DisplayName("類目樹")
class CategoryTreeTest {

    /**
     * 一棵三層的樹，與種入的壓測資料同形狀：
     * <pre>
     * 1 3C 產品
     * ├── 2 手機
     * │   ├── 4 旗艦
     * │   └── 5 平價
     * └── 3 筆電
     *     └── 6 輕薄
     * </pre>
     */
    private static CategoryTree threeLevels() {
        Category root = Category.root(1L, "3C 產品", 0);
        Category phone = Category.child(2L, root, "手機", 0);
        Category laptop = Category.child(3L, root, "筆電", 1);
        return CategoryTree.of(List.of(
                root, phone, laptop,
                Category.child(4L, phone, "旗艦", 0),
                Category.child(5L, phone, "平價", 1),
                Category.child(6L, laptop, "輕薄", 0)));
    }

    @Nested
    @DisplayName("展開子孫")
    class Descendants {

        @Test
        @DisplayName("中間層要帶出底下所有葉節點——商品只掛在葉節點上")
        void middleLevelIncludesLeaves() {
            // 少了這個，點「手機」會得到空頁面，而它底下明明有商品
            assertThat(threeLevels().withDescendants(2L)).containsExactlyInAnyOrder(2L, 4L, 5L);
        }

        @Test
        @DisplayName("根節點帶出整棵樹")
        void rootIncludesEverything() {
            assertThat(threeLevels().withDescendants(1L))
                    .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L);
        }

        @Test
        @DisplayName("葉節點只有自己")
        void leafIsAlone() {
            assertThat(threeLevels().withDescendants(4L)).containsExactly(4L);
        }

        @Test
        @DisplayName("不存在的類目回傳只含自己的集合，於是查不到商品")
        void unknownCategoryFiltersToNothing() {
            // 回傳空集合會被下游當成「不篩選」，
            // 那會讓一個打錯的類目 ID 安靜地變成「顯示全部商品」
            assertThat(threeLevels().withDescendants(999L)).containsExactly(999L);
        }
    }

    @Nested
    @DisplayName("涵蓋整棵樹的判斷")
    class CoversAll {

        @Test
        @DisplayName("根的子樹涵蓋全部——呼叫端據此改成完全不下條件")
        void rootCoversAll() {
            CategoryTree tree = threeLevels();
            assertThat(tree.coversAll(tree.withDescendants(1L))).isTrue();
        }

        @Test
        @DisplayName("中間層不涵蓋全部")
        void middleDoesNotCoverAll() {
            CategoryTree tree = threeLevels();
            assertThat(tree.coversAll(tree.withDescendants(2L))).isFalse();
        }

        @Test
        @DisplayName("多個根時，其中一個根的子樹不算涵蓋全部")
        void oneOfSeveralRootsDoesNotCoverAll() {
            Category a = Category.root(1L, "3C", 0);
            Category b = Category.root(2L, "生活", 1);
            CategoryTree tree = CategoryTree.of(List.of(a, b, Category.child(3L, a, "手機", 0)));

            assertThat(tree.coversAll(tree.withDescendants(1L))).isFalse();
        }
    }

    @Nested
    @DisplayName("壞資料")
    class Malformed {

        @Test
        @DisplayName("父子成環時不可無限遞迴")
        void cycleTerminates() {
            // parent_id 沒有外鍵約束，資料壞掉時 A→B→A 是可能的。
            // 沒有防護的話這裡會吃光執行緒，而症狀是「商品列表整個沒有回應」
            CategoryTree tree = CategoryTree.of(List.of(
                    Category.restore(1L, 2L, "甲", 2, 0),
                    Category.restore(2L, 1L, "乙", 2, 0)));

            assertThat(tree.withDescendants(1L)).containsExactlyInAnyOrder(1L, 2L);
        }

        @Test
        @DisplayName("父節點不存在時，該分支就從樹上斷開，不影響其他分支")
        void orphanBranchDoesNotBreakOthers() {
            Category root = Category.root(1L, "3C", 0);
            CategoryTree tree = CategoryTree.of(List.of(
                    root,
                    Category.child(2L, root, "手機", 0),
                    Category.restore(3L, 99L, "孤兒", 2, 0)));

            assertThat(tree.withDescendants(1L)).containsExactlyInAnyOrder(1L, 2L);
        }

        @Test
        @DisplayName("空的類目表不會爆炸")
        void emptyTree() {
            CategoryTree tree = CategoryTree.of(List.of());

            assertThat(tree.withDescendants(1L)).containsExactly(1L);
            assertThat(tree.size()).isZero();
        }
    }
}

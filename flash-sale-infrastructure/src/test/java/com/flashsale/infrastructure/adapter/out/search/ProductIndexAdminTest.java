package com.flashsale.infrastructure.adapter.out.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 舊索引的清理。
 *
 * <h2>這裡只有一條規則不能錯</h2>
 *
 * <p><b>絕不刪掉 alias 正在指的那一個索引。</b>
 * 錯了的結果不是「多佔一點磁碟」，是線上搜尋整個消失——
 * 而且要等到有人搜尋才會發現。這種程式碼不該只靠讀過一遍來保證。
 *
 * <p>清理本身存在的理由：{@code switchAliasTo} 保留舊索引供回退，
 * 但先前<b>沒有任何東西會刪掉它們</b>。每重建一次就留下一份完整副本，
 * 而重建是維運按得到的按鈕。磁碟滿了的症狀是整個 Elasticsearch 進入唯讀。
 */
@DisplayName("搜尋索引清理")
class ProductIndexAdminTest {

    /** 版本號是建立當下的毫秒數，數字越大越新。 */
    private static final String V1 = "products_v1000_aaa";
    private static final String V2 = "products_v2000_bbb";
    private static final String V3 = "products_v3000_ccc";
    private static final String V4 = "products_v4000_ddd";

    @Nested
    @DisplayName("絕不刪 alias 指向的索引")
    class NeverDeletesLive {

        @Test
        @DisplayName("live 索引不在刪除清單裡，即使它是最舊的那一個")
        void liveIsExcludedEvenWhenOldest() {
            // V1 最舊，但 alias 指著它——例如剛回退過
            List<String> obsolete = ProductIndexAdmin.selectObsolete(
                    Set.of(V1, V2, V3, V4), Set.of(V1), 1);

            assertThat(obsolete).doesNotContain(V1);
        }

        @Test
        @DisplayName("alias 同時指向多個索引時，全部都不刪")
        void allLiveIndicesAreExcluded() {
            // 切換失敗會留下這種中間狀態。此時更不能亂刪
            List<String> obsolete = ProductIndexAdmin.selectObsolete(
                    Set.of(V1, V2, V3), Set.of(V2, V3), 0);

            assertThat(obsolete).containsExactly(V1);
        }

        @Test
        @DisplayName("只有一個索引且它是 live 時，什麼都不刪")
        void singleLiveIndexIsKept() {
            assertThat(ProductIndexAdmin.selectObsolete(Set.of(V1), Set.of(V1), 0)).isEmpty();
        }
    }

    @Nested
    @DisplayName("保留代數")
    class Retention {

        @Test
        @DisplayName("保留一代：live 之外再留最新的一個，其餘刪掉")
        void keepsOneGenerationForRollback() {
            // V4 是 live；V3 保留供回退；V2、V1 刪掉
            List<String> obsolete = ProductIndexAdmin.selectObsolete(
                    Set.of(V1, V2, V3, V4), Set.of(V4), 1);

            assertThat(obsolete).containsExactlyInAnyOrder(V2, V1);
        }

        @Test
        @DisplayName("保留零代：live 以外全刪")
        void keepNoneDeletesAllButLive() {
            List<String> obsolete = ProductIndexAdmin.selectObsolete(
                    Set.of(V1, V2, V3), Set.of(V3), 0);

            assertThat(obsolete).containsExactlyInAnyOrder(V2, V1);
        }

        @Test
        @DisplayName("負數視為 0，不會因為算術而多留或漏刪")
        void negativeIsTreatedAsZero() {
            assertThat(ProductIndexAdmin.selectObsolete(Set.of(V1, V2), Set.of(V2), -5))
                    .containsExactly(V1);
        }

        @Test
        @DisplayName("要保留的代數比現有的還多時不刪任何東西")
        void keepMoreThanExistsDeletesNothing() {
            assertThat(ProductIndexAdmin.selectObsolete(Set.of(V1, V2), Set.of(V2), 10)).isEmpty();
        }
    }

    @Nested
    @DisplayName("新舊的判定")
    class Ordering {

        @Test
        @DisplayName("用毫秒數的數值比大小，不是字串")
        void sortsNumericallyNotLexicographically() {
            // 字串排序會把 "products_v9000..." 排在 "products_v10000..." 之後，
            // 於是較新的那一個被當成舊的刪掉。位數增加的那一天就會發生
            String older = "products_v9000_aaa";
            String newer = "products_v10000_bbb";

            List<String> obsolete = ProductIndexAdmin.selectObsolete(
                    Set.of(older, newer), Set.of(), 1);

            assertThat(obsolete).containsExactly(older);
        }

        @Test
        @DisplayName("名稱不符規則的索引排到最後，優先被清掉")
        void unparseableNamesAreTreatedAsOldest() {
            // 不是我們建的東西。它絕不會是 live（那個已經被過濾掉了），
            // 而我們不知道它是什麼——先清它比先清一個真的舊索引安全
            String foreign = "products_vUNKNOWN";

            List<String> obsolete = ProductIndexAdmin.selectObsolete(
                    Set.of(foreign, V1, V2), Set.of(V2), 1);

            assertThat(obsolete).containsExactly(foreign);
        }
    }
}

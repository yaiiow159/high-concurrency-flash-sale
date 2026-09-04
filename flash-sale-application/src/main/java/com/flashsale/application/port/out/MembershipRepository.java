package com.flashsale.application.port.out;

import com.flashsale.domain.membership.MemberAccount;
import com.flashsale.domain.membership.PointReason;
import com.flashsale.domain.membership.PointTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 會員帳戶與積分流水的持久化埠（出站）。 */
public interface MembershipRepository {

    /**
     * 取帳戶；沒有就<b>當場建一個空的</b>。
     *
     * <p>回 {@link MemberAccount#fresh} 而不是 {@code Optional.empty()}：
     * 每一個登入的人都該看得到自己的會員頁，而「還沒有帳戶」
     * 與「帳戶都是 0」在畫面上長得一樣，卻要走兩條不同的程式路徑。
     */
    MemberAccount findAccount(Long userId);

    /**
     * 記一筆積分異動並同步餘額與累計消費。
     *
     * <p><b>兩件事必須在同一個交易裡</b>：流水寫了但餘額沒動，
     * 對帳就會發現不一致而沒有人知道哪一邊是對的。
     *
     * <p>餘額的更新是<b>條件式增量 UPDATE</b>（ADR-0016 決策 2）：
     *
     * <pre>
     *   UPDATE member_account SET point_balance = point_balance + ? WHERE user_id = ?
     * </pre>
     *
     * 不是「讀出來、加、寫回去」——那在兩個並行的入帳下會吃掉其中一筆。
     * 與庫存扣減、券的核銷、評分聚合同一個手法，這是本專案第四次用它。
     *
     * <p><b>冪等由 {@code (user_id, reason, refNo)} 的唯一索引保證。</b>
     * 訂單完成事件是至少一次投遞，重放不可以變成第二次入帳。
     *
     * @param spendDelta 累計消費的增減；{@code null} 表示不動它
     *                   （例如兌換優惠券只扣點、不影響等級）
     * @return {@code true} 表示這次真的記錄了；{@code false} 代表唯一索引擋下重複，
     *         而那是併發下的正常結果，不是錯誤
     */
    boolean record(Long userId, long delta, PointReason reason, String refNo,
                   BigDecimal spendDelta, Instant now);

    /**
     * 兌換：扣點，且點數不足時<b>不扣</b>。
     *
     * <p>守衛條件 {@code AND point_balance >= :cost} 寫在 SQL 裡而非 Java 裡，
     * 這是它能安全的唯一理由——與 {@code InventoryJpaRepository.deductAvailable} 完全同形。
     * 先 SELECT 再 UPDATE 的話，兩個並行請求都會讀到「還有 500 點」然後各扣一次。
     *
     * @return {@code false} 代表點數不足或重複兌換，扣減未發生
     */
    boolean redeem(Long userId, long cost, String refNo, Instant now);

    /** 流水，新到舊。 */
    List<PointTransaction> findTransactions(Long userId, int offset, int limit);

    /** 某一筆來源單號的異動；退款要按比例扣回時需要查出原始入帳的點數。 */
    Optional<PointTransaction> findByReference(Long userId, PointReason reason, String refNo);
}

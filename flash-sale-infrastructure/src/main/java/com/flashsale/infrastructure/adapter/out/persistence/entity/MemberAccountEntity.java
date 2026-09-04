package com.flashsale.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * 會員帳戶。
 *
 * <p><b>沒有任何 setter，也沒有業務方法。</b> 這個實體只用於「讀出來」
 * 與「INSERT 一列空的」；所有變動都走條件式增量 UPDATE。
 * 開一個 setter 出來，就會有人寫「讀出來、加、存回去」——
 * 而那在兩個並行的入帳下會吃掉其中一筆。與 {@code ProductRatingEntity} 同一個判斷。
 */
@Entity
@Table(name = "member_account")
public class MemberAccountEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "point_balance", nullable = false)
    private long pointBalance;

    @Column(name = "cumulative_spend", nullable = false, precision = 14, scale = 2)
    private BigDecimal cumulativeSpend;

    @Column(name = "tier", nullable = false, length = 16)
    private String tier;

    protected MemberAccountEntity() {
        // JPA 專用
    }

    /** 空帳戶。第一次入帳前必須先有這一列，增量 UPDATE 才有東西可加。 */
    public MemberAccountEntity(Long userId) {
        this.userId = userId;
        this.pointBalance = 0L;
        this.cumulativeSpend = BigDecimal.ZERO;
        this.tier = "BRONZE";
    }

    public Long getUserId() {
        return userId;
    }

    public long getPointBalance() {
        return pointBalance;
    }

    public BigDecimal getCumulativeSpend() {
        return cumulativeSpend;
    }

    public String getTier() {
        return tier;
    }
}

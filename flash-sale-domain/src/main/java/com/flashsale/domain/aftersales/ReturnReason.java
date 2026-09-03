package com.flashsale.domain.aftersales;

/**
 * 退貨原因。
 *
 * <p>用列舉而非自由文字，是為了讓「哪些商品常被退、為什麼」成為可統計的資料。
 * 自由文字寫得再詳細，也只能用人眼一筆一筆讀。
 * 細節仍可另外補在 {@code reasonDetail} 裡，兩者並存。
 *
 * <p><b>責任歸屬留在這裡而不是另開欄位</b>：
 * 「商品瑕疵」與「買家不想要了」在運費與績效上是不同的帳，
 * 而那個差別完全由原因決定，沒有第二個變數。
 */
public enum ReturnReason {

    /** 商品瑕疵或損壞。責任在賣方。 */
    DEFECTIVE,

    /** 收到的與描述不符。責任在賣方。 */
    NOT_AS_DESCRIBED,

    /** 出貨錯誤（寄錯規格或品項）。責任在賣方。 */
    WRONG_ITEM,

    /** 買家改變心意。責任在買方。 */
    CHANGED_MIND,

    /** 其他，需在 {@code reasonDetail} 說明。 */
    OTHER;

    /** 是否為賣方責任。決定運費由誰負擔，也是退貨率報表的分母該不該算進去的依據。 */
    public boolean isSellerFault() {
        return this == DEFECTIVE || this == NOT_AS_DESCRIBED || this == WRONG_ITEM;
    }
}

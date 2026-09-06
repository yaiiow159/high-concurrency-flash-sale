package com.flashsale.domain.aftersales;

/** 退貨原因。 */
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

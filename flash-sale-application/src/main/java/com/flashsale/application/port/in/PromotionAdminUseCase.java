package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.PageView;
import com.flashsale.application.port.in.dto.PromotionAdminView;
import com.flashsale.application.port.in.dto.PromotionCommand;

/** 後台維護優惠規則。券本身不在這裡管——券是規則發給某個人的實例，只看數字。 */
public interface PromotionAdminUseCase {

    /** type 為 null 代表全部；新到舊。 */
    PageView<PromotionAdminView> list(String type, int page, int size);

    PromotionAdminView create(PromotionCommand command);

    /**
     * 覆寫規則。已發出的券會跟著新規則計算——名稱與金額是下單時才快照進訂單，
     * 改規則不影響歷史訂單，但會影響還沒用的券。
     */
    PromotionAdminView update(Long promotionId, PromotionCommand command);

    PromotionAdminView setEnabled(Long promotionId, boolean enabled);
}

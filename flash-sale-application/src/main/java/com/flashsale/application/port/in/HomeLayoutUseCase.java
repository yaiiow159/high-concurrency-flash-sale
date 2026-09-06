package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.HomeLayoutView;

/** 前台讀首頁版型。 */
public interface HomeLayoutUseCase {

    /** 目前該顯示的版位與內容。已過濾停用與不在上架期間的。 */
    HomeLayoutView currentLayout();
}

package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.UserView;

/** 使用者查詢入站埠。 */
public interface UserQueryUseCase {

    UserView findById(Long userId);
}

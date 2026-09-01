package com.flashsale.application.port.in;

import com.flashsale.application.port.in.command.RegisterUserCommand;
import com.flashsale.application.port.in.dto.UserView;

/** 註冊入站埠。 */
public interface UserRegistrationUseCase {

    /**
     * @throws com.flashsale.domain.shared.BusinessException 信箱已被註冊時
     */
    UserView register(RegisterUserCommand command);
}

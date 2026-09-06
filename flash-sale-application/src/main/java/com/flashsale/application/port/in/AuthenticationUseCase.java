package com.flashsale.application.port.in;

import com.flashsale.application.port.in.command.LoginCommand;
import com.flashsale.application.port.in.dto.SessionTokens;

/** 認證入站埠：登入、續期、登出。 */
public interface AuthenticationUseCase {

    SessionTokens login(LoginCommand command);

    /** 以 refresh token 換取新的令牌組，並輪替掉舊的。 */
    SessionTokens refresh(String rawRefreshToken);

    /** 登出：撤銷此 refresh token。無效的 token 靜默忽略，不對外洩漏它是否存在。 */
    void logout(String rawRefreshToken);
}

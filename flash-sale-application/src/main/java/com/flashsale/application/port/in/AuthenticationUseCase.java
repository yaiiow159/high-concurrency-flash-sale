package com.flashsale.application.port.in;

import com.flashsale.application.port.in.command.LoginCommand;
import com.flashsale.application.port.in.dto.SessionTokens;

/** 認證入站埠：登入、續期、登出。 */
public interface AuthenticationUseCase {

    SessionTokens login(LoginCommand command);

    /**
     * 以 refresh token 換取新的令牌組，並輪替掉舊的。
     *
     * <p>若傳入的是<b>已被輪替過</b>的 token，代表該憑證曾經外洩，
     * 實作必須撤銷整條輪替鏈並拒絕本次請求。
     */
    SessionTokens refresh(String rawRefreshToken);

    /** 登出：撤銷此 refresh token。無效的 token 靜默忽略，不對外洩漏它是否存在。 */
    void logout(String rawRefreshToken);
}

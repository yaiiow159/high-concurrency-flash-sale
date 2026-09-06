package com.flashsale.application.service;

import com.flashsale.application.config.AuthPolicy;
import com.flashsale.application.port.in.AuthenticationUseCase;
import com.flashsale.application.port.in.command.LoginCommand;
import com.flashsale.application.port.in.dto.SessionTokens;
import com.flashsale.application.port.out.AccessTokenIssuer;
import com.flashsale.application.port.out.PasswordHasher;
import com.flashsale.application.port.out.RefreshTokenRepository;
import com.flashsale.application.port.out.SecureTokenGenerator;
import com.flashsale.application.port.out.UserRepository;
import com.flashsale.domain.identity.Email;
import com.flashsale.domain.identity.RefreshToken;
import com.flashsale.domain.identity.User;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/** 認證服務：登入、續期、登出。 */
@Service
public class AuthenticationService implements AuthenticationUseCase {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordHasher passwordHasher;
    private final AccessTokenIssuer accessTokenIssuer;
    private final SecureTokenGenerator tokenGenerator;
    private final RefreshTokenRevoker tokenRevoker;
    private final AuthPolicy policy;
    private final Clock clock;

    public AuthenticationService(UserRepository userRepository,
                                 RefreshTokenRepository refreshTokenRepository,
                                 PasswordHasher passwordHasher,
                                 AccessTokenIssuer accessTokenIssuer,
                                 SecureTokenGenerator tokenGenerator,
                                 RefreshTokenRevoker tokenRevoker,
                                 AuthPolicy policy,
                                 Clock clock) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordHasher = passwordHasher;
        this.accessTokenIssuer = accessTokenIssuer;
        this.tokenGenerator = tokenGenerator;
        this.tokenRevoker = tokenRevoker;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SessionTokens login(LoginCommand command) {
        User user = authenticate(command);
        user.ensureCanAuthenticate();

        // 每次登入開一條新的輪替鏈：不同裝置各自獨立，
        // 其中一條被偵測到重用時，不會波及其他裝置。
        return issueSession(user, tokenGenerator.generateFamilyId());
    }

    /** 驗證帳密。 */
    private User authenticate(LoginCommand command) {
        Optional<User> found = userRepository.findByEmail(Email.of(command.email()));
        if (found.isEmpty()) {
            passwordHasher.wasteTime();
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        User user = found.get();
        if (!passwordHasher.matches(command.rawPassword(), user.passwordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        return user;
    }

    @Override
    @Transactional
    public SessionTokens refresh(String rawRefreshToken) {
        RefreshToken stored = requireStoredToken(rawRefreshToken);
        Instant now = clock.instant();

        // 已輪替過的 token 再度出現 —— 合法用戶端不會保留它。
        if (stored.isRotated()) {
            handleTokenReuse(stored, now);
        }
        if (!stored.isUsableAt(now)) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        User user = userRepository.findById(stored.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        // 續期時重新檢查帳號狀態：停權後不該還能靠舊的 refresh token 續下去。
        user.ensureCanAuthenticate();

        SessionTokens tokens = issueSession(user, stored.familyId());
        stored.rotateTo(tokenGenerator.hashToken(tokens.refreshToken()), now);
        refreshTokenRepository.save(stored);
        return tokens;
    }

    /** 重用偵測：撤銷整條輪替鏈並拒絕本次請求。 */
    private void handleTokenReuse(RefreshToken stored, Instant now) {
        // 必須走獨立交易：本方法結尾會拋例外，若撤銷跟著外層交易一起回滾，
        // 就會變成「偵測到外洩卻什麼都沒撤銷」——攻擊者的令牌照樣有效。
        int revoked = tokenRevoker.revokeFamily(stored.familyId(), now);
        // 這是安全事件，必須留下痕跡供事後調查。
        log.warn("偵測到 refresh token 重用，已撤銷整條輪替鏈 userId={}, family={}, 撤銷 {} 筆",
                stored.userId(), stored.familyId(), revoked);
        throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Override
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        // 查不到就靜默結束：回報「這個 token 不存在」等於提供一支驗證 token 的 API。
        refreshTokenRepository.findByTokenHash(tokenGenerator.hashToken(rawRefreshToken))
                .ifPresent(token -> {
                    token.revoke(clock.instant());
                    refreshTokenRepository.save(token);
                });
    }

    private RefreshToken requireStoredToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        return refreshTokenRepository.findByTokenHash(tokenGenerator.hashToken(rawRefreshToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
    }

    private SessionTokens issueSession(User user, String familyId) {
        Instant now = clock.instant();
        AccessTokenIssuer.IssuedAccessToken accessToken = accessTokenIssuer.issue(user);

        String rawRefreshToken = tokenGenerator.generateToken();
        refreshTokenRepository.save(RefreshToken.issue(
                tokenGenerator.hashToken(rawRefreshToken),
                user.id(),
                familyId,
                now,
                now.plus(policy.refreshTokenTtl())));

        return SessionTokens.of(accessToken.value(), accessToken.expiresIn(),
                rawRefreshToken, policy.refreshTokenTtl());
    }
}

package com.flashsale.api.adapter.in.web.security;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 把令牌中的使用者身分解析成 Controller 參數。
 *
 * <p>集中在這一處轉換，好處是 Controller 完全不必認得 Spring Security 的型別——
 * 若日後從 JWT 換成別種認證機制，只要改這個類別，所有 Controller 都不用動。
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final AuthenticatedUserProvider userProvider;

    public CurrentUserArgumentResolver(AuthenticatedUserProvider userProvider) {
        this.userProvider = userProvider;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && Long.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        // 走到這裡代表已通過 Security 過濾鏈，理論上必有身分；
        // 但若有人誤把端點加進 permitAll 清單，這裡會明確拋 401 而非 NullPointerException。
        return userProvider.currentUserId()
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }
}

package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.security.ApiAccessDeniedHandler;
import com.flashsale.api.adapter.in.web.security.ApiAuthenticationEntryPoint;
import com.flashsale.api.adapter.in.web.security.AuthenticatedUserProvider;
import com.flashsale.api.adapter.in.web.security.CurrentUserArgumentResolver;
import com.flashsale.api.config.SecurityConfig;
import com.flashsale.api.config.WebMvcConfig;
import com.flashsale.application.port.in.ActivityQueryUseCase;
import com.flashsale.application.port.in.OrderQueryUseCase;
import com.flashsale.application.port.in.SeckillUseCase;
import com.flashsale.application.port.in.StockWarmupUseCase;
import com.flashsale.application.port.in.command.SeckillCommand;
import com.flashsale.application.port.in.dto.SeckillTicket;
import com.flashsale.infrastructure.adapter.out.ratelimit.UserRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認證與授權的行為測試。
 *
 * <p>這些測試存在的理由很直接：<b>權限設定的錯誤不會讓任何東西「壞掉」</b>——
 * 少一條規則，端點就默默對全世界開放，功能測試依然全綠。
 * 只有明確斷言「沒帶令牌必須被擋」，這類漏洞才會在 CI 被抓到。
 */
@WebMvcTest(controllers = {SeckillController.class, ActivityController.class})
@Import({SecurityConfig.class, WebMvcConfig.class, CurrentUserArgumentResolver.class,
        AuthenticatedUserProvider.class, ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class})
@DisplayName("API 認證與授權")
class SeckillControllerSecurityTest {

    private static final long USER_ID = 9001L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean private SeckillUseCase seckillUseCase;
    @MockBean private OrderQueryUseCase orderQueryUseCase;
    @MockBean private ActivityQueryUseCase activityQueryUseCase;
    @MockBean private StockWarmupUseCase stockWarmupUseCase;
    @MockBean private UserRateLimiter userRateLimiter;

    @BeforeEach
    void setUp() {
        when(userRateLimiter.tryAcquire(anyString(), anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("未帶令牌的搶購請求：回 401，且不得進入業務邏輯")
    void rejectsSeckillWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/seckill/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seckillBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0004"));

        // 關鍵斷言：請求必須在到達 Use Case 之前就被擋下。
        verify(seckillUseCase, never()).attempt(any());
    }

    @Test
    @DisplayName("帶有效令牌：放行，且身分取自令牌的 sub 而非請求內容")
    void acceptsSeckillWithValidToken() throws Exception {
        when(seckillUseCase.attempt(any())).thenReturn(SeckillTicket.accepted("ORDER-1"));

        mockMvc.perform(post("/api/v1/seckill/orders")
                        .with(jwt().jwt(token -> token.subject(String.valueOf(USER_ID))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seckillBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.orderNo").value("ORDER-1"));

        verify(seckillUseCase).attempt(argThatUserIs());
    }

    @Test
    @DisplayName("令牌的 sub 不是合法 userId：回 401，不可讓 null 身分流進業務層")
    void rejectsTokenWithNonNumericSubject() throws Exception {
        mockMvc.perform(post("/api/v1/seckill/orders")
                        .with(jwt().jwt(token -> token.subject("not-a-user-id")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seckillBody()))
                .andExpect(status().isUnauthorized());

        verify(seckillUseCase, never()).attempt(any());
    }

    @Test
    @DisplayName("訂單查詢同樣需要認證")
    void rejectsOrderQueryWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/seckill/orders/123456"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("活動查詢開放匿名瀏覽——商品頁不該逼使用者先登入")
    void allowsAnonymousActivityBrowsing() throws Exception {
        mockMvc.perform(get("/api/v1/activities"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("預熱端點：一般使用者的令牌不足以呼叫，回 403 而非 401")
    void rejectsWarmUpForNonAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/activities/1001/warm-up")
                        .with(jwt().jwt(token -> token.subject(String.valueOf(USER_ID))
                                .claim("scope", "seckill:order"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A0005"));

        verify(stockWarmupUseCase, never()).warmUp(any(), anyBoolean());
    }

    @Test
    @DisplayName("預熱端點：具備 seckill:admin scope 才放行")
    void allowsWarmUpForAdmin() throws Exception {
        when(stockWarmupUseCase.warmUp(1001L, false)).thenReturn(1000L);

        mockMvc.perform(post("/api/v1/activities/1001/warm-up")
                        .with(jwt().jwt(token -> token.subject("1").claim("scope", "seckill:admin"))))
                .andExpect(status().isOk());

        verify(stockWarmupUseCase).warmUp(1001L, false);
    }

    /** 驗證傳進 Use Case 的身分確實來自令牌的 sub，而非請求內容。 */
    private static SeckillCommand argThatUserIs() {
        return argThat(command -> command.userId().equals(USER_ID));
    }

    private static String seckillBody() {
        return """
                {"activityId":1001,"quantity":1,"requestId":"security-test-1"}
                """;
    }
}

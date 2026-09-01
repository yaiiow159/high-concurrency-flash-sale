package com.flashsale.api.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認證流程的整合測試——對著<b>真實的 MySQL</b> 執行。
 *
 * <p><b>為什麼非得用真的資料庫？</b>
 * 這裡要驗證的風險是「交易邊界」，而那是 mock 結構上就看不到的東西。
 *
 * <p>實際發生過的例子：重用偵測撤銷整條輪替鏈後拋例外拒絕請求，
 * 但拋例外讓外層交易回滾，把撤銷一起還原掉。
 * 單元測試完全通過——mock 顯示 {@code revokeFamily} 確實被呼叫了，
 * 而它確實被呼叫了。回滾發生在 mock 之外。
 *
 * <p>這一類 bug 只有「真的寫進資料庫、真的 commit、再真的讀回來」才會現形。
 * 本測試因此刻意不 mock 任何持久化元件。
 *
 * <p>Kafka 不啟動（本流程用不到），排程也設成極長間隔——
 * 它們與認證無關，讓它們跑只會製造雜訊與不穩定。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("認證流程整合測試")
class AuthenticationIntegrationTest {

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("flash_sale")
                    .withUsername("flashsale")
                    .withPassword("flashsale");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // Kafka 消費端不啟動：認證流程用不到，讓它反覆重連只會拖慢測試並製造雜訊
        registry.add("spring.kafka.listener.auto-startup", () -> "false");

        // 排程設成極長間隔。它們與認證無關，在測試期間跑只會產生
        // 「Kafka 連不上」之類的錯誤日誌，掩蓋掉真正該看的東西。
        registry.add("flash-sale.outbox.relay-interval-ms", () -> "3600000");
        registry.add("flash-sale.order.close-interval-ms", () -> "3600000");
        registry.add("flash-sale.stock.warmup-interval-ms", () -> "3600000");
        registry.add("flash-sale.reconciliation.interval-ms", () -> "3600000");
        registry.add("flash-sale.reconciliation.initial-delay-ms", () -> "3600000");
        registry.add("flash-sale.payment.refund-scan-interval-ms", () -> "3600000");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Nested
    @DisplayName("註冊與登入")
    class RegisterAndLogin {

        @Test
        @DisplayName("完整流程：註冊 → 登入 → 以 access token 存取受保護端點")
        void registersAndAuthenticates() throws Exception {
            String email = uniqueEmail();
            register(email, "correct-horse", "Alice");

            String accessToken = login(email, "correct-horse").get("accessToken").asText();

            mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.email").value(email))
                    .andExpect(jsonPath("$.data.role").value("CUSTOMER"));
        }

        @Test
        @DisplayName("信箱大小寫不同仍算同一個帳號——唯一索引必須擋下")
        void treatsEmailCaseInsensitively() throws Exception {
            String email = uniqueEmail();
            register(email, "correct-horse", "Alice");

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(registerBody(email.toUpperCase(), "other-pass", "Impostor")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("B0009"));
        }

        @Test
        @DisplayName("密碼錯誤與帳號不存在的回應必須完全相同")
        void indistinguishableFailureResponses() throws Exception {
            String email = uniqueEmail();
            register(email, "correct-horse", "Alice");

            String wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(email, "wrong")))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            String noSuchAccount = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(uniqueEmail(), "wrong")))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            // 任何差異都能被用來枚舉出哪些信箱已註冊
            assertThat(wrongPassword).isEqualTo(noSuchAccount);
        }
    }

    @Nested
    @DisplayName("Refresh token 輪替與重用偵測")
    class RotationAndReuse {

        @Test
        @DisplayName("續期會輪替：舊的 refresh token 立即失效")
        void rotatesRefreshToken() throws Exception {
            String email = uniqueEmail();
            register(email, "correct-horse", "Alice");
            String first = login(email, "correct-horse").get("refreshToken").asText();

            String second = refresh(first).get("refreshToken").asText();
            assertThat(second).isNotEqualTo(first);

            // 新的可以用
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(refreshBody(second)))
                    .andExpect(status().isOk());
        }

        /**
         * <b>這是本測試類別存在的理由。</b>
         *
         * <p>重用偵測的正確性取決於「撤銷必須真的被 commit」。
         * 曾經有一版程式撤銷後拋例外，導致交易回滾把撤銷還原——
         * 單元測試全綠，但實際上什麼都沒撤銷。
         *
         * <p>只有真的寫進資料庫、commit、再用另一個請求讀回來，才驗證得了這件事。
         */
        @Test
        @DisplayName("重用已輪替的 token：整條輪替鏈失效，連最新的也不能用")
        void reuseRevokesEntireFamily() throws Exception {
            String email = uniqueEmail();
            register(email, "correct-horse", "Alice");

            String t1 = login(email, "correct-horse").get("refreshToken").asText();
            String t2 = refresh(t1).get("refreshToken").asText();
            String t3 = refresh(t2).get("refreshToken").asText();

            // 模擬竊取者拿著外洩的舊 token 來換
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(refreshBody(t1)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("A0007"));

            // 關鍵斷言：撤銷必須已 commit，因此最新的 t3 現在也該失效。
            // 若撤銷被交易回滾，這一行會拿到 200 而測試失敗。
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(refreshBody(t3)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("A0007"));
        }

        @Test
        @DisplayName("不同裝置的輪替鏈互相獨立：一條被撤銷不影響另一條")
        void familiesAreIsolated() throws Exception {
            String email = uniqueEmail();
            register(email, "correct-horse", "Alice");

            String deviceA = login(email, "correct-horse").get("refreshToken").asText();
            String deviceB = login(email, "correct-horse").get("refreshToken").asText();

            // 裝置 A 觸發重用偵測
            String rotatedA = refresh(deviceA).get("refreshToken").asText();
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(refreshBody(deviceA)))
                    .andExpect(status().isUnauthorized());
            assertThat(rotatedA).isNotBlank();

            // 裝置 B 不該被波及——否則一個人的手機被盜會讓全公司登出
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(refreshBody(deviceB)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("登出後該 token 立即失效")
        void logoutRevokesToken() throws Exception {
            String email = uniqueEmail();
            register(email, "correct-horse", "Alice");
            String token = login(email, "correct-horse").get("refreshToken").asText();

            mockMvc.perform(post("/api/v1/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(refreshBody(token)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(refreshBody(token)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("登出無效 token 一律回 204——回報它不存在等於提供驗證 token 的 API")
        void logoutIsSilentForUnknownToken() throws Exception {
            mockMvc.perform(post("/api/v1/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(refreshBody("never-existed-token")))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("授權")
    class Authorization {

        @Test
        @DisplayName("未帶令牌存取受保護端點：401")
        void rejectsAnonymousAccess() throws Exception {
            mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("一般使用者打管理端點：403 而非 401")
        void rejectsNonAdminOnAdminEndpoint() throws Exception {
            String email = uniqueEmail();
            register(email, "correct-horse", "Alice");
            String accessToken = login(email, "correct-horse").get("accessToken").asText();

            mockMvc.perform(post("/api/v1/activities/1001/warm-up")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("A0005"));
        }

        @Test
        @DisplayName("已移除的 dev-token 端點不再存在")
        void devTokenEndpointIsGone() throws Exception {
            mockMvc.perform(post("/api/v1/auth/dev-token").param("userId", "999"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ---- helpers ----

    private void register(String email, String password, String name) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email, password, name)))
                .andExpect(status().isCreated());
    }

    private JsonNode login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private JsonNode refresh(String refreshToken) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    /** 每個測試用不同信箱，避免彼此干擾——容器在整個類別間共用。 */
    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private static String registerBody(String email, String password, String name) {
        return """
                {"email":"%s","password":"%s","displayName":"%s"}
                """.formatted(email, password, name);
    }

    private static String loginBody(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }

    private static String refreshBody(String refreshToken) {
        return """
                {"refreshToken":"%s"}
                """.formatted(refreshToken);
    }
}

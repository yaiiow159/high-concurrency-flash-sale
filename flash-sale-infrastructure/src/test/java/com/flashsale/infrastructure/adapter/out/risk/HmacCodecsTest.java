package com.flashsale.infrastructure.adapter.out.risk;

import com.flashsale.application.port.out.ChallengeCodec;
import com.flashsale.domain.risk.QualificationToken;
import com.flashsale.infrastructure.config.RiskProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 純 CPU，不需要 Docker。 */
@DisplayName("資格憑證與驗證題的簽章")
class HmacCodecsTest {

    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");
    private static final RiskProperties PROPS = new RiskProperties(
            "unit-test-secret-0123456789abcdef0123456789", true,
            Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(3), Duration.ofMinutes(10),
            600, 5, 3, 5, 60);

    @Nested
    @DisplayName("資格憑證")
    class Token {

        private final HmacQualificationTokenCodec codec = new HmacQualificationTokenCodec(PROPS);

        @Test
        @DisplayName("簽了再驗：原樣回來")
        void roundTrip() {
            QualificationToken token = new QualificationToken(88L, 1001L, NOW.plusSeconds(60), "nonce-1");

            assertThat(codec.verify(codec.issue(token))).contains(token);
        }

        @Test
        @DisplayName("改任何一個欄位，簽章就對不上")
        void tamperedTokenRejected() {
            String issued = codec.issue(new QualificationToken(88L, 1001L, NOW.plusSeconds(60), "n"));
            String[] parts = issued.split("\\.");

            String otherUser = "89." + String.join(".", parts[1], parts[2], parts[3], parts[4]);
            String laterExpiry = String.join(".", parts[0], parts[1], String.valueOf(NOW.plusSeconds(999_999).getEpochSecond()), parts[3], parts[4]);

            assertThat(codec.verify(otherUser)).isEmpty();
            assertThat(codec.verify(laterExpiry)).isEmpty();
        }

        @Test
        @DisplayName("別的金鑰簽的不算數")
        void differentSecretRejected() {
            RiskProperties other = new RiskProperties("another-secret-0123456789abcdef0123456789", true,
                    Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(3), Duration.ofMinutes(10),
                    600, 5, 3, 5, 60);
            String issued = new HmacQualificationTokenCodec(other).issue(
                    new QualificationToken(88L, 1001L, NOW.plusSeconds(60), "n"));

            assertThat(codec.verify(issued)).isEmpty();
        }

        @Test
        @DisplayName("垃圾輸入回 empty，不拋例外——熱路徑上例外比拒絕貴")
        void garbageIsEmpty() {
            assertThat(codec.verify(null)).isEmpty();
            assertThat(codec.verify("")).isEmpty();
            assertThat(codec.verify("a.b.c")).isEmpty();
            assertThat(codec.verify("x.y.z.w.v")).isEmpty();
            assertThat(codec.verify("1".repeat(600))).isEmpty();
        }
    }

    @Nested
    @DisplayName("驗證題")
    class Challenge {

        private final HmacChallengeCodec codec = new HmacChallengeCodec(PROPS);

        @Test
        @DisplayName("答對放行；題目長得像「a + b = ?」")
        void correctAnswer() {
            ChallengeCodec.Issued issued = codec.issue(NOW);
            String[] operands = issued.question().replace(" = ?", "").split(" \\+ ");
            int answer = Integer.parseInt(operands[0]) + Integer.parseInt(operands[1]);

            assertThat(codec.verify(issued.challengeToken(), String.valueOf(answer), NOW)).isTrue();
            assertThat(codec.verify(issued.challengeToken(), " " + answer + " ", NOW)).isTrue();
        }

        @Test
        @DisplayName("答錯、過期、非數字都不過")
        void wrongAnswerExpiryAndGarbage() {
            ChallengeCodec.Issued issued = codec.issue(NOW);

            assertThat(codec.verify(issued.challengeToken(), "-1", NOW)).isFalse();
            assertThat(codec.verify(issued.challengeToken(), "abc", NOW)).isFalse();
            assertThat(codec.verify(issued.challengeToken(), "5", issued.expiresAt())).isFalse();
            assertThat(codec.verify("1.2.3", "3", NOW)).isFalse();
        }
    }
}

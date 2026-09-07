package com.flashsale.domain.risk;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("風險評分")
class RiskAssessmentTest {

    private static final RiskPolicy POLICY = RiskPolicy.defaults();

    @Test
    @DisplayName("老帳號、獨立 IP 與裝置：零分，放行")
    void cleanSignalsPass() {
        RiskAssessment result = RiskAssessment.evaluate(new RiskSignals(86_400, 1, 1, 1), POLICY);

        assertThat(result.score()).isZero();
        assertThat(result.rejected()).isFalse();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    @DisplayName("單一訊號不拒絕：宿舍共用 IP、剛註冊就來搶，都有無辜的解釋")
    void singleSignalIsNotEnough() {
        assertThat(RiskAssessment.evaluate(new RiskSignals(10, 1, 1, 1), POLICY).rejected()).isFalse();
        assertThat(RiskAssessment.evaluate(new RiskSignals(86_400, 50, 1, 1), POLICY).rejected()).isFalse();
    }

    @Test
    @DisplayName("新帳號 + 同 IP 多帳號：疊起來才像機器人，拒絕並說明原因")
    void stackedSignalsReject() {
        RiskAssessment result = RiskAssessment.evaluate(new RiskSignals(10, 50, 1, 1), POLICY);

        assertThat(result.score()).isEqualTo(70);
        assertThat(result.rejected()).isTrue();
        assertThat(result.reasons()).containsExactly("新註冊帳號", "同一 IP 多個帳號");
    }

    @Test
    @DisplayName("門檻是「超過」不是「達到」：剛好 5 個帳號共用 IP 仍放行")
    void thresholdIsExclusive() {
        assertThat(RiskAssessment.evaluate(new RiskSignals(10, POLICY.maxUsersPerIp(), 1, 1), POLICY).rejected())
                .isFalse();
    }

    @Nested
    @DisplayName("資格憑證")
    class Token {

        private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");

        @Test
        @DisplayName("人與活動都對、未過期：可用")
        void usable() {
            QualificationToken token = new QualificationToken(1L, 1001L, NOW.plusSeconds(60), "n");

            assertThatCode(() -> token.ensureUsableBy(1L, 1001L, NOW)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("別人的、別檔活動的、過期的：同一個錯誤碼，不透露是哪一項對不上")
        void rejectsMismatchAndExpiry() {
            QualificationToken token = new QualificationToken(1L, 1001L, NOW.plusSeconds(60), "n");

            assertThatThrownBy(() -> token.ensureUsableBy(2L, 1001L, NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.QUALIFICATION_INVALID);
            assertThatThrownBy(() -> token.ensureUsableBy(1L, 1002L, NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.QUALIFICATION_INVALID);
            assertThatThrownBy(() -> token.ensureUsableBy(1L, 1001L, NOW.plusSeconds(60)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.QUALIFICATION_INVALID);
        }
    }

    @Nested
    @DisplayName("黑名單")
    class Blacklist {

        private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");

        @Test
        @DisplayName("沒有到期時間代表永久")
        void permanentWhenNoExpiry() {
            assertThat(new BlacklistEntry(1L, "刷單", 9L, NOW, null).isActiveAt(NOW.plusSeconds(999_999))).isTrue();
        }

        @Test
        @DisplayName("到期後視為不存在")
        void expires() {
            BlacklistEntry entry = new BlacklistEntry(1L, "刷單", 9L, NOW, NOW.plusSeconds(60));

            assertThat(entry.isActiveAt(NOW.plusSeconds(59))).isTrue();
            assertThat(entry.isActiveAt(NOW.plusSeconds(60))).isFalse();
        }

        @Test
        @DisplayName("原因必填：事後沒人說得出為什麼")
        void requiresReason() {
            assertThatThrownBy(() -> new BlacklistEntry(1L, " ", 9L, NOW, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PARAMETER);
        }
    }
}

package com.flashsale.domain.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 錯誤碼本身的約束。 */
@DisplayName("錯誤碼")
class ErrorCodeTest {

    @Test
    @DisplayName("沒有兩個錯誤共用同一個碼")
    void codesAreUnique() {
        Map<String, List<String>> byCode = Arrays.stream(ErrorCode.values())
                .collect(Collectors.groupingBy(ErrorCode::code,
                        Collectors.mapping(Enum::name, Collectors.toList())));

        List<Map.Entry<String, List<String>>> collisions = byCode.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .toList();

        assertThat(collisions)
                .as("這些錯誤碼被重複使用了，前端無法區分：%s", collisions)
                .isEmpty();
    }

    @Test
    @DisplayName("碼的前綴決定分類，而 C 開頭代表可重試")
    void prefixDeterminesRetryability() {
        // 這條規則不只是命名慣例：Kafka 消費端的重試分類直接讀它
        // （見 KafkaConsumerConfig.isRetryable）。前綴弄錯，
        // 訊息會直接進 DLT 或反過來無限重試
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.code())
                    .as("%s 的碼格式不對", code.name())
                    .matches("^[ABC][0-9]{4}$");
            assertThat(code.retryable())
                    .as("%s(%s) 的可重試性應與前綴一致", code.name(), code.code())
                    .isEqualTo(code.code().startsWith("C"));
        }
    }
}

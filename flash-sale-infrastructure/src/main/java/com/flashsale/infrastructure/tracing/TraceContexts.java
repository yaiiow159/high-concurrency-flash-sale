package com.flashsale.infrastructure.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 把當前 trace 存成 W3C {@code traceparent} 字串、事後再還原（ADR-0029）。
 * Outbox 兩側就靠它接續：寫入事件時抓，排程中繼時還原。
 * Tracer 不存在時（測試、關掉追蹤）一律 no-op，呼叫端不必判斷。
 */
@Component
public class TraceContexts {

    private static final Logger log = LoggerFactory.getLogger(TraceContexts.class);
    private static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;
    private final AtomicBoolean formatWarned = new AtomicBoolean();

    // 有兩個建構子時 Spring 需要被告知用哪一個，否則會去找不存在的無參建構子
    @Autowired
    public TraceContexts(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer.getIfAvailable();
        this.propagator = propagator.getIfAvailable();
    }

    /** 測試用：沒有追蹤。 */
    public static TraceContexts noop() {
        return new TraceContexts((Tracer) null, (Propagator) null);
    }

    private TraceContexts(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** 當下 trace 的 32 位十六進位 id；沒有 span 時回 empty。 */
    public Optional<String> currentTraceId() {
        if (tracer == null || tracer.currentSpan() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tracer.currentSpan().context().traceId());
    }

    /** 當下沒有 span 時回 empty。 */
    public Optional<String> current() {
        if (tracer == null || propagator == null || tracer.currentSpan() == null) {
            return Optional.empty();
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(tracer.currentTraceContext().context(), carrier, Map::put);
        String traceparent = carrier.get(TRACEPARENT);
        if (traceparent == null && formatWarned.compareAndSet(false, true)) {
            // 傳播格式被改成 B3 之類時這裡拿不到東西，Outbox 接續會安靜失效——至少要吵一次
            log.warn("當前 span 存在但 propagator 沒有寫出 traceparent（欄位：{}），Outbox 兩側的 trace 不會接續",
                    propagator.fields());
        }
        return Optional.ofNullable(traceparent);
    }

    /**
     * 在還原出來的上下文底下開一個 span。span 的生命週期由呼叫端決定（{@link Trace#end}），
     * 進出執行緒的 scope 則只在 {@link Trace#inScope} 期間——投遞是先送一批再逐筆等 ack，
     * 若 scope 一直掛在執行緒上，下一個事件的 span 會誤認前一個為父節點。
     * traceparent 為 null 或不合法時照常開 span，只是變成新的 trace。
     */
    public Trace open(String traceparent, String spanName) {
        if (tracer == null || propagator == null) {
            return Trace.NOOP;
        }
        Map<String, String> carrier = traceparent == null || traceparent.isBlank()
                ? Map.of() : Map.of(TRACEPARENT, traceparent);
        Span span = propagator.extract(carrier, Map::get).name(spanName).start();
        return new Trace() {
            @Override
            public <T> T inScope(Supplier<T> action) {
                try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                    return action.get();
                }
            }

            @Override
            public void error(Throwable error) {
                span.error(error);
            }

            @Override
            public void end() {
                // 追蹤程式碼絕不可改變投遞成敗的判定：這裡任何例外都吞掉
                try {
                    span.end();
                } catch (RuntimeException ignored) {
                    // 見上
                }
            }
        };
    }

    /** 一段被追蹤的工作。 */
    public interface Trace {

        Trace NOOP = new Trace() {
            @Override
            public <T> T inScope(Supplier<T> action) {
                return action.get();
            }

            @Override
            public void error(Throwable error) {
            }

            @Override
            public void end() {
            }
        };

        /** 在這個 span 的 scope 裡跑：裡面產生的子 span 與 log 的 traceId 都掛在它底下。 */
        <T> T inScope(Supplier<T> action);

        void error(Throwable error);

        void end();
    }
}

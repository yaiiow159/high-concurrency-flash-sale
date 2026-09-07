package com.flashsale.infrastructure.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 把當前 trace 存成 W3C {@code traceparent} 字串、事後再還原（ADR-0029）。
 * Outbox 兩側就靠它接續：寫入事件時抓，排程中繼時還原。
 * Tracer 不存在時（測試、關掉追蹤）一律 no-op，呼叫端不必判斷。
 */
@Component
public class TraceContexts {

    private static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

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

    /** 當下沒有 span 時回 empty。 */
    public Optional<String> current() {
        if (tracer == null || propagator == null || tracer.currentSpan() == null) {
            return Optional.empty();
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(tracer.currentTraceContext().context(), carrier, Map::put);
        return Optional.ofNullable(carrier.get(TRACEPARENT));
    }

    /**
     * 在還原出來的上下文底下跑一段動作。traceparent 為 null 或不合法時就照常跑，
     * 只是開一條新的 trace——斷鏈比不投遞好。
     */
    public <T> T runUnder(String traceparent, String spanName, Supplier<T> action) {
        if (tracer == null || propagator == null || traceparent == null || traceparent.isBlank()) {
            return action.get();
        }
        Map<String, String> carrier = Map.of(TRACEPARENT, traceparent);
        Span span = propagator.extract(carrier, Map::get).name(spanName).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return action.get();
        } finally {
            span.end();
        }
    }
}

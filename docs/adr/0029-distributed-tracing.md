# ADR-0029：分散式追蹤，以及 Outbox 兩側的 trace 接續

- 狀態：已接受
- 日期：2026-09-07
- 相關：[ADR-0004](0004-outbox-saga-over-seata.md)（Outbox）、
  [ADR-0023](0023-queue-depth-as-service-level.md)（佇列深度）

---

## 背景

一筆秒殺請求走 API → Redis Lua → Kafka → 消費端 → MySQL → Outbox → Kafka → 通知／積分／出貨。
出事時能拿到的只有各處 log 裡的 `orderNo`，靠人肉把它們串起來。

指標（Prometheus）回答的是「整體有多慢、有多少失敗」；它回答不了「**這一筆**為什麼慢」——
是 Redis 那 2ms、Kafka 投遞的 5ms linger、還是消費端在 MySQL 上等了 300ms 的鎖。

---

## 決策

### 1. Micrometer Tracing + OpenTelemetry bridge，OTLP 送到 Tempo

Spring Boot 3 的 observation 已經替 HTTP server、`KafkaTemplate`、`@KafkaListener`、JDBC（`datasource-micrometer`）
與 Lettuce 產生 span，只差把 bridge 與 exporter 放進 classpath。選 OTel bridge 而不是 Brave：
OTLP 是 Tempo／Jaeger／各家 SaaS 共通的線路格式，換後端只改一個 endpoint。

Tempo 而不是 Zipkin：Grafana 已經在了，trace ↔ metrics ↔ log 在同一個畫面裡跳轉，
不必再開一個 UI。

### 2. Outbox 兩側要手動接續 trace

Outbox 是刻意切斷的：事件先落 MySQL，由排程在**另一個執行緒、另一個時間點**搬到 Kafka。
observation 的自動傳播靠的是執行緒上的當前 span，排程執行緒上什麼都沒有——
於是每一個由 Outbox 投遞的事件都會開一條全新的 trace，`order.paid` 之後的通知、積分、出貨
與那筆訂單的 trace 斷開。

做法：`outbox_event` 多一欄 `trace_context`，寫入事件時把當下的 W3C `traceparent` 存進去；
中繼時從那一欄還原 span 上下文、在它底下開 `outbox.relay` span，再送 Kafka。
消費端照常從 header 取到 traceparent，於是整條鏈仍是同一個 trace id。

存的是 `traceparent` 字串而不是 trace id：前者帶 parent span id 與 sampled 旗標，
還原出來的 span 才會掛在正確的父節點下、且尊重上游的取樣決定。

### 3. 取樣率是設定，開發環境 100%

正式環境的秒殺尖峰每秒上千筆，全採樣會讓 Tempo 的寫入量跟業務流量一樣大。
`management.tracing.sampling.probability` 由環境變數覆寫；本機預設 1.0 是為了「每一筆都查得到」。

### 4. log 帶 traceId

log 格式加上 `traceId`／`spanId`。從一行 ERROR 直接拿到 trace id 貼進 Grafana，
是這件事對維運最直接的價值——比 service map 好看的圖有用得多。

---

## 被否決的方案

**只靠 `orderNo` 串 log。** 它在 Redis 與 Kafka 的 span 裡不存在，
而且回答不了「這 300ms 花在哪」——log 沒有時間區間。

**自己在 Kafka header 帶 traceId、消費端手動開 span。** 那正是 observation 已經做掉的事；
手寫一份只會在下一次升級時與框架的傳播格式打架。

**Outbox 事件不接續 trace，接受斷鏈。** 這會讓「付款成功後通知為什麼沒寄」變成兩條 trace 的人工比對，
而那是這件事最常被問的問題。

---

## 後果

- 熱路徑每個請求多建一個 span 與若干子 span，取樣後匯出是非同步批次，不在請求路徑上
- `outbox_event.trace_context` 是 nullable：排程或維運工具寫入的事件沒有上游 trace，那時開新的
- Tempo 本地儲存保留 48 小時；它是除錯工具，不是稽核紀錄

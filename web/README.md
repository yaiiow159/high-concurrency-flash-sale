# 秒殺前端

Nuxt 3 + Vue 3 + TypeScript。目前只實作**秒殺頁**——
用真實前端驗證後端 API，把設計問題逼出來，是 P1 動手前最有價值的回饋。

## 啟動

需要後端先跑起來（見專案根目錄的 README）。

```bash
npm install
npm run dev
```

開 http://localhost:5173

> 開發埠用 5173 而非 Nuxt 預設的 3000 —— 後者被 `docker-compose.yml`
> 裡的 Grafana 佔用了。

## 三個關鍵設計

### 1. 秒殺頁是削峰漏斗的第 0 層

| 部分 | 策略 | 理由 |
|---|---|---|
| 商品資訊、活動時間 | ISR + CDN | 100 萬次瀏覽不該有一次打到 origin |
| 庫存數字 | 客戶端獨立請求 | 變動極快，快取它只會讓使用者看到過期數字 |
| 搶購按鈕 | 開賣前就緒 + 隨機抖動 | 見下 |

**互動能力必須在開賣前就緒**。若按鈕要等 hydration 完成才能點，
開賣瞬間的第一波使用者會全部點空。

**開賣瞬間加隨機抖動**（0–300ms）。所有人的倒數同時歸零、同時送出請求，
會製造一個尖銳到不必要的脈衝；打散幾百毫秒對使用者無感，對後端差別很大。

### 2. 時間一律來自伺服器

客戶端時鐘可能偏差數分鐘。直接用 `Date.now()` 倒數會讓時鐘快的使用者
提早狂打 API、慢的則錯過開賣。

`ActivityView.serverTime` 隨活動資料一起回來，
以 NTP 的方式扣掉往返時間的一半算出偏移：

```
偏移 ≈ serverTime − (送出時間 + 收到時間) / 2
```

放在活動回應裡而非另開 `/server-time` 端點——前端本來就要取活動資料，
多一個端點就多一次往返，而開賣前那一秒的往返最不該浪費。

### 3. 令牌：BFF + httpOnly cookie

| 令牌 | 存放 | 為什麼 |
|---|---|---|
| Access token | 記憶體（Pinia，不持久化） | localStorage 是 XSS 的直接目標 |
| Refresh token | **httpOnly cookie**，由 Nuxt server routes 設定 | JS 完全碰不到 |

後端在回應主體中同時給出兩個令牌；`server/api/auth/*` 這層 BFF
把 refresh token 攔下來寫進 cookie，**只把 access token 交給瀏覽器**。

已實測驗證：`document.cookie` 為空、`localStorage`／`sessionStorage` 為空、
SSR payload 中 `accessToken` 為 `null`（重要——payload 會被 ISR 快取到 CDN）。

#### refresh 的併發收斂

後端每次續期都會**輪替** refresh token，舊的立即失效。
若三個請求同時 401 而各自發一次續期，後兩次會拿著已失效的令牌，
反而觸發後端的重用偵測，導致整條輪替鏈被撤銷、使用者莫名被登出。

`stores/auth.ts` 以一個 in-flight Promise 收斂併發呼叫——
與後端的 Redis 冪等是同一類問題：**併發下的重複請求要收斂成一次**。

## 輪詢節奏

| 對象 | 節奏 | 停止條件 |
|---|---|---|
| 庫存 | 每 2 秒 | **售罄即停**——再問也不會變，而售罄正是流量最大的時刻 |
| 訂單 | 前 10 秒每 1 秒，10–30 秒每 3 秒 | 30 秒逾時，請使用者去訂單頁查 |

訂單輪詢**必須有上限**。無限輪詢在尖峰時會變成第二波流量，
而且是打在系統已經很吃力的時候。

逾時不等於失敗：庫存可能已扣、訂單也還在建立中，只是消費端還沒跟上。

## 目錄

```
app/
├── pages/
│   ├── index.vue           活動列表（ISR 60s）
│   └── seckill/[id].vue    秒殺頁（ISR 300s）
├── components/
│   ├── CountdownTimer.vue  用校正後的伺服器時間
│   ├── StockIndicator.vue
│   ├── SeckillButton.vue   開賣前就緒 + 隨機抖動 + 送出後禁用
│   └── AuthPanel.vue
├── composables/
│   ├── useApi.ts           401 自動續期並重送
│   ├── useSeckill.ts       退避輪詢 + 分層訂單輪詢
│   └── useServerTime.ts    時鐘偏移校正
├── stores/auth.ts          access token 只放記憶體 + 續期併發收斂
└── types/api.ts            手寫；應改為從 OpenAPI 產生

server/api/auth/            BFF：refresh token 進 httpOnly cookie
```

## 已知缺口

- **型別手寫**。應從 springdoc 的 `/v3/api-docs` 以 `openapi-typescript` 產生，
  並在 CI 檢查產物有無 diff——讓後端改欄位時前端**編譯就失敗**，
  而不是上線後才在 console 看到 `undefined`
- **沒有商品列表以外的頁面**。購物車、結帳、訂單中心屬於 P1／P2
- **沒有前端測試**。Vitest 元件測試與 Playwright 端到端測試尚未建立

# API 說明文件

所有 API 都經 gateway 存取，前綴 `/api/v1`：

- 直接打 gateway：`http://localhost:8000/api/v1/...`
- 經網站（Nginx 同源代理）：`http://localhost/api/v1/...`
- 互動式文件：Swagger UI <http://localhost:8000/swagger-ui.html>（匯集 draw-service、campaign-service、record-service 的 OpenAPI 3.1，可用 Keycloak 登入後試打）

範例中的回應都取自實際執行的系統（id、UUID 與時間會不同）。

## 目錄

- [通則](#通則)
- [路由總覽](#路由總覽)
- [USER API](#user-api)
  - [GET /campaigns/{id}](#get-campaignsid)
  - [GET /campaigns/{id}/me](#get-campaignsidme)
  - [POST /campaigns/{id}/draws](#post-campaignsiddraws)
  - [GET /campaigns/{id}/draws/me](#get-campaignsiddrawsme)
- [ADMIN API：活動](#admin-api活動)
- [ADMIN API：獎品](#admin-api獎品)
- [ADMIN API：統計](#admin-api統計)
- [冪等（Idempotency-Key）](#冪等idempotency-key)
- [限流](#限流)
- [錯誤格式](#錯誤格式)
- [錯誤碼](#錯誤碼)

---

## 通則

### 身分驗證

除了 Swagger UI 與 OpenAPI 文件外，每個 API 都需要 Keycloak 簽發的 access token：

```http
Authorization: Bearer <access_token>
```

- 取得 token：瀏覽器以 Authorization Code + PKCE 登入（前端 client `spintowin-frontend`、Swagger UI client `spintowin-swagger`）。命令列可用僅供開發的 `spintowin-cli` client：

  ```bash
  curl -s -X POST http://localhost:8080/realms/spintowin/protocol/openid-connect/token \
    -d grant_type=password -d client_id=spintowin-cli -d username=user1 -d password=user1
  ```

- token 有效 15 分鐘。
- **使用者 id 一律取自 token 的 `sub`**，API 不接受由呼叫端指定的使用者。
- 角色取自 token 的 realm roles：

| 角色 | 可呼叫 |
|---|---|
| `USER` | `/campaigns/**` |
| `ADMIN` | `/admin/**` |

預建帳號：`admin`（ADMIN + USER）、`user1`～`user3`（USER），密碼與帳號相同。

| 情況 | 回應 |
|---|---|
| 沒有 token、token 無效或過期 | `401 UNAUTHORIZED`，並帶 `WWW-Authenticate: Bearer` |
| token 有效但缺少角色（例如 USER 呼叫 `/admin/**`） | `403 FORBIDDEN` |

### 格式

- Request / response body 皆為 JSON（UTF-8）；錯誤為 `application/problem+json`。
- 時間為 UTC 的 ISO-8601 字串，例如 `2026-12-01T00:00:00Z`。
- **Win Probability 以百分比表示**，0～100、最多 4 位小數（`0.0001` = 百萬分之一）；回應固定 4 位小數（例如 `9.5000`）。
- `noWinProbability` 是銘謝惠顧的機率 = 100 − 所有 Prize 機率總和。
- 銘謝惠顧的 Draw Result：`prizeId` 與 `prizeName` 為 `null`，`won` 為 `false`。

---

## 路由總覽

| Method | Path | 角色 | 服務 | 說明 |
|---|---|---|---|---|
| GET | `/campaigns/{id}` | USER | campaign | 活動資訊、獎品與機率 |
| GET | `/campaigns/{id}/me` | USER | draw | 我的剩餘次數 |
| POST | `/campaigns/{id}/draws` | USER | draw | 抽獎（單抽或連抽）；限流 |
| GET | `/campaigns/{id}/draws/me` | USER | record | 我的抽獎紀錄（分頁，新到舊） |
| POST | `/admin/campaigns` | ADMIN | campaign | 建立活動（DRAFT） |
| GET | `/admin/campaigns` | ADMIN | campaign | 列出所有活動 |
| GET | `/admin/campaigns/{id}` | ADMIN | campaign | 查看活動 |
| PUT | `/admin/campaigns/{id}` | ADMIN | campaign | 修改活動（僅 DRAFT） |
| POST | `/admin/campaigns/{id}/activate` | ADMIN | campaign | 上線（DRAFT → ACTIVE） |
| POST | `/admin/campaigns/{id}/end` | ADMIN | campaign | 結束（DRAFT / ACTIVE → ENDED） |
| POST | `/admin/campaigns/{id}/redis/rebuild` | ADMIN | campaign | 從 MySQL 重建 Redis |
| POST | `/admin/campaigns/{id}/prizes` | ADMIN | campaign | 新增獎品（僅 DRAFT） |
| PUT | `/admin/campaigns/{id}/prizes/{prizeId}` | ADMIN | campaign | 修改獎品名稱與機率 |
| DELETE | `/admin/campaigns/{id}/prizes/{prizeId}` | ADMIN | campaign | 刪除獎品（僅 DRAFT） |
| PATCH | `/admin/campaigns/{id}/prizes/{prizeId}/stock` | ADMIN | campaign | 增減庫存 |
| GET | `/admin/campaigns/{id}/stats` | ADMIN | record | 活動抽獎統計 |

公開（不需 token）：`/swagger-ui.html`、`/swagger-ui/**`、`/v3/api-docs/**`、`/{service}/v3/api-docs`。

路徑參數 `{id}`、`{prizeId}` 為正整數；非數字（例如 `/campaigns/abc`）回 `400 VALIDATION_FAILED`，`errors[].field` 為參數名稱。

---

## USER API

### GET /campaigns/{id}

活動資訊，給轉盤頁使用：時間窗、Draw Quota、Campaign Status，以及依 Prize 順序（id 由小到大）排列的獎品與機率。不含庫存。

```bash
curl -s http://localhost:8000/api/v1/campaigns/1 -H "Authorization: Bearer $TOKEN"
```

`200 OK`

```json
{
  "id": 1,
  "name": "週年慶轉盤抽獎",
  "startAt": "2026-01-01T00:00:00Z",
  "endAt": "2030-12-31T23:59:59Z",
  "drawQuota": 10,
  "status": "ACTIVE",
  "prizes": [
    { "id": 1, "name": "藍牙耳機", "winProbability": 1.0000 },
    { "id": 2, "name": "100 元購物金", "winProbability": 9.5000 },
    { "id": 3, "name": "免運券", "winProbability": 30.0000 }
  ],
  "noWinProbability": 59.5000
}
```

| 欄位 | 說明 |
|---|---|
| `status` | `ACTIVE` / `ENDED` |
| `drawQuota` | 每位使用者在此活動可抽的總次數 |

錯誤：`404 CAMPAIGN_NOT_FOUND`（活動不存在，或仍是 DRAFT：尚未上線的活動對使用者而言不存在；ADMIN API 照常可查）。

---

### GET /campaigns/{id}/me

目前使用者在此活動的次數使用狀況（即時，取自 Redis）。活動結束後仍可查詢。

```bash
curl -s http://localhost:8000/api/v1/campaigns/1/me -H "Authorization: Bearer $TOKEN"
```

`200 OK`

```json
{ "campaignId": 1, "drawQuota": 10, "usedDraws": 7, "remainingDraws": 3 }
```

錯誤：`409 CAMPAIGN_NOT_ACTIVE`（活動不存在或從未上線）。

---

### POST /campaigns/{id}/draws

單抽或連抽。整個判定在 Redis 以單一 Lua script 原子執行：

1. 同一使用者、同一 `Idempotency-Key` 已有結果 → 直接回傳先前的回應（見[冪等](#冪等idempotency-key)）。
2. 活動須為 `ACTIVE`，且現在時間在 `startAt`～`endAt` 內，否則 `409 CAMPAIGN_NOT_ACTIVE`。
3. 剩餘次數 < `count` → `409 QUOTA_EXCEEDED`，**整筆拒絕、不扣任何東西**。
4. 每一抽獨立判定：依機率落點決定 Prize；該 Prize 的 Stock 為 0 時改判銘謝惠顧，否則 Stock 減 1。
5. 已用次數加 `count`，回傳結果。

| 位置 | 名稱 | 型別 | 必填 | 說明 |
|---|---|---|---|---|
| path | `id` | long | 是 | Campaign id |
| header | `Idempotency-Key` | string | 是 | 這次抽獎請求的唯一識別，最多 128 字元；建議每次按下抽獎產生新的 UUID，網路重試沿用同一個值 |
| body | `count` | integer | 是 | 1 = 單抽，2～10 = 連抽；不得超過剩餘次數 |

```bash
curl -s -X POST http://localhost:8000/api/v1/campaigns/1/draws \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: 3f0c2a8e-6d1b-4a8e-9a57-0d6f1c2b9e41" \
  -H "Content-Type: application/json" \
  -d '{"count":3}'
```

`200 OK`

```json
{
  "requestId": "f5a8c065-6a51-470f-9556-6706e1e8bd09",
  "drawnAt": "2026-11-21T03:23:33.803Z",
  "remainingDraws": 3,
  "results": [
    { "drawId": "f5a8c065-6a51-470f-9556-6706e1e8bd09-1", "prizeId": null, "prizeName": null, "won": false },
    { "drawId": "f5a8c065-6a51-470f-9556-6706e1e8bd09-2", "prizeId": 3, "prizeName": "免運券", "won": true },
    { "drawId": "f5a8c065-6a51-470f-9556-6706e1e8bd09-3", "prizeId": null, "prizeName": null, "won": false }
  ]
}
```

| 欄位 | 說明 |
|---|---|
| `requestId` | 這次抽獎請求的 id（伺服器產生） |
| `drawnAt` | Redis 判定的時間 |
| `remainingDraws` | 扣除這次之後的剩餘次數 |
| `results[]` | 依抽獎順序排列，長度 = `count` |
| `results[].drawId` | 單次 Draw 的 id：`{requestId}-{從 1 起算的序號}`，與「我的紀錄」相同 |

錯誤：

| Status | code | 情況 | 範例 `detail` |
|---|---|---|---|
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | 沒帶或空白的 `Idempotency-Key` | `The Idempotency-Key header is required.` |
| 400 | `VALIDATION_FAILED` | `count` 缺少或不在 1～10；`Idempotency-Key` 超過 128 字元（`errors[].field` = `Idempotency-Key`） | `Request has invalid fields.` |
| 409 | `QUOTA_EXCEEDED` | 剩餘次數不足 `count` | `Requested 5 draws but only 3 remain.` |
| 409 | `CAMPAIGN_NOT_ACTIVE` | 活動不存在、不是 ACTIVE、尚未開始或已結束（`detail` 說明是哪一種） | `Campaign 2 is not active.` |
| 429 | `RATE_LIMITED` | 同一使用者抽獎太快（見[限流](#限流)） | `Too many draw requests. Please slow down and try again shortly.` |

```json
{
  "title": "Conflict",
  "status": 409,
  "detail": "Requested 5 draws but only 3 remain.",
  "instance": "/api/v1/campaigns/1/draws",
  "code": "QUOTA_EXCEEDED"
}
```

> 抽獎結果經 Redis Stream outbox 與 Kafka 非同步寫入 MySQL：回應一定代表已經判定並扣減完成，「我的紀錄」與後台統計通常在 1 秒內反映。

---

### GET /campaigns/{id}/draws/me

目前使用者在此活動的抽獎紀錄，每一抽一筆。排序由新到舊：`drawnAt` 遞減，同一次連抽內最後一抽在前。

| 位置 | 名稱 | 型別 | 預設 | 說明 |
|---|---|---|---|---|
| path | `id` | long | | Campaign id |
| query | `page` | integer | 0 | 頁碼，從 0 開始，≥ 0 |
| query | `size` | integer | 20 | 每頁筆數，1～100 |

```bash
curl -s "http://localhost:8000/api/v1/campaigns/1/draws/me?page=0&size=5" -H "Authorization: Bearer $TOKEN"
```

`200 OK`

```json
{
  "content": [
    {
      "drawId": "f5a8c065-6a51-470f-9556-6706e1e8bd09-3",
      "requestId": "f5a8c065-6a51-470f-9556-6706e1e8bd09",
      "prizeId": null,
      "prizeName": null,
      "won": false,
      "drawnAt": "2026-11-21T03:23:33.803Z"
    },
    {
      "drawId": "f5a8c065-6a51-470f-9556-6706e1e8bd09-2",
      "requestId": "f5a8c065-6a51-470f-9556-6706e1e8bd09",
      "prizeId": 3,
      "prizeName": "免運券",
      "won": true,
      "drawnAt": "2026-11-21T03:23:33.803Z"
    },
    {
      "drawId": "0e88e9f6-f53a-4b11-adf2-7a49477ea6a8-1",
      "requestId": "0e88e9f6-f53a-4b11-adf2-7a49477ea6a8",
      "prizeId": 2,
      "prizeName": "100 元購物金",
      "won": true,
      "drawnAt": "2026-11-21T03:22:58.229Z"
    }
  ],
  "page": 0,
  "size": 5,
  "totalElements": 7,
  "totalPages": 2
}
```

- `prizeName` 是抽中當下的名稱（之後改名不影響歷史紀錄）。
- 沒有紀錄時：`{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0}`。

錯誤：`400 VALIDATION_FAILED`（`page` < 0、`size` 不在 1～100）：

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "Request has invalid fields.",
  "instance": "/api/v1/campaigns/1/draws/me",
  "code": "VALIDATION_FAILED",
  "errors": [{ "field": "size", "message": "must be greater than or equal to 1" }]
}
```

---

## ADMIN API：活動

### Campaign Status 規則

```text
DRAFT ──activate──▶ ACTIVE ──end──▶ ENDED
  │                                    ▲
  └────────────────end─────────────────┘
```

DRAFT 可以直接結束（放棄尚未上線的活動）；ENDED 不能再轉換。

| 操作 | DRAFT | ACTIVE | ENDED |
|---|---|---|---|
| 修改活動（名稱、時間窗、Draw Quota） | ✓ | — | — |
| 新增、刪除獎品 | ✓ | — | — |
| 修改獎品名稱、機率；增減庫存 | ✓ | ✓（立即對之後的抽獎生效） | — |
| 抽獎 | — | ✓（時間窗內） | — |

不允許的操作回 `409 CAMPAIGN_NOT_EDITABLE`；不允許的狀態轉換回 `409 INVALID_STATUS_TRANSITION`。

### 活動回應（AdminCampaignResponse）

所有活動 API 都回傳這個格式（`GET /admin/campaigns` 回傳陣列）：

```json
{
  "id": 1,
  "name": "週年慶轉盤抽獎",
  "startAt": "2026-01-01T00:00:00Z",
  "endAt": "2030-12-31T23:59:59Z",
  "drawQuota": 10,
  "status": "ACTIVE",
  "prizes": [
    { "id": 1, "name": "藍牙耳機", "winProbability": 1.0000, "totalStock": 10, "remainingStock": 10 },
    { "id": 2, "name": "100 元購物金", "winProbability": 9.5000, "totalStock": 200, "remainingStock": 199 },
    { "id": 3, "name": "免運券", "winProbability": 30.0000, "totalStock": 1000, "remainingStock": 995 }
  ],
  "noWinProbability": 59.5000
}
```

| 欄位 | 說明 |
|---|---|
| `prizes` | 依 id 排序（這也是機率區間的順序） |
| `prizes[].totalStock` | 設定的總庫存（初始值加上所有增減量） |
| `prizes[].remainingStock` | 剩餘庫存。ACTIVE 的活動回傳 Redis 的即時值（抽獎當下即扣減）；活動不在 Redis 中（例如 Redis 資料遺失、尚未重建）或非 ACTIVE 時，回傳 MySQL 的值 |

### 活動請求（CampaignRequest）

`POST /admin/campaigns` 與 `PUT /admin/campaigns/{id}` 共用：

| 欄位 | 型別 | 規則 |
|---|---|---|
| `name` | string | 必填、不可空白、最多 100 字 |
| `startAt` | string (ISO-8601 UTC) | 必填 |
| `endAt` | string (ISO-8601 UTC) | 必填，須晚於 `startAt`（錯誤放在 `endAt` 欄位） |
| `drawQuota` | integer | 必填，> 0 |

### POST /admin/campaigns

建立活動，狀態為 DRAFT，尚無獎品。

```bash
curl -s -X POST http://localhost:8000/api/v1/admin/campaigns \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"聖誕節轉盤","startAt":"2026-11-01T00:00:00Z","endAt":"2026-12-31T23:59:59Z","drawQuota":5}'
```

`201 Created`，`Location: http://localhost:8000/api/v1/admin/campaigns/2`

```json
{
  "id": 2,
  "name": "聖誕節轉盤",
  "startAt": "2026-11-01T00:00:00Z",
  "endAt": "2026-12-31T23:59:59Z",
  "drawQuota": 5,
  "status": "DRAFT",
  "prizes": [],
  "noWinProbability": 100.0000
}
```

驗證失敗 `400 VALIDATION_FAILED`：

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "Request has invalid fields.",
  "instance": "/api/v1/admin/campaigns",
  "code": "VALIDATION_FAILED",
  "errors": [
    { "field": "name", "message": "must not be blank" },
    { "field": "endAt", "message": "must be after startAt" },
    { "field": "drawQuota", "message": "must be greater than 0" }
  ]
}
```

### GET /admin/campaigns

所有活動（依 id 排序，不分頁），每筆為[活動回應](#活動回應admincampaignresponse)。`200 OK`。

### GET /admin/campaigns/{id}

單一活動。`200 OK`；錯誤 `404 CAMPAIGN_NOT_FOUND`。

### PUT /admin/campaigns/{id}

整筆覆寫名稱、時間窗與 Draw Quota，只有 DRAFT 可以修改。body 同[活動請求](#活動請求campaignrequest)。

`200 OK`（活動回應）。錯誤：`400 VALIDATION_FAILED`、`404 CAMPAIGN_NOT_FOUND`、`409 CAMPAIGN_NOT_EDITABLE`：

```json
{
  "title": "Conflict",
  "status": 409,
  "detail": "Campaign 2 is ACTIVE; only DRAFT campaigns can be edited.",
  "instance": "/api/v1/admin/campaigns/2",
  "code": "CAMPAIGN_NOT_EDITABLE"
}
```

### POST /admin/campaigns/{id}/activate

DRAFT → ACTIVE。上線條件：至少一個 Prize、機率總和 ≤ 100%。上線時把活動設定、機率區間與 Stock 載入 Redis，之後即可在時間窗內抽獎。無 body。

`200 OK`（活動回應，`status` 為 `ACTIVE`）。錯誤：

| Status | code | 範例 `detail` |
|---|---|---|
| 404 | `CAMPAIGN_NOT_FOUND` | `Campaign 42 not found.` |
| 409 | `INVALID_STATUS_TRANSITION` | `Campaign 2 cannot transition from ACTIVE to ACTIVE.` |
| 409 | `CAMPAIGN_HAS_NO_PRIZE` | `Campaign 2 has no Prize; add at least one before activating.` |
| 409 | `PROBABILITY_SUM_EXCEEDED` | 機率總和超過 100% |

### POST /admin/campaigns/{id}/end

DRAFT / ACTIVE → ENDED，之後不能再抽獎（紀錄、統計、剩餘次數仍可查詢）。DRAFT 直接結束即放棄尚未上線的活動。已經 ENDED 的活動回 `409 INVALID_STATUS_TRANSITION`。無 body。

`200 OK`（活動回應，`status` 為 `ENDED`）。錯誤：`404 CAMPAIGN_NOT_FOUND`、`409 INVALID_STATUS_TRANSITION`。

### POST /admin/campaigns/{id}/redis/rebuild

Redis 資料遺失後使用：以 MySQL 的設定與 `remaining_stock` 覆寫該活動在 Redis 中的設定、機率區間與 Stock（不動使用者的已用次數）。只適用於 ACTIVE 活動。

> 若在 Redis 正常、抽獎事件尚未消費完時執行，那些抽獎扣掉的 Stock 會被加回，因此只應在 Redis 資料遺失後使用。

`200 OK`（活動回應）。錯誤：`404 CAMPAIGN_NOT_FOUND`、`409 CAMPAIGN_NOT_ACTIVE`（`Campaign 2 is ENDED; only ACTIVE campaigns have live state.`）。

---

## ADMIN API：獎品

### 獎品回應（AdminPrizeResponse）

```json
{ "id": 4, "name": "聖誕禮盒", "winProbability": 5.2500, "totalStock": 50, "remainingStock": 50 }
```

### POST /admin/campaigns/{id}/prizes

新增獎品，只有 DRAFT 可以新增。新增後所有獎品的機率總和不得超過 100%。

| 欄位 | 型別 | 規則 |
|---|---|---|
| `name` | string | 必填、不可空白、最多 100 字 |
| `stock` | integer | 必填，≥ 0 |
| `winProbability` | number | 必填，0～100，最多 4 位小數 |

```bash
curl -s -X POST http://localhost:8000/api/v1/admin/campaigns/2/prizes \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"聖誕禮盒","stock":50,"winProbability":5.25}'
```

`201 Created`，`Location: http://localhost:8000/api/v1/admin/campaigns/2/prizes/4`

```json
{ "id": 4, "name": "聖誕禮盒", "winProbability": 5.2500, "totalStock": 50, "remainingStock": 50 }
```

錯誤：

| Status | code | 範例 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | `errors: [{"field":"stock","message":"must be greater than or equal to 0"}, {"field":"winProbability","message":"numeric value out of bounds (<3 digits>.<4 digits> expected)"}]` |
| 404 | `CAMPAIGN_NOT_FOUND` | |
| 409 | `CAMPAIGN_NOT_EDITABLE` | `Campaign 2 is ACTIVE; Prizes can only be added or deleted while DRAFT.` |
| 409 | `PROBABILITY_SUM_EXCEEDED` | `Total Win Probability would be 105.2500%, which exceeds 100%.` |

### PUT /admin/campaigns/{id}/prizes/{prizeId}

修改名稱與機率。DRAFT 與 ACTIVE 皆可；ACTIVE 時立即同步 Redis，對之後的抽獎生效。**庫存不在此覆寫**（用 `PATCH …/stock`），避免蓋掉同時被抽走的數量。

| 欄位 | 型別 | 規則 |
|---|---|---|
| `name` | string | 必填、不可空白、最多 100 字 |
| `winProbability` | number | 必填，0～100，最多 4 位小數 |

```bash
curl -s -X PUT http://localhost:8000/api/v1/admin/campaigns/2/prizes/4 \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"聖誕禮盒（大）","winProbability":6}'
```

`200 OK`

```json
{ "id": 4, "name": "聖誕禮盒（大）", "winProbability": 6.0000, "totalStock": 50, "remainingStock": 50 }
```

錯誤：`400 VALIDATION_FAILED`、`404 CAMPAIGN_NOT_FOUND`、`404 PRIZE_NOT_FOUND`（`Prize 1 not found in Campaign 2.`，獎品不屬於此活動時也是）、`409 CAMPAIGN_NOT_EDITABLE`（ENDED）、`409 PROBABILITY_SUM_EXCEEDED`。

### DELETE /admin/campaigns/{id}/prizes/{prizeId}

刪除獎品，只有 DRAFT 可以刪除。`204 No Content`。

錯誤：`404 CAMPAIGN_NOT_FOUND`、`404 PRIZE_NOT_FOUND`、`409 CAMPAIGN_NOT_EDITABLE`。

### PATCH /admin/campaigns/{id}/prizes/{prizeId}/stock

以**增減量**調整庫存：`totalStock` 與 `remainingStock` 同時加上 `delta`，剩餘庫存不得變負數。DRAFT 與 ACTIVE 皆可；ACTIVE 時以 Redis 的即時剩餘量原子判定與套用，不會和同時進行的抽獎互相覆蓋。回應的 `remainingStock` 在 ACTIVE 時是套用後的 Redis 即時值。

| 欄位 | 型別 | 規則 |
|---|---|---|
| `delta` | integer | 必填；正數增加、負數減少 |

```bash
curl -s -X PATCH http://localhost:8000/api/v1/admin/campaigns/2/prizes/4/stock \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"delta":20}'
```

`200 OK`

```json
{ "id": 4, "name": "聖誕禮盒（大）", "winProbability": 6.0000, "totalStock": 70, "remainingStock": 70 }
```

錯誤：

| Status | code | 範例 `detail` |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 缺少 `delta` |
| 404 | `CAMPAIGN_NOT_FOUND` / `PRIZE_NOT_FOUND` | |
| 409 | `CAMPAIGN_NOT_EDITABLE` | `Campaign 2 is ENDED; its Prizes can no longer be changed.` |
| 409 | `STOCK_NEGATIVE` | `Prize 4 has 70 remaining; a delta of -100 would make Stock negative.` |

---

## ADMIN API：統計

### GET /admin/campaigns/{id}/stats

活動的總抽獎次數、銘謝惠顧次數與各 Prize 抽出數。`prizes` 只列出至少被抽中一次的 Prize（依 `prizeId` 排序）；不認得的活動回傳全為 0 的統計。資料經 Kafka 非同步寫入，與抽獎有些微延遲。

```bash
curl -s http://localhost:8000/api/v1/admin/campaigns/1/stats -H "Authorization: Bearer $ADMIN_TOKEN"
```

`200 OK`

```json
{
  "campaignId": 1,
  "totalDraws": 15,
  "noWinCount": 9,
  "prizes": [
    { "prizeId": 2, "prizeName": "100 元購物金", "drawnCount": 1 },
    { "prizeId": 3, "prizeName": "免運券", "drawnCount": 5 }
  ]
}
```

USER 呼叫回 `403 FORBIDDEN`。

---

## 冪等（Idempotency-Key）

抽獎 API 必須帶 `Idempotency-Key` header，用來防止連點或網路重送造成重複扣次數：

- 範圍是 **(Campaign, 使用者, key)**：不同使用者用相同的 key 互不影響。
- 第一次成功判定後，結果保存 **24 小時**；期間以相同 key 重送，回傳與第一次**完全相同**的回應（同一個 `requestId`、`results`、`remainingDraws`），不會再扣次數或庫存，也不會產生新的抽獎事件。即使活動已經結束，重送仍回傳原本的結果。
- 被拒絕的請求（`QUOTA_EXCEEDED`、`CAMPAIGN_NOT_ACTIVE`、驗證錯誤、限流）**不會**記住這個 key，之後可用同一個 key 再試。
- 重送時 body 的 `count` 不會被比對，結果以第一次為準。
- 前端做法：每次按下抽獎產生新的 UUID；同一次操作的網路重試沿用同一個 UUID。

---

## 限流

gateway 對**抽獎 API（`POST /campaigns/{id}/draws`）**依使用者（token 的 `sub`）做 token bucket 限流，計數存在 Redis，所以多個 gateway 實例共用同一個額度：

- 預設每位使用者每秒補充 5 個 token、最多累積 5 個（`DRAW_RATE_LIMIT_REPLENISH_RATE`、`DRAW_RATE_LIMIT_BURST_CAPACITY`）。
- 每個請求不論 `count` 多少都只花 1 個 token。
- 其他 API 不限流。

超過時回 `429`：

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json
Retry-After: 1
X-RateLimit-Remaining: 0
X-RateLimit-Burst-Capacity: 5
X-RateLimit-Replenish-Rate: 5
X-RateLimit-Requested-Tokens: 1
```

```json
{
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Too many draw requests. Please slow down and try again shortly.",
  "instance": "/api/v1/campaigns/1/draws",
  "code": "RATE_LIMITED"
}
```

被限流的請求不會到達 draw-service，不扣任何東西；稍後以同一個 `Idempotency-Key` 重試即可。

---

## 錯誤格式

所有錯誤都是 [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457)，`Content-Type: application/problem+json`：

| 欄位 | 說明 |
|---|---|
| `type` | 省略（依 RFC 9457 即 `about:blank`：以 `code` 區分錯誤種類） |
| `title` | HTTP status 的標準說明，例如 `Conflict` |
| `status` | HTTP status code |
| `detail` | 這次錯誤的具體說明（英文，給開發者看） |
| `instance` | 發生錯誤的請求路徑（含 `/api/v1`） |
| `code` | 機器可讀的錯誤碼，前端以它判斷錯誤種類 |
| `errors` | 只在欄位驗證錯誤時出現：`[{ "field": "...", "message": "..." }]` |

```json
{
  "title": "Not Found",
  "status": 404,
  "detail": "Campaign 42 not found.",
  "instance": "/api/v1/campaigns/42",
  "code": "CAMPAIGN_NOT_FOUND"
}
```

## 錯誤碼

| code | Status | 說明 | 可能出現的 API |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | 請求的欄位或參數不合法，`errors[]` 列出每個欄位。JSON 格式錯誤（無法解析）也是這個碼，但沒有 `errors[]` | 有 body 或 query 參數的 API、非數字的路徑參數 |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | 抽獎請求缺少 `Idempotency-Key` header | 抽獎 |
| `UNAUTHORIZED` | 401 | 未帶 access token，或 token 無效、過期 | 全部 |
| `FORBIDDEN` | 403 | token 有效，但沒有需要的角色 | 全部 |
| `CAMPAIGN_NOT_FOUND` | 404 | Campaign 不存在 | 活動資訊、所有 `/admin/campaigns/{id}/…`（統計除外） |
| `PRIZE_NOT_FOUND` | 404 | Prize 不存在，或不屬於路徑上的 Campaign | 修改、刪除獎品、增減庫存 |
| `INVALID_STATUS_TRANSITION` | 409 | 不允許的 Campaign Status 轉換（只能 DRAFT → ACTIVE、DRAFT / ACTIVE → ENDED） | 上線、結束 |
| `CAMPAIGN_NOT_EDITABLE` | 409 | Campaign 目前的 Status 不允許這項修改 | 修改活動、新增／修改／刪除獎品、增減庫存 |
| `CAMPAIGN_HAS_NO_PRIZE` | 409 | Campaign 沒有任何 Prize，不能上線 | 上線 |
| `PROBABILITY_SUM_EXCEEDED` | 409 | 修改後所有 Prize 的機率總和會超過 100% | 新增、修改獎品、上線 |
| `STOCK_NEGATIVE` | 409 | 增減量會讓剩餘庫存變成負數 | 增減庫存 |
| `CAMPAIGN_NOT_ACTIVE` | 409 | Campaign 不是 ACTIVE、尚未開始、已結束，或從未上線 | 抽獎、剩餘次數、重建 Redis |
| `QUOTA_EXCEEDED` | 409 | 剩餘次數不足這次的 `count`，整筆拒絕且不扣任何東西 | 抽獎 |
| `RATE_LIMITED` | 429 | 同一使用者呼叫抽獎 API 太快（每秒 5 次） | 抽獎 |
| `INTERNAL_ERROR` | 500 | 非預期的伺服器錯誤（`detail` 不含內部細節，詳細內容記在服務日誌） | 全部 |

服務內的其他框架層錯誤（例如不支援的 HTTP method）以 HTTP status 名稱作為 `code`，例如 `METHOD_NOT_ALLOWED`。

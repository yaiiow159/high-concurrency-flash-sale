#!/usr/bin/env python3
"""熱路徑守門 —— PostToolUse hook。

SeckillApplicationService.attempt 是每秒要跑數萬次的路徑。
這條路徑上多一次網路往返、多一個交易、多一個鎖，
在尖峰時就是延遲翻倍與連線池耗盡。

這些違規的共同特徵是「加上去的當下毫無感覺，壓測時才會炸」——
review 時很容易放過，因為每一行單獨看都很合理。
所以在存檔當下就標記出來。

不阻擋（有些情況確實需要例外），但會明確說明代價。
"""

import json
import re
import sys
from pathlib import Path

HOTPATH_FILES = {"SeckillApplicationService.java"}

# (正則, 標題, 為什麼這在熱路徑上是問題)
RULES = [
    (
        # 同時涵蓋 @Transactional 與全限定寫法 @org.springframework...Transactional
        re.compile(r"@(?:[\w.]+\.)?Transactional\b"),
        "熱路徑出現 @Transactional",
        "即使方法內沒有 DB 操作，這個註解仍會從連線池取得連線並開啟交易。"
        "秒殺尖峰下連線池會在幾秒內耗盡，連查詢也會一起卡死。"
        "削峰的前提就是熱路徑上沒有資料庫。",
    ),
    (
        re.compile(r"\b(executeWithLock|tryExecuteWithLock|synchronized|ReentrantLock)\b"),
        "熱路徑出現鎖",
        "Lua 腳本已在 Redis 單執行緒模型下保證原子性，再加鎖只會把並行度壓成 1，"
        "換來零額外安全性。詳見 ADR-0003。",
    ),
    (
        re.compile(r"\.(get|join)\(\)|Thread\.sleep|CompletableFuture\.allOf"),
        "熱路徑出現阻塞等待",
        "阻塞會佔住 Tomcat 執行緒。秒殺場景下寧可快速失敗讓使用者重試，"
        "也不要讓執行緒排隊等待——後者會把區域性的慢，放大成全域的不可用。",
    ),
    (
        re.compile(r"for\s*\(.*\)\s*\{[^}]*\b(redisTemplate|stockRepository|kafkaTemplate)\b", re.DOTALL),
        "疑似在迴圈中呼叫遠端服務",
        "N 次迴圈就是 N 次網路往返。需要批次操作就寫進 Lua 腳本，"
        "或改用 pipeline（不需要原子性時）。",
    ),
]


def main() -> int:
    try:
        event = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return 0

    file_path = (event.get("tool_input") or {}).get("file_path")
    if not file_path or Path(file_path).name not in HOTPATH_FILES:
        return 0

    try:
        source = Path(file_path).read_text(encoding="utf-8")
    except OSError:
        return 0

    # 只檢查程式碼，避免被 Javadoc 中提及這些關鍵字的說明文字誤觸。
    code = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.DOTALL)

    findings = [(title, why) for pattern, title, why in RULES if pattern.search(code)]
    if not findings:
        return 0

    message = ["[熱路徑警示] SeckillApplicationService 是每秒數萬次的路徑，發現："]
    for title, why in findings:
        message.append(f"\n  ● {title}\n    {why}")
    message.append(
        "\n\n若確實需要這個操作，先問：能不能移到 MQ 消費端的慢車道？"
        "\n那條路徑的並行度由分區數控制，壓力可預測，不會被前端流量直接沖垮。"
    )

    print("".join(message), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())

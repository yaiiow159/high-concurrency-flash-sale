#!/usr/bin/env python3
"""分層依賴守門 —— PostToolUse hook。

在檔案存檔的當下就擋下跨層違規，而不是等到 CI 跑 ArchUnit 才發現。
兩者是互補的：ArchUnit 完整但慢（要編譯全專案），這支快但只看 import 字串。

設計取捨：
  * 只做純文字檢查，不編譯、不啟動 JVM —— 必須在 200ms 內結束，
    否則每次存檔都要等，人會想辦法把它關掉。
  * 寧可漏報也不要誤報 —— 被誤擋幾次之後，這個 hook 就等於不存在了。
  * 阻擋時要說明「為什麼」與「怎麼改」，不能只說「不允許」。

退出碼語意（Claude Code hook 協定）：
  0 = 通過
  2 = 阻擋，stderr 的內容會回饋給 Claude
"""

import json
import re
import sys
from pathlib import Path

# 各模組的禁用 import 前綴。與 CLAUDE.md 及 ArchitectureTest 保持同步。
FORBIDDEN = {
    "flash-sale-domain": {
        "prefixes": (
            "org.springframework",
            "jakarta.persistence",
            "com.fasterxml.jackson",
            "org.apache.kafka",
            "org.redisson",
            "io.micrometer",
            "com.flashsale.application",
            "com.flashsale.infrastructure",
            "com.flashsale.api",
        ),
        "reason": (
            "領域層必須維持零框架依賴。一旦沾上框架，業務規則就再也無法脫離"
            "基礎設施被測試——現在的領域測試是毫秒級且不需要 Docker，這是它的代價換來的。"
        ),
        "fix": (
            "把技術細節抽成 application/port/out 的介面，由 infrastructure 實作；"
            "領域層只認得自己定義的型別。"
        ),
    },
    "flash-sale-application": {
        "prefixes": (
            "com.flashsale.infrastructure",
            "com.flashsale.api",
            "org.springframework.data",
            "org.springframework.web",
            "org.springframework.boot",
            "org.apache.kafka",
            "org.redisson",
            "jakarta.persistence",
        ),
        "reason": (
            "應用層只能認得 Port 介面。直接依賴具體技術後，換掉 Redis 或 Kafka "
            "就會變成需要改動 Use Case 的重構，而 Use Case 是業務邏輯所在。"
        ),
        "fix": (
            "在 application/port/out 定義介面（用業務語彙命名，例如 StockRepository "
            "而非 RedisLuaExecutor），在 infrastructure 實作它。"
        ),
    },
}

IMPORT_PATTERN = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)", re.MULTILINE)

# spring-context 與 spring-tx 是應用層明確允許的例外（僅用於註解）。
APPLICATION_ALLOWED = (
    "org.springframework.stereotype",
    "org.springframework.transaction",
    "org.springframework.context",
)


def module_of(path: Path) -> str | None:
    for part in path.parts:
        if part in FORBIDDEN:
            return part
    return None


def is_allowed_exception(module: str, imported: str) -> bool:
    if module != "flash-sale-application":
        return False
    return imported.startswith(APPLICATION_ALLOWED)


def main() -> int:
    try:
        event = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return 0  # 讀不到事件就安靜放行，hook 不該成為新的故障點

    file_path = (event.get("tool_input") or {}).get("file_path")
    if not file_path or not file_path.endswith(".java"):
        return 0

    path = Path(file_path)
    module = module_of(path)
    if module is None:
        return 0

    try:
        source = path.read_text(encoding="utf-8")
    except OSError:
        return 0

    rule = FORBIDDEN[module]
    violations = [
        imported
        for imported in IMPORT_PATTERN.findall(source)
        if imported.startswith(rule["prefixes"]) and not is_allowed_exception(module, imported)
    ]

    if not violations:
        return 0

    print(
        f"[分層違規] {path.name} 位於 {module}，出現了不該有的依賴：\n"
        + "\n".join(f"  - {v}" for v in sorted(set(violations)))
        + f"\n\n為什麼不行：{rule['reason']}"
        f"\n怎麼改：{rule['fix']}"
        f"\n\n（同樣的規則也由 ArchitectureTest 在 CI 驗證。"
        f"請修正依賴方向，不要放寬 ArchUnit 規則。）",
        file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
    sys.exit(main())

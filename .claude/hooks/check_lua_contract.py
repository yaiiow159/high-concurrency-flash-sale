#!/usr/bin/env python3
"""Lua 契約同步提醒 —— PostToolUse hook。

Lua 腳本的回傳碼、StockDeductionOutcome 列舉、以及併發測試是一份三方契約。
改了其中一處而忘記另外兩處，後果是超賣——而且不會有任何錯誤訊息：
測試全綠、日誌乾淨，只有事後對帳才會發現庫存對不上。

這支 hook 不阻擋（改到一半本來就會暫時不同步），只在存檔當下提醒，
並直接把要跑的指令列出來，省下「要跑哪個測試來著」的往返。

退出碼 0 搭配 stderr 輸出 = 通過但顯示訊息。
"""

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

LUA_DEDUCT = REPO_ROOT / "flash-sale-infrastructure/src/main/resources/lua/seckill_deduct.lua"
OUTCOME_ENUM = REPO_ROOT / "flash-sale-domain/src/main/java/com/flashsale/domain/stock/StockDeductionOutcome.java"

WATCHED_NAMES = {
    "seckill_deduct.lua",
    "seckill_restore.lua",
    "StockDeductionOutcome.java",
    "StockDeductionResult.java",
    "RedisStockRepository.java",
}

TEST_COMMAND = (
    "mvn test -pl flash-sale-infrastructure "
    "-Dtest=RedisStockRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false"
)


def lua_return_codes() -> set[int]:
    """抓出 Lua 中所有 `return { <code>, ... }` 的第一個元素。"""
    if not LUA_DEDUCT.exists():
        return set()
    source = LUA_DEDUCT.read_text(encoding="utf-8")
    return {int(m) for m in re.findall(r"return\s*\{\s*(-?\d+)\s*,", source)}


def enum_codes() -> set[int]:
    """抓出列舉中所有 `NAME(<code>)` 的碼。"""
    if not OUTCOME_ENUM.exists():
        return set()
    source = OUTCOME_ENUM.read_text(encoding="utf-8")
    return {int(m) for m in re.findall(r"^\s*[A-Z_]+\((-?\d+)\)", source, re.MULTILINE)}


def main() -> int:
    try:
        event = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return 0

    file_path = (event.get("tool_input") or {}).get("file_path")
    if not file_path or Path(file_path).name not in WATCHED_NAMES:
        return 0

    lines = [f"[庫存契約] 你改動了 {Path(file_path).name}，這屬於三方契約的一部分。"]

    lua_codes, java_codes = lua_return_codes(), enum_codes()
    if lua_codes and java_codes and lua_codes != java_codes:
        only_lua = sorted(lua_codes - java_codes)
        only_java = sorted(java_codes - lua_codes)
        lines.append("\n⚠ 回傳碼目前不同步：")
        if only_lua:
            lines.append(f"  - 只在 Lua 出現：{only_lua}（StockDeductionOutcome.fromCode 會拋例外）")
        if only_java:
            lines.append(f"  - 只在列舉出現：{only_java}（腳本從不回傳，可能是殘留的死碼）")

    lines.append(
        "\n請確認三處都已同步：\n"
        "  1. lua/seckill_deduct.lua 的回傳碼\n"
        "  2. StockDeductionOutcome 的列舉與 toException() 分支\n"
        "  3. RedisStockRepositoryTest 的對應案例\n"
        f"\n改完務必執行防超賣驗證：\n  {TEST_COMMAND}"
    )

    print("\n".join(lines), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())

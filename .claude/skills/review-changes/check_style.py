#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
程式碼格式與註解的機械檢查。

這支腳本只檢查**能被機器明確判定**的規則。判斷不了的（註解寫得對不對、
命名貼不貼切、快照該不該用引用）留給 SKILL.md 的人工審查清單——
把主觀判斷塞進正則表達式，得到的只會是一堆誤報，
而誤報多的檢查最後一定會被 `# noqa` 掉。

與 .claude/hooks/ 的分工：
  hooks       存檔當下擋下「會出事」的違規（分層、熱路徑、Lua 契約）
  這支腳本    review 時檢查「會慢慢腐蝕」的問題（註解、格式、慣例）

用法：
    python .claude/skills/review-changes/check_style.py            # 檢查未提交的改動
    python .claude/skills/review-changes/check_style.py --all      # 檢查整個專案
    python .claude/skills/review-changes/check_style.py a.java b.java
"""
from __future__ import annotations

import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

MAX_LINE_LENGTH = 120

# 簡體字偵測（專案要求註解使用繁體中文）。
#
# 只收錄「簡體專用形」——也就是這個字在繁體中根本不會出現。
# 像「作」「只」「哪」這種兩邊同形的字絕不能放進來，
# 放進去會讓整個檢查變成一台誤報機器，而誤報多的檢查最後一定會被關掉。
#
# 這份字表**不是手打的**——手打過兩次，兩次都混進了「作」「只」「量」「向」
# 這種兩邊同形的字，讓檢查變成一台誤報機器。
#
# 正確作法是拿現有程式碼當驗收集：這個專案的註解全是繁體，
# 因此任何在現有程式碼中出現過的字，都不可能是「簡體專用形」。
# 字表 = 候選集 − 現有程式碼用過的字 − 手動剔除的歧義字（里、后、術）。
#
# 驗收標準：對現有程式碼跑 --all，simplified-chinese 必須是 0 筆。
# 之後若要擴充字表，用同一個方法驗證，不要憑印象加字。
SIMPLIFIED_CHARS = (
    "业东个为举买乱争亏产们会关务华单卖卫厂厅历压县发变叠叶号叹员团国图圆"
    "场坏块坚宁宝实审宪宫对寻导尔尘尝尽层届属岁岗峡帅师带帮库应开张弹归当"
    "录彻径怀态怜总恋恳恼惊惧惯愿战户执扩扫扬扰护报担拟择挂挥损换据数无时"
    "显机杀杂权条来杨极构枪标栋树样档桥检楼欢欧残毁毕汇汉汤沟没沪泪泽浅浆"
    "测济浏渐渔温灭点烟热爱爷犹狱环现电畅疗监盘础确种积称竞笔简类粉粮紧纤"
    "约级纪纯纲纳纵纷纸纹线练组细织终绍经结绕绘给络绝统继绩绪续维绿缓编缩"
    "网罗职联见计订认让议记讲论设识试话询该详语误说请谢贝责货质购贵贷费资"
    "车运还这进远违连迟适选逊递遗释钟钢钱铁铜银锁锐错长门闭问闲间闻阅队阳"
    "阴阶际陆陈险随隐难雾静韩页顶项顺须顾预领题风飞饭饰饱馆马驱驶驾验鸟"
)
SIMPLIFIED_PATTERN = re.compile("[" + "".join(sorted(set(SIMPLIFIED_CHARS))) + "]")

# 領域層與應用層禁止直接取得系統時間——時間一律注入 Clock（CLAUDE.md 規則 9）。
CLOCK_BYPASS = re.compile(r"\b(?:Instant\.now|System\.currentTimeMillis|LocalDate(?:Time)?\.now)\s*\(")

# 業務例外一律用 BusinessException + ErrorCode，不新增自訂例外類別。
CUSTOM_EXCEPTION = re.compile(
    r"class\s+(\w+)\s+extends\s+(?:Runtime)?Exception\b")

# 「做了什麼」型註解：只是把下一行程式碼翻成中文，沒有解釋為什麼。
NARRATING_COMMENT = re.compile(
    r"//\s*(?:"
    r"(?:迴圈|循環)(?:遍歷|處理)"
    r"|(?:呼叫|調用)\w+"
    r"|(?:設定|設置|賦值)\w*(?:為|成)"
    r"|(?:回傳|返回)\w+"
    r"|(?:建立|創建|新增)一個"
    r"|取得\w+的值"
    r"|判斷是否為?\s*(?:null|空)"
    r")")

TODO_PATTERN = re.compile(r"//\s*(TODO|FIXME|XXX|HACK)\b", re.IGNORECASE)

DOMAIN_OR_APPLICATION = ("flash-sale-domain/src/main", "flash-sale-application/src/main")


@dataclass
class Finding:
    path: str
    line: int
    rule: str
    message: str

    def render(self) -> str:
        return f"  {self.path}:{self.line}  [{self.rule}] {self.message}"


def strip_strings_and_comments(source: str) -> str:
    """把字串字面值換成空白，避免字串內容被當成程式碼誤判。"""
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', source)


def is_comment_line(line: str) -> bool:
    stripped = line.strip()
    return stripped.startswith(("//", "*", "/*", "/**"))


def check_java(path: Path, rel: str) -> list[Finding]:
    findings: list[Finding] = []
    source = path.read_text(encoding="utf-8", errors="replace")
    lines = source.splitlines()
    code_only = strip_strings_and_comments(source)

    for number, line in enumerate(lines, start=1):
        if len(line) > MAX_LINE_LENGTH:
            findings.append(Finding(rel, number, "line-length",
                                    f"行長 {len(line)} 超過 {MAX_LINE_LENGTH}"))

        if "\t" in line:
            findings.append(Finding(rel, number, "tab",
                                    "使用了 tab，專案一律用空白縮排"))

        if line.rstrip() != line:
            findings.append(Finding(rel, number, "trailing-space", "行尾有多餘空白"))

        simplified = SIMPLIFIED_PATTERN.findall(line)
        if simplified and is_comment_line(line):
            findings.append(Finding(rel, number, "simplified-chinese",
                                    f"註解含簡體字 {''.join(sorted(set(simplified)))}，"
                                    "專案註解一律用繁體中文"))

        if NARRATING_COMMENT.search(line):
            findings.append(Finding(rel, number, "narrating-comment",
                                    "註解在複述程式碼做了什麼。改寫成「為什麼要這樣做」，"
                                    "或直接刪掉"))

        todo = TODO_PATTERN.search(line)
        if todo:
            findings.append(Finding(rel, number, "todo",
                                    f"留下了 {todo.group(1)}。要嘛現在處理，"
                                    "要嘛開成 issue——留在程式碼裡的沒有人會回來看"))

    if any(rel.replace("\\", "/").startswith(prefix) for prefix in DOMAIN_OR_APPLICATION):
        for match in CLOCK_BYPASS.finditer(code_only):
            number = code_only[:match.start()].count("\n") + 1
            findings.append(Finding(rel, number, "clock",
                                    f"直接取得系統時間（{match.group(0)}）。"
                                    "時間一律注入 Clock，否則「活動結束後不能下單」這種規則沒辦法測"))

    for match in CUSTOM_EXCEPTION.finditer(code_only):
        name = match.group(1)
        if name in {"BusinessException"}:
            continue
        number = code_only[:match.start()].count("\n") + 1
        findings.append(Finding(rel, number, "custom-exception",
                                f"新增了自訂例外 {name}。業務例外一律用 "
                                "BusinessException + ErrorCode，"
                                "否則 GlobalExceptionHandler 的映射會漏掉它"))

    findings.extend(check_public_api_docs(rel, lines))
    return findings


def check_public_api_docs(rel: str, lines: list[str]) -> list[Finding]:
    """
    領域層的 public 型別必須有 Javadoc。

    只檢查型別宣告，不檢查每個方法——強制每個 getter 都寫 Javadoc
    只會逼出一堆「取得 xxx」這種零資訊的註解，比沒有更糟。
    """
    if not rel.replace("\\", "/").startswith("flash-sale-domain/src/main"):
        return []

    findings: list[Finding] = []
    declaration = re.compile(r"^public\s+(?:final\s+|abstract\s+)?"
                             r"(class|interface|enum|record)\s+(\w+)")
    for index, line in enumerate(lines):
        match = declaration.match(line.strip())
        if not match:
            continue
        # 往回找最近的非空行，看是不是 Javadoc 收尾
        previous = next((lines[j].strip() for j in range(index - 1, -1, -1)
                         if lines[j].strip()), "")
        if not previous.endswith("*/"):
            findings.append(Finding(rel, index + 1, "missing-javadoc",
                                    f"領域型別 {match.group(2)} 缺少 Javadoc。"
                                    "領域層是規則的所在地，沒有說明的規則等於沒有規則"))
    return findings


def check_sql(path: Path, rel: str) -> list[Finding]:
    findings: list[Finding] = []
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    for number, line in enumerate(lines, start=1):
        if len(line) > MAX_LINE_LENGTH:
            findings.append(Finding(rel, number, "line-length",
                                    f"行長 {len(line)} 超過 {MAX_LINE_LENGTH}"))
        simplified = SIMPLIFIED_PATTERN.findall(line)
        if simplified and ("--" in line or "COMMENT" in line.upper()):
            findings.append(Finding(rel, number, "simplified-chinese",
                                    f"含簡體字 {''.join(sorted(set(simplified)))}"))
    return findings


def changed_files() -> list[str]:
    """未提交的改動（含已 staged 與未 staged），加上未追蹤的新檔。"""
    tracked = subprocess.run(
        ["git", "diff", "HEAD", "--name-only", "--diff-filter=ACMR"],
        capture_output=True, text=True, encoding="utf-8").stdout.split()
    untracked = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard"],
        capture_output=True, text=True, encoding="utf-8").stdout.split()
    return tracked + untracked


def all_files() -> list[str]:
    return subprocess.run(
        ["git", "ls-files", "*.java", "*.sql"],
        capture_output=True, text=True, encoding="utf-8").stdout.split()


def main() -> int:
    args = sys.argv[1:]
    if args == ["--all"]:
        targets = all_files()
    elif args:
        targets = args
    else:
        targets = changed_files()

    findings: list[Finding] = []
    checked = 0
    for rel in targets:
        path = Path(rel)
        if not path.is_file():
            continue
        if path.suffix == ".java":
            findings.extend(check_java(path, rel))
            checked += 1
        elif path.suffix == ".sql":
            findings.extend(check_sql(path, rel))
            checked += 1

    if not checked:
        print("沒有需要檢查的 .java / .sql 檔案。")
        return 0

    if not findings:
        print(f"檢查 {checked} 個檔案：格式與註解沒有問題。")
        return 0

    by_rule: dict[str, list[Finding]] = {}
    for finding in findings:
        by_rule.setdefault(finding.rule, []).append(finding)

    print(f"檢查 {checked} 個檔案，發現 {len(findings)} 個問題：\n")
    for rule, group in sorted(by_rule.items(), key=lambda item: -len(item[1])):
        print(f"[{rule}] {len(group)} 處")
        for finding in group[:10]:
            print(finding.render())
        if len(group) > 10:
            print(f"  …另外還有 {len(group) - 10} 處")
        print()

    # 回傳非零讓 CI 能擋下，但這支腳本刻意不接進 PostToolUse hook：
    # 格式問題不該在寫到一半時打斷思路，它屬於 review 階段。
    return 1


if __name__ == "__main__":
    sys.exit(main())

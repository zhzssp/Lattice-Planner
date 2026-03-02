from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parents[1]
SRC_DIR = ROOT_DIR / "src"

# 参与统计和导出的源文件后缀（可按需调整）
EXTENSIONS = [".java", ".html", ".js", ".css", ".xml", ".properties"]


def collect_source_lines() -> list[str]:
    """
    按文件路径排序，收集 src/ 下所有源文件内容，
    并在每个文件前插入一行文件头标记，形成一份“完整程序清单”。
    """
    files: list[Path] = []
    for ext in EXTENSIONS:
        files.extend(SRC_DIR.rglob(f"*{ext}"))

    # 去重并排序，确保“程序清单”顺序稳定
    unique_files = sorted({p for p in files if p.is_file()})

    all_lines: list[str] = []
    for path in unique_files:
        rel_path = path.relative_to(ROOT_DIR).as_posix()
        header = f"// ===== File: {rel_path} ====="
        all_lines.append(header)

        # 逐行读取，去掉行尾换行符，保留空行（空行也算作一行）
        with path.open("r", encoding="utf-8") as f:
            for raw in f:
                line = raw.rstrip("\r\n")
                all_lines.append(line)

    return all_lines


def split_front_back(
    lines: list[str], page_lines: int = 60, pages_each: int = 30
) -> tuple[list[str], list[str]]:
    """
    根据“每页 page_lines 行、每段 pages_each 页”的规则，
    从完整程序清单中截取“前 30 页”和“后 30 页”的行。

    - 若总行数 <= 60 页（两段之和），则直接返回全部行作为“前段”，后段为空。
    """
    need_each = page_lines * pages_each  # 1500
    total = len(lines)

    if total <= need_each * 2:
        # 程序总行数不超过 60 页，直接完整导出
        return lines, []

    front = lines[:need_each]
    back = lines[-need_each:]
    return front, back


def write_markdown(front: list[str], back: list[str], out_path: Path) -> None:
    """
    将截取的前/后段源程序写入 Markdown 文件，
    便于后续用 Typora / VS Code / Word 等工具导出为 PDF。
    """
    out_path.parent.mkdir(parents=True, exist_ok=True)

    with out_path.open("w", encoding="utf-8") as f:
        f.write("# Lattice-Planner 源程序鉴别材料（前 30 页 + 后 30 页）\n\n")
        f.write(
            "> 说明：本文件按每页约 60 行的连续源程序行数，"
            "从完整程序清单中选取前 30 页和后 30 页，用于软件著作权登记的源程序鉴别材料参考。\n\n"
        )

        if front:
            f.write("## 前 30 页（约 1800 行）\n\n```text\n")
            for line in front:
                # 防止三反引号破坏 Markdown 代码块
                safe_line = line.replace("```", "` ` `")
                f.write(safe_line + "\n")
            f.write("```\n\n")

        if back:
            f.write("## 后 30 页（约 1800 行）\n\n```text\n")
            for line in back:
                safe_line = line.replace("```", "` ` `")
                f.write(safe_line + "\n")
            f.write("```\n")
        else:
            f.write("（当前源程序总行数不超过 60 页，已完整列出全部源代码行。）\n")


def main() -> None:
    all_lines = collect_source_lines()
    front, back = split_front_back(all_lines)

    out_path = ROOT_DIR / "docs" / "source_program_front_back.md"
    write_markdown(front, back, out_path)

    print(
        f"已生成源程序鉴别材料 Markdown：{out_path}\n"
        "可用 Typora / VS Code 或导入 Word 后导出为 PDF，"
        "再按“前 30 页 + 后 30 页”要求提交。"
    )


if __name__ == "__main__":
    main()


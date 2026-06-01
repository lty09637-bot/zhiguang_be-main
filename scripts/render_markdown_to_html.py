from __future__ import annotations

import html
import pathlib
import re
import sys


def inline_format(text: str) -> str:
    escaped = html.escape(text, quote=False)
    return re.sub(r"`([^`]+)`", r"<code>\1</code>", escaped)


def convert_markdown(md_text: str, title: str) -> str:
    lines = md_text.splitlines()
    body: list[str] = []

    for raw in lines:
        line = raw.strip()
        if not line:
            continue
        if line.startswith("# "):
            body.append(f"<h1>{inline_format(line[2:])}</h1>")
        elif line.startswith("## "):
            body.append(f"<h2>{inline_format(line[3:])}</h2>")
        elif line.startswith("### "):
            body.append(f"<h3>{inline_format(line[4:])}</h3>")
        else:
            body.append(f"<p>{inline_format(line)}</p>")

    body_html = "\n      ".join(body)
    return f"""<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>{html.escape(title)}</title>
    <style>
      :root {{
        --bg: #f4f1ea;
        --paper: #fffdf8;
        --text: #171717;
        --muted: #5b5b5b;
        --line: #e8dfd1;
        --accent: #9e3d1f;
        --accent-soft: rgba(158, 61, 31, 0.08);
      }}

      * {{
        box-sizing: border-box;
      }}

      body {{
        margin: 0;
        font-family: "PingFang SC", "Hiragino Sans GB", "Noto Sans CJK SC", sans-serif;
        background:
          radial-gradient(circle at top left, rgba(158, 61, 31, 0.08), transparent 30%),
          linear-gradient(180deg, #f7f3eb 0%, #f1ece2 100%);
        color: var(--text);
      }}

      .shell {{
        max-width: 980px;
        margin: 0 auto;
        padding: 48px 20px 72px;
      }}

      .hero {{
        margin-bottom: 24px;
      }}

      .eyebrow {{
        display: inline-block;
        margin-bottom: 10px;
        padding: 6px 12px;
        border-radius: 999px;
        background: var(--accent-soft);
        color: var(--accent);
        font-size: 13px;
        font-weight: 700;
        letter-spacing: 0.04em;
      }}

      .hero h1 {{
        margin: 0 0 10px;
        font-size: clamp(34px, 5vw, 54px);
        line-height: 1.04;
        letter-spacing: -0.03em;
      }}

      .hero p {{
        max-width: 760px;
        margin: 0;
        color: var(--muted);
        font-size: 16px;
        line-height: 1.75;
      }}

      article {{
        background: rgba(255, 253, 248, 0.9);
        border: 1px solid var(--line);
        border-radius: 28px;
        padding: 36px 32px 44px;
        box-shadow: 0 20px 60px rgba(56, 42, 24, 0.08);
        backdrop-filter: blur(8px);
      }}

      article h1 {{
        margin: 0 0 18px;
        font-size: 38px;
        line-height: 1.15;
      }}

      article h2 {{
        margin: 34px 0 14px;
        padding-top: 18px;
        border-top: 1px solid var(--line);
        font-size: 25px;
        line-height: 1.3;
      }}

      article h3 {{
        margin: 26px 0 10px;
        font-size: 20px;
        line-height: 1.4;
      }}

      article p {{
        margin: 0 0 12px;
        color: #252525;
        font-size: 15.5px;
        line-height: 1.9;
      }}

      article code {{
        padding: 0.15em 0.45em;
        border-radius: 0.45em;
        background: #f0e7dc;
        font-family: "SFMono-Regular", "Menlo", monospace;
        font-size: 0.92em;
      }}

      @media (max-width: 640px) {{
        .shell {{
          padding: 28px 14px 40px;
        }}

        article {{
          padding: 24px 18px 28px;
          border-radius: 20px;
        }}

        article h1 {{
          font-size: 30px;
        }}

        article h2 {{
          font-size: 22px;
        }}

        article h3 {{
          font-size: 18px;
        }}
      }}
    </style>
  </head>
  <body>
    <main class="shell">
      <section class="hero">
        <span class="eyebrow">Kafka Interview Notes</span>
        <h1>Kafka 面试题整理</h1>
        <p>按基础概念、Producer、Consumer、存储高可用、顺序与幂等、生产排障和项目落地七个维度整理，适合直接浏览和临面前快速复习。</p>
      </section>
      <article>
      {body_html}
      </article>
    </main>
  </body>
</html>
"""


def main() -> int:
    if len(sys.argv) != 3:
        print("Usage: python3 render_markdown_to_html.py <input.md> <output.html>", file=sys.stderr)
        return 1

    input_path = pathlib.Path(sys.argv[1])
    output_path = pathlib.Path(sys.argv[2])
    markdown = input_path.read_text(encoding="utf-8")
    title = input_path.stem
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(convert_markdown(markdown, title), encoding="utf-8")
    print(output_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

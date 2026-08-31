#!/usr/bin/env python3
"""
translation_coverage.py

Compares values/strings.xml (base/English) against every values-<locale>/strings.xml
in an Android res/ directory, computes a completion percentage per locale, and
optionally emits shields.io-style SVG badges + a markdown table.

Usage:
    python translation_coverage.py <res_dir> [--svg-out <dir>] [--md-out <file>] [--json-out <file>]

Example:
    python translation_coverage.py app/src/main/res --svg-out badges --md-out COVERAGE.md
"""

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def parse_strings_xml(path: Path) -> set[str]:
    """
    Returns the set of translatable string-resource names in a strings.xml file.
    Skips entries marked translatable="false" and skips <string-array>/<plurals>
    child items but DOES count the array/plurals resource itself by name,
    since that's what matters for "is this resource present" comparisons.
    """
    if not path.exists():
        return set()

    try:
        tree = ET.parse(path)
    except ET.ParseError as e:
        print(f"warning: failed to parse {path}: {e}", file=sys.stderr)
        return set()

    root = tree.getroot()
    names = set()

    for tag in ("string", "string-array", "plurals"):
        for el in root.findall(tag):
            translatable = el.get("translatable", "true").lower()
            if translatable == "false":
                continue
            name = el.get("name")
            if name:
                names.add(name)

    return names


def locale_dirs(res_dir: Path) -> list[Path]:
    """Find all values-<locale>/ dirs that contain a strings.xml, excluding qualifiers
    that aren't language/region (e.g. values-night, values-v21, values-sw600dp)."""
    result = []
    # matches values-<lang>[-r<REGION>] e.g. values-hi, values-zh-rCN, values-pt-rBR
    locale_re = re.compile(r"^values-([a-z]{2,3})(-r[A-Z]{2})?$")
    for d in sorted(res_dir.glob("values-*")):
        if not d.is_dir():
            continue
        if not (d / "strings.xml").exists():
            continue
        if locale_re.match(d.name):
            result.append(d)
    return result


def locale_code_from_dir(dirname: str) -> str:
    # values-zh-rCN -> zh-CN, values-hi -> hi
    m = re.match(r"^values-([a-z]{2,3})(?:-r([A-Z]{2}))?$", dirname)
    if not m:
        return dirname.replace("values-", "")
    lang, region = m.group(1), m.group(2)
    return f"{lang}-{region}" if region else lang


def compute_coverage(res_dir: Path) -> dict:
    base_path = res_dir / "values" / "strings.xml"
    base_names = parse_strings_xml(base_path)

    if not base_names:
        print(f"error: no translatable strings found in {base_path}", file=sys.stderr)
        sys.exit(1)

    total = len(base_names)
    report = {
        "base_count": total,
        "base_path": str(base_path),
        "locales": {},
    }

    for d in locale_dirs(res_dir):
        code = locale_code_from_dir(d.name)
        names = parse_strings_xml(d / "strings.xml")

        translated = names & base_names
        missing = base_names - names
        extra = names - base_names  # present in translation but not in base (stale/orphaned)

        pct = round(100.0 * len(translated) / total, 1) if total else 0.0

        report["locales"][code] = {
            "dir": d.name,
            "translated": len(translated),
            "total": total,
            "percent": pct,
            "missing_keys": sorted(missing),
            "extra_keys": sorted(extra),
        }

    return report


# --- SVG badge generation (shields.io "flat" style, no external deps) --------

def badge_color(pct: float) -> str:
    if pct >= 95:
        return "#4c1"       # bright green
    if pct >= 80:
        return "#97ca00"    # green
    if pct >= 60:
        return "#dfb317"    # yellow
    if pct >= 40:
        return "#fe7d37"    # orange
    return "#e05d44"        # red


def make_badge_svg(label: str, pct: float) -> str:
    value_text = f"{pct:.1f}%"
    color = badge_color(pct)

    # crude but workable width estimation (px per char at 11px Verdana-ish)
    def text_width(s: str) -> int:
        return max(6, int(len(s) * 6.5)) + 10

    label_w = text_width(label)
    value_w = text_width(value_text)
    total_w = label_w + value_w

    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{total_w}" height="20" role="img" aria-label="{label}: {value_text}">
  <linearGradient id="s" x2="0" y2="100%">
    <stop offset="0" stop-color="#bbb" stop-opacity=".1"/>
    <stop offset="1" stop-opacity=".1"/>
  </linearGradient>
  <clipPath id="r">
    <rect width="{total_w}" height="20" rx="3" fill="#fff"/>
  </clipPath>
  <g clip-path="url(#r)">
    <rect width="{label_w}" height="20" fill="#555"/>
    <rect x="{label_w}" width="{value_w}" height="20" fill="{color}"/>
    <rect width="{total_w}" height="20" fill="url(#s)"/>
  </g>
  <g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" font-size="11">
    <text x="{label_w / 2:.0f}" y="14">{label}</text>
    <text x="{label_w + value_w / 2:.0f}" y="14">{value_text}</text>
  </g>
</svg>'''
    return svg


def write_badges(report: dict, out_dir: Path):
    out_dir.mkdir(parents=True, exist_ok=True)
    for code, info in report["locales"].items():
        svg = make_badge_svg(code, info["percent"])
        (out_dir / f"{code}.svg").write_text(svg, encoding="utf-8")

    overall = round(
        sum(v["percent"] for v in report["locales"].values()) / len(report["locales"]), 1
    ) if report["locales"] else 0.0
    (out_dir / "overall.svg").write_text(make_badge_svg("translations", overall), encoding="utf-8")
    return overall


def make_combined_table_svg(report: dict) -> str:
    """One self-contained SVG: a table of all locales + bar + percent, for embedding in README."""
    rows = sorted(report["locales"].items(), key=lambda kv: -kv[1]["percent"])

    row_h = 24
    header_h = 30
    pad = 12
    label_w = 90
    bar_w = 200
    pct_w = 55
    width = pad * 2 + label_w + bar_w + pct_w
    height = header_h + row_h * len(rows) + pad

    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
             f'viewBox="0 0 {width} {height}" font-family="Verdana,Geneva,DejaVu Sans,sans-serif">']
    parts.append(f'<rect width="{width}" height="{height}" fill="#0d1117" rx="6"/>')
    parts.append(f'<text x="{pad}" y="20" fill="#c9d1d9" font-size="13" font-weight="bold">Translation Coverage</text>')

    y = header_h
    for code, info in rows:
        pct = info["percent"]
        color = badge_color(pct)
        bar_fill = max(2, int(bar_w * pct / 100))

        parts.append(f'<text x="{pad}" y="{y + 16}" fill="#c9d1d9" font-size="12">{code}</text>')
        bx = pad + label_w
        parts.append(f'<rect x="{bx}" y="{y + 6}" width="{bar_w}" height="10" rx="5" fill="#30363d"/>')
        parts.append(f'<rect x="{bx}" y="{y + 6}" width="{bar_fill}" height="10" rx="5" fill="{color}"/>')
        parts.append(f'<text x="{bx + bar_w + 10}" y="{y + 16}" fill="#8b949e" font-size="12">{pct:.0f}%</text>')
        y += row_h

    parts.append('</svg>')
    return "\n".join(parts)


def write_combined_svg(report: dict, out_path: Path):
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(make_combined_table_svg(report), encoding="utf-8")


def write_markdown(report: dict, out_path: Path, svg_rel_dir: str | None = None):
    out_path.parent.mkdir(parents=True, exist_ok=True)
    lines = ["| Locale | Coverage | Translated / Total |", "|---|---|---|"]
    for code, info in sorted(report["locales"].items(), key=lambda kv: -kv[1]["percent"]):
        if svg_rel_dir:
            badge = f"![{code}]({svg_rel_dir}/{code}.svg)"
        else:
            badge = f"{info['percent']}%"
        lines.append(f"| `{code}` | {badge} | {info['translated']} / {info['total']} |")
    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("res_dir", type=Path, help="path to res/ directory (contains values/, values-*/)")
    ap.add_argument("--svg-out", type=Path, default=None, help="directory to write per-locale SVG badges")
    ap.add_argument("--combined-svg", type=Path, default=None,
                     help="path to write a single combined table SVG (e.g. badges/coverage.svg) for README embedding")
    ap.add_argument("--md-out", type=Path, default=None, help="path to write a markdown coverage table")
    ap.add_argument("--json-out", type=Path, default=None, help="path to write raw JSON report")
    ap.add_argument("--fail-under", type=float, default=None,
                     help="exit nonzero if any locale is below this percent (useful for CI gating)")
    args = ap.parse_args()

    res_dir = args.res_dir.resolve()
    if not res_dir.is_dir():
        print(f"error: {res_dir} is not a directory", file=sys.stderr)
        sys.exit(1)

    report = compute_coverage(res_dir)

    # console summary
    print(f"Base: {report['base_count']} translatable strings\n")
    for code, info in sorted(report["locales"].items(), key=lambda kv: -kv[1]["percent"]):
        print(f"  {code:8s} {info['percent']:5.1f}%  ({info['translated']}/{info['total']})")
        if info["missing_keys"]:
            preview = ", ".join(info["missing_keys"][:5])
            more = f" (+{len(info['missing_keys']) - 5} more)" if len(info["missing_keys"]) > 5 else ""
            print(f"           missing: {preview}{more}")
        if info["extra_keys"]:
            print(f"           extra/orphaned: {len(info['extra_keys'])} keys not in base")

    if args.json_out:
        args.json_out.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"\nwrote {args.json_out}")

    if args.svg_out:
        overall = write_badges(report, args.svg_out)
        print(f"wrote SVG badges to {args.svg_out} (overall: {overall}%)")

    if args.combined_svg:
        write_combined_svg(report, args.combined_svg)
        print(f"wrote {args.combined_svg}")

    if args.md_out:
        svg_rel = str(args.svg_out) if args.svg_out else None
        write_markdown(report, args.md_out, svg_rel_dir=svg_rel)
        print(f"wrote {args.md_out}")

    if args.fail_under is not None:
        failing = {c: i["percent"] for c, i in report["locales"].items() if i["percent"] < args.fail_under}
        if failing:
            print(f"\nFAIL: locales below {args.fail_under}%: {failing}", file=sys.stderr)
            sys.exit(1)


if __name__ == "__main__":
    main()

"""Print one version's section of CHANGELOG.md as GitHub release notes.

Usage: changelog_section.py CHANGELOG.md 0.2.0

The changelog is hard-wrapped, and GitHub renders every newline in a release
body as a line break, so each paragraph and list item is joined back onto one
line. Exits non-zero when the version has no section, so a release cannot go
out with empty notes.
"""
import sys


def section(text: str, version: str) -> list[str]:
    lines = text.split("\n")
    header = f"## [{version}]"
    try:
        start = next(i for i, line in enumerate(lines) if line.strip() == header) + 1
    except StopIteration:
        sys.exit(f"CHANGELOG has no section {header}")
    end = next((i for i in range(start, len(lines)) if lines[i].startswith("## [")), len(lines))
    return lines[start:end]


def unwrap(lines: list[str]) -> str:
    out: list[str] = []
    for line in lines:
        previous = out[-1] if out else ""
        if line.startswith("  ") and previous.startswith("- "):
            out[-1] = previous + " " + line.strip()
        elif line and previous and not line.startswith(("- ", "#")) and not previous.startswith("#"):
            out[-1] = previous + " " + line.strip()
        else:
            out.append(line)
    return "\n".join(out).strip() + "\n"


if __name__ == "__main__":
    path, version = sys.argv[1], sys.argv[2]
    with open(path, encoding="utf-8") as changelog:
        notes = unwrap(section(changelog.read(), version))
    if not notes.strip():
        sys.exit(f"CHANGELOG section [{version}] is empty")
    sys.stdout.write(notes)

"""Markdown summary of the backend test reports (Surefire and Failsafe) for the GitHub Actions job summary.

Usage: python3 .github/scripts/test_summary.py >> "$GITHUB_STEP_SUMMARY"
"""

import glob
import xml.etree.ElementTree as ET

REPORTS = (("Unit", "backend/target/surefire-reports"), ("Integration", "backend/target/failsafe-reports"))


def first_line(text, limit=200):
    lines = (text or "").strip().splitlines()
    line = lines[0] if lines else ""
    return (line[:limit] + "…" if len(line) > limit else line).replace("|", "\\|")


def main():
    rows, failed = [], []
    for kind, directory in REPORTS:
        total = failures = skipped = 0
        paths = sorted(glob.glob(f"{directory}/TEST-*.xml"))
        for path in paths:
            suite = ET.parse(path).getroot()
            total += int(suite.get("tests", 0))
            failures += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
            skipped += int(suite.get("skipped", 0))
            for case in suite.iter("testcase"):
                problem = case.find("failure")
                if problem is None:
                    problem = case.find("error")
                if problem is not None:
                    message = first_line(problem.get("message") or problem.text)
                    failed.append(f"| {kind} | `{case.get('classname')}.{case.get('name')}` | {message} |")
        if not paths:
            rows.append(f"| {kind} | — | — | — | — |")
            continue
        passed = total - failures - skipped
        status = "✅" if failures == 0 else "❌"
        rows.append(f"| {status} {kind} | {total} | {passed} | {failures} | {skipped} |")

    print("## Backend tests\n")
    print("| | Total | Passed | Failed | Skipped |")
    print("|---|---:|---:|---:|---:|")
    print("\n".join(rows))
    if failed:
        print("\n### Failed tests\n")
        print("| | Test | Message |")
        print("|---|---|---|")
        print("\n".join(failed))
    print("\nFull reports: the `test-reports` artifact of this run.")


if __name__ == "__main__":
    main()

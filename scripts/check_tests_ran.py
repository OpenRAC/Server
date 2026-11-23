#!/usr/bin/env python3
"""Fails when JUnit's XML reports show a test class was skipped instead of run.

The PostgreSQL tests skip themselves without a database, which would pass quietly in CI.

Usage:
    scripts/check_tests_ran.py REPORT.xml...
"""

import sys
import xml.etree.ElementTree as ElementTree


def main():
    """Reads each report and fails on none run or any skipped."""
    reports = sys.argv[1:]
    problems = []

    # A glob that matched nothing reaches here unexpanded.
    if not reports or any("*" in report for report in reports): problems.append("no test reports found")

    for report in reports:
        if "*" in report: continue

        suite = ElementTree.parse(report).getroot()
        tests = int(suite.get("tests", 0))
        skipped = int(suite.get("skipped", 0))
        print(f"{suite.get('name')}: {tests} tests, {skipped} skipped")

        if tests == 0 or skipped: problems.append(f"{suite.get('name')} did not run every test")

    for problem in problems:
        print(problem, file=sys.stderr)

    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

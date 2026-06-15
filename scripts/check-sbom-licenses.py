#!/usr/bin/env python3
"""Validate a CycloneDX SBOM against the open-source license allow-list and
write a human-readable THIRD-PARTY attribution file.

Used for the CycloneDX BOMs that don't have their own license-policy tooling:
the VS Code extension (cyclonedx-npm) and the IntelliJ plugin (cyclonedx gradle
plugin). The Maven modules are gated separately by the license-maven-plugin
allow-list in the root pom.xml.

Behaviour:
  * Every component's license is normalised (variant spellings -> canonical id).
  * Components with no license metadata are resolved via the overrides file
    (scripts/sbom-license-overrides.json), keyed by "group:name". Some upstreams
    (notably the IntelliJ Platform jars) ship no per-artifact license metadata,
    so their license is asserted there from the project's published license.
  * The build FAILS (exit 1) if any component resolves to a license that is not
    on the allow-list, or has no license at all.
  * On success, THIRD-PARTY.txt is (re)written next to the BOM.

Usage:
  check-sbom-licenses.py --bom <bom.json> --output <THIRD-PARTY.txt> --name <label>
"""

from __future__ import annotations

import argparse
import json
import os
import sys

# Reviewed, accepted open-source licenses (canonical SPDX ids). Keep in sync with
# the <includedLicenses> allow-list in the root pom.xml (Maven side).
ALLOWED = {
    "Apache-2.0",
    "MIT",
    "ISC",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "EPL-1.0",
    "EPL-2.0",
    "GPL-2.0-with-classpath-exception",
}

# Normalise the spellings upstreams use into the canonical ids above.
MERGES = {
    "apache license 2.0": "Apache-2.0",
    "apache license, version 2.0": "Apache-2.0",
    "apache-2.0": "Apache-2.0",
    "the apache software license, version 2.0": "Apache-2.0",
    "mit license": "MIT",
    "the mit license": "MIT",
    "mit": "MIT",
    "isc license": "ISC",
    "isc": "ISC",
    "bsd-2-clause": "BSD-2-Clause",
    "the bsd 2-clause license": "BSD-2-Clause",
    "bsd-3-clause": "BSD-3-Clause",
    "new bsd license": "BSD-3-Clause",
    "the new bsd license": "BSD-3-Clause",
    "eclipse public license 1.0": "EPL-1.0",
    "epl-1.0": "EPL-1.0",
    "eclipse public license 2.0": "EPL-2.0",
    "eclipse public license, version 2.0": "EPL-2.0",
    "epl-2.0": "EPL-2.0",
    "gpl-2.0-with-classpath-exception": "GPL-2.0-with-classpath-exception",
}


def canonical(raw: str) -> str:
    """Map a raw license id/name/expression to a canonical id (or echo it back)."""
    if not raw:
        return ""
    key = raw.strip().lower()
    return MERGES.get(key, raw.strip())


def component_licenses(comp: dict) -> list[str]:
    """Pull every license string off a CycloneDX component (id, name, expression)."""
    out: list[str] = []
    for entry in comp.get("licenses", []) or []:
        lic = entry.get("license")
        if isinstance(lic, dict):
            val = lic.get("id") or lic.get("name")
            if val:
                out.append(val)
        expr = entry.get("expression")
        if expr:
            out.append(expr)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bom", required=True, help="path to CycloneDX bom.json")
    ap.add_argument("--output", required=True, help="path to write THIRD-PARTY.txt")
    ap.add_argument("--name", required=True, help="label for the component set")
    ap.add_argument(
        "--overrides",
        default=os.path.join(os.path.dirname(__file__), "sbom-license-overrides.json"),
        help="path to the group:name -> license override file",
    )
    args = ap.parse_args()

    with open(args.bom, encoding="utf-8") as fh:
        bom = json.load(fh)

    overrides = {}
    if os.path.exists(args.overrides):
        with open(args.overrides, encoding="utf-8") as fh:
            overrides = json.load(fh).get("overrides", {})

    rows: list[tuple[str, str, str]] = []  # (sort key, license display, line)
    violations: list[str] = []

    for comp in bom.get("components", []) or []:
        group = comp.get("group", "")
        name = comp.get("name", "")
        version = comp.get("version", "")
        coord = f"{group}:{name}" if group else name

        raw_licenses = component_licenses(comp)
        source = ""
        if not raw_licenses and coord in overrides:
            raw_licenses = [overrides[coord]]
            source = " (asserted)"

        canon = [canonical(x) for x in raw_licenses]

        if not canon:
            violations.append(f"  - {coord}@{version}: NO LICENSE (add to {os.path.basename(args.overrides)} if known-OSS)")
            disp = "NO LICENSE"
        else:
            disallowed = [c for c in canon if c not in ALLOWED]
            if disallowed:
                violations.append(f"  - {coord}@{version}: not allow-listed: {', '.join(disallowed)}")
            disp = ", ".join(canon)

        purl = comp.get("purl", "")
        rows.append((coord.lower(), disp, f"     ({disp}{source}) {name}:{version}" + (f" ({purl})" if purl else "")))

    rows.sort(key=lambda r: r[0])

    header = (
        f"Third-party dependencies for {args.name} "
        f"({len(rows)} component(s)).\n"
        "Generated from the committed CycloneDX SBOM by "
        "scripts/check-sbom-licenses.py — do not edit by hand.\n\n"
    )
    with open(args.output, "w", encoding="utf-8") as fh:
        fh.write(header)
        fh.write("\n".join(r[2] for r in rows))
        fh.write("\n")

    if violations:
        sys.stderr.write(
            f"\nLICENSE CHECK FAILED for {args.name} — "
            f"{len(violations)} component(s) violate the open-source allow-list:\n"
        )
        sys.stderr.write("\n".join(violations) + "\n")
        sys.stderr.write(f"\nAllowed licenses: {', '.join(sorted(ALLOWED))}\n")
        return 1

    print(f"License check OK for {args.name}: {len(rows)} component(s), all allow-listed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#    Copyright (c) 2026 SOPTIM AG
#
#    Licensed under the Apache License, Version 2.0 (the "License");
#    you may not use this file except in compliance with the License.
#    You may obtain a copy of the License at
#
#        http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.
#
#    SPDX-License-Identifier: Apache-2.0

"""The result model: what the contract obliges a consumer to tolerate, and the ergonomics on top."""

from __future__ import annotations

import json

import pytest

from cimvocabcheck import Code, Report, ReportParseError, Severity, parse_report
from cimvocabcheck._ergonomics import severity_at_least

from .conftest import report_json

FINDING = {
    "severity": "ERROR",
    "code": "UNKNOWN_CLASS",
    "line": 3,
    "column": 12,
    "term": "http://iec.ch/TC57/CIM100#ACLineSegmentt",
    "graph": "http://example.org/graph",
    "message": "Class does not exist in profile.",
    "foundInOtherProfiles": ["http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0"],
}


def one_result(*annotations, file="q.rq", valid=False):
    return json.loads(
        report_json(results=[{"file": file, "valid": valid, "annotations": list(annotations)}])
    )


def test_reads_every_documented_field():
    report = Report.from_dict(one_result(FINDING))

    annotation = report.results[0].annotations[0]
    assert annotation.severity == Severity.ERROR
    assert annotation.code == Code.UNKNOWN_CLASS
    assert (annotation.line, annotation.column) == (3, 12)
    assert annotation.term.endswith("ACLineSegmentt")
    assert annotation.graph == "http://example.org/graph"
    assert annotation.found_in_other_profiles == (
        "http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0",
    )


def test_omitted_optional_fields_are_none_not_missing():
    annotation = Report.from_dict(
        one_result({"severity": "WARN", "code": "UNUSED_VARIABLE", "message": "x"})
    ).results[0].annotations[0]

    assert annotation.line is None
    assert annotation.column is None
    assert annotation.term is None
    assert annotation.found_in_other_profiles == ()


def test_unknown_fields_are_kept_rather_than_rejected():
    """The contract adds fields in minor versions; a consumer must not break on one it predates."""
    document = one_result(dict(FINDING, futureField="soon"))
    document["futureTopLevel"] = {"anything": True}

    report = Report.from_dict(document)

    assert report.extra == {"futureTopLevel": {"anything": True}}
    assert report.results[0].annotations[0].extra == {"futureField": "soon"}


def test_unknown_code_is_a_finding_not_an_error():
    """New rule codes ship in minor versions — an unrecognised one must still parse."""
    annotation = Report.from_dict(
        one_result({"severity": "ERROR", "code": "A_RULE_FROM_THE_FUTURE", "message": "x"})
    ).results[0].annotations[0]

    assert annotation.code == "A_RULE_FROM_THE_FUTURE"
    assert annotation.is_error
    assert not annotation.is_known_code


def test_known_codes_cover_the_published_enum():
    schema = json.loads(
        (
            __import__("pathlib").Path(__file__).resolve().parent.parent
            / "src"
            / "cimvocabcheck"
            / "schemas"
            / "cimvocabcheck-report-1.schema.json"
        ).read_text(encoding="utf-8")
    )

    assert set(Code.ALL) == set(schema["definitions"]["code"]["enum"])
    assert set(Severity.ALL) == set(schema["definitions"]["severity"]["enum"])


def test_missing_required_field_names_where_it_was_missing():
    with pytest.raises(ReportParseError) as raised:
        Report.from_dict(one_result({"severity": "ERROR", "code": "SYNTAX_ERROR"}))

    assert "message" in str(raised.value)
    assert "annotations[0]" in str(raised.value)


def test_wrong_type_names_the_field():
    with pytest.raises(ReportParseError) as raised:
        Report.from_dict(one_result(dict(FINDING, line="three")))

    assert "$.results[0].annotations[0].line" in str(raised.value)
    assert "expected an integer" in str(raised.value)


def test_a_boolean_is_not_an_integer():
    """bool subclasses int in Python; a count that arrived as True is a malformed report."""
    document = json.loads(report_json())
    document["summary"]["files"] = True

    with pytest.raises(ReportParseError):
        Report.from_dict(document)


def test_incompatible_contract_major_is_refused():
    with pytest.raises(ReportParseError) as raised:
        parse_report(report_json(contractVersion="2.0"))

    assert "contract 1.x" in str(raised.value)


def test_newer_contract_minor_is_accepted():
    assert parse_report(report_json(contractVersion="1.7")).contract_major == 1


def test_output_that_is_not_json_is_a_parse_error():
    with pytest.raises(ReportParseError) as raised:
        parse_report("Exception in thread \"main\" java.lang.NoClassDefFoundError\n")

    assert "not JSON" in str(raised.value)


def test_findings_are_flat_and_carry_their_file():
    report = Report.from_dict(
        json.loads(
            report_json(
                results=[
                    {"file": "a.rq", "valid": False, "annotations": [FINDING]},
                    {
                        "file": "b.rq",
                        "valid": True,
                        "annotations": [
                            {"severity": "WARN", "code": "UNUSED_VARIABLE", "message": "w"}
                        ],
                    },
                ]
            )
        )
    )

    assert not report.ok
    assert report.invalid_files == ("a.rq",)
    assert [f.file for f in report.findings()] == ["a.rq", "b.rq"]
    assert len(report.errors) == 1
    assert len(report.warnings) == 1
    assert report.findings(code=Code.UNKNOWN_CLASS)[0].file == "a.rq"
    assert report.for_file("b.rq").valid
    assert report.for_file("missing.rq") is None


def test_finding_renders_as_a_compiler_style_line():
    report = Report.from_dict(one_result(FINDING))

    assert str(report.findings()[0]) == (
        "q.rq:3:12: ERROR[UNKNOWN_CLASS] Class does not exist in profile."
    )


def test_location_omits_positions_the_engine_could_not_resolve():
    annotation = Report.from_dict(
        one_result({"severity": "ERROR", "code": "SYNTAX_ERROR", "message": "x"})
    ).results[0].annotations[0]

    assert annotation.location("q.rq") == "q.rq"


def test_severity_ordering_tolerates_a_severity_it_does_not_know():
    assert severity_at_least(Severity.ERROR, Severity.WARN)
    assert not severity_at_least(Severity.INFO, Severity.WARN)
    assert not severity_at_least("CATASTROPHE", Severity.WARN)


def test_a_severity_floor_selects_everything_at_least_that_severe():
    report = Report.from_dict(
        one_result(
            FINDING,
            {"severity": "WARN", "code": "UNUSED_VARIABLE", "message": "w"},
            {"severity": "INFO", "code": "QUERY_IMPLIED_TYPE", "message": "i"},
        )
    )

    assert len(report.findings(min_severity=Severity.WARN)) == 2
    assert len(report.findings(min_severity=Severity.ERROR)) == 1
    assert len(report.findings(min_severity=Severity.INFO)) == 3

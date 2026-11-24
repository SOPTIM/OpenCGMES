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

"""Typed readers the generated ``from_dict`` code is built from.

Each reader reports the JSON path it failed at, so a malformed report names the offending field
instead of surfacing as a ``KeyError`` three frames deep. Absent *optional* fields are normal —
the engine omits them rather than emitting ``null``.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from collections.abc import Mapping as MappingABC
from collections.abc import Sequence as SequenceABC
from typing import Any, TypeVar

from .errors import ReportParseError

T = TypeVar("T")

_MISSING = object()


def _fail(path: str, expected: str, value: Any) -> ReportParseError:
    return ReportParseError(
        f"malformed report at {path}: expected {expected}, got {type(value).__name__}"
    )


def mapping(value: Any, path: str) -> Mapping[str, Any]:
    """Asserts that ``value`` is a JSON object before any field is read from it."""
    if not isinstance(value, MappingABC):
        raise _fail(path, "an object", value)
    return value


def _get(data: Mapping[str, Any], key: str, path: str, required: bool) -> Any:
    if key in data:
        return data[key]
    if required:
        raise ReportParseError(f"malformed report at {path}: required field {key!r} is missing")
    return _MISSING


def _scalar(
    data: Mapping[str, Any],
    key: str,
    path: str,
    required: bool,
    kind: type,
    label: str,
) -> Any:
    value = _get(data, key, path, required)
    if value is _MISSING:
        return None
    # bool is a subclass of int in Python; an integer field must not silently accept True.
    if not isinstance(value, kind) or (kind is int and isinstance(value, bool)):
        raise _fail(f"{path}.{key}", label, value)
    return value


def string(data: Mapping[str, Any], key: str, path: str, required: bool = False) -> Any:
    return _scalar(data, key, path, required, str, "a string")


def integer(data: Mapping[str, Any], key: str, path: str, required: bool = False) -> Any:
    return _scalar(data, key, path, required, int, "an integer")


def boolean(data: Mapping[str, Any], key: str, path: str, required: bool = False) -> Any:
    return _scalar(data, key, path, required, bool, "a boolean")


def obj(
    data: Mapping[str, Any],
    key: str,
    path: str,
    factory: Callable[[Any, str], T],
    required: bool = False,
) -> Any:
    value = _get(data, key, path, required)
    if value is _MISSING:
        return None
    return factory(value, f"{path}.{key}")


def _sequence(data: Mapping[str, Any], key: str, path: str, required: bool) -> Sequence[Any]:
    value = _get(data, key, path, required)
    if value is _MISSING:
        return ()
    # str is a Sequence; an array field that arrived as a string is a malformed report.
    if not isinstance(value, SequenceABC) or isinstance(value, (str, bytes)):
        raise _fail(f"{path}.{key}", "an array", value)
    return value


def strings(
    data: Mapping[str, Any], key: str, path: str, required: bool = False
) -> tuple[str, ...]:
    items = _sequence(data, key, path, required)
    for i, item in enumerate(items):
        if not isinstance(item, str):
            raise _fail(f"{path}.{key}[{i}]", "a string", item)
    return tuple(items)


def objects(
    data: Mapping[str, Any],
    key: str,
    path: str,
    factory: Callable[[Any, str], T],
    required: bool = False,
) -> tuple[T, ...]:
    items = _sequence(data, key, path, required)
    return tuple(factory(item, f"{path}.{key}[{i}]") for i, item in enumerate(items))


def extra(data: Mapping[str, Any], known: tuple[str, ...]) -> dict[str, Any]:
    """Collects fields this binding does not know.

    The contract adds fields within a major version and obliges consumers to ignore the ones they
    do not recognise. Keeping them costs nothing and lets a caller read a field that is newer than
    the binding without waiting for a release.
    """
    return {k: v for k, v in data.items() if k not in known}

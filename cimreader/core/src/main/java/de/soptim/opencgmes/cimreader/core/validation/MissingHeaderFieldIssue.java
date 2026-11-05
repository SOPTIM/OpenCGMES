/*
 *    Copyright (c) 2026 SOPTIM AG
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    SPDX-License-Identifier: Apache-2.0
 */

package de.soptim.opencgmes.cimreader.core.validation;

import de.soptim.opencgmes.cimxml.graph.CimModelHeader;

public class MissingHeaderFieldIssue implements ValidationIssue {

  private final CimModelHeader header;
  private final String missingField;

  public MissingHeaderFieldIssue(CimModelHeader header, String missingField) {
    this.header = header;
    this.missingField = missingField;
  }

  @Override
  public String getMessage() {
    if (header.getModel() == null) {
      return "Missing field '" + missingField + "' in header";
    }
    return "Missing field '" + missingField + "' in header of model '" + header.getModel() + "'";
  }
}

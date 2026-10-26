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

package de.soptim.opencgmes.cimreader.core.exception;

import de.soptim.opencgmes.cimreader.core.validation.CimValidator;

public class InvalidImportException extends ImportException {

  private final CimValidator validator;

  public InvalidImportException(CimValidator validator) {
    this.validator = validator;
    StringBuilder message = new StringBuilder();
    message.append("Import failed due to validation errors:\n");
    for (var issue : validator.getIssues()) {
      message.append("- ").append(issue.getMessage()).append("\n");
    }
    super(message.toString());
  }

  public CimValidator getValidator() {
    return validator;
  }
}

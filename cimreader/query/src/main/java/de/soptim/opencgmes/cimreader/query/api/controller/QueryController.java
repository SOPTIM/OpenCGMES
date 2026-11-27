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

package de.soptim.opencgmes.cimreader.query.api.controller;

import de.soptim.opencgmes.cimreader.query.service.query.QueryResultUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/query/{tso}/{scenarioTime}")
public class QueryController {

  private final QueryResultUseCase queryResultUseCase;

  @PostMapping
  public ResponseEntity<String> getQueryResult(
      @PathVariable String tso, @PathVariable String scenarioTime, @RequestBody String sparql) {
    var result = queryResultUseCase.getQueryResult(tso, scenarioTime, sparql);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(result.mediaType()))
        .body(result.body());
  }

}

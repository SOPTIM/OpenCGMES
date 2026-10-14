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

package de.soptim.opencgmes.cimvocabcheck.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.soptim.opencgmes.cimvocabcheck.core.schema.RdfsSchemaIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Lists of profiles and classes in a finding come out in one defined order — by IRI — whatever
 * order the schema was loaded in.
 *
 * <p>They used to follow the iteration order of the JDK's immutable collections, which is
 * randomised per JVM, so the same query against the same schema could be reported differently on
 * every run. Eight entries make an accidentally sorted order a one-in-40320 event.
 */
public class MessageOrderTest {

  private static final String NS = "http://example.org/";
  private static final List<String> NAMES = List.of("a", "b", "c", "d", "e", "f", "g", "h");

  private static String profile(String name) {
    return "urn:profile:" + name;
  }

  /** Profiles {@code a}–{@code h}, added last to first; every one but {@code a} has the class. */
  private static SparqlValidationApi api() {
    var builder = RdfsSchemaIndex.builder();
    for (String name : NAMES.reversed()) {
      var classes = name.equals("a") ? List.of(NS + "Other") : List.of(NS + "Shared");
      builder.addProfile(profile(name), classes, List.of());
    }
    return new SparqlValidationApi(builder.build());
  }

  private static List<VersionIri> profiles(List<String> names) {
    return names.stream().map(name -> VersionIri.of(profile(name))).toList();
  }

  private static String listed(List<String> names) {
    var msg = new StringBuilder();
    IriFormat.appendIris(msg, profiles(names));
    return msg.toString();
  }

  private static SparqlValidationAnnotation single(SparqlValidationResult result) {
    assertEquals(result.annotations().toString(), 1, result.annotations().size());
    return result.annotations().getFirst();
  }

  @Test
  public void profilesATermExistsInAreListedByIri() {
    var finding =
        single(
            api()
                .validateSparql(
                    "SELECT * WHERE { ?s a <" + NS + "Shared> }", profiles(List.of("a"))));

    var elsewhere = NAMES.subList(1, NAMES.size());
    assertEquals(profiles(elsewhere), List.copyOf(finding.foundInOtherProfiles()));
    assertTrue(
        finding.message(),
        finding.message().endsWith("Exists in profiles " + listed(elsewhere) + "."));
  }

  @Test
  public void theProfilesInScopeAreListedByIri() {
    var finding = single(api().validateSparql("SELECT * WHERE { ?s a <" + NS + "Missing> }"));

    assertEquals(profiles(NAMES), List.copyOf(finding.selectedProfiles()));
    assertTrue(finding.message(), finding.message().contains("profiles " + listed(NAMES) + "."));
  }

  @Test
  public void theScopeIsListedByIriWhateverOrderItWasGivenIn() {
    var finding =
        single(
            api()
                .validateSparql(
                    "SELECT * WHERE { ?s a <" + NS + "Missing> }",
                    profiles(List.of("h", "a", "d"))));

    assertTrue(
        finding.message(),
        finding.message().contains("profiles " + listed(List.of("a", "d", "h"))));
  }

  @Test
  public void expectedDomainClassesAreListedByIri() {
    var domains = new ArrayList<String>();
    for (String name : NAMES.reversed()) {
      domains.add(NS + "Domain" + name.toUpperCase());
    }
    var index =
        RdfsSchemaIndex.builder()
            .addProfile(
                profile("a"),
                List.of(NS + "Typed"),
                List.of(NS + "prop"),
                Map.of(NS + "prop", domains),
                Map.of(),
                Map.of())
            .build();

    var finding =
        single(
            new SparqlValidationApi(index)
                .validateSparql(
                    "SELECT * WHERE { ?s a <"
                        + NS
                        + "Typed> ; <"
                        + NS
                        + "prop> ?o . FILTER(?o) }"));

    var expected = NAMES.stream().map(n -> "<" + NS + "Domain" + n.toUpperCase() + ">").toList();
    assertTrue(
        finding.message(),
        finding.message().contains("expected one of [" + String.join(", ", expected) + "]"));
  }
}

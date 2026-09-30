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

package de.soptim.opencgmes.cimvocabcheck.lsp;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Go-to-definition for terms of a model held in RDFArchitect: a link that opens the term there.
 *
 * <p>Such a term has no source file — it lives in a browser session's working copy — and
 * RDFArchitect is where it is edited, so that is where Ctrl+Click takes the user. The location is a
 * virtual {@code rdfarchitect://term/<payload>/<profile>/<name>} URI that nothing is written for:
 * both editor integrations resolve it themselves, show the term in their RDFArchitect view when it
 * is navigated to, and close the placeholder tab again. That indirection is deliberate — both
 * editors resolve a Ctrl+Click target while the user is merely hovering, so opening the view can
 * only hang off the navigation itself.
 *
 * <p>The payload is the {@code key=value} directive naming the term, instance, dataset and graph,
 * Base64url-encoded: LSP4IJ percent-decodes a location URI before resolving it, which would tear an
 * encoded graph name apart. The profile and local name follow it only so the editors' "choose a
 * declaration" lists read clearly.
 */
final class RdfArchitectTermLink {

  /** The URI scheme both editor integrations resolve. */
  static final String SCHEME = "rdfarchitect";

  private RdfArchitectTermLink() {}

  /**
   * Where a term lives in RDFArchitect: what the editor needs to show it.
   *
   * @param baseUrl the instance, or {@code null} when only the editor knows it
   * @param dataset the dataset holding the model, or {@code null} for a snapshot link
   * @param graph the graph holding this profile, or {@code null} when it is not known
   * @param profile how the profile reads, and what keeps two profiles' links apart
   */
  record Target(String baseUrl, String dataset, String graph, String profile) {}

  /** The location that opens {@code termIri} in RDFArchitect, as {@code target} holds it. */
  static Location locationFor(String termIri, Target target) {
    return new Location(uriFor(termIri, target), new Range(new Position(0, 0), new Position(0, 0)));
  }

  static String uriFor(String termIri, Target target) {
    String payload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(directive(termIri, target).getBytes(StandardCharsets.UTF_8));
    String path =
        "/"
            + payload
            + "/"
            + EndpointDefinitionPeek.slug(target.profile())
            + "/"
            + EndpointDefinitionPeek.localName(termIri);
    try {
      return new URI(SCHEME, "term", path, null).toASCIIString();
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Cannot build an RDFArchitect link for " + termIri, e);
    }
  }

  /**
   * The {@code key=value} pairs the editors read, each value percent-encoded — a graph is named
   * after the file it was imported from, and those have spaces in them.
   */
  static String directive(String termIri, Target target) {
    var out = new StringBuilder("class=").append(encode(termIri));
    if (target.baseUrl() != null) {
      out.append(" base=").append(encode(target.baseUrl()));
    }
    if (target.dataset() != null) {
      out.append(" dataset=").append(encode(target.dataset()));
    }
    if (target.graph() != null) {
      out.append(" graph=").append(encode(target.graph()));
    }
    return out.toString();
  }

  /** Percent-encoding, with spaces as {@code %20} rather than {@code +} so both clients agree. */
  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}

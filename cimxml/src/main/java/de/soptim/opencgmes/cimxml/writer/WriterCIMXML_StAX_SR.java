/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.soptim.opencgmes.cimxml.writer;

import com.sun.xml.txw2.output.IndentingXMLStreamWriter;
import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import java.io.OutputStream;
import java.io.Writer;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.PrefixMap;

/**
 * CIMXML parser. This implementation is based on the RDF/XML reader ReaderRDFXML_StAX_SR in Apache
 * Jena, originally. It has been adapted to the CIMXML needs.
 * <p>
 * This implementation uses StAX via {@link XMLStreamReader}.
 *
 * @see <a
 * href="https://webstore.iec.ch/en/publication/25939">https://webstore.iec.ch/en/publication/25939</a>
 */
public class WriterCIMXML_StAX_SR {

  public static XMLOutputFactory createXMLOutputFactory() {
    var factory = new com.fasterxml.aalto.stax.OutputFactoryImpl();
    factory.configureForSpeed();
    return factory;
  }

  private static final XMLOutputFactory xmlOutputFactory = createXMLOutputFactory();

  public void write(OutputStream out, CimDatasetGraph cimDatasetGraph) {
    write(out, cimDatasetGraph, null, false);
  }

  public void write(OutputStream out, CimDatasetGraph cimDatasetGraph, PrefixMap prefixMap,
      boolean sorted) {
    try {
      var xmlStreamWriter = new IndentingXMLStreamWriter(
          xmlOutputFactory.createXMLStreamWriter(out));
      serialize(xmlStreamWriter, cimDatasetGraph, prefixMap, sorted);
    } catch (XMLStreamException ex) {
      throw new RiotException("Failed to create the XMLStreamWriter", ex);
    }
  }

  public void write(Writer out, CimDatasetGraph cimDatasetGraph) {
    write(out, cimDatasetGraph, null, false);
  }

  public void write(Writer out, CimDatasetGraph cimDatasetGraph, PrefixMap prefixMap,
      boolean sorted) {
    try {
      var xmlStreamWriter = new IndentingXMLStreamWriter(
          xmlOutputFactory.createXMLStreamWriter(out));
      serialize(xmlStreamWriter, cimDatasetGraph, prefixMap, sorted);
    } catch (XMLStreamException ex) {
      throw new RiotException("Failed to create the XMLStreamWriter", ex);
    }
  }

  private void serialize(XMLStreamWriter xmlStreamWriter, CimDatasetGraph cimDatasetGraph,
      PrefixMap prefixMap, boolean sorted) {
    var serializer = new SerializerCIMXML_StAX_SR(xmlStreamWriter, cimDatasetGraph, prefixMap,
        sorted);
    try {
      serializer.serialize();
    } catch (XMLStreamException e) {
      throw new RiotException(e);
    }
  }
}

package de.soptim.opencgmes.cimxml.writer;

import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import java.util.Map.Entry;
import javax.xml.stream.XMLStreamException;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.PrefixMap;
import org.codehaus.stax2.XMLStreamWriter2;

public class SerializerCIMXML_StAX_SR {

  private final XMLStreamWriter2 xmlStreamWriter;
  private final CimDatasetGraph cimDatasetGraph;
  private final PrefixMap prefixMap;

  public SerializerCIMXML_StAX_SR(XMLStreamWriter2 xmlStreamWriter, CimDatasetGraph cimDatasetGraph,
      PrefixMap prefixMap) {
    this.xmlStreamWriter = xmlStreamWriter;
    this.cimDatasetGraph = cimDatasetGraph;
    this.prefixMap = prefixMap == null ? cimDatasetGraph.prefixes() : prefixMap;
  }

  void serialize() throws XMLStreamException {
    if (cimDatasetGraph.isFullModel()) {
      serializeFullModel();
    } else if (cimDatasetGraph.isDifferenceModel()) {
      serializeDifferenceModel();
    } else {
      throw new RiotException(
          "Dataset must be either a FullModel or a DifferenceModel!"
      );
    }
    xmlStreamWriter.writeStartDocument();
    for (Entry<String, String> entry : prefixMap.getMapping().entrySet()) {
      xmlStreamWriter.setPrefix(entry.getKey(), entry.getValue());
    }
    xmlStreamWriter.setPrefix("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
    xmlStreamWriter.writeStartElement("rdf", "RDF", null);
    xmlStreamWriter.writeNamespace("rdf", "RDF");
    xmlStreamWriter.writeEndElement();
    xmlStreamWriter.writeEndDocument();
  }

  private void serializeFullModel() {

  }

  private void serializeDifferenceModel() {

  }
}

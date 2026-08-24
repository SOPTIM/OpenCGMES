package de.soptim.opencgmes.cimxml.writer;

import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.stream.XMLStreamWriter;

/**
 * IEC 61970-552 CIMXML writer for OpenCGMES.
 *
 * <p>This writer creates a Common Information Model (CIM) XML file for a {@link CimDatasetGraph}
 * and writes it into a {@link Writer}, an {@link OutputStream} or to a {@link Path}.
 * It handles special features unique to CIMXML such as:
 *
 * <ul>
 *   <li>Replacing urn:uuid: with underscores</li>
 *   <li>Removing datatype information from the output file</li>
 *   <li>Support for FullModel and DifferenceModel structures</li>
 * </ul>
 * This implementation uses StAX via {@link XMLStreamWriter}.
 *
 * <p>Optionally, the output may also be sorted.
 *
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // Create writer
 * CimXmlWriter writer = new CimXmlWriter();
 *
 * // Write a sorted CIMXML model
 * writer.writeModel(Path.of("model.xml"), dataset, true);
 * }</pre>
 *
 * @see CimDatasetGraph
 * @see <a href="https://webstore.iec.ch/publication/25939">IEC 61970-552 Standard</a>
 */
public class CimXmlWriter {

  private final WriterCimXmlStaxSr writer;

  private static final int MAX_BUFFER_SIZE = 64 * 4096;

  /**
   * Creates a new CimXmlWriter.
   */
  public CimXmlWriter() {
    this.writer = new WriterCimXmlStaxSr();
  }

  /**
   * Writes CIMXML for the given CIM Dataset Graph to the given Writer.
   *
   * @param writer          the Writer receiving the CIMXML
   * @param cimDatasetGraph the input CIM Dataset Graph
   * @param sorted          whether the resulting CIMXML should be sorted. This may impact
   *                        performance
   */
  public void writeCimModel(final Writer writer, final CimDatasetGraph cimDatasetGraph,
      final boolean sorted) {
    this.writer.write(writer, cimDatasetGraph, null, sorted);
  }

  /**
   * Writes CIMXML for the given CIM Dataset Graph to the given OutputStream.
   *
   * @param outputStream    the OutputStream receiving the CIMXML
   * @param cimDatasetGraph the input CIM Dataset Graph
   * @param sorted          whether the resulting CIMXML should be sorted. This may impact
   *                        performance
   */
  public void writeCimModel(final OutputStream outputStream, final CimDatasetGraph cimDatasetGraph,
      final boolean sorted) {
    this.writer.write(outputStream, cimDatasetGraph, null, sorted);
  }

  /**
   * Writes CIMXML for the given CIM Dataset Graph to the given Path.
   *
   * @param resultFilePath  the filePath of the resulting CIMXML file
   * @param cimDatasetGraph the input CIM Dataset Graph
   * @param sorted          whether the resulting CIMXML should be sorted. This may impact
   *                        performance
   */
  public void writeCimModel(final Path resultFilePath, final CimDatasetGraph cimDatasetGraph,
      final boolean sorted) throws IOException {
    try (BufferedOutputStream outputStream = new BufferedOutputStream(
        Files.newOutputStream(resultFilePath), MAX_BUFFER_SIZE)) {
      this.writer.write(outputStream, cimDatasetGraph, null, sorted);
    }
  }
}

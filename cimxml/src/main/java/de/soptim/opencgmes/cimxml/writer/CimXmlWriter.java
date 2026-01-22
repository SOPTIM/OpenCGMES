package de.soptim.opencgmes.cimxml.writer;

import de.soptim.opencgmes.cimxml.sparql.core.CimDatasetGraph;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class CimXmlWriter {

  private final WriterCIMXML_StAX_SR writer;

  private static final int MAX_BUFFER_SIZE = 64 * 4096;

  /**
   * Creates a new CimXmlWriter.
   */
  public CimXmlWriter() {
    this.writer = new WriterCIMXML_StAX_SR();
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

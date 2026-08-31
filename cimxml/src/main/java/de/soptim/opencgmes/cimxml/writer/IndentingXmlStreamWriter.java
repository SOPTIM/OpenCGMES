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

package de.soptim.opencgmes.cimxml.writer;

import javax.xml.namespace.NamespaceContext;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.codehaus.stax2.XMLStreamWriter2;

/**
 * Wrapper for a {@link XMLStreamWriter2} that will addindentation to the output of the given
 * writer.
 * Uses the same general rules as {@code com.sun.xml.txw2.output.IndentingXMLStreamWriter} with
 * a few minor differences:
 *
 * <ul>
 *   <li>Does not use a stack to keep track of the elements seen, as it is unnecessary.</li>
 *   <li>Adds a new line after XML Processing Instructions.</li>
 *   <li>Uses {@link XMLStreamWriter2#writeRaw(char[], int, int)} to write whitespace
 *   as a small performance improvement.</li>
 *   <li>Only does a single write of a preinstantiated buffer per line.</li>
 * </ul>
 *
 * {@code com.sun.xml.txw2.output.IndentingXMLStreamWriter}, which this implementation is based on,
 * is licensed under the <a href="https://www.eclipse.org/org/documents/edl-v10/">Eclipse Distribution License - v 1.0</a>.
 */
public class IndentingXmlStreamWriter implements XMLStreamWriter {

  private static final int SEEN_NOTHING = 0;
  private static final int SEEN_ELEMENT = 1;
  private static final int SEEN_DATA = 2;

  /**
   * Depth for which the indent array will be initialized.
   */
  private static final int INITIAL_DEPTH_CAPACITY = 16;
  private static final int DEFAULT_INDENT_SIZE = 2;
  public static final String LINE_BREAK = "\n";

  private final XMLStreamWriter2 wrapped;
  private final int indentSize;

  private int depth = 0;
  private int state = SEEN_NOTHING;

  /**
   * Array to create indents with without recreating the string each time.
   */
  private char[] indent;

  /**
   * Creates an IndentingXmlStreamWriter with the default indent size {@value DEFAULT_INDENT_SIZE}.
   *
   * @param wrapped the {@link XMLStreamWriter2} wrapped by the IndentingXmlStreamWriter
   */
  public IndentingXmlStreamWriter(XMLStreamWriter2 wrapped) {
    this(wrapped, DEFAULT_INDENT_SIZE);
  }

  /**
   * Creates an IndentingXmlStreamWriter.
   *
   * @param wrapped the {@link XMLStreamWriter2} wrapped by the IndentingXmlStreamWriter
   * @param indentSize the amount of spaces used for indentation
   *
   * @throws IllegalArgumentException if the indentSize is negative
   */
  public IndentingXmlStreamWriter(XMLStreamWriter2 wrapped, int indentSize) {
    if (indentSize < 0) {
      throw new IllegalArgumentException("indentWidth must not be negative: " + indentSize);
    }
    this.wrapped = wrapped;
    this.indentSize = indentSize;
    this.indent = newIndentBuffer(indentSize * INITIAL_DEPTH_CAPACITY);
  }

  private static char[] newIndentBuffer(int length) {
    char[] buffer = new char[length];
    java.util.Arrays.fill(buffer, ' ');
    return buffer;
  }

  private void doIndent() throws XMLStreamException {
    int length = indentSize * depth;
    if (length > indent.length) {
      indent = newIndentBuffer(indent.length * 2); // double the size of the indent buffer
    }
    wrapped.writeRaw(indent, 0, length);
  }

  private void onStartElement() throws XMLStreamException {
    state = SEEN_NOTHING;
    if (depth > 0) {
      wrapped.writeRaw(LINE_BREAK);
    }
    doIndent();
    depth++;
  }

  private void onEndElement() throws XMLStreamException {
    depth--;
    if (state == SEEN_ELEMENT) {
      wrapped.writeRaw(LINE_BREAK);
      doIndent();
    }
    state = SEEN_ELEMENT;
  }

  private void onEmptyElement() throws XMLStreamException {
    state = SEEN_ELEMENT;
    if (depth > 0) {
      wrapped.writeRaw(LINE_BREAK);
    }
    doIndent();
  }

  // region Elements

  @Override
  public void writeStartElement(String localName) throws XMLStreamException {
    onStartElement();
    wrapped.writeStartElement(localName);
  }

  @Override
  public void writeStartElement(String namespaceUri, String localName) throws XMLStreamException {
    onStartElement();
    wrapped.writeStartElement(namespaceUri, localName);
  }

  @Override
  public void writeStartElement(String prefix, String localName, String namespaceUri)
      throws XMLStreamException {
    onStartElement();
    wrapped.writeStartElement(prefix, localName, namespaceUri);
  }

  @Override
  public void writeEmptyElement(String namespaceUri, String localName) throws XMLStreamException {
    onEmptyElement();
    wrapped.writeEmptyElement(namespaceUri, localName);
  }

  @Override
  public void writeEmptyElement(String prefix, String localName, String namespaceUri)
      throws XMLStreamException {
    onEmptyElement();
    wrapped.writeEmptyElement(prefix, localName, namespaceUri);
  }

  @Override
  public void writeEmptyElement(String localName) throws XMLStreamException {
    onEmptyElement();
    wrapped.writeEmptyElement(localName);
  }

  @Override
  public void writeEndElement() throws XMLStreamException {
    onEndElement();
    wrapped.writeEndElement();
  }

  // endregion

  // region Data

  @Override
  public void writeCData(String data) throws XMLStreamException {
    state = SEEN_DATA;
    wrapped.writeCData(data);
  }

  @Override
  public void writeCharacters(String text) throws XMLStreamException {
    state = SEEN_DATA;
    wrapped.writeCharacters(text);
  }

  @Override
  public void writeCharacters(char[] text, int start, int len) throws XMLStreamException {
    state = SEEN_DATA;
    wrapped.writeCharacters(text, start, len);
  }

  // endregion

  // region Linebreaks only

  @Override
  public void writeStartDocument() throws XMLStreamException {
    wrapped.writeStartDocument();
    wrapped.writeRaw(LINE_BREAK);
  }

  @Override
  public void writeStartDocument(String version) throws XMLStreamException {
    wrapped.writeStartDocument(version);
    wrapped.writeRaw(LINE_BREAK);
  }

  @Override
  public void writeStartDocument(String encoding, String version) throws XMLStreamException {
    wrapped.writeStartDocument(encoding, version);
    wrapped.writeRaw(LINE_BREAK);
  }

  @Override
  public void writeProcessingInstruction(String target) throws XMLStreamException {
    wrapped.writeProcessingInstruction(target);
    wrapped.writeRaw(LINE_BREAK);
  }

  @Override
  public void writeProcessingInstruction(String target, String data) throws XMLStreamException {
    wrapped.writeProcessingInstruction(target, data);
    wrapped.writeRaw(LINE_BREAK);
  }

  // endregion

  // region Passthrough

  @Override
  public void writeEndDocument() throws XMLStreamException {
    wrapped.writeEndDocument();
  }

  @Override
  public void close() throws XMLStreamException {
    wrapped.close();
  }

  @Override
  public void flush() throws XMLStreamException {
    wrapped.flush();
  }

  @Override
  public void writeAttribute(String localName, String value) throws XMLStreamException {
    wrapped.writeAttribute(localName, value);
  }

  @Override
  public void writeAttribute(String prefix, String namespaceUri, String localName, String value)
      throws XMLStreamException {
    wrapped.writeAttribute(prefix, namespaceUri, localName, value);
  }

  @Override
  public void writeAttribute(String namespaceUri, String localName, String value)
      throws XMLStreamException {
    wrapped.writeAttribute(namespaceUri, localName, value);
  }

  @Override
  public void writeNamespace(String prefix, String namespaceUri) throws XMLStreamException {
    wrapped.writeNamespace(prefix, namespaceUri);
  }

  @Override
  public void writeDefaultNamespace(String namespaceUri) throws XMLStreamException {
    wrapped.writeDefaultNamespace(namespaceUri);
  }

  @Override
  public void writeComment(String data) throws XMLStreamException {
    wrapped.writeComment(data);
  }

  @Override
  public void writeDTD(String dtd) throws XMLStreamException {
    wrapped.writeDTD(dtd);
  }

  @Override
  public void writeEntityRef(String name) throws XMLStreamException {
    wrapped.writeEntityRef(name);
  }

  @Override
  public String getPrefix(String uri) throws XMLStreamException {
    return wrapped.getPrefix(uri);
  }

  @Override
  public void setPrefix(String prefix, String uri) throws XMLStreamException {
    wrapped.setPrefix(prefix, uri);
  }

  @Override
  public void setDefaultNamespace(String uri) throws XMLStreamException {
    wrapped.setDefaultNamespace(uri);
  }

  @Override
  public void setNamespaceContext(NamespaceContext context) throws XMLStreamException {
    wrapped.setNamespaceContext(context);
  }

  @Override
  public NamespaceContext getNamespaceContext() {
    return wrapped.getNamespaceContext();
  }

  @Override
  public Object getProperty(String name) throws IllegalArgumentException {
    return wrapped.getProperty(name);
  }

  // endregion
}

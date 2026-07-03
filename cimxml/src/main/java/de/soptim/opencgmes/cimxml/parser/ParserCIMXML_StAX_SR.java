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

package de.soptim.opencgmes.cimxml.parser;

import de.soptim.opencgmes.cimxml.CimHeaderVocabulary;
import de.soptim.opencgmes.cimxml.CimXmlDocumentContext;
import de.soptim.opencgmes.cimxml.graph.CimNamespaceFactoryRegistry;
import de.soptim.opencgmes.cimxml.parser.system.StreamCimXml;
import de.soptim.opencgmes.cimxml.rdfs.CimProfileRegistry;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.atlas.lib.Cache;
import org.apache.jena.atlas.lib.CacheFactory;
import org.apache.jena.atlas.lib.EscapeStr;
import org.apache.jena.atlas.lib.Lib;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.datatypes.xsd.impl.XMLLiteralType;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.iri3986.provider.IRIProvider3986;
import org.apache.jena.irix.IRIException;
import org.apache.jena.irix.IRIx;
import org.apache.jena.irix.SystemIRIx;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.lang.rdfxml.RDFXMLParseException;
import org.apache.jena.riot.system.ErrorHandler;
import org.apache.jena.riot.system.FactoryRDF;
import org.apache.jena.riot.system.FactoryRDFCaching;
import org.apache.jena.riot.system.SyntaxLabels;
import org.apache.jena.sparql.graph.NodeConst;
import org.apache.jena.sys.JenaSystem;
import org.apache.jena.util.XML11Char;
import org.apache.jena.vocabulary.RDF;
import org.codehaus.stax2.XMLStreamReader2;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.Pattern;

import static javax.xml.stream.XMLStreamConstants.*;
import static org.apache.jena.riot.SysRIOT.fmtMessage;


/**
 * This implementation is based on the RDF/XML parser ParserRRX_StAX_SR in Apache Jena, originally.
 * It has been adapted to handle CIMXML specifics.
 */
@SuppressWarnings("PMD")
public class ParserCIMXML_StAX_SR {

    static {
        JenaSystem.init();
        if (!(SystemIRIx.getProvider() instanceof IRIProvider3986)) {
            Lib.setenv(SystemIRIx.sysPropertyProvider, "IRI3986");
            SystemIRIx.reset();
        }
    }

    private static final int IRI_CACHE_SIZE = 8192;
    private final IndentedWriter trace;

    private final XMLStreamReader2 xmlSource;

    private Cache<String, IRIx> iriCacheForBaseNull = null;
    private Cache<String, IRIx> currentIriCache = null;
    private final Map<IRIx, Cache<String, IRIx>> mapBaseIriToCache = new HashMap<>();
    private final FactoryRDF factoryRDF = new FactoryRDFCaching(IRI_CACHE_SIZE, SyntaxLabels.createLabelToNode());

    // Constants
    private static final String rdfNS = RDF.uri;
    private static final String xmlNS = "http://www.w3.org/XML/1998/namespace";
    private static final String xmlBaseForCIMXML = "urn:uuid:";

    private static final Pattern CIM_UUID_PATTERN = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern CIM_UUID_PATTERN_NO_DASHES = Pattern.compile("^[0-9a-f]{8}[0-9a-f]{4}[0-9a-f]{4}[0-9a-f]{4}[0-9a-f]{12}$");

    private boolean hasRDF = false;
    private boolean hasCimXmlNamespace = false;
    private boolean isCimXmlModel = false;
    private String versionOfCIMXML = null;

    private final ErrorHandler errorHandler;
    private final StreamCimXml destination;
    private final CimProfileRegistry cimProfileRegistry;
    private static final String PROCESSING_INSTRUCTION_IEC61970_552 = "iec61970-552";

    private void updateCurrentIriCacheForCurrentBase() {
        if (currentBase != null) {
            currentIriCache = mapBaseIriToCache
                    .computeIfAbsent(currentBase,
                            b -> CacheFactory.createSimpleCache(IRI_CACHE_SIZE)
                    );
        } else {
            if (iriCacheForBaseNull == null) {
                iriCacheForBaseNull = CacheFactory.createSimpleCache(IRI_CACHE_SIZE);
            }
            currentIriCache = iriCacheForBaseNull;
        }
    }

    private boolean isDifferentFromCurrentBase(IRIx base) {
        if (currentBase != null) {
            return !currentBase.equals(base);
        } else return base != null;
    }

    private record BaseLang(IRIx base, String lang, Cache<String, IRIx> iriCache) {}
    private final Deque<BaseLang> stack = new ArrayDeque<>();
    // Just these operations:

    private void pushFrame(IRIx base, String lang) {
        BaseLang frame = new BaseLang(currentBase, currentLang, currentIriCache);
        stack.push(frame);
        currentLang = lang;
        if (isDifferentFromCurrentBase(base)) {
            currentBase = base;
            updateCurrentIriCacheForCurrentBase();
        }
    }

    private void popFrame() {
        BaseLang frame = stack.pop();
        currentLang = frame.lang;
        if (isDifferentFromCurrentBase(frame.base)) {
            currentBase = frame.base;
            currentIriCache = frame.iriCache;
        }
    }

    /** Mark the usage of a QName */
    private enum QNameUsage {
        TypedNodeElement("typed node element"), PropertyElement("property element");
        final String msg;
        QNameUsage(String msg) { this.msg = msg; }
    }

    // ---- Error handlers

    private RiotException RDFXMLparseError(String message) {
        return RDFXMLparseError(message, location());
    }

    private void RDFXMLparseWarning(String message) {
        RDFXMLparseWarning(message, location());
    }

    private RiotException RDFXMLparseError(String message, Location location) {
        if ( location == null ) {
            errorHandler.error(message, -1, -1);
            // The error handler normally throws an exception - for RDF/XML parsing it is required.
            return new RiotException(fmtMessage(message, -1, -1)) ;
        }

        errorHandler.error(message, location.getLineNumber(), location.getColumnNumber());
        // The error handler normally throws an exception - for RDF/XML parsing it is required.
        return new RiotException(fmtMessage(message, location.getLineNumber(), location.getColumnNumber()));
    }

    private void RDFXMLparseWarning(String message, Location location) {
        if ( location != null )
            errorHandler.warning(message, location.getLineNumber(), location.getColumnNumber());
        else
            errorHandler.warning(message, -1, -1);
    }

    // Tracking for ID on nodes (not reification usage)
    // We limit the number of local fragment IDs tracked because map only grows.
    // A base URI may be re-introduced so this isn't nested scoping.
    private int countTrackingIDs = 0;
    private final Map<IRIx, Map<String,  Location>> trackUsedIDs = new HashMap<>();
    private Location previousUseOfID(String idStr, Location location) {
        final Map<String, Location> scope = trackUsedIDs.computeIfAbsent(currentBase, k->new HashMap<>());
        Location prev = scope.get(idStr);
        if ( prev != null )
            return prev;
        if ( countTrackingIDs > 10000 )
            return null;
        scope.put(idStr, location);
        countTrackingIDs++;
        return null;
    }

    // Collecting characters does not need to be a stack because there are
    // no nested objects while gathering characters for lexical or XMLLiterals.
    private final StringBuilder accCharacters = new StringBuilder(100);

    private IRIx currentBase;
    private String currentLang = null;
    private Map<Node, CimProfileRegistry.PropertyInfo> currentDataTypeMap = null;
    private Set<Node> currentListOfPropertiesNotInProfile = null;
    private Set<Node> currentCimProfiles = null;

    /** Integer holder for rdf:li */
    private static class Counter { int value = 1; }

    public ParserCIMXML_StAX_SR(XMLStreamReader2 reader, CimProfileRegistry cimProfileRegistry, String xmlBase,
                                StreamCimXml destination, ErrorHandler errorHandler) {
        // Debug
        IndentedWriter out = IndentedWriter.stdout.clone();
        out.setFlushOnNewline(true);
        out.setUnitIndent(4);
        out.setLinePrefix("# ");
        this.trace = out;
        // Debug

        this.xmlSource = reader;
        this.errorHandler = errorHandler;
        if ( xmlBase != null ) {
            this.currentBase = IRIx.create(xmlBase);
        } else {
            this.currentBase = null;
        }
        updateCurrentIriCacheForCurrentBase();
        this.currentLang = "";
        this.destination = destination;
        this.cimProfileRegistry = cimProfileRegistry;
    }

    // CIMXML model header constants.
    private static final QName mdFullModel = new QName(CimHeaderVocabulary.NS_MD, CimHeaderVocabulary.CLASSNAME_FULL_MODEL);
    private static final QName dmDifferenceModel = new QName(CimHeaderVocabulary.NS_DM, CimHeaderVocabulary.CLASSNAME_DIFFERENCE_MODEL);

    // CIMXML difference model constants.
    private static final QName dmForwardDifferences = new QName(CimHeaderVocabulary.NS_DM, CimHeaderVocabulary.TAG_NAME_FORWARD_DIFFERENCES);
    private static final QName dmReverseDifferences = new QName(CimHeaderVocabulary.NS_DM, CimHeaderVocabulary.TAG_NAME_REVERSE_DIFFERENCES);
    private static final QName dmPreconditions = new QName(CimHeaderVocabulary.NS_DM, CimHeaderVocabulary.TAG_NAME_PRECONDITIONS);

    private static final QName rdfRDF = new QName(rdfNS, "RDF");
    private static final QName rdfDescription = new QName(rdfNS, "Description");
    private static final QName rdfID = new QName(rdfNS, "ID");
    private static final QName rdfNodeID = new QName(rdfNS, "nodeID");
    private static final QName rdfAbout = new QName(rdfNS, "about");
    private static final QName rdfType = new QName(rdfNS, "type");

    private static final QName rdfContainerItem = new QName(rdfNS, "li");
    private static final QName rdfDatatype = new QName(rdfNS, "datatype");
    private static final QName rdfParseType = new QName(rdfNS, "parseType");
    private static final QName rdfResource = new QName(rdfNS, "resource");
    private static final QName rdfVersion = new QName(rdfNS, "version");

    private static final QName rdfAboutEach = new QName(rdfNS, "aboutEach");
    private static final QName rdfAboutEachPrefix = new QName(rdfNS, "aboutEachPrefix");
    private static final QName rdfBagID = new QName(rdfNS, "bagID");

    private static final QName xmlQNameBase = new QName(XMLConstants.XML_NS_URI, "base");
    private static final QName xmlQNameLang = new QName(XMLConstants.XML_NS_URI, "lang");
    // xml:space is a now-deprecated XML attribute that related to handing
    // whitespace characters inside elements. Skip it.
    private static final QName xmlQNameSpace = new QName(XMLConstants.XML_NS_URI, "space");

    private static final String parseTypeCollection    = "Collection";
    private static final String parseTypeLiteral       = "Literal";
    private static final String parseTypeLiteralAlt    = "literal";
    private static final String parseTypeLiteralStmts  = "Statements";    // CIM GitHub issue 2473
    private static final String parseTypeResource      = "Resource";
    // This is a dummy parseType for when there is no given rdf:parseType.
    private static final String parseTypePlain = "$$";

    // Grammar productions.
    // 6.2.2 Production coreSyntaxTerms
    // rdf:RDF | rdf:ID | rdf:about | rdf:parseType | rdf:resource | rdf:nodeID | rdf:datatype
    private static final Set<QName> $coreSyntaxTerms =
            Set.of(rdfRDF, rdfID, rdfAbout, rdfParseType, rdfResource, rdfNodeID, rdfDatatype);

    // 6.2.3 Production syntaxTerms
    // coreSyntaxTerms | rdf:Description | rdf:li
    private static final Set<QName> $syntaxTerms =
            Set.of(rdfRDF, rdfID, rdfAbout, rdfParseType, rdfResource, rdfNodeID, rdfDatatype,
                   rdfDescription, rdfContainerItem);

    // 6.2.4 Production oldTerms
    // rdf:aboutEach | rdf:aboutEachPrefix | rdf:bagID
    private static final Set<QName> $oldTerms = Set.of(rdfAboutEach, rdfAboutEachPrefix, rdfBagID);

    private static final Set<String> $allowedUnqualified =
            Set.of(rdfAbout.getLocalPart(), rdfID.getLocalPart(), rdfResource.getLocalPart(),
                   rdfParseType.getLocalPart(), rdfType.getLocalPart());

    private boolean coreSyntaxTerm(QName qName) {
        if ( ! rdfNS.equals(qName.getNamespaceURI()) )
            return false;
        return $coreSyntaxTerms.contains(qName);
    }

    // 6.2.5 Production nodeElementURIs
    // anyURI - ( coreSyntaxTerms | rdf:li | oldTerms )
    private static boolean allowedNodeElementURIs(QName qName) {
        if ( ! rdfNS.equals(qName.getNamespaceURI()) )
            return true;
        if ( $coreSyntaxTerms.contains(qName) )
            return false;
        if ( rdfContainerItem.equals(qName) )
            return false;
        if ( $oldTerms.contains(qName) )
            return false;
        return true;
    }

    // 6.2.6 Production propertyElementURIs
    // anyURI - ( coreSyntaxTerms | rdf:Description | oldTerms )
    private static boolean allowedPropertyElementURIs(QName qName) {
        if ( ! rdfNS.equals(qName.getNamespaceURI()) )
            return true;
        if ( $coreSyntaxTerms.contains(qName) )
            return false;
        if ( rdfDescription.equals(qName) )
            return false;
        if ( $oldTerms.contains(qName) )
            return false;
        return true;
    }

    // 6.2.7 Production propertyAttributeURIs
    // anyURI - ( coreSyntaxTerms | rdf:Description | rdf:li | oldTerms )
    private static boolean allowedPropertyAttributeURIs(QName qName) {
        if ( ! rdfNS.equals(qName.getNamespaceURI()) )
            return true;
        if ( $coreSyntaxTerms.contains(qName) )
            return false;
        if ( rdfDescription.equals(qName) )
            return false;
        if ( rdfContainerItem.equals(qName) )
            return false;
        if ( $oldTerms.contains(qName) )
            return false;
        return true;
    }

    private static boolean allowedUnqualifiedTerm(String localName) {
        return $allowedUnqualified.contains(localName);
    }

    /** The attributes that guide the RDF/XML parser. */
    private static final Set<QName> $rdfSyntaxAttributes =
            Set.of(rdfRDF, rdfAbout, rdfNodeID, rdfID, rdfParseType, rdfDatatype, rdfResource);

    private static boolean isSyntaxAttribute(QName qName) {
        return $rdfSyntaxAttributes.contains(qName);
    }

    // xml:space is a now-deprecated XML attribute that related to handing
    // whitespace characters inside elements.
    private static final Set<QName> $xmlReservedTerms = Set.of(xmlQNameBase, xmlQNameLang, xmlQNameSpace);

    /** Recognized XML namespace Qname */
    private static boolean isXMLQName(QName qName) {
        return $xmlReservedTerms.contains(qName);
    }

    private static boolean isXMLNamespace(QName qName) {
        return xmlNS.equals(qName.getNamespaceURI());
    }

    // start: whole doc
    //   6.2.8 Production doc
    //   6.2.11 Production nodeElement
    // doc or production nodeElement.
    // start:
    //  6.2.9 Production RDF
    void parse() {

        int eventType = nextEventAny();

        // XMLStreamReader does not generate "START_DOCUMENT"
        // The first event is DTD or START_ELEMENT.
        // XMLEventReader does generate a START_DOCUMENT

        if ( lookingAt(eventType, END_DOCUMENT) )
            throw RDFXMLparseError("Empty document", location());

        if ( lookingAt(eventType, DTD) )
            eventType = nextEventTag();

        // rdf:RDF or rdf:Description.
        if ( ! lookingAt(eventType, START_ELEMENT) )
            throw RDFXMLparseError("Not a start element: "+strEventType(eventType), location());

        boolean hasFrame = false;

        // <rdf:RDF>
        if ( qNameMatches(rdfRDF, qName()) ) {
            if ( ReaderCIMXML_StAX_SR.TRACE )
                trace.println("rdf:RDF");
            // Not necessary to track this element when parsing.
            hasFrame = startElement();
            // Only the XML base and namespaces that apply throughout rdf:RDF are parser output.
            emitInitialBaseAndNamespacesDetermineCimNamespaceAndSetBaseIfNeeded();
            hasRDF = true;
            eventType = nextEventTag();
        }

        incIndent();
        if ( hasRDF )
            nodeElementLoop(eventType);
        else
            nodeElementSingle(eventType);
        decIndent();

        // Possible </rdf:RDF>
        if ( hasRDF ) {
            endElement(hasFrame);
            if ( ReaderCIMXML_StAX_SR.TRACE )
                trace.println("/rdf:RDF");
            eventType = nextEventAny();
        }
        // Now past <rdf:RDF...></rdf:RDF>
        while ( isWhitespace(eventType) )
            eventType = nextEventAny();
    }

    // ---- Node elements

    /**
     * Top level node element loop inside &lt;rdf:RDF&gt;, &lt;/rdf:RDF&gt;.
     * This is zero or more node elements.
     */
    private void nodeElementLoop(int eventType) {
        // There was an rdf:RDF
        // ---- Node Loop - sequence of zero or more RDF/XML node elements.
        // generic move over multiple elements
        while ( eventType >= 0 ) {
            if ( ! lookingAt(eventType, START_ELEMENT) )
                break;
            nodeElement();
            eventType = nextEventTag();
        }
    }

    /** Top level single node element. (no &lt;rdf:RDF&gt;, &lt;/rdf:RDF&gt;) */
    private void nodeElementSingle(int eventType) {
        if ( ! lookingAt(eventType, START_ELEMENT) )
            return ;
        nodeElement();
    }

    /**
     * One node element.
     * On entry, the start of node element has been read.
     * On exit, have read the end tag for the node element.
     */
    private void nodeElement() {
        nodeElement(null);
    }

    /**
     * Process one node element. The subject may have been determined;
     * this is needed for nested node elements.
     */
    private void nodeElement(Node subject) {
        QName qName = qName();

        if ( ReaderCIMXML_StAX_SR.TRACE )
            trace.println(">> nodeElement: "+str(location())+" "+str(qName));

        if ( ! allowedNodeElementURIs(qName) )
            throw RDFXMLparseError("Not allowed as a node element tag: '"+str(qName)+"'");


        // rdf:resource not allowed on a node element
        String rdfResourceStr = attribute(rdfResource);
        if ( rdfResourceStr != null )
            throw RDFXMLparseError("rdf:resource not allowed as attribute here: "+str(qName));

        incIndent();
        boolean hasFrame = startElement();
        if ( subject == null )
            subject = attributesToSubjectNode();
        nodeElementProcess(subject);
        endElement(hasFrame);
        decIndent();

        if ( ReaderCIMXML_StAX_SR.TRACE )
            trace.println("<< nodeElement: "+str(location())+" "+strEventType(eventType()));
    }

    private void nodeElementProcess(Node subject) {
        QName qName = qName();
        Location location = location();
        var isFullModel = false;
        if ( ! qNameMatches(qName, rdfDescription) ) {
            // Typed Node Element
            if ( isMemberProperty(qName) )
                RDFXMLparseWarning(str(qName)+" is being used on a typed node");
            else {
                if ( isNotRecognizedRDFtype(qName) )
                    RDFXMLparseWarning(str(qName)+" is not a recognized RDF term for a type");
            }
            if ( hasCimXmlNamespace && !isCimXmlModel /*found no FullModel or DifferenceModel yet*/ ) {
                // Switch context if FullModel or DifferenceModel
                if ( qNameMatches(qName, mdFullModel) ) {
                    destination.setCurrentContext(CimXmlDocumentContext.fullModel);
                    isFullModel = true;
                    isCimXmlModel = true;
                }
                else if ( qNameMatches(qName, dmDifferenceModel) ) {
                    destination.setCurrentContext(CimXmlDocumentContext.differenceModel);
                    isCimXmlModel = true;
                }

                if (isCimXmlModel) {
                    if (cimProfileRegistry == null) {
                        RDFXMLparseWarning("No CimProfileRegistry has been provided, so missing datatypes in CIMXML cannot be resolved.", location);
                    } else {
                        currentDataTypeMap = cimProfileRegistry.getHeaderPropertiesAndDatatypes(versionOfCIMXML);
                        if (currentDataTypeMap.isEmpty()) {
                            RDFXMLparseWarning("No header profile has been registered for CIM version " + versionOfCIMXML, location);
                        }
                        currentListOfPropertiesNotInProfile = new HashSet<>();
                    }
                }
            }
            Node object = qNameToIRI(qName, QNameUsage.TypedNodeElement, location);
            emit(subject, NodeConst.nodeRDFType, object, location);
        }

        processPropertyAttributes(subject, qName, false, location);

        // Finished with the node start tag.
        int event = nextEventTag();
        event = propertyElementLoop(subject, event);

        if ( ! lookingAt(event, END_ELEMENT) )
            throw RDFXMLparseError("Expected end element for "+qName());

        if (isCimXmlModel && isFullModel) {
            destination.setCurrentContext(CimXmlDocumentContext.body);
            initCimModelHeaderCurrentProfileAndCurrentDatatypeMap(location);
        }
    }

    private void initCimModelHeaderCurrentProfileAndCurrentDatatypeMap(Location location) {
        final var cimModelHeader = destination.getModelHeader();
        if (cimModelHeader == null) {
            RDFXMLparseWarning("No model header has been found in CIMXML.", location);
            return;
        }

        currentCimProfiles = cimModelHeader.getProfiles();
        if (currentCimProfiles == null || currentCimProfiles.isEmpty()) {
            RDFXMLparseWarning("No profile IRIs have been found in the CIMXML model header", location);
            return;
        }

        if (cimProfileRegistry == null) {
            return;
        }

        if (!currentCimProfiles.iterator().next().isURI()) {
            RDFXMLparseWarning("The profiles the ModelHeader are not URIs. Most likely no FileHeaderProfile has been provided. Converting them to URI-Nodes.", location);

            Set<Node> uriProfiles = new HashSet<>();
            for(var profile : currentCimProfiles) {
                uriProfiles.add(NodeFactory.createURI(profile.getLiteralLexicalForm()));
            }
            currentCimProfiles = uriProfiles;
        }
        currentListOfPropertiesNotInProfile = new HashSet<>();
        currentDataTypeMap = cimProfileRegistry.getPropertiesAndDatatypes(currentCimProfiles);
        if (currentDataTypeMap == null) {
            RDFXMLparseWarning("The profiles in the model header could not be found in the CimProfileRegistry. Profiles: " + currentCimProfiles.toString(), location);
        }
    }

    // ---- Property elements

    private int propertyElementLoop(Node subject, int event) {
        Counter listElementCounter = new Counter();
        while (lookingAt(event, START_ELEMENT)) {
            propertyElement(subject, listElementCounter, location());
            event = nextEventTag();
        }
        return event;
    }

    /**
     * Property elements.
     * On entry, the start of property has been inspected but not consumed.
     */
    private void propertyElement(Node subject, Counter listElementCounter, Location location) {
        // Pass in location?
        if ( ReaderCIMXML_StAX_SR.TRACE )
            trace.println(">> propertyElement: "+str(location())+" "+str(qName()));

        incIndent();
        boolean hasFrame = startElement();
        QName qName = qName();

        if ( ! allowedPropertyElementURIs(qName) )
            throw RDFXMLparseError("QName not allowed for property: "+str(qName));

        if ( isNotRecognizedRDFproperty(qName) )
            RDFXMLparseWarning(str(qName)+" is not a recognized RDF property");

        propertyElementProcess(subject, qName, listElementCounter, location);
        endElement(hasFrame);
        decIndent();

        if ( ReaderCIMXML_StAX_SR.TRACE )

            trace.println("<< propertyElement: "+str(location())+" "+str(qName));
    }

    private void propertyElementProcess(Node subject, QName qName,
                                       Counter listElementCounter, Location location) {
        Node property;
        RDFDatatype datatypeFromCimProfile = null;

        // If there is a blank node label, the element must be empty,
        // Check NCName if blank node created
        String objBlankNodeLabel = attribute(rdfNodeID);
        String rdfResourceStr = attribute(rdfResource);
        String datatype = attribute(rdfDatatype);
        String parseType = objectParseType();

        if ( qNameMatches(rdfContainerItem, qName) )
            property = iriDirect(rdfNS+"_"+(listElementCounter.value++));
        else {
            property = qNameToIRI(qName, QNameUsage.PropertyElement, location);

            if (hasCimXmlNamespace
                    && ( parseType == parseTypePlain || !"Statements".equals(parseType) ) // reference equality with parseTypePlain is a performance optimization
                    && currentDataTypeMap != null) {
                final var propertyAndType = currentDataTypeMap.get(property);
                if (propertyAndType != null) {
                    property = propertyAndType.property(); // override to support reuse of property references across profiles
                    datatypeFromCimProfile = propertyAndType.primitiveType();
                } else {
                    if (!currentListOfPropertiesNotInProfile.contains(property)) {
                        currentListOfPropertiesNotInProfile.add(property);
                        RDFXMLparseWarning("Property '" + str(qName) + "' could not be found in current profiles. Profiles: " + currentCimProfiles , location);
                    }
                }
            }
        }

        Node reify = reifyStatement(location);
        Emitter emitter = (reify==null) ? this::emit : (s,p,o,loc)->emitReify(reify, s, p, o, loc);

        // Checking
        if ( datatype != null ) {
            if ( parseType != null && parseType != parseTypePlain )
                throw RDFXMLparseError("rdf:datatype can not be used with rdf:parseType.");
            if ( rdfResourceStr != null )
                throw RDFXMLparseError("rdf:datatype can not be used with rdf:resource.");
            if ( objBlankNodeLabel != null )
                throw RDFXMLparseError("rdf:datatype can not be used with rdf:NodeId.");
        }

        if ( rdfResourceStr != null && objBlankNodeLabel != null )
            throw RDFXMLparseError("Can't have both rdf:nodeId and rdf:resource on a property element");

        if ( rdfResourceStr != null && parseType != parseTypePlain )
            throw RDFXMLparseError("Both rdf:resource and rdf:ParseType on a property element. Only one allowed");

        if ( objBlankNodeLabel != null && parseType != parseTypePlain )
            throw RDFXMLparseError("Both rdf:NodeId and rdf:ParseType on a property element. Only one allowed");

        Node resourceObj = null;

        if ( rdfResourceStr != null )
            resourceObj = iriResolveCimAware(rdfResourceStr, location);

        if ( objBlankNodeLabel != null )
            resourceObj = blankNode(objBlankNodeLabel, location);

        Node innerSubject = processPropertyAttributes(resourceObj, qName, true, location);
        if ( resourceObj == null && innerSubject != null ) {
            emitter.emit(subject, property, innerSubject, location);
            int event = nextEventAny();
            if ( ! lookingAt(event, END_ELEMENT) )
                throw RDFXMLparseError("Expecting end element tag when using property attributes on a property element");
            return;
        }

        if ( resourceObj != null ) {
            emitter.emit(subject, property, resourceObj, location);
            // Must be an empty element.
            int event = nextEventAny();
            if ( ! lookingAt(event, END_ELEMENT) )
                throw RDFXMLparseError("Expecting end element tag when using rdf:resource or rdf:NodeId on a property.");
            return;
        }

        if (parseType != parseTypePlain) {
            String parseTypeName = parseType;
            switch (parseTypeName) {
                case parseTypeLiteralAlt -> {
                    RDFXMLparseWarning("Encountered rdf:parseType='literal'. Treated as rdf:parseType='Literal'", location());
                    parseTypeName = "Literal";
                }
                // CIM (Common Information Model) - see GitHub issue 2473
                case parseTypeLiteralStmts -> {
                    if (!hasCimXmlNamespace) {
                        RDFXMLparseWarning("Encountered rdf:parseType='Statements'. Treated as rdf:parseType='Literal'", location());
                        parseTypeName = "Literal";
                    }
                }
            }
            switch (parseTypeName) {
                case parseTypeResource -> {
                    // Implicit <rdf:Description><rdf:Description> i.e. fresh blank node
                    if (ReaderCIMXML_StAX_SR.TRACE)
                        trace.println("rdfParseType=Resource");
                    parseTypeResource(subject, property, emitter, location);
                    return;
                }
                case parseTypeLiteral -> {
                    if (ReaderCIMXML_StAX_SR.TRACE)
                        trace.println("rdfParseType=Literal");
                    parseTypeLiteral(subject, property, emitter, location);
                    return;
                }
                case parseTypeCollection -> {
                    if (ReaderCIMXML_StAX_SR.TRACE)
                        trace.println("rdfParseType=Collection");
                    parseTypeCollection(subject, property, emitter, location);
                    return;
                }
                case parseTypeLiteralStmts -> {
                    // must be CIMXML, as checked above - if not, treated as Literal
                    if (ReaderCIMXML_StAX_SR.TRACE)
                        trace.println("rdfParseType=Statements");
                    final var oldContext = destination.getCurrentContext();
                    var isDifferenceModelContainer = false;
                    if (qNameMatches(qName, dmForwardDifferences)) {
                        destination.setCurrentContext(CimXmlDocumentContext.forwardDifferences);
                        isDifferenceModelContainer = true;
                    } else if (qNameMatches(qName, dmReverseDifferences)) {
                        destination.setCurrentContext(CimXmlDocumentContext.reverseDifferences);
                        isDifferenceModelContainer = true;
                    } else if (qNameMatches(qName, dmPreconditions)) {
                        destination.setCurrentContext(CimXmlDocumentContext.preconditions);
                        isDifferenceModelContainer = true;
                    } else {
                        RDFXMLparseWarning("rdf:parseType='Statements' used on an element that is not a recognized CIMXML difference model container (forwardDifferences, reverseDifferences, preconditions). Treated as rdf:parseType='Literal'", location());
                        parseTypeLiteral(subject, property, emitter, location);
                    }
                    if ( isDifferenceModelContainer
                            && oldContext == CimXmlDocumentContext.differenceModel ) { // else already in diff model
                        initCimModelHeaderCurrentProfileAndCurrentDatatypeMap(location);
                    }
                    nodeElementLoop(nextEventTag());
                    return;
                }
                case parseTypePlain -> {
                } // The code below.
                default -> throw RDFXMLparseError("Not a legal defined rdf:parseType: " + parseType);
            }
        }

        // General read anything.
        // Can't peek, which would have allowed using getElementText.
        // We need a lookahead of 2 to see if this is a text-only element, then an end element
        // or ignorable whitespace then a nested node start element.
        int event = nextEventAny();

        // Need to see if text or nested.
        if ( lookingAt(event, CHARACTERS) ) {
            // Initial characters.
            // This may include comments. Slurp until not characters.
            accCharacters.setLength(0);

            while(lookingAt(event, CHARACTERS)) {
                String text = xmlSource.getText();
                accCharacters.append(text);
                event = nextEventAny();
            }
            if ( lookingAt(event, START_ELEMENT) ) {
                if ( ! isWhitespace(accCharacters) ) {
                    String msg = nonWhitespaceMsg(accCharacters.toString());
                    throw RDFXMLparseError("Content before node element. '"+msg+"'");
                }
                processNestedNodeElement(subject, property, emitter);
                return;
            }
            if ( lookingAt(event, END_ELEMENT) ) {
                final String lexicalForm = accCharacters.toString();
                final Location loc = location();
                final Node obj;
                // Characters - lexical form.
                if ( datatypeFromCimProfile != null && datatypeFromCimProfile != XSDDatatype.XSDstring )
                    if (datatypeFromCimProfile == XSDDatatype.XSDanyURI)
                        obj = createURI(lexicalForm);
                    else
                        obj = literalDatatype(lexicalForm, datatypeFromCimProfile);
                else if ( datatype != null )
                    obj = literalDatatype(lexicalForm, datatype);
                else if ( currentLang() != null )
                    obj = literal(lexicalForm, currentLang);
                else
                    obj = literal(lexicalForm);
                emitter.emit(subject, property, obj, loc);
                return;
            }
            throw RDFXMLparseError("Unexpected element: "+strEventType(event));

        } else if ( lookingAt(event, START_ELEMENT) ) {
            // No content before start element
            processNestedNodeElement(subject, property, emitter);
        } else if (lookingAt(event, END_ELEMENT) ) {
            emitter.emit(subject, property, NodeConst.emptyString, location);
        } else {
            throw RDFXMLparseError("Malformed property. "+strEventType(event));
        }
    }

    private Node processPropertyAttributes(Node resourceObj, QName qName, boolean isPropertyElement, Location location) {
        // Subject may not yet be decided.
        List<Integer> indexes = gatherPropertyAttributes(location);
        if ( indexes.isEmpty() )
            return null;
        if ( isPropertyElement ) {
            String parseTypeStr = objectParseType();
            if ( parseTypeStr != parseTypePlain ) {
                  // rdf:parseType found.
                  throw RDFXMLparseError("The attribute rdf:parseType is not permitted with property attributes on a property element: "+str(qName), location);
            }
        }

        Node innerSubject = (resourceObj==null) ? blankNode() : resourceObj;
        outputPropertyAttributes(innerSubject, indexes, location);
        return innerSubject;
    }

    private List<Integer> gatherPropertyAttributes(Location location) {
        int N = xmlSource.getAttributeCount();
        if ( N == 0 )
            return List.of();
        List<Integer> attributeIdx = new ArrayList<>(N);
        for ( int idx = 0 ; idx < N ; idx++ ) {
            QName qName =  xmlSource.getAttributeName(idx);
            boolean isPropertyAttribute = checkPropertyAttribute(qName, location);
            if ( isPropertyAttribute )
                attributeIdx.add(idx);
        }
        return attributeIdx;
    }

    private void outputPropertyAttributes(Node subject, List<Integer> indexes, Location location) {
        for ( int index : indexes ) {
            QName qName =  xmlSource.getAttributeName(index);
            if ( rdfType.equals(qName) ) {
                String iriStr = xmlSource.getAttributeValue(index);
                Node type = iriResolveCimAware(iriStr, location);
                emit(subject, RDF.Nodes.type, type, location);
                return;
            }
            Node property = attributeToIRI(qName, location);
            String lexicalForm =  xmlSource.getAttributeValue(index);
            Node object = literal(lexicalForm, currentLang);
            emit(subject, property, object, location);
        }
    }

    /** Return true if this is a property attribute. */
    private boolean checkPropertyAttribute(QName qName, Location location) {
        if ( isSyntaxAttribute(qName) )
            return false;

        // The default namespace (i.e. prefix "") does not apply to attributes.

        // 6.1.2 Element Event - attributes

        if (coreSyntaxTerm(qName) )
            return false;

        if ( ! allowedPropertyAttributeURIs(qName) )
            throw RDFXMLparseError("Not allowed as a property attribute: '"+str(qName)+"'");

        if ( isNotRecognizedRDFproperty(qName) )
            RDFXMLparseWarning(str(qName)+" is not a recognized RDF term for a property attribute");

        if ( isXMLQName(qName) )
            return false;

        if ( isXMLNamespace(qName) ) {
            // Unrecognized qnames in the XMLnamespace are a warning and are ignored.
            RDFXMLparseWarning("Unrecognized XML attribute: '"+str(qName)+"'");
            return false;
        }

        if ( StringUtils.isBlank(qName.getNamespaceURI()) ) {
            String localName = qName.getLocalPart();
            return checkPropertyAttributeUnqualifiedTerm(localName, location);
        }
        return true;
    }

    private boolean checkPropertyAttributeUnqualifiedTerm(String localName, Location location) {
        if ( allowedUnqualifiedTerm(localName) )
            return true;
        if ( localName.length() >= 3 ) {
            String chars3 = localName.substring(0, 3);
            if ( chars3.equalsIgnoreCase("xml") ) {
                // 6.1.2 Element Event
                // "all attribute information items with [prefix] property having no value and which
                // have [local name] beginning with xml (case independent comparison)"
                // Test: unrecognised-xml-attributes/test002.rdf
                RDFXMLparseWarning("Unrecognized XML non-namespaced attribute '"+localName+"' - ignored", location);
                return false;
            }
        }
        // 6.1.4 Attribute Event
        // "Other non-namespaced ·local-name· accessor values are forbidden."
        throw RDFXMLparseError("Non-namespaced attribute not allowed as a property attribute: '"+localName+"'", location);
    }


    private String objectParseType() {
        String parseTypeStr = attribute(rdfParseType);
        return  ( parseTypeStr != null ) ? parseTypeStr : parseTypePlain;
    }

    private void parseTypeResource(Node subject, Node property, Emitter emitter, Location location) {
        Node innerSubject = blankNode();
        emitter.emit(subject, property, innerSubject, location);
        // Move to first property
        int event = nextEventTag();
        propertyElementLoop(innerSubject, event);
    }

    private void parseTypeLiteral(Node subject, Node property, Emitter emitter, Location location) {
        String text = xmlLiteralAccumulateText();
        Node object = literalDatatype(text, XMLLiteralType.rdfXMLLiteral);
        emitter.emit(subject, property, object, location);
    }

    private static final String openStartTag = "<";
    private static final String closeStartTag = ">";
    private static final String openEndTag = "</";
    private static final String closeEndTag = ">";

    private String xmlLiteralAccumulateText() {

        // Map prefix -> URI
        Map<String, String> namespaces = Map.of();
        Deque<Map<String, String>> stackNamespaces = new ArrayDeque<>();

        // Current inscope.
        // Need to remember?
        //startElt.getNamespaceContext().

        accCharacters.setLength(0);
        StringBuilder sBuff = accCharacters;
        int event = START_ELEMENT;
        int depth = 0;
        while( event >= 0 ) {
            // Really, really any event.
            event = nextEventRaw();
            if ( lookingAt(event, START_ELEMENT) ) {
                depth++;
                incIndent();
                // -- Namespaces
                // The StAX parser has already processed the xmlns for the
                // declarations element and the setting  are in the NamespaceContext.
                stackNamespaces.push(namespaces);
                namespaces = new HashMap<>(namespaces);

                sBuff.append(openStartTag);
                QName qname = qName();
                if ( qname.getPrefix() != null && ! XMLConstants.DEFAULT_NS_PREFIX.equals(qname.getPrefix()) ) {
                    sBuff.append(qname.getPrefix());
                    sBuff.append(":");
                }
                sBuff.append(qname.getLocalPart());

                // -- namespaces
                xmlLiteralNamespaces(namespaces, sBuff);
                xmlLiteralAttributes(sBuff);

                sBuff.append(closeStartTag);

            } else if ( lookingAt(event, END_ELEMENT) ) {
                decIndent();
                depth--;
                if ( depth < 0 ) {
                    break;
                }
                namespaces = stackNamespaces.pop();
                sBuff.append(openEndTag);
                QName qname = qName();
                if ( qname.getPrefix() != null && ! XMLConstants.DEFAULT_NS_PREFIX.equals(qname.getPrefix()) ) {
                    //sBuff.append(qname.getPrefix());
                    sBuff.append(xmlSource.getPrefix());
                    sBuff.append(":");
                }
                sBuff.append(qname.getLocalPart());
                sBuff.append(closeEndTag);
            } else if ( lookingAt(event, CHARACTERS) ) {
                String s = xmlSource.getText();
                s = xmlLiteralEscapeText(s);
                sBuff.append(s);
            } else if ( lookingAt(event, COMMENT) ) {
                String commentText = xmlSource.getText();
                sBuff.append("<!--");
                sBuff.append(commentText);
                sBuff.append("-->");
            } else if ( lookingAt(event, PROCESSING_INSTRUCTION) ) {
                String target = xmlSource.getPITarget();
                String data = xmlSource.getPIData();
                accCharacters.append("<?");
                accCharacters.append(target);
                accCharacters.append(' ');
                accCharacters.append(data);
                accCharacters.append("?>");
            } else {
                throw RDFXMLparseError("Unexpected event in rdf:XMLLiteral: "+strEventType(event));
            }
        }
        String x = sBuff.toString();
        accCharacters.setLength(0);
        return x ;
    }

    private void xmlLiteralAttributes(StringBuilder sBuff) {
        // Sort attributes
        Map<String, String> attrs = new TreeMap<>();
        int N = xmlSource.getAttributeCount();
        for ( int i = 0 ; i < N ; i++ ) {
            String value = xmlSource.getAttributeValue(i);
            QName qName =  xmlSource.getAttributeName(i);
            String attrName = str(qName);
            attrs.put(attrName, value);
        }

        for ( Map.Entry<String, String> e : attrs.entrySet() ) {
            String attrQName = e.getKey();
            String attrValue = e.getValue();
            sBuff.append(" ");
            sBuff.append(attrQName);
            sBuff.append("=\"");
            sBuff.append(xmlLiteralEscapeAttr(attrValue));
            sBuff.append("\"");
        }
    }

    /**
     * Process all QNames used in this element.
     */
    private void xmlLiteralNamespaces(Map<String, String> namespaces, StringBuilder sBuff) {
        NamespaceContext nsCxt = xmlSource.getNamespaceContext();

        // Map prefix -> URI, sorted by prefix. This is only the required namespaces.
        // The other map, namespaces, is all the namespaces in scope.
        Map<String, String> outputNS = new TreeMap<>();

        xmlLiteralNamespaceQName(outputNS, namespaces, nsCxt, qName());

        int N = xmlSource.getAttributeCount();
        for ( int i = 0 ; i < N ; i++ ) {
            xmlLiteralNamespaceQName(outputNS, namespaces, nsCxt, xmlSource.getAttributeName(i));
        }
        // Output.
        for ( String prefix : outputNS.keySet() ) {
            String uri = outputNS.get(prefix);
            if ( uri == null )
                // Undefined default namespace.
                continue;
            sBuff.append(" ");
            if ( prefix.isEmpty() ) {
                sBuff.append("xmlns=\"");
                sBuff.append(uri);
                sBuff.append("\"");
            } else {
                sBuff.append("xmlns:");
                sBuff.append(prefix);
                sBuff.append("=\"");
                sBuff.append(uri);
                sBuff.append("\"");
            }
        }
    }

    /** Process one QName - insert a namespace if the prefix is not in scope as given by the namespace mapping. */
    private void xmlLiteralNamespaceQName(Map<String, String> outputNS, Map<String, String> namespaces, NamespaceContext nsCxt, QName qName) {
        String prefix = qName.getPrefix();
        String namespaceURI = nsCxt.getNamespaceURI(prefix);
        if ( namespaceURI == null ) {
            // No default in scope.
        }

        // Not seen this prefix, or it was a different value.
        if ( !"".equals(namespaceURI) &&      // this first condition is needed for woodstox and aalto to work
                (! namespaces.containsKey(prefix) ||
                 ( namespaceURI != null && ! namespaces.get(prefix).equals(namespaceURI)) )) {
            // Define in current XML subtree.
            outputNS.put(prefix, namespaceURI);
            namespaces.put(prefix, namespaceURI);
        }
    }

    /**
     * Escape text used in an XML content.
     */
    private String xmlLiteralEscapeText(CharSequence stringAcc) {
        StringBuilder sBuff = new StringBuilder();
        int len = stringAcc.length() ;
        for (int i = 0; i < len; i++) {
            char c = stringAcc.charAt(i);
            String replace = switch (c) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                //case '"' -> "&quot;";
                //case '\'' -> replace = "&apos;";
                default -> null;
            };
            if ( replace == null )
                sBuff.append(c);
            else
                sBuff.append(replace);
        }
        return sBuff.toString();
    }

    /**
     * Escape text used in an XML attribute value.
     */
    private String xmlLiteralEscapeAttr(CharSequence stringAcc) {
        StringBuilder sBuff = new StringBuilder();
        int len = stringAcc.length() ;
        for (int i = 0; i < len; i++) {
            char c = stringAcc.charAt(i);
            String replace = switch (c) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                //case '>' -> "&gt;";
                case '"' -> "&quot;";
                //case '\'' -> replace = "&apos;";
                default -> null;
            };
            if ( replace == null )
                sBuff.append(c);
            else
                sBuff.append(replace);
        }
        return sBuff.toString();
    }

    // --- RDF Collections

    private void parseTypeCollection(Node subject, Node property, Emitter emitter, Location location) {
        Node lastCell = null ;
        int event;
        while(true) {
            event = nextEventTag();
            if ( ! lookingAt(event, START_ELEMENT) )
                break;
            location = location();
            Node thisCell = blankNode();
            if ( lastCell == null ) {
                // First list item. Link the list in.
                emitter.emit(subject, property, thisCell, location);
            } else {
                // Link to the previous element. No reification.
                emit(lastCell, NodeConst.nodeRest, thisCell, location);
            }
            Node itemSubject = attributesToSubjectNode();
            emit(thisCell, RDF.Nodes.first, itemSubject, location);
            nodeElement(itemSubject);
            lastCell = thisCell;
        }

        // Finish the list.
        if ( lastCell != null ) {
            emit(lastCell, NodeConst.nodeRest, NodeConst.nodeNil, location);
        } else {
            // It was an empty list
            emitter.emit(subject, property, NodeConst.nodeNil, location);
        }
    }

    private Node reifyStatement(Location location) {
        String reifyId = attribute(rdfID);
        if ( reifyId == null )
            return null;
        return iriFromIDCimAware(reifyId, location);
    }

    /** Return the lang in-scope. Return null for none. */
    private String currentLang() {
        if ( currentLang == null || currentLang.isEmpty() )
            return null;
        return currentLang;
    }

    private void processNestedNodeElement(Node subject, Node property, Emitter emitter) {
        // Nested / RDF/XML striped syntax.
        boolean hasFrame = startElement();
        Node subjectInner = attributesToSubjectNode();
        // subject property INNER
        emitter.emit(subject, property, subjectInner, location());

        // Process as a node element, having decided the subject.
        nodeElement(subjectInner);

        // End property tag.
        int eventType = nextEventTag();
        if ( lookingAt(eventType, START_ELEMENT) )
            throw RDFXMLparseError("Start tag after inner node element (only one node element permitted): got "+qName());
        if ( ! lookingAt(eventType, END_ELEMENT) )
            throw RDFXMLparseError("Expected an end element: got "+strEventType(eventType));
        // -- end
        endElement(hasFrame);
    }

    /** Subject for a node element */
    private Node attributesToSubjectNode() {
        // Subject
        // The spec implies these happen in order (i.e. later overrides earlier)
        // but the test suite has negative test cases.
        // If there is an attribute a with a.URI == rdf:ID, then e.subject := uri(identifier := resolve(e, concat("#", a.string-value))).
        // If there is an attribute a with a.URI == rdf:nodeID, then e.subject := bnodeid(identifier:=a.string-value).
        // If there is an attribute a with a.URI == rdf:about then e.subject := uri(identifier := resolve(e, a.string-value)).

        String iriStr = attribute(rdfAbout);
        // Check NCName if URI created,
        String idStr = attribute(rdfID);
        // Check NCName if blank node created
        String nodeId = attribute(rdfNodeID);

        if ( nodeId != null && idStr != null && iriStr != null )
            throw RDFXMLparseError("All of rdf:about, rdf:NodeId and rdf:ID found. Must be only one.");

        if ( idStr != null && iriStr != null )
            throw RDFXMLparseError("Both rdf:about and rdf:ID found. Must be only one.");

        if ( nodeId != null && iriStr != null )
            throw RDFXMLparseError("Both rdf:about and rdf:NodeID found. Must be only one.");

        if ( nodeId != null && idStr != null )
            throw RDFXMLparseError("Both rdf:NodeID rdf:ID found. Must be only one.");

        Location location = location();

        if ( iriStr != null )
            return iriResolveCimAware(iriStr, location);

        if ( idStr != null )
            return iriFromIDCimAware(idStr, location);

        if ( nodeId != null )
            return blankNode(nodeId, location);

        // None of the above. It's a fresh blank node.
        return blankNode();
    }

    // ---- Nodes

    private Node qNameToIRI(QName qName, QNameUsage usage, Location location) {
        if ( StringUtils.isBlank(qName.getNamespaceURI()) )
            throw RDFXMLparseError("Unqualified "+usage.msg+" not allowed: <"+qName.getLocalPart()+">", location);
        String uriStr = strQNameToIRI(qName);
        return iriDirect(uriStr);
    }

    /** This is the RDF rule for creating an IRI from a QName. */
    private String strQNameToIRI(QName qName) {
        return qName.getNamespaceURI()+qName.getLocalPart();
    }

    private Node attributeToIRI(QName qName, Location location) {
        String namespaceURI = qName.getNamespaceURI();
        String localName = qName.getLocalPart();
        if ( StringUtils.isBlank(namespaceURI) ) {
            if ( allowedUnqualifiedTerm(localName) )
                namespaceURI = rdfNS;
            else
                // else rejected in checkPropertyAttribute
                throw RDFXMLparseError("Unqualified property attribute not allowed: '"+localName+"'", location);
        }
        String uriStr = namespaceURI+localName;
        return iriDirect(uriStr);
    }

    // ---- Reading XML

    /** Get the string value for an attribute by QName */
    private String attribute(QName qName) {
        return xmlSource.getAttributeValue(qName.getNamespaceURI(), qName.getLocalPart());
    }

    /** XML parsing error. */
    private RiotException handleXMLStreamException(XMLStreamException ex) {
        String msg = xmlStreamExceptionMessage(ex);
        if ( ex.getLocation() != null ) {
            int line = ex.getLocation().getLineNumber();
            int col = ex.getLocation().getColumnNumber();
            errorHandler.fatal(msg, line, col);
        } else
            errorHandler.fatal(msg, -1, -1);
        // Should not happen.
        return new RiotException(ex.getMessage(), ex);
    }

    /** Get the detail message from an XMLStreamException */
    private String xmlStreamExceptionMessage(XMLStreamException ex) {
        String msg = ex.getMessage();
        if ( ex.getLocation() != null ) {
            // XMLStreamException with a location is two lines, and has the line/col in the first line.
            // Deconstruct the XMLStreamException message to get the detail part.
            String marker = "\nMessage: ";
            int i = msg.indexOf(marker);
            if ( i > 0 ) {
                msg = msg.substring(i+marker.length());
            }
        }
        return msg;
    }

    private int eventType() {
        return xmlSource.getEventType();
    }

    /** Only valid in START_ELEMENT and END_ELEMENT */
    private QName qName() {
        return xmlSource.getName();
    }

    private static boolean lookingAt(int eventType, int expectedEventType) {
        return eventType == expectedEventType;
    }

    private Location location() { return xmlSource.getLocation(); }

    /**
     * Move to next tag, skipping DTDs and "skipping unimportant whitespace and comments".
     * Returns a start event, endEvent or null.
     */
    private int nextEventTag() {
        // Similar to XMLStreamReader::nextTag(). This code works with DTD local
        // character entities and assumes the StAX parser
        // character entities and then manages the text replacement.
        try {
            while(xmlSource.hasNext()) {
                int evType = read();
                switch (evType) {
                    case START_ELEMENT, END_ELEMENT -> {
                        if ( ReaderCIMXML_StAX_SR.TRACE )
                            System.out.println("-- Tag: "+strEventType(evType));
                        return evType;
                    }
                    case CHARACTERS, CDATA -> {
                        String chars = xmlSource.getText();
                        if ( ! isWhitespace(chars) ) {
                            String text = nonWhitespaceMsg(chars);
                            throw RDFXMLparseError("Expecting a start or end element. Got characters '"+text+"'");
                        }
                        // Skip
                    }
                    case COMMENT, DTD -> {
                        // Loop
                    }
                    //case SPACE:
                    //case PROCESSING_INSTRUCTION:
                    //case ENTITY_DECLARATION:
                    default ->
                        // Not expecting any other type of event.
                        throw RDFXMLparseError("Unexpected  event "+strEventType(evType));
                }
                // and loop
            }
            return -1;
        } catch (XMLStreamException ex) {
            throw handleXMLStreamException(ex);
        }
    }

    /** Move to XMLEvent, skipping comments. */
    private int nextEventAny() {
        try {
            int ev = -1;
            while(xmlSource.hasNext()) {
                ev = read();
                if ( isComment(ev) )
                    continue;
                if ( lookingAt(ev, PROCESSING_INSTRUCTION) ) {
                    if (PROCESSING_INSTRUCTION_IEC61970_552.equals(xmlSource.getPITarget())) {
                        final var versionOfIEC61970_552 = xmlSource.getPIData();
                        destination.setVersionOfIec61970_552(versionOfIEC61970_552);
                    } else {
                        RDFXMLparseWarning("XML Processing instruction - ignored");
                    }
                    continue;
                }
                break;
            }
            if ( ReaderCIMXML_StAX_SR.TRACE ) {
                if ( ev < 0 )
                    System.out.println("-- Read: end of events");
                else
                    System.out.println("-- Event: "+strEventType(ev));
            }
            return ev;
        } catch (XMLStreamException ex) {
            throw handleXMLStreamException(ex);
        }
    }

    /** Move to next XMLEvent regardless of the event type. */
    private int nextEventRaw() {
        try {
            int ev = -1;
            if ( ! xmlSource.hasNext() )
                return ev;
            ev = read();
            if ( ReaderCIMXML_StAX_SR.TRACE ) {
                if ( ev < 0 )
                    System.out.println("-- Read: end of events");
                else
                    System.out.println("-- Event: "+strEventType(ev));
            }
            return ev;
        } catch (XMLStreamException ex) {
            throw handleXMLStreamException(ex);
        }
    }

    private int read() throws XMLStreamException {
        int eventType = xmlSource.next();
        if ( ReaderCIMXML_StAX_SR.TRACE )
            System.out.println("-- Read: "+strEventType(eventType));
        return eventType;
    }

    // ---- XML element processing

    /**
     * State an element (rdf:RDF, node, property)
     * Process namespace (tracing in development).
     * Process xml:base, xml:lang.
     * These set the context for inner elements and need a stack.
     */
    private boolean startElement() {
        return processBaseAndLang();
    }

    private boolean processBaseAndLang() {
        IRIx xmlBase = xmlBase();
        String xmlLang = xmlLang();

        if ( ReaderCIMXML_StAX_SR.TRACE ) {
            if ( xmlBase != null )
                trace.printf("+ BASE <%s>\n", xmlBase);
            if ( xmlLang != null )
                trace.printf("+ LANG @%s\n", xmlLang);
        }
        boolean hasFrame = (xmlBase != null || xmlLang != null);
        if ( hasFrame ) {
            pushFrame(xmlBase != null ? xmlBase : currentBase,
                    xmlLang != null ? xmlLang : currentLang);
        }
        return hasFrame;
    }

    private void endElement(boolean hasFrame) {
        if ( hasFrame )
            popFrame();
    }

    // ---- Parser output

    private void emitInitialBaseAndNamespacesDetermineCimNamespaceAndSetBaseIfNeeded() {

        String rdfVersionStr = attribute(rdfVersion);
        if ( rdfVersionStr != null )
            emitVersion(rdfVersionStr);

        int numNS = xmlSource.getNamespaceCount();
        for ( int i = 0 ; i < numNS ; i++ ) {
            final String prefixURI = xmlSource.getNamespaceURI(i);
            String prefix = xmlSource.getNamespacePrefix(i);
            if ( prefix == null ) {
                prefix = "";
            } else if ("cim".equals(prefix)) {
                // Determine CIM version.
                versionOfCIMXML = prefixURI;
                if (!CimNamespaceFactoryRegistry.hasProfileFactory(prefixURI)) {
                    RDFXMLparseWarning("The provided 'cim' namespace: " + prefixURI + " is not registered in CimNamespaceMapper.", location());
                }
                if (ReaderCIMXML_StAX_SR.TRACE) {
                    trace.printf("CIM version of CIMXML: %s\n", versionOfCIMXML);
                }
            }
            emitPrefix(prefix, prefixURI);
        }

        String xmlBase = attribute(xmlQNameBase);
        if (versionOfCIMXML != null) {
            hasCimXmlNamespace = true;
            destination.setCimNamespace(versionOfCIMXML);
            if (xmlBase == null) {
                xmlBase = xmlBaseForCIMXML;
                currentBase = IRIx.create(xmlBase);
                if (ReaderCIMXML_StAX_SR.TRACE)
                    trace.printf("+ BASE (default for CIMXML) <%s>\n", xmlBase);
            }
        }
        if ( xmlBase != null )
            emitBase(xmlBase);
    }

    interface Emitter { void emit(Node subject, Node property, Node object, Location location); }

    private void emit(Node subject, Node property, Node object, Location location) {
        Objects.requireNonNull(subject);
        Objects.requireNonNull(property);
        Objects.requireNonNull(object);
        Objects.requireNonNull(location);
        destination.triple(Triple.create(subject, property, object));
    }

    private void emitReify(Node reify, Node subject, Node property, Node object, Location location) {
        emit(subject, property, object, location);
        if ( reify != null ) {
            emit(reify, NodeConst.nodeRDFType, RDF.Nodes.Statement, location);
            emit(reify, RDF.Nodes.subject, subject, location);
            emit(reify, RDF.Nodes.predicate, property, location);
            emit(reify, RDF.Nodes.object, object, location);
        }
    }

    private void emitBase(String base) {
        destination.base(base);
    }

    private void emitPrefix(String prefix, String iriStr) {
        destination.prefix(prefix, iriStr);
    }

    private void emitVersion(String version) {
        destination.version(version);
    }

    /**
     * Generate a new base IRIx.
     * If this is relative, issue a warning.
     * It is an error to use it and the error is generated
     * sin {@link #resolveIRIx}.
     */
    private IRIx xmlBase() {
        String baseStr = attribute(xmlQNameBase);
        if ( baseStr == null )
            return null;
        Location location = location();
        IRIx irix = resolveIRIxAny(baseStr, location);
        if ( irix.isRelative() )
            RDFXMLparseWarning("Relative URI for base: <"+baseStr+">", location);
        return irix;
    }

    private String xmlLang() {
        return attribute(xmlQNameLang);
    }

    // ---- RDF Terms (Nodes)

    private Node iriFromID(String idStr, Location location) {
        checkValidNCName(idStr, location);
        Location prev = previousUseOfID(idStr, location);
        if ( prev != null )
            // Already in use
            RDFXMLparseWarning("Reuse of rdf:ID '"+idStr+"' at "+str(prev), location);
        return iriResolve("#"+idStr, location);
    }

    /** Create a URI. The IRI is known to be resolved. */
    private Node iriDirect(String uriStr) {
        Objects.requireNonNull(uriStr);
        return factoryRDF.createURI(uriStr);
    }

    /**
     * Create a URI. The IRI is resolved by this operation.
     */
    private Node iriResolve(String uriStr, Location location) {
        Objects.requireNonNull(uriStr);
        return uriStr.startsWith("_:")
                ?  createURI(uriStr) // <_:label> syntax. Handled by the FactoryRDF via the parser profile.
                :  createURI(resolveIRIx(uriStr, location).str());
    }

    private IRIx resolveIRIx(String uriStr, Location location) {
        // This does not use the parser profile because the base stacks and unstacks in RDF/XML.
        try {
            IRIx iri = resolveIRIxAny(uriStr, location);
            if ( iri.isRelative() )
                throw RDFXMLparseError("Relative URI encountered: <"+iri.str()+">" , location);
            return iri;
        } catch (IRIException ex) {
            throw RDFXMLparseError(ex.getMessage(), location);
        }
    }

    private IRIx resolveIRIxAny(String uriStr, Location location) {
        try {
            return currentIriCache.get(uriStr, uri -> {
                if ( currentBase != null ) {
                    return currentBase.resolve(uri);
                } else {
                    return IRIx.create(uriStr);
                }
            });
        } catch (IRIException ex) {
            throw RDFXMLparseError(ex.getMessage(), location);
        }
    }

    /**
     * Create a CIM UUID IRI or a warning and resolves the IRI using iriResolve.
     * @param uriStr The full URI string (for warning messages).
     * @param uuidPart The part after "urn:uuid:" or the part after "#_".
     * @param location Location for warnings.
     * @return Node or null if not valid CIM UUID.
     */
    private Node createCimUuid(String uriStr, String uuidPart, Location location) {
        // Simple  or
        return switch (uuidPart.length()) {
            case 36 -> { // expect UUID - 550e8400-e29b-41d4-a716-446655440000
                if ( CIM_UUID_PATTERN.matcher(uuidPart).matches() ) {
                    yield iriDirect(xmlBaseForCIMXML + uuidPart);
                } else {
                    final var lowerCase = uuidPart.toLowerCase(Locale.ROOT);
                    if ( CIM_UUID_PATTERN.matcher(lowerCase).matches() ) {
                        // warn parsed UUID with upper case into lower case form.
                        RDFXMLparseWarning("CIM UUID with upper case letters: '"+uuidPart+"' - converted to lower case form.", location);
                        yield iriDirect(xmlBaseForCIMXML + lowerCase);
                    }
                    RDFXMLparseWarning("Not a valid CIM UUID: '"+uriStr+"'", location);
                    yield iriResolve(uriStr, location);
                }
            }
            case 32 -> { // expect UUID without dashes - 550e8400e29b41d4a716446655440000
                if ( CIM_UUID_PATTERN_NO_DASHES.matcher(uuidPart).matches() ) {
                    // warn parsed UUID without dashes into dashed form.
                    RDFXMLparseWarning("CIM UUID without dashes: '"+uuidPart+"' - converted to dashed form.", location);
                    yield iriDirect(toDashedUUID(uuidPart));
                } else {
                    final var lowerCase = uuidPart.toLowerCase(Locale.ROOT);
                    if ( CIM_UUID_PATTERN_NO_DASHES.matcher(lowerCase).matches() ) {
                        // warn parsed UUID with upper case into lower case form.
                        RDFXMLparseWarning("CIM UUID with upper case letters and without dashes: '"+uuidPart+"' - converted to lower case dashed form.", location);
                        yield iriDirect(toDashedUUID(lowerCase));
                    }
                    RDFXMLparseWarning("Not a valid CIM UUID: '"+uriStr+"'", location);
                    yield null;
                }
            }
            default -> {
                RDFXMLparseWarning("Not a valid CIM UUID: '"+uriStr+"'", location);
                yield null;
            }
        };
    }

    private static String toDashedUUID(String uuidPart) {
       final var b = new StringBuilder(xmlBaseForCIMXML.length() + 36)
                .append(xmlBaseForCIMXML)
                .append(uuidPart, 0, 8).append('-')
                .append(uuidPart, 8, 12).append('-')
                .append(uuidPart, 12, 16).append('-')
                .append(uuidPart, 16, 20).append('-')
                .append(uuidPart, 20, 32);
        return b.toString();
    }

    private Node iriFromIDCimAware(String idStr, Location location) {
        if ( hasCimXmlNamespace && idStr != null && idStr.startsWith("_") ) {
            final var uri = createCimUuid(idStr, idStr.substring(1), location);
            if ( uri != null )
                return uri;
        }
        return iriFromID(idStr, location);
    }

    /**
     * Create a URI. The IRI is resolved by this operation.
     */
    private Node iriResolveCimAware(String uriStr, Location location) {
        Objects.requireNonNull(uriStr);
        if ( hasCimXmlNamespace && uriStr.startsWith("#_") ) {
            final var uri = createCimUuid(uriStr, uriStr.substring(2), location);
            if ( uri != null )
                return uri;
        }
        return iriResolve(uriStr, location);
    }

    /** Done in accordance to the parser profile policy. */
    private Node createURI(final String iriStr) {
        Objects.requireNonNull(iriStr);
        return factoryRDF.createURI(iriStr);
    }

    private Node blankNode() {
        return factoryRDF.createBlankNode();
    }

    private Node blankNode(String label, Location location) {
        Objects.requireNonNull(label);
        Objects.requireNonNull(location);
        checkValidNCName(label, location);
        return factoryRDF.createBlankNode(label);
    }

    private Node literal(String lexical) {
        Objects.requireNonNull(lexical);
        return factoryRDF.createStringLiteral(lexical);
    }

    /**
     * Create literal with a language (rdf:langString).
     * If lang is null or "", create a xsd:string
     */
    private Node literal(String lexical, String lang) {
        if ( lang == null )
            return literal(lexical);
        Objects.requireNonNull(lexical);
        return factoryRDF.createLangLiteral(lexical, lang);
    }

    private Node literalDatatype(String lexical, String datatype) {
        Objects.requireNonNull(lexical);
        Objects.requireNonNull(datatype);
        RDFDatatype dt = NodeFactory.getType(datatype);
        return factoryRDF.createTypedLiteral(lexical, dt);
    }

    private Node literalDatatype(String lexical, RDFDatatype datatype) {
        Objects.requireNonNull(lexical);
        Objects.requireNonNull(datatype);
        return factoryRDF.createTypedLiteral(lexical, datatype);
    }

    // ---- Functions

    private boolean qNameMatches(QName qName1, QName qName2) {
        // QName actually ignores prefix for QName.equals.
        return Objects.equals(qName1.getNamespaceURI(), qName2.getNamespaceURI()) &&
                Objects.equals(qName1.getLocalPart(), qName2.getLocalPart());
    }

    private void checkValidNCName(String string, Location location) {
        //boolean isValid = XMLChar.isValidNCName(string);
        boolean isValid = XML11Char.isXML11ValidNCName(string);
        if ( ! isValid )
            RDFXMLparseWarning("Not a valid XML NCName: '"+string+"'", location);
    }

    private static boolean isNotRDF(QName qName) {
        return !rdfNS.equals(qName.getNamespaceURI());
    }

    /** Test for {@code rdf:_NNNNN}. */
    private static boolean isMemberProperty(QName qName) {
        if (isNotRDF(qName))
            return false;
        return isMemberPropertyLocalName(qName.getLocalPart());
    }

    private static boolean isMemberPropertyLocalName(String localName) {
        if ( ! localName.startsWith("_") )
            return false;
        String number = localName.substring(1);
        if (number.startsWith("-") || number.startsWith("0"))
            return false;

        try {
            Integer.parseInt(number);
            return true;
        } catch (NumberFormatException e) {
            try {
                // It might be > Integer.MAX_VALUE !!
                BigInteger i = new BigInteger(number);
                return true;
            } catch (NumberFormatException ee) {
                return false;
            }
        }
    }

    // "nil" is in the W3C RDF test suite
    private static final Set<String> knownRDF = Set.of
            ("Bag", "Seq", "Alt", "List","XMLLiteral", "Property", "Statement",
             "type", "li", "subject", "predicate","object","value","first","rest", "nil");

    private static final Set<String> knownRDFProperties = knownRDF;
    private static final Set<String> knownRDFTypes = knownRDF;

    /**
     * Return false if acceptable: not the RDF namespace, in the RDF namespace but
     * recognized for a type.
     * If return true, issue a warning.
     */
    private boolean isNotRecognizedRDFtype(QName qName) {
        if (isNotRDF(qName))
            return false;
        String ln = qName.getLocalPart();
        return ! knownRDFTypes.contains(ln);
    }

    private boolean isNotRecognizedRDFproperty(QName qName) {
        if (isNotRDF(qName))
            return false;
        String ln = qName.getLocalPart();
        if ( isMemberPropertyLocalName(ln) )
            return false;
        return ! knownRDFProperties.contains(ln);
    }

    private void incIndent() {
        if ( ReaderCIMXML_StAX_SR.TRACE )
            trace.incIndent();
    }

    private void decIndent() {
        if ( ReaderCIMXML_StAX_SR.TRACE )
            trace.decIndent();
    }

    // ---- XMLEvents functions

//    /** Return the contents of a text-only element.*/
//    private String nextText()

    private static boolean isComment(int eventType) {
        return eventType == COMMENT;
    }

    private boolean isWhitespace(int eventType) {
        if ( lookingAt(eventType, CHARACTERS ) ) {
            String s = xmlSource.getText();
            return isWhitespace(s);
        }
        return false;
    }

    private static boolean isWhitespace(CharSequence x) {
        return StringUtils.isWhitespace(x);
    }

    private static String str(Location location) {
        if ( location == null )
            return "[-,-]";
        if ( location.getLineNumber() < 0 && location.getColumnNumber() < 0 )
            return "[?,?]";
        if ( location.getLineNumber() < 0 )
            return String.format("[-, Col: %d]", location.getColumnNumber());
        if ( location.getColumnNumber() < 0 )
            return String.format("[Line: %d, -]", location.getLineNumber());
        return String.format("[Line: %d, Col: %d]", location.getLineNumber(), location.getColumnNumber());
    }

    private static String str(QName qName) {
        String prefix = qName.getPrefix();
        if ( prefix == null || prefix.isEmpty() )
            return String.format("%s", qName.getLocalPart());
        return String.format("%s:%s", qName.getPrefix(), qName.getLocalPart());
    }

    private String strEventType(int eventType) {
            return switch (eventType) {
                case START_ELEMENT ->   str(xmlSource.getName());
                case END_ELEMENT ->     "/"+str(xmlSource.getName());
                case CHARACTERS ->      "Event Characters";
                // @see #ATTRIBUTE
                // @see #NAMESPACE
                // @see #PROCESSING_INSTRUCTION
                // @see #SPACE:
                case COMMENT ->         "Event Comment";
                case START_DOCUMENT ->  "Event StartDocument";
                case END_DOCUMENT ->    "Event EndDocument";
                case DTD ->             "DTD";
                case ENTITY_DECLARATION -> "DTD Entity Decl";
                case ENTITY_REFERENCE ->   "DTD Entity Ref";
                // @see #DTD
                    default ->""+eventType;
            };
    }

    /** The string for the first non-whitespace index. */
    private static String nonWhitespaceMsg(String string) {
        final int MaxLen = 10; // Short - this is for error messages
        // Find the start of non-whitespace.
        // Slice, truncate if necessary.
        // Make safe.
        int length = string.length();
        for ( int i = 0 ; i < length ; i++ ) {
            if ( !Character.isWhitespace(string.charAt(i)) ) {
                int len = Math.min(MaxLen, length - i);
                String x = string.substring(i, i+len);
                if ( length > MaxLen )
                    x = x+"...";
                // Escape characters, especially newlines and backspaces.
                x = EscapeStr.stringEsc(x);
                x = x.stripTrailing();
                return x;
            }
        }
        throw new RDFXMLParseException("Failed to find any non-whitespace characters");
    }
}

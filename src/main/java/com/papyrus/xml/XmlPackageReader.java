package com.papyrus.xml;

import com.papyrus.api.ExportException;
import com.papyrus.config.ExportProperties;
import com.papyrus.domain.XmlUnit;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import org.xml.sax.*;

@Component
public class XmlPackageReader {
    private final ExportProperties limits;

    public XmlPackageReader(ExportProperties limits) { this.limits = limits; }

    public List<XmlUnit> read(InputStream input) {
        List<XmlUnit> units = new ArrayList<>();
        Set<String> names = new HashSet<>();
        long total = 0;
        int count = 0;
        long textLines = 0;
        try (var in = new PushbackInputStream(input, 4)) {
            byte[] magic = in.readNBytes(4);
            if (magic.length != 4 || magic[0] != 'P' || magic[1] != 'K'
                    || magic[2] != 3 || magic[3] != 4) {
                throw new ExportException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INVALID_ZIP", "È richiesto uno ZIP non vuoto contenente XML.");
            }
            in.unread(magic);
            try (var zip = new ZipInputStream(in)) {
                ZipEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = zip.getNextEntry()) != null) {
                    if (++count > limits.maxEntries()) throw ExportException.tooLarge("Troppe entry nello ZIP.");
                    String name = entry.getName();
                    validateName(name);
                    if (!names.add(name)) throw ExportException.invalid("Nome duplicato nello ZIP: " + name);
                    if (!entry.isDirectory() && !name.toLowerCase(Locale.ROOT).endsWith(".xml")) {
                        throw ExportException.invalid("Lo ZIP deve contenere soltanto file XML: " + name);
                    }
                    var out = new ByteArrayOutputStream();
                    long size = 0;
                    int n;
                    while ((n = zip.read(buffer)) != -1) {
                        size += n;
                        total += n;
                        if (size > limits.maxEntryBytes() || total > limits.maxExpandedBytes()) {
                            throw ExportException.tooLarge("Superato il limite dei dati XML decompressi.");
                        }
                        out.write(buffer, 0, n);
                    }
                    zip.closeEntry(); // also verifies the entry's CRC
                    if (entry.isDirectory()) {
                        if (size != 0) throw ExportException.invalid("Entry directory contenente dati.");
                    } else {
                        XmlUnit unit = parse(out.toByteArray());
                        if (unit.content() != null) {
                            textLines += checkLines(unit.content());
                            if (textLines > limits.maxTextLines()) throw ExportException.tooLarge("Troppe righe testuali nel pacchetto.");
                        }
                        units.add(unit);
                    }
                }
            }
        } catch (ExportException ex) {
            throw ex;
        } catch (IOException ex) {
            throw ExportException.invalid("ZIP danneggiato o non leggibile.");
        } catch (IllegalArgumentException ex) {
            throw ExportException.invalid("ZIP con metadati o nomi non validi.");
        }
        if (units.isEmpty()) throw ExportException.invalid("Nessun XML nel pacchetto.");
        return List.copyOf(units);
    }

    private long checkLines(String content) {
        long lines = 1;
        int width = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') { lines++; width = 0; }
            else if (++width > limits.maxLineChars()) throw ExportException.tooLarge("Riga testuale troppo lunga.");
        }
        return lines;
    }

    private void validateName(String name) {
        if (name.isBlank() || name.length() > 512 || name.startsWith("/") || name.contains("\\")
                || name.indexOf(':') >= 0 || name.chars().anyMatch(Character::isISOControl)
                || Arrays.asList(name.split("/")).contains("..") || Path.of(name).isAbsolute()) {
            throw ExportException.invalid("Nome di entry ZIP non ammesso.");
        }
    }

    private XmlUnit parse(byte[] bytes) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", limits.maxXmlDepth());
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            var root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            if (!root.getTagName().equals("unit") || root.getNamespaceURI() != null) {
                throw ExportException.invalid("È supportato il formato GS <unit> senza namespace.");
            }
            String id = metadata(root, "id", true);
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")) throw ExportException.invalid("ID XML non valido.");
            List<String> children = new ArrayList<>();
            Element childList = child(root, "children", false);
            if (childList != null) {
                for (Node n = childList.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element e) {
                        if (!e.getTagName().equals("child")) throw ExportException.invalid("Elemento inatteso in children di " + id);
                        children.add(e.getTextContent().strip());
                    }
                }
            }
            Element dsl = child(root, "dsl", false);
            String language = dsl == null ? null : metadata(dsl, "language", true);
            String content = dsl == null ? null : textContent(child(dsl, "content", true));
            return new XmlUnit(id, metadata(root, "title", false), metadata(root, "creator", false),
                    metadata(root, "target", false), children, language, content, bytes);
        } catch (ExportException ex) {
            throw ex;
        } catch (SAXException | IOException ex) {
            throw ExportException.invalid("XML non valido: DTD/entità esterne non sono consentite e la profondità è limitata.");
        } catch (ParserConfigurationException ex) {
            throw new IllegalStateException("Parser XML sicuro non configurabile.", ex);
        }
    }

    private String textContent(Element element) {
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) throw ExportException.invalid("content deve contenere testo o CDATA, non markup annidato.");
        }
        return element.getTextContent(); // no trim, Unicode normalization or editorial substitutions
    }

    private String metadata(Element parent, String tag, boolean required) {
        Element el = child(parent, tag, required);
        String value = el == null ? "" : el.getTextContent().strip();
        if (required && value.isEmpty()) throw ExportException.invalid("Campo XML obbligatorio vuoto: " + tag);
        return value;
    }

    private Element child(Element parent, String tag, boolean required) {
        Element result = null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) {
                if (result != null) throw ExportException.invalid("Campo XML duplicato: " + tag);
                result = e;
            }
        }
        if (result == null && required) throw ExportException.invalid("Campo XML obbligatorio mancante: " + tag);
        return result;
    }

    private static class DefaultHandler implements ErrorHandler {
        public void warning(SAXParseException e) throws SAXException { throw e; }
        public void error(SAXParseException e) throws SAXException { throw e; }
        public void fatalError(SAXParseException e) throws SAXException { throw e; }
    }
}

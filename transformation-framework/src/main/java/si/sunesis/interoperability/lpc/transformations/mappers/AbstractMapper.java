/*
 *  Copyright (c) 2023-2024 Sunesis and/or its affiliates
 *  and other contributors as indicated by the @author tags and
 *  the contributor list.
 *
 *  Licensed under the MIT License (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  https://opensource.org/licenses/MIT
 *
 *  The software is provided "AS IS", WITHOUT WARRANTY OF ANY KIND, express or
 *  implied, including but not limited to the warranties of merchantability,
 *  fitness for a particular purpose and noninfringement. in no event shall the
 *  authors or copyright holders be liable for any claim, damages or other
 *  liability, whether in an action of contract, tort or otherwise, arising from,
 *  out of or in connection with the software or the use or other dealings in the
 *  software. See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package si.sunesis.interoperability.lpc.transformations.mappers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Map;

/**
 * @author David Trafela, Sunesis
 * @since 1.0.0
 */
@Data
@Slf4j
public abstract class AbstractMapper {

    protected String path;
    protected String type;
    protected String[] values;
    protected String pattern;

    @ToString.Exclude
    protected DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

    @ToString.Exclude
    protected ObjectMapper objectMapper = new ObjectMapper();

    protected AbstractMapper() {
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Extracts a value from an XML string using the configured XPath expression.
     * Parses the XML string into a document and delegates to the document-based method.
     *
     * @param xmlInput The XML string to extract the value from
     * @return The extracted value as a string, or null if extraction fails
     */
    public String getMappedValueXML(String xmlInput) {
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(xmlInput)));

            return getMappedValueXML(document);
        } catch (Exception e) {
            log.error("Error parsing XML", e);
        }

        return null;
    }

    /**
     * Extracts a value from an XML document using the configured XPath expression.
     * Uses XPath to extract the value and then processes it according to the configured type.
     *
     * @param xmlInput The XML document to extract the value from
     * @return The extracted value as a string, processed according to its type, or null if extraction fails
     */
    public String getMappedValueXML(Document xmlInput) {
        try {
            // Parse the input XML string
            XPathFactory xpathFactory = XPathFactory.newInstance();
            XPath xpath = xpathFactory.newXPath();

            // Example: Extract value using XPath expression
            String statusXPath = getPath();
            XPathExpression expr = xpath.compile(statusXPath);
            String value = (String) expr.evaluate(xmlInput, XPathConstants.STRING);

            log.debug("Value at XML path {} : {}", statusXPath, value);

            return getValue(value);
        } catch (Exception e) {
            log.error("Error parsing XML", e);
        }

        return null;
    }

    public String getMappedValueJSON(String jsonInput) throws JsonProcessingException {
        JsonNode rootNode = objectMapper.readTree(jsonInput);

        return getMappedValueJSON(rootNode);
    }

    public String getMappedValueJSON(JsonNode jsonInput) {
        if (getPath().startsWith("//")) {
            setPath(getPath().substring(1));
        }

        JsonNode resultNode = jsonInput.at(getPath());

        // Check if the result node exists
        if (!resultNode.isMissingNode()) {
            log.debug("Value at JSON path {} : {}", getPath(), resultNode);
            return getValue(resultNode.asText());
        } else {
            log.debug("No value found at JSON path {}", getPath());
        }

        return "null";
    }

    public String getMappedValueModbus(Map<Integer, Object> modbusInput) {
        setPath(getPath().replace("/", ""));

        if (!modbusInput.containsKey(Integer.parseInt(getPath()))) {
            return null;
        }

        Object value = modbusInput.get(Integer.parseInt(getPath()));

        if (value instanceof Double d) {
            return String.valueOf(d);
        } else if (value instanceof Integer i) {
            return String.valueOf(i);
        } else if (value instanceof Float f) {
            return String.valueOf(f);
        } else if (value instanceof Long l) {
            return String.valueOf(l);
        }

        return null;
    }

    private String getValue(String cleanedValue) {
        cleanedValue = cleanedValue.trim();
        if (getValues() != null && getValues().length > 0) {
            return getArrayValue(cleanedValue);
        } else if (getPattern() != null && (getType().equalsIgnoreCase("date") || getType().equalsIgnoreCase("datetime"))) {
            return getPatternValue(cleanedValue);
        }

        if (getType().toLowerCase().contains("str")) {
            return "\"" + cleanedValue + "\"";
        } else if (isNumber(cleanedValue)) {
            double d = Double.parseDouble(cleanedValue);

            if (getType().toLowerCase().contains("int")) {
                return String.valueOf((int) d);
            } else if (getType().toLowerCase().contains("float") || getType().toLowerCase().contains("double")) {
                return String.valueOf(d);
            }
        }

        return cleanedValue;
    }

    private String getArrayValue(String cleanedValue) {
        if (getType().toLowerCase().contains("int")) {
            if (isNumber(cleanedValue)) {
                return getValues()[Integer.parseInt(cleanedValue)];
            }
            for (int iii = 0; iii < getValues().length; iii++) {
                if (getValues()[iii].equalsIgnoreCase(cleanedValue.trim())) {
                    return String.valueOf(iii);
                }
            }
        } else if (getType().toLowerCase().contains("str")) {
            if (isNumber(cleanedValue)) {
                return "\"" + getValues()[Integer.parseInt(cleanedValue)] + "\"";
            }

            for (int iii = 0; iii < getValues().length; iii++) {
                if (getValues()[iii].equalsIgnoreCase(cleanedValue.trim())) {
                    return "\"" + iii + "\"";
                }
            }

            return null;
        }

        return null;
    }

    private String getPatternValue(String cleanedValue) {
        setPattern(getPattern().replace("\"", "'"));
        log.debug("Pattern: {}", getPattern());
        log.debug("Date value to parse: {}", cleanedValue);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(getPattern());

        if (isNumber(cleanedValue)) {
            Date date = new Date(Long.parseLong(cleanedValue));
            LocalDateTime ldt = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
            return "\"" + ldt.format(formatter) + "\"";
        }

        Date date;

        if (getPattern().contains("H") || getPattern().contains("h") || getPattern().contains("K") || getPattern().contains("k")) {
            LocalDateTime ldt = LocalDateTime.parse(cleanedValue, formatter);
            date = Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
        } else {
            LocalDate ld = LocalDate.parse(cleanedValue, formatter);
            date = Date.from(ld.atStartOfDay(ZoneId.systemDefault()).toInstant());
        }

        log.debug("Parsed date: {}", date);

        return "\"" + date.getTime() + "\"";
    }

    public static boolean isNumber(String input) {
        try {
            Double.parseDouble(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}


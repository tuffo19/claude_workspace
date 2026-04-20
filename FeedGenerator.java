import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;

public class FeedGenerator {

    private static final String ATOM_NS = "http://www.w3.org/2005/Atom";
    private static final String G_NS = "http://base.google.com/ns/1.0";

    private static final String SQL =
            "select catentry_id as \"g:id\", " +
            "       title as \"title\", " +
            "       cat_desc as \"description\", " +
            "       gtin as \"g:gtin\" " +
            "from products";

    public String generateFeed(Connection connection) throws Exception {
        StringWriter stringWriter = new StringWriter();
        XMLOutputFactory factory = XMLOutputFactory.newInstance();
        XMLStreamWriter writer = factory.createXMLStreamWriter(stringWriter);

        writer.writeStartDocument("UTF-8", "1.0");

        writer.setDefaultNamespace(ATOM_NS);
        writer.setPrefix("g", G_NS);
        writer.writeStartElement(ATOM_NS, "feed");
        writer.writeDefaultNamespace(ATOM_NS);
        writer.writeNamespace("g", G_NS);

        writeTextElement(writer, ATOM_NS, "title", "Elesa IT");

        writer.writeEmptyElement(ATOM_NS, "link");
        writer.writeAttribute("href", "https://www.elesa-ganter.com.tr/tr/tur");
        writer.writeAttribute("rel", "alternate");
        writer.writeAttribute("type", "text/html");

        String updated = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        writeTextElement(writer, ATOM_NS, "updated", updated);

        try (PreparedStatement ps = connection.prepareStatement(SQL);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String id = rs.getString("g:id");
                String title = rs.getString("title");
                String description = rs.getString("description");
                String gtin = rs.getString("g:gtin");

                writer.writeStartElement(ATOM_NS, "entry");

                writeTextElement(writer, G_NS, "id", nullToEmpty(id));
                writeTextElement(writer, ATOM_NS, "title", nullToEmpty(title));
                writeTextElement(writer, ATOM_NS, "description", nullToEmpty(description));

                if (gtin != null) {
                    writeTextElement(writer, G_NS, "gtin", gtin);
                }

                writer.writeEndElement();
            }
        }

        writer.writeEndElement();
        writer.writeEndDocument();
        writer.flush();
        writer.close();

        return stringWriter.toString();
    }

    private static void writeTextElement(XMLStreamWriter writer, String namespace,
                                         String localName, String text) throws Exception {
        writer.writeStartElement(namespace, localName);
        if (text != null && !text.isEmpty()) {
            writer.writeCharacters(text);
        }
        writer.writeEndElement();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

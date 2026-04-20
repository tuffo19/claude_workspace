import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;

/**
 * Naming convention for SQL column aliases (applied per entry):
 *
 *   "name"                 -> <name>                        (Atom namespace, default)
 *   "g:name"               -> <g:name>                      (Google namespace, prefix "g:")
 *   "parent.child"         -> <parent><child>...            (nested, each segment follows rules above)
 *   "...name?"             -> emitted only when value != null
 *                             (without "?", null is written as an empty element)
 *
 *  Examples:
 *     catentry_id as "g:id"                  -> <g:id>...</g:id>
 *     title       as "title"                 -> <title>...</title>
 *     cat_desc    as "description"           -> <description>...</description>
 *     gtin        as "g:gtin?"               -> omitted if null
 *     prod_price  as "g:shipping.g:price"    -> <g:shipping><g:price>...</g:price></g:shipping>
 */
public class FeedGenerator {

    private static final String ATOM_NS = "http://www.w3.org/2005/Atom";
    private static final String G_NS = "http://base.google.com/ns/1.0";
    private static final String G_PREFIX = "g:";
    private static final String NESTING_SEPARATOR = ".";
    private static final String OPTIONAL_SUFFIX = "?";

    private static final String SQL =
            "select catentry_id as \"g:id\", " +
            "       title as \"title\", " +
            "       cat_desc as \"description\", " +
            "       gtin as \"g:gtin?\", " +
            "       prod_price as \"g:shipping.g:price\" " +
            "from products";

    public String generateFeed(Connection connection) throws Exception {
        StringWriter out = new StringWriter();
        XMLOutputFactory factory = XMLOutputFactory.newInstance();
        XMLStreamWriter writer = factory.createXMLStreamWriter(out);

        writer.writeStartDocument("UTF-8", "1.0");
        writer.setDefaultNamespace(ATOM_NS);
        writer.setPrefix("g", G_NS);
        writer.writeStartElement(ATOM_NS, "feed");
        writer.writeDefaultNamespace(ATOM_NS);
        writer.writeNamespace("g", G_NS);

        writeLeaf(writer, ATOM_NS, "title", "Elesa IT");
        writer.writeEmptyElement(ATOM_NS, "link");
        writer.writeAttribute("href", "https://www.elesa-ganter.com.tr/tr/tur");
        writer.writeAttribute("rel", "alternate");
        writer.writeAttribute("type", "text/html");
        writeLeaf(writer, ATOM_NS, "updated",
                OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        try (PreparedStatement ps = connection.prepareStatement(SQL);
             ResultSet rs = ps.executeQuery()) {

            List<Column> columns = parseColumns(rs.getMetaData());

            while (rs.next()) {
                writer.writeStartElement(ATOM_NS, "entry");
                writeEntry(writer, rs, columns);
                writer.writeEndElement();
            }
        }

        writer.writeEndElement();
        writer.writeEndDocument();
        writer.flush();
        writer.close();
        return out.toString();
    }

    private static List<Column> parseColumns(ResultSetMetaData meta) throws Exception {
        List<Column> columns = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(Column.parse(meta.getColumnLabel(i)));
        }
        return columns;
    }

    private static void writeEntry(XMLStreamWriter writer, ResultSet rs,
                                   List<Column> columns) throws Exception {
        Node root = new Node(null);
        for (Column col : columns) {
            root.insert(col, rs.getString(col.rawLabel));
        }
        root.writeChildren(writer);
    }

    // --- Column parsing -----------------------------------------------------

    private static class Column {
        final String rawLabel;         // original alias (used for rs.getString)
        final List<Segment> path;      // parsed path of nested elements
        final boolean optional;        // if true, leaf is omitted when value is null

        Column(String rawLabel, List<Segment> path, boolean optional) {
            this.rawLabel = rawLabel;
            this.path = path;
            this.optional = optional;
        }

        static Column parse(String label) {
            String work = label;
            boolean optional = work.endsWith(OPTIONAL_SUFFIX);
            if (optional) {
                work = work.substring(0, work.length() - OPTIONAL_SUFFIX.length());
            }
            List<Segment> path = new ArrayList<>();
            for (String part : work.split(Pattern.quote(NESTING_SEPARATOR))) {
                path.add(Segment.parse(part));
            }
            return new Column(label, path, optional);
        }
    }

    private static class Segment {
        final String namespace;
        final String localName;

        Segment(String namespace, String localName) {
            this.namespace = namespace;
            this.localName = localName;
        }

        static Segment parse(String part) {
            if (part.startsWith(G_PREFIX)) {
                return new Segment(G_NS, part.substring(G_PREFIX.length()));
            }
            return new Segment(ATOM_NS, part);
        }

        String key() { return namespace + "|" + localName; }
    }

    // --- Tree of elements to emit ------------------------------------------

    private static class Node {
        final Segment segment;                                    // null for the root
        final Map<String, Node> children = new LinkedHashMap<>(); // insertion order preserved
        String value;
        boolean isLeaf;
        boolean optional;

        Node(Segment segment) { this.segment = segment; }

        void insert(Column column, String value) {
            Node current = this;
            for (Segment seg : column.path) {
                Node child = current.children.get(seg.key());
                if (child == null) {
                    child = new Node(seg);
                    current.children.put(seg.key(), child);
                }
                current = child;
            }
            current.value = value;
            current.isLeaf = true;
            current.optional = column.optional;
        }

        void write(XMLStreamWriter writer) throws Exception {
            if (isLeaf && children.isEmpty()) {
                if (value == null && optional) {
                    return;
                }
                writeLeaf(writer, segment.namespace, segment.localName, value);
            } else {
                writer.writeStartElement(segment.namespace, segment.localName);
                writeChildren(writer);
                writer.writeEndElement();
            }
        }

        void writeChildren(XMLStreamWriter writer) throws Exception {
            for (Node child : children.values()) {
                child.write(writer);
            }
        }
    }

    private static void writeLeaf(XMLStreamWriter writer, String namespace,
                                  String localName, String text) throws Exception {
        writer.writeStartElement(namespace, localName);
        if (text != null && !text.isEmpty()) {
            writer.writeCharacters(text);
        }
        writer.writeEndElement();
    }
}

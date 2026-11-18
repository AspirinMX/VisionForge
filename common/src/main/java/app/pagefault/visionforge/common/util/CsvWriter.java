package app.pagefault.visionforge.common.util;

import java.nio.charset.StandardCharsets;

/**
 * Builds RFC-4180 UTF-8 CSV documents (with BOM) while neutralizing spreadsheet formula injection.
 */
public final class CsvWriter {

    private static final String UTF8_BOM = "\uFEFF";

    private final StringBuilder buffer = new StringBuilder(UTF8_BOM);
    private boolean rowStarted = false;

    public CsvWriter addHeader(String... headers) {
        if (headers == null || headers.length == 0) {
            return this;
        }
        for (String header : headers) {
            addTextCell(header);
        }
        return endRow();
    }

    public CsvWriter addTextCell(Object value) {
        appendSeparator();
        String text = value == null ? "" : value.toString();
        String neutralized = neutralizeFormula(text);
        appendQuoted(neutralized);
        return this;
    }

    public CsvWriter addNumberCell(Number value) {
        appendSeparator();
        if (value != null) {
            buffer.append(value);
        }
        return this;
    }

    public CsvWriter addBooleanCell(boolean value) {
        appendSeparator();
        buffer.append(value);
        return this;
    }

    public CsvWriter endRow() {
        buffer.append("\r\n");
        rowStarted = false;
        return this;
    }

    public byte[] toUtf8Bytes() {
        return buffer.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendSeparator() {
        if (rowStarted) {
            buffer.append(',');
        }
        rowStarted = true;
    }

    private void appendQuoted(String value) {
        boolean needsQuotes = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            buffer.append(value);
            return;
        }
        buffer.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') {
                buffer.append("\"\"");
            } else {
                buffer.append(ch);
            }
        }
        buffer.append('"');
    }

    static String neutralizeFormula(String value) {
        if (value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }
}

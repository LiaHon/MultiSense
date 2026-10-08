package edu.multisense.collector;

final class Csv {
    static String cell(Object value) {
        String s = value == null ? "" : value.toString();
        return s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0
            ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
    static String row(Object... values) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < values.length; i++) { if (i > 0) b.append(','); b.append(cell(values[i])); }
        return b.append('\n').toString();
    }
}

package org.eversolo.winamp.tags;

/**
 * Just enough JSON to read two fields out of a lyrics reply.
 *
 * Deliberate: the shape is known, only two fields are wanted, and adding a JSON library to an
 * app with no dependencies at all is a poor trade. It lives here, in plain Java, because the
 * unescaping is the part worth testing - an `.lrc` is a file of lines, and every one of those
 * line breaks arrives as a two-character `\\n` inside a JSON string. Get that wrong and every
 * song becomes one very long lyric.
 */
public final class Json {

    private Json() {}

    /**
     * Pull one string value out of JSON without a parser.
     *
     * Deliberate: the only fields wanted are two, the shape is known, and adding a JSON
     * library to an app that has no dependencies at all is a poor trade. It unescapes the
     * handful of sequences that appear in lyrics - \\n above all, since that is what makes
     * an .lrc a file of lines rather than one long one.
     */
    public static String string(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) return null;
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;     // null, or a number
        StringBuilder sb = new StringBuilder();
        for (i++; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '\\' && i + 1 < json.length()) {
                char e = json.charAt(++i);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                        break;
                    default: sb.append(e);
                }
            } else if (ch == '"') {
                break;
            } else {
                sb.append(ch);
            }
        }
        String out = sb.toString();
        return out.isEmpty() ? null : out;
    }

    public static Long number(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) return null;
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) i++;
        if (i == start) return null;
        try {
            return (long) Double.parseDouble(json.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

}

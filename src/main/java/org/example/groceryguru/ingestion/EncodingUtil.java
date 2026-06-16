package org.example.groceryguru.ingestion;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decodes Croatian grocery feeds, which are a mix of UTF-8 and windows-1250.
 *
 * Strategy: try a STRICT UTF-8 decode first. Valid UTF-8 byte sequences are
 * almost never also valid as accidental windows-1250-read-as-UTF-8, so if the
 * bytes decode cleanly as UTF-8 that is the real encoding. Only when the strict
 * decode fails (malformed bytes) do we fall back to windows-1250.
 *
 * This avoids the earlier bug where a single legacy byte caused a whole UTF-8
 * file to be re-read as windows-1250, turning "ČOKOLADA" into "ÄŚOKOLADA".
 */
public final class EncodingUtil {

    private static final Charset WIN1250 = Charset.forName("windows-1250");

    private EncodingUtil() {}

    public static String detectAndDecode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";

        // UTF-8 BOM -> definitely UTF-8.
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }

        // Strict UTF-8: reject malformed/unmappable so we can tell real UTF-8 apart.
        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return utf8.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            // Not valid UTF-8 -> legacy Croatian windows-1250.
            return new String(bytes, WIN1250);
        }
    }
}

package com.stog.backend.place.search;

import java.text.Normalizer;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class PlaceSearchNormalizer {
    public Normalized normalize(String value) {
        if (value == null) {
            return new Normalized("", "");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(normalized.length());
        boolean separatorPending = false;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) {
                if (separatorPending && result.length() > 0) {
                    result.append(' ');
                }
                result.appendCodePoint(codePoint);
                separatorPending = false;
            } else if (result.length() > 0) {
                separatorPending = true;
            }
            offset += Character.charCount(codePoint);
        }
        String normalizedName = result.toString();
        return new Normalized(normalizedName, normalizedName.replace(" ", ""));
    }

    public record Normalized(String normalized_name, String compact_name) {
    }
}

package tn.hydra.document.service;

import org.springframework.stereotype.Component;
import tn.hydra.document.domain.DocType;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Builds the mandatory file name: PRJ-<CODE>_<TYPE>_<Description>_<RevXX>.<ext>
 * The server owns the final name so conventions stay consistent.
 */
@Component
public class FilenameBuilder {

    /** Upper-cased, accent-stripped, non-alphanumeric collapsed to single dashes. */
    public static String sanitize(String value) {
        if (value == null || value.isBlank()) return "DOC";
        String t = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        t = t.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-+|-", "").replaceAll("-+$", "");
        return t.isEmpty() ? "DOC" : t.toUpperCase(Locale.ROOT);
    }

    public static String extension(String originalFilename) {
        if (originalFilename == null) return "bin";
        int i = originalFilename.lastIndexOf('.');
        return (i >= 0 && i < originalFilename.length() - 1)
                ? originalFilename.substring(i + 1).toLowerCase(Locale.ROOT) : "bin";
    }

    public String build(String projectCode, DocType type, String description,
                        String revLabel, String originalFilename) {
        String code = (projectCode == null || projectCode.isBlank()) ? "PRJ" : "PRJ-" + projectCode;
        String typeStr = (type == null) ? "AUTRE" : type.name();
        String rev = (revLabel == null || revLabel.isBlank()) ? "Rev.0" : revLabel.replace(' ', '_');
        return code + "_" + typeStr + "_" + sanitize(description) + "_" + rev
                + "." + extension(originalFilename);
    }
}
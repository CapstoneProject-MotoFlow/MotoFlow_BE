package fu.hl.g64.motorflow.common.text;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * R-01: what a search box compares, so that "nguyen van an" finds "Nguyễn Văn An". Text is folded to lower case
 * without Vietnamese accents, "đ" included. The database does the same with the SQL function {@code search_fold}
 * (V13), so a query compares {@code search_fold(column) like :pattern escape '\'} with {@link #likePattern}.
 */
public final class SearchText {

    /** The SQL function that folds a column the way {@link #fold} folds the search text. */
    public static final String SQL_FUNCTION = "search_fold";

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private SearchText() {
    }

    /** Lower case, without accents. Null stays null. */
    public static String fold(String text) {
        if (text == null) {
            return null;
        }
        String noMarks = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        return noMarks.replace('đ', 'd').replace('Đ', 'd').toLowerCase(Locale.ROOT);
    }

    /**
     * A LIKE pattern matching folded text that contains the folded, trimmed search text anywhere. {@code \ % _}
     * typed by the user are taken literally, so the query must say {@code escape '\'}.
     */
    public static String likePattern(String text) {
        String escaped = fold(text.strip()).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** Criteria form of {@code search_fold(value) like :pattern escape '\'}, with a pattern from {@link #likePattern}. */
    public static Predicate like(CriteriaBuilder cb, Expression<String> value, String pattern) {
        return cb.like(cb.function(SQL_FUNCTION, String.class, value), pattern, '\\');
    }

    /** Whether the folded value contains the folded search text, for lists filtered in memory. */
    public static boolean contains(String value, String foldedNeedle) {
        return value != null && fold(value).contains(foldedNeedle);
    }
}

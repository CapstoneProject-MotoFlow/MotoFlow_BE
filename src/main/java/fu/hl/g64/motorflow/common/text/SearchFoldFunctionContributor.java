package fu.hl.g64.motorflow.common.text;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * Lets JPQL call the SQL function search_fold (V13) as {@code search_fold(x)} returning text, so it can stand on the
 * left of LIKE. Registered in META-INF/services.
 */
public class SearchFoldFunctionContributor implements FunctionContributor {

    @Override
    public void contributeFunctions(FunctionContributions functions) {
        functions.getFunctionRegistry().registerPattern(SearchText.SQL_FUNCTION, SearchText.SQL_FUNCTION + "(?1)",
                functions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.STRING));
    }
}

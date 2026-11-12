package fu.hl.g64.motorflow.support;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared calls and checks for the Report 5.1 unit tests (one test class per function, one method per UTC).
 *
 * <p>Conventions for those classes:
 * <ul>
 *   <li>Class {@code <Function>UnitTest} in the package of the module under test, annotated {@code @Tag("report51")},
 *       extending the module's existing test data ({@code PurchaseOrderTestData}, {@code GoodsReceiptTestData},
 *       {@code SalesOrderTestData}, {@code TransferFlowTestData}, {@code StockCountTestData}) or, where none fits,
 *       {@link ApiTestUsers}. All of them run on {@link IntegrationTest}: embedded PostgreSQL and {@link MutableClock}.</li>
 *   <li>One method per case, {@code utc001()}, with {@code @DisplayName("UTC001 Normal: ...")}; number and type
 *       (Normal, Abnormal, Boundary) as in the specification.</li>
 *   <li>The user comes from the token the test data already signed in ({@code ownerToken}, {@code managerToken},
 *       {@code staffToken}, ... or {@code signIn("username")}); {@link #NO_TOKEN} calls without signing in.</li>
 * </ul>
 *
 * <p>Use: {@code mvc.perform(as(ownerToken, post("/api/v1/purchase-orders"), json(body)))
 * .andExpect(expectError(HttpStatus.BAD_REQUEST, "MSG20"))}.
 */
public final class Report51Support {

    /** Calls the API without an access token. */
    public static final String NO_TOKEN = null;

    private Report51Support() {
    }

    /** {@code request} sent by the user signed in with {@code token}, or by nobody when it is {@link #NO_TOKEN}. */
    public static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, token);
    }

    /** {@code request} with a JSON body, sent by the user signed in with {@code token} (or nobody). */
    public static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request, String json) {
        return as(token, request).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    /** The response is the error {@code code} (for example "MSG05") with HTTP status {@code status}. */
    public static ResultMatcher expectError(HttpStatus status, String code) {
        ResultMatcher httpStatus = status().is(status.value());
        ResultMatcher errorCode = jsonPath("$.code").value(code);
        return result -> {
            httpStatus.match(result);
            errorCode.match(result);
        };
    }
}

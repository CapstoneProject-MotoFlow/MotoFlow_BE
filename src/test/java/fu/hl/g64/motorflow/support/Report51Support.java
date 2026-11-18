package fu.hl.g64.motorflow.support;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    /**
     * Runs {@code a} and {@code b} on two threads released together by a latch, and returns their results in the order
     * a, b. Each call gives up after 30 seconds.
     *
     * <p>Which tool for which concurrency case:
     * <ul>
     *   <li>The same action pressed twice, or by two people at once (two dispatches, two approvals) → this method.
     *       Which request wins is up to the database, so assert on the set of outcomes, e.g.
     *       {@code assertThat(statuses).containsExactlyInAnyOrder(200, 409)}, then the loser's {@code $.code}, then
     *       that the database was written exactly once (movements, balances, lots, documents created).</li>
     *   <li>"No write is lost" (a request must wait for a row another transaction holds) →
     *       {@link IntegrationTest#whileAnotherTransactionHoldsTheRow}, which always puts the request in the middle of
     *       the other transaction instead of relying on timing. See {@code CatalogApiTest} and
     *       {@code CustomerApiTest}. If it fails with "The request never waited for the row lock", the service does
     *       not lock that row: report it as a suspected defect.</li>
     * </ul>
     * Sign in (get tokens) before calling this, so both threads only send the request under test.
     */
    public static List<MvcResult> atTheSameTime(Callable<MvcResult> a, Callable<MvcResult> b) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = pool.submit(released(a, ready, start));
            Future<MvcResult> second = pool.submit(released(b, ready, start));
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("The two requests never got ready to start");
            }
            start.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<MvcResult> released(Callable<MvcResult> call, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            return call.call();
        };
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

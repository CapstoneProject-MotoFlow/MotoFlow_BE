package fu.hl.g64.motorflow.inventory;

import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Checks shared by the stock document API tests (PR D.1). */
public final class StockDocumentChecks {

    private static final String LIST = "/api/v1/stock-documents";
    private static final String DETAIL = "/api/v1/stock-documents/{type}/{sourceId}";

    private StockDocumentChecks() {
    }

    /**
     * The list is the reference for the detail. The Owner lists every warehouse (size 100, so the whole list), then for
     * every row GETs the detail of that document in that warehouse and direction. The summary must equal the row, the
     * number of lines must equal lineCount, and the lines' quantities and values must add up to the summary.
     *
     * @return the number of rows checked
     */
    public static int assertDetailsMatchTheList(MockMvc mvc, JsonMapper json, String ownerToken) throws Exception {
        JsonNode page = json.readTree(mvc.perform(get(LIST).header(HttpHeaders.AUTHORIZATION, ownerToken)
                        .param("size", "100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long total = page.get("totalItems").asLong();
        JsonNode rows = page.get("items");
        assertThat(total).isLessThanOrEqualTo(100);
        assertThat((long) rows.size()).isEqualTo(total);
        for (JsonNode row : rows) {
            String label = row.get("code").asText();
            String body = mvc.perform(get(DETAIL, row.get("sourceType").asText(), row.get("sourceId").asLong())
                            .header(HttpHeaders.AUTHORIZATION, ownerToken)
                            .param("storeId", row.get("storeId").asText())
                            .param("direction", row.get("direction").asText()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JsonNode detail = json.readTree(body);
            assertThat(detail.get("document")).as("summary of %s", label).isEqualTo(row);
            JsonNode lines = detail.get("lines");
            assertThat((long) lines.size()).as("lines of %s", label).isEqualTo(row.get("lineCount").asLong());
            long quantity = 0;
            BigDecimal value = BigDecimal.ZERO;
            for (JsonNode line : lines) {
                quantity += line.get("quantity").asLong();
                if (line.hasNonNull("value")) {
                    value = value.add(line.get("value").decimalValue());
                }
            }
            assertThat(quantity).as("quantity of %s", label).isEqualTo(row.get("quantity").asLong());
            if (row.hasNonNull("value")) {
                assertThat(value.compareTo(row.get("value").decimalValue())).as("value of %s", label).isZero();
            }
        }
        return rows.size();
    }
}

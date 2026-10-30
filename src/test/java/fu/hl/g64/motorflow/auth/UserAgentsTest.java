package fu.hl.g64.motorflow.auth;

import fu.hl.g64.motorflow.auth.service.UserAgents;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class UserAgentsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 | Chrome 141 · Windows",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0 | Edge 141 · Windows",
            "Mozilla/5.0 (Linux; Android 14; SM-A546E) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/25.0 Chrome/121.0.0.0 Mobile Safari/537.36 | Samsung Internet 25 · Android 14",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1 | Safari 17 · iPhone (iOS 17)",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:128.0) Gecko/20100101 Firefox/128.0 | Firefox 128 · macOS",
            "PostmanRuntime/7.43.0 | Postman 7",
            "curl/8.9.1 | curl",
            "Mozilla/5.0 (Windows NT; Windows NT 10.0; en-US) WindowsPowerShell/5.1.26100.9818 | PowerShell 5 · Windows",
            "'' | NULL",
    })
    void describesBrowserAndSystem(String userAgent, String expected) {
        assertThat(UserAgents.describe(userAgent)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = "NULL")
    void missingUserAgentGivesNull(String userAgent) {
        assertThat(UserAgents.describe(userAgent)).isNull();
    }
}

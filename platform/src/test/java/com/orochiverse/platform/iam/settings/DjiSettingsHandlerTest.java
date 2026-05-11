package com.orochiverse.platform.iam.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.orochiverse.platform.iam.admin.common.AdminExceptions.UnprocessableException;
import com.orochiverse.platform.iam.settings.SettingsKindHandler.TestResult;

class DjiSettingsHandlerTest {

    private final ConnectionTester tester = mock(ConnectionTester.class);
    private final DjiSettingsHandler handler = new DjiSettingsHandler(tester);

    @Test
    void kind_and_secret_keys() {
        assertThat(handler.kind()).isEqualTo(SettingsKind.DJI);
        assertThat(handler.secretKeys()).containsExactly("appSecret");
    }

    @Test
    void valid_values_pass() {
        handler.validate(Map.of(
                "region", "ap",
                "endpointUrl", "https://api-cloud.dji.com",
                "appKey", "k", "appSecret", "s"));
    }

    @Test
    void region_constrained() {
        assertThatThrownBy(() -> handler.validate(Map.of(
                "region", "mars", "endpointUrl", "https://x")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("region");
    }

    @Test
    void endpoint_must_be_http_or_https() {
        assertThatThrownBy(() -> handler.validate(Map.of(
                "region", "us", "endpointUrl", "ftp://nope")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("endpointUrl");
    }

    @Test
    void app_key_and_secret_optional_at_validate_time() {
        // Partial config is fine until you actually try to use it.
        handler.validate(Map.of("region", "ap", "endpointUrl", "https://x"));
    }

    @Test
    void test_dispatches_to_http_probe() {
        when(tester.httpProbe("https://api-cloud.dji.com")).thenReturn(TestResult.ok(120));
        TestResult r = handler.test(Map.of("endpointUrl", "https://api-cloud.dji.com"));
        assertThat(r.ok()).isTrue();
    }
}

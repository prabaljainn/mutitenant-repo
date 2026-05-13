package com.orochiverse.platform.iam.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.orochiverse.platform.iam.admin.common.AdminExceptions.UnprocessableException;
import com.orochiverse.platform.iam.settings.SettingsKindHandler.TestResult;

class MqttSettingsHandlerTest {

    private final ConnectionTester tester = mock(ConnectionTester.class);
    private final MqttSettingsHandler handler = new MqttSettingsHandler(tester);

    @Test
    void kind_and_secret_keys_are_what_the_service_dispatches_on() {
        assertThat(handler.kind()).isEqualTo(SettingsKind.MQTT);
        assertThat(handler.secretKeys()).containsExactly("password");
    }

    @Test
    void valid_values_pass() {
        handler.validate(Map.of(
                "host", "mqtt.example.com",
                "port", 8883,
                "transport", "tls",
                "topicPrefix", "cloudgcs/x/",
                "username", "x",
                "password", "y"));
    }

    @Test
    void unknown_keys_rejected_so_typos_dont_get_persisted() {
        assertThatThrownBy(() -> handler.validate(Map.of(
                "host", "h", "port", 1883, "hostName", "oops")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("hostName");
    }

    @Test
    void host_required() {
        assertThatThrownBy(() -> handler.validate(Map.of("port", 8883)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("host");
    }

    @Test
    void port_must_be_in_range() {
        assertThatThrownBy(() -> handler.validate(Map.of("host", "h", "port", 70000)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("between 1 and 65535");
    }

    @Test
    void transport_constrained_to_allowed_set() {
        assertThatThrownBy(() -> handler.validate(Map.of(
                "host", "h", "port", 1883, "transport", "udp")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("transport");
    }

    @Test
    void port_string_is_coerced() {
        // Defensive — JSON sometimes serialises numbers as strings via
        // the SPA's input fields. Handler should accept either.
        handler.validate(Map.of("host", "h", "port", "8883"));
    }

    @Test
    void test_dispatches_to_tcp_probe() {
        when(tester.tcpProbe("mqtt.x.io", 8883)).thenReturn(TestResult.ok(42));
        TestResult r = handler.test(Map.of("host", "mqtt.x.io", "port", 8883));
        assertThat(r.ok()).isTrue();
        assertThat(r.latencyMs()).isEqualTo(42);
    }
}

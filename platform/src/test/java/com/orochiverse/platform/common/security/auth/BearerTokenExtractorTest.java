package com.orochiverse.platform.common.security.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class BearerTokenExtractorTest {

    @Test
    void extracts_a_well_formed_bearer_token() {
        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer abc.def.ghi");

        assertThat(BearerTokenExtractor.extract(req)).contains("abc.def.ghi");
    }

    @Test
    void scheme_match_is_case_insensitive() {
        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "bearer abc");

        assertThat(BearerTokenExtractor.extract(req)).contains("abc");
    }

    @Test
    void trims_surrounding_whitespace_from_the_header_and_token() {
        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "  Bearer    abc.def.ghi  ");

        assertThat(BearerTokenExtractor.extract(req)).contains("abc.def.ghi");
    }

    @Test
    void returns_empty_when_header_is_missing() {
        assertThat(BearerTokenExtractor.extract(new MockHttpServletRequest())).isEmpty();
    }

    @Test
    void returns_empty_when_scheme_is_not_bearer() {
        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        assertThat(BearerTokenExtractor.extract(req)).isEmpty();
    }

    @Test
    void returns_empty_when_token_part_is_blank() {
        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer    ");

        assertThat(BearerTokenExtractor.extract(req)).isEmpty();
    }

    @Test
    void returns_empty_when_only_the_scheme_is_present() {
        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer");

        assertThat(BearerTokenExtractor.extract(req)).isEmpty();
    }
}

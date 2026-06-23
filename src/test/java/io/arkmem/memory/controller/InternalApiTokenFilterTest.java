package io.arkmem.memory.controller;

import io.arkmem.memory.config.ArkMemProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InternalApiTokenFilterTest {

    @Test
    void allowsRequestsWhenTokenIsNotConfigured() throws Exception {
        InternalApiTokenFilter filter = new InternalApiTokenFilter(new ArkMemProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/memories");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsMemoryApiWithoutMatchingToken() throws Exception {
        ArkMemProperties properties = new ArkMemProperties();
        properties.getApi().setInternalToken("secret-token");
        InternalApiTokenFilter filter = new InternalApiTokenFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/memories");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void acceptsBearerAndApiKeyHeaders() throws Exception {
        ArkMemProperties properties = new ArkMemProperties();
        properties.getApi().setInternalToken("secret-token");
        InternalApiTokenFilter filter = new InternalApiTokenFilter(properties);

        MockHttpServletRequest bearerRequest = new MockHttpServletRequest("POST", "/search");
        bearerRequest.addHeader("Authorization", "Bearer secret-token");
        MockHttpServletResponse bearerResponse = new MockHttpServletResponse();
        filter.doFilter(bearerRequest, bearerResponse, new MockFilterChain());
        assertEquals(200, bearerResponse.getStatus());

        MockHttpServletRequest apiKeyRequest = new MockHttpServletRequest("DELETE", "/memories/id-1");
        apiKeyRequest.addHeader("X-API-Key", "secret-token");
        MockHttpServletResponse apiKeyResponse = new MockHttpServletResponse();
        filter.doFilter(apiKeyRequest, apiKeyResponse, new MockFilterChain());
        assertEquals(200, apiKeyResponse.getStatus());
    }

    @Test
    void leavesHealthAndConfigEndpointsPublic() throws Exception {
        ArkMemProperties properties = new ArkMemProperties();
        properties.getApi().setInternalToken("secret-token");
        InternalApiTokenFilter filter = new InternalApiTokenFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ready");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }
}

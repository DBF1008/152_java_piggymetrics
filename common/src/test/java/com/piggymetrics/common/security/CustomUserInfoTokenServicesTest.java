package com.piggymetrics.common.security;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2ClientContext;
import org.springframework.security.oauth2.client.OAuth2RestOperations;
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException;
import org.springframework.security.oauth2.provider.OAuth2Authentication;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class CustomUserInfoTokenServicesTest {

    private static final String USERINFO_URI = "http://localhost:5000/uaa/users/current";
    private static final String CLIENT_ID = "test-client";

    private CustomUserInfoTokenServices tokenServices;

    @Mock
    private OAuth2RestOperations restTemplate;

    @Mock
    private OAuth2ClientContext clientContext;

    @Before
    public void setup() {
        initMocks(this);
        tokenServices = new CustomUserInfoTokenServices(USERINFO_URI, CLIENT_ID);
        tokenServices.setRestTemplate(restTemplate);

        when(restTemplate.getOAuth2ClientContext()).thenReturn(clientContext);
        when(clientContext.getAccessToken()).thenReturn(null);
    }

    @Test
    public void shouldLoadAuthenticationWithValidUserinfoResponse() {
        Map<String, Object> userinfo = new HashMap<>();
        userinfo.put("user", "john");

        Map<String, Object> oauth2Request = new HashMap<>();
        oauth2Request.put("clientId", "account-service");
        oauth2Request.put("scope", Arrays.asList("server", "read"));
        userinfo.put("oauth2Request", oauth2Request);

        when(restTemplate.getForEntity(ArgumentMatchers.eq(USERINFO_URI), ArgumentMatchers.eq(Map.class)))
                .thenReturn(new ResponseEntity<>(userinfo, HttpStatus.OK));

        OAuth2Authentication auth = tokenServices.loadAuthentication("valid-token");

        assertEquals("john", auth.getPrincipal());
        assertEquals("account-service", auth.getOAuth2Request().getClientId());
        assertTrue(auth.getOAuth2Request().getScope().contains("server"));
        assertTrue(auth.getOAuth2Request().getScope().contains("read"));
        assertEquals(2, auth.getOAuth2Request().getScope().size());
    }

    @Test(expected = InvalidTokenException.class)
    public void shouldThrowInvalidTokenExceptionWhenUserinfoContainsError() {
        Map<String, Object> errorMap = new HashMap<>();
        errorMap.put("error", "invalid_token");

        when(restTemplate.getForEntity(ArgumentMatchers.anyString(), ArgumentMatchers.eq(Map.class)))
                .thenReturn(new ResponseEntity<>(errorMap, HttpStatus.OK));

        tokenServices.loadAuthentication("bad-token");
    }

    @Test(expected = InvalidTokenException.class)
    public void shouldThrowInvalidTokenExceptionWhenRestCallFails() {
        when(restTemplate.getForEntity(ArgumentMatchers.anyString(), ArgumentMatchers.eq(Map.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        tokenServices.loadAuthentication("any-token");
    }

    @Test
    public void shouldReturnUnknownPrincipalWhenNoPrincipalKeysPresent() {
        Map<String, Object> userinfo = new HashMap<>();
        Map<String, Object> oauth2Request = new HashMap<>();
        oauth2Request.put("clientId", CLIENT_ID);
        oauth2Request.put("scope", Collections.emptyList());
        userinfo.put("oauth2Request", oauth2Request);

        when(restTemplate.getForEntity(ArgumentMatchers.anyString(), ArgumentMatchers.eq(Map.class)))
                .thenReturn(new ResponseEntity<>(userinfo, HttpStatus.OK));

        OAuth2Authentication auth = tokenServices.loadAuthentication("token");

        assertEquals("unknown", auth.getPrincipal());
        assertEquals(CLIENT_ID, auth.getOAuth2Request().getClientId());
    }

    @Test
    public void shouldReturnEmptyScopeWhenOauth2RequestHasNoScope() {
        Map<String, Object> userinfo = new HashMap<>();
        userinfo.put("user", "john");

        Map<String, Object> oauth2Request = new HashMap<>();
        oauth2Request.put("clientId", CLIENT_ID);
        userinfo.put("oauth2Request", oauth2Request);

        when(restTemplate.getForEntity(ArgumentMatchers.anyString(), ArgumentMatchers.eq(Map.class)))
                .thenReturn(new ResponseEntity<>(userinfo, HttpStatus.OK));

        OAuth2Authentication auth = tokenServices.loadAuthentication("token");

        assertEquals("john", auth.getPrincipal());
        assertNotNull(auth.getOAuth2Request().getScope());
        assertTrue(auth.getOAuth2Request().getScope().isEmpty());
    }

    @Test(expected = NullPointerException.class)
    public void shouldThrowNullPointerExceptionWhenOauth2RequestIsMissing() {
        Map<String, Object> userinfo = new HashMap<>();
        userinfo.put("user", "john");

        when(restTemplate.getForEntity(ArgumentMatchers.anyString(), ArgumentMatchers.eq(Map.class)))
                .thenReturn(new ResponseEntity<>(userinfo, HttpStatus.OK));

        tokenServices.loadAuthentication("token");
    }

    @Test
    public void shouldUseFirstMatchingPrincipalKey() {
        Map<String, Object> userinfo = new HashMap<>();
        userinfo.put("username", "from-username");
        userinfo.put("name", "from-name");

        Map<String, Object> oauth2Request = new HashMap<>();
        oauth2Request.put("clientId", CLIENT_ID);
        oauth2Request.put("scope", Collections.emptyList());
        userinfo.put("oauth2Request", oauth2Request);

        when(restTemplate.getForEntity(ArgumentMatchers.anyString(), ArgumentMatchers.eq(Map.class)))
                .thenReturn(new ResponseEntity<>(userinfo, HttpStatus.OK));

        OAuth2Authentication auth = tokenServices.loadAuthentication("token");

        assertEquals("from-username", auth.getPrincipal());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void shouldThrowUnsupportedExceptionOnReadAccessToken() {
        tokenServices.readAccessToken("any-token");
    }
}

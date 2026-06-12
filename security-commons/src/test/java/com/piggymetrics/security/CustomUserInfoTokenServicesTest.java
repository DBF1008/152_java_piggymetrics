package com.piggymetrics.security;

import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2ClientContext;
import org.springframework.security.oauth2.client.OAuth2RestOperations;
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.OAuth2Request;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests pinning the behaviour of the shared resource-server token services.
 *
 * The service rebuilds an {@link OAuth2Request} carrying clientId and scope extracted from the
 * userinfo endpoint; that scope is what powers the controllers' {@code #oauth2.hasScope('server')}
 * checks. These tests lock in the current behaviour across normal, error and missing-field cases so
 * future permission changes can't silently drift.
 */
public class CustomUserInfoTokenServicesTest {

	private static final String USER_INFO_URI = "http://localhost/uaa/users/current";
	private static final String ACCESS_TOKEN = "token-value";

	private OAuth2RestOperations restTemplate;
	private CustomUserInfoTokenServices tokenServices;

	@Before
	public void setup() {
		restTemplate = mock(OAuth2RestOperations.class);
		OAuth2ClientContext clientContext = mock(OAuth2ClientContext.class);
		when(restTemplate.getOAuth2ClientContext()).thenReturn(clientContext);
		when(clientContext.getAccessToken()).thenReturn(null);

		tokenServices = new CustomUserInfoTokenServices(USER_INFO_URI, "account-service");
		tokenServices.setRestTemplate(restTemplate);
	}

	// --- normal token ---

	@Test
	public void shouldExtractClientIdAndScopeFromValidToken() {
		Map<String, Object> userInfo = new HashMap<>();
		userInfo.put("name", "test-user");
		userInfo.put("oauth2Request", oauth2Request("browser", Arrays.asList("ui", "server")));
		stubUserInfo(userInfo);

		OAuth2Authentication authentication = tokenServices.loadAuthentication(ACCESS_TOKEN);

		assertEquals("test-user", authentication.getName());
		assertTrue(authentication.isAuthenticated());

		OAuth2Request request = authentication.getOAuth2Request();
		assertEquals("browser", request.getClientId());
		assertTrue(request.isApproved());
		Set<String> scope = request.getScope();
		assertTrue(scope.contains("ui"));
		assertTrue(scope.contains("server"));
	}

	@Test
	public void shouldExposeServerScopeForServerClient() {
		stubUserInfo(userInfoWith("statistics-service", oauth2Request("statistics-service", Arrays.asList("server"))));
		assertTrue(tokenServices.loadAuthentication(ACCESS_TOKEN).getOAuth2Request().getScope().contains("server"));

		stubUserInfo(userInfoWith("demo", oauth2Request("browser", Arrays.asList("ui"))));
		assertFalse(tokenServices.loadAuthentication(ACCESS_TOKEN).getOAuth2Request().getScope().contains("server"));
	}

	// --- exception / error return ---

	@Test(expected = InvalidTokenException.class)
	public void shouldThrowInvalidTokenWhenUserInfoReturnsError() {
		Map<String, Object> error = new HashMap<>();
		error.put("error", "invalid_token");
		stubUserInfo(error);

		tokenServices.loadAuthentication(ACCESS_TOKEN);
	}

	@Test(expected = InvalidTokenException.class)
	public void shouldThrowInvalidTokenWhenUserInfoCallFails() {
		when(restTemplate.getForEntity(anyString(), eq(Map.class)))
				.thenThrow(new RuntimeException("userinfo endpoint unavailable"));

		tokenServices.loadAuthentication(ACCESS_TOKEN);
	}

	// --- missing fields ---

	@Test
	public void shouldDefaultToEmptyScopeWhenScopeMissing() {
		stubUserInfo(userInfoWith("test-user", oauth2Request("browser", null)));

		OAuth2Request request = tokenServices.loadAuthentication(ACCESS_TOKEN).getOAuth2Request();

		assertEquals("browser", request.getClientId());
		assertTrue(request.getScope().isEmpty());
	}

	@Test
	public void shouldFallBackToUnknownPrincipalWhenPrincipalMissing() {
		Map<String, Object> userInfo = new HashMap<>();
		userInfo.put("oauth2Request", oauth2Request("browser", Arrays.asList("server")));
		stubUserInfo(userInfo);

		assertEquals("unknown", tokenServices.loadAuthentication(ACCESS_TOKEN).getName());
	}

	// --- helpers ---

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void stubUserInfo(Map<String, Object> body) {
		when(restTemplate.getForEntity(anyString(), eq(Map.class)))
				.thenReturn(new ResponseEntity<Map>(body, HttpStatus.OK));
	}

	private static Map<String, Object> userInfoWith(String principal, Map<String, Object> oauth2Request) {
		Map<String, Object> userInfo = new HashMap<>();
		userInfo.put("name", principal);
		userInfo.put("oauth2Request", oauth2Request);
		return userInfo;
	}

	private static Map<String, Object> oauth2Request(String clientId, List<String> scope) {
		Map<String, Object> request = new HashMap<>();
		request.put("clientId", clientId);
		if (scope != null) {
			request.put("scope", scope);
		}
		return request;
	}
}

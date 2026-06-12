package com.piggymetrics.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piggymetrics.auth.domain.User;
import com.piggymetrics.auth.repository.UserRepository;
import com.piggymetrics.auth.service.UserServiceImpl;
import com.sun.security.auth.UserPrincipal;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-to-service regression tests for {@link UserController}.
 *
 * Uses a REAL {@link UserServiceImpl} with a mocked {@link UserRepository},
 * so the full path — request deserialization, service logic (duplicate check,
 * BCrypt hashing), error handling — is exercised end-to-end without a database.
 *
 * Covers:
 *   1. Successful registration (password is BCrypt-hashed before persist)
 *   2. Duplicate username → 400 via ErrorHandler
 *   3. Missing request body → 400
 *   4. GET /users/current returns the authenticated principal
 */
@RunWith(SpringRunner.class)
public class UserControllerServiceIntegrationTest {

	private static final ObjectMapper mapper = new ObjectMapper();
	private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

	private UserController controller;

	@Mock
	private UserRepository userRepository;

	private MockMvc mockMvc;

	@Before
	public void setup() {
		initMocks(this);

		// Real service — the whole point is to test controller → service wiring
		UserServiceImpl userService = new UserServiceImpl();
		ReflectionTestUtils.setField(userService, "repository", userRepository);

		controller = new UserController();
		ReflectionTestUtils.setField(controller, "userService", userService);

		// Register the controller advice so service-level IllegalArgumentException
		// is translated into an HTTP 400 (mirrors production behaviour)
		this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new ErrorHandler())
				.build();
	}

	// ---------------------------------------------------------------
	// 1. Registration — happy path
	// ---------------------------------------------------------------

	@Test
	public void shouldCreateUserAndHashPassword() throws Exception {
		User user = new User();
		user.setUsername("testuser");
		user.setPassword("testpassword");

		mockMvc.perform(post("/users")
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(user)))
				.andExpect(status().isOk());

		// Verify the password was BCrypt-hashed before being saved
		ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
		verify(userRepository, times(1)).save(captor.capture());
		User saved = captor.getValue();
		assertNotNull("saved user must not be null", saved);
		assertTrue("password must be BCrypt-hashed before persist",
				encoder.matches("testpassword", saved.getPassword()));
	}

	// ---------------------------------------------------------------
	// 2. Duplicate username → 400
	// ---------------------------------------------------------------

	@Test
	public void shouldReturn400WhenUsernameAlreadyExists() throws Exception {
		// Simulate an existing user in the repository
		User existing = new User();
		existing.setUsername("testuser");
		existing.setPassword("hashed");
		when(userRepository.findById("testuser")).thenReturn(Optional.of(existing));

		User duplicate = new User();
		duplicate.setUsername("testuser");
		duplicate.setPassword("password");

		mockMvc.perform(post("/users")
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(duplicate)))
				.andExpect(status().isBadRequest());

		// Service must NOT have saved the duplicate
		verify(userRepository, never()).save(any());
	}

	// ---------------------------------------------------------------
	// 3. Illegal / malformed request → 400
	// ---------------------------------------------------------------

	@Test
	public void shouldReturn400WhenRequestBodyIsMissing() throws Exception {
		mockMvc.perform(post("/users")
						.contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest());
	}

	// ---------------------------------------------------------------
	// 4. Current user endpoint
	// ---------------------------------------------------------------

	@Test
	public void shouldReturnCurrentUserFromPrincipal() throws Exception {
		mockMvc.perform(get("/users/current")
						.principal(new UserPrincipal("test")))
				.andExpect(status().isOk());
	}
}

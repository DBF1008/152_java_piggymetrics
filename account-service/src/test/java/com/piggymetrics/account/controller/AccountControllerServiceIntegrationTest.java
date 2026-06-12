package com.piggymetrics.account.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.piggymetrics.account.client.AuthServiceClient;
import com.piggymetrics.account.client.StatisticsServiceClient;
import com.piggymetrics.account.domain.*;
import com.piggymetrics.account.repository.AccountRepository;
import com.piggymetrics.account.service.AccountServiceImpl;
import com.sun.security.auth.UserPrincipal;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-to-service regression tests for {@link AccountController}.
 *
 * Uses a REAL {@link AccountServiceImpl} with mocked infrastructure
 * ({@link AccountRepository}, {@link AuthServiceClient}, {@link StatisticsServiceClient}),
 * so the full path — Bean Validation, service logic (duplicate check, default Saving,
 * field merge, Assert guards), ErrorHandler mapping — is exercised end-to-end.
 *
 * Covers:
 *   1. Registration — happy path, duplicate account, validation boundaries
 *   2. Current-account update — happy path, non-existent account, invalid nested Saving
 */
@RunWith(SpringRunner.class)
public class AccountControllerServiceIntegrationTest {

	private static final ObjectMapper mapper = new ObjectMapper();

	private AccountController controller;

	@Mock
	private AccountRepository accountRepository;

	@Mock
	private AuthServiceClient authClient;

	@Mock
	private StatisticsServiceClient statisticsClient;

	private MockMvc mockMvc;

	@Before
	public void setup() {
		initMocks(this);

		// Real service — we want to test controller → service wiring
		AccountServiceImpl accountService = new AccountServiceImpl();
		ReflectionTestUtils.setField(accountService, "repository", accountRepository);
		ReflectionTestUtils.setField(accountService, "authClient", authClient);
		ReflectionTestUtils.setField(accountService, "statisticsClient", statisticsClient);

		controller = new AccountController();
		ReflectionTestUtils.setField(controller, "accountService", accountService);

		// Register ErrorHandler so service-level IllegalArgumentException
		// is translated into HTTP 400 (same as production)
		this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new ErrorHandler())
				.build();
	}

	// ---------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------

	private Saving createValidSaving() {
		Saving saving = new Saving();
		saving.setAmount(new BigDecimal(1500));
		saving.setCurrency(Currency.USD);
		saving.setInterest(new BigDecimal("3.32"));
		saving.setDeposit(true);
		saving.setCapitalization(false);
		return saving;
	}

	private Account createValidAccount(String name) {
		Account account = new Account();
		account.setName(name);
		account.setSaving(createValidSaving());
		return account;
	}

	// ---------------------------------------------------------------
	// 1. Registration — happy path
	// ---------------------------------------------------------------

	@Test
	public void shouldRegisterNewAccountAndCallAuthService() throws Exception {
		User user = new User();
		user.setUsername("testuser");
		user.setPassword("password123");

		mockMvc.perform(post("/")
						.principal(new UserPrincipal("testuser"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(user)))
				.andExpect(status().isOk());

		// Service must have called auth-service to create the user
		verify(authClient, times(1)).createUser(any(User.class));
		// Service must have persisted the account
		verify(accountRepository, times(1)).save(any(Account.class));
	}

	// ---------------------------------------------------------------
	// 2. Duplicate username → 400
	// ---------------------------------------------------------------

	@Test
	public void shouldReturn400WhenAccountAlreadyExists() throws Exception {
		Account existing = new Account();
		existing.setName("testuser");
		when(accountRepository.findByName("testuser")).thenReturn(existing);

		User user = new User();
		user.setUsername("testuser");
		user.setPassword("password123");

		mockMvc.perform(post("/")
						.principal(new UserPrincipal("testuser"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(user)))
				.andExpect(status().isBadRequest());

		// Must NOT have called auth-service or saved the account
		verify(authClient, never()).createUser(any());
		verify(accountRepository, never()).save(any());
	}

	// ---------------------------------------------------------------
	// 3. Validation boundaries — username
	// ---------------------------------------------------------------

	@Test
	public void shouldReturn400WhenUsernameTooShort() throws Exception {
		User user = new User();
		user.setUsername("ab");       // min = 3
		user.setPassword("password123");

		mockMvc.perform(post("/")
						.principal(new UserPrincipal("test"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(user)))
				.andExpect(status().isBadRequest());
	}

	@Test
	public void shouldReturn400WhenUsernameTooLong() throws Exception {
		User user = new User();
		user.setUsername("a2345678901234567890x");  // 21 chars, max = 20
		user.setPassword("password123");

		mockMvc.perform(post("/")
						.principal(new UserPrincipal("test"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(user)))
				.andExpect(status().isBadRequest());
	}

	@Test
	public void shouldReturn400WhenUsernameNull() throws Exception {
		User user = new User();
		// username left null — @NotNull must reject
		user.setPassword("password123");

		mockMvc.perform(post("/")
						.principal(new UserPrincipal("test"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(user)))
				.andExpect(status().isBadRequest());
	}

	// ---------------------------------------------------------------
	// 4. Validation boundaries — password
	// ---------------------------------------------------------------

	@Test
	public void shouldReturn400WhenPasswordTooShort() throws Exception {
		User user = new User();
		user.setUsername("testuser");
		user.setPassword("12345");    // min = 6

		mockMvc.perform(post("/")
						.principal(new UserPrincipal("test"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(user)))
				.andExpect(status().isBadRequest());
	}

	@Test
	public void shouldReturn400WhenPasswordTooLong() throws Exception {
		User user = new User();
		user.setUsername("testuser");
		user.setPassword(new String(new char[41]).replace('\0', 'a'));  // 41 chars, max = 40

		mockMvc.perform(post("/")
						.principal(new UserPrincipal("test"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(user)))
				.andExpect(status().isBadRequest());
	}

	@Test
	public void shouldReturn400WhenPasswordNull() throws Exception {
		User user = new User();
		user.setUsername("testuser");
		// password left null — @NotNull must reject

		mockMvc.perform(post("/")
						.principal(new UserPrincipal("test"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(user)))
				.andExpect(status().isBadRequest());
	}

	@Test
	public void shouldReturn400WhenRegistrationBodyMissing() throws Exception {
		mockMvc.perform(post("/")
						.principal(new UserPrincipal("test"))
						.contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest());
	}

	// ---------------------------------------------------------------
	// 5. Current-account update — happy path
	// ---------------------------------------------------------------

	@Test
	public void shouldSaveCurrentAccountChanges() throws Exception {
		Account existing = createValidAccount("testuser");
		when(accountRepository.findByName("testuser")).thenReturn(existing);

		Account update = createValidAccount("testuser");
		update.setNote("updated note");

		mockMvc.perform(put("/current")
						.principal(new UserPrincipal("testuser"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(update)))
				.andExpect(status().isOk());

		// Service must have persisted the changes
		verify(accountRepository, times(1)).save(any(Account.class));
		// Service must have triggered statistics update
		verify(statisticsClient, times(1)).updateStatistics(eq("testuser"), any(Account.class));
	}

	// ---------------------------------------------------------------
	// 6. Current-account update — non-existent account → 400
	// ---------------------------------------------------------------

	@Test
	public void shouldReturn400WhenUpdatingNonExistentAccount() throws Exception {
		// No account in the repository → service throws IllegalArgumentException
		when(accountRepository.findByName("nobody")).thenReturn(null);

		Account update = createValidAccount("nobody");

		mockMvc.perform(put("/current")
						.principal(new UserPrincipal("nobody"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(update)))
				.andExpect(status().isBadRequest());

		verify(accountRepository, never()).save(any());
	}

	// ---------------------------------------------------------------
	// 7. Current-account update — invalid nested Saving → 400
	// ---------------------------------------------------------------

	@Test
	public void shouldReturn400WhenSavingFieldsInvalid() throws Exception {
		Account existing = createValidAccount("testuser");
		when(accountRepository.findByName("testuser")).thenReturn(existing);

		// Saving with all @NotNull fields left null
		Account update = new Account();
		update.setName("testuser");
		update.setSaving(new Saving());   // amount/currency/interest/deposit/capitalization all null

		mockMvc.perform(put("/current")
						.principal(new UserPrincipal("testuser"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(update)))
				.andExpect(status().isBadRequest());
	}

	@Test
	public void shouldReturn400WhenAccountSavingMissing() throws Exception {
		Account existing = createValidAccount("testuser");
		when(accountRepository.findByName("testuser")).thenReturn(existing);

		// Account with saving = null — @NotNull on saving must reject
		Account update = new Account();
		update.setName("testuser");

		mockMvc.perform(put("/current")
						.principal(new UserPrincipal("testuser"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(update)))
				.andExpect(status().isBadRequest());
	}
}

package com.piggymetrics.account.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piggymetrics.account.client.AuthServiceClient;
import com.piggymetrics.account.client.StatisticsServiceClient;
import com.piggymetrics.account.domain.*;
import com.piggymetrics.account.repository.AccountRepository;
import com.piggymetrics.account.service.AccountServiceImpl;
import com.sun.security.auth.UserPrincipal;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression net for the account creation ({@code POST /}) and current-account save
 * ({@code PUT /current}) endpoints, exercised <em>through the controller into the real
 * service</em>.
 *
 * <p>Unlike {@link AccountControllerTest}, which mocks {@link com.piggymetrics.account.service.AccountService}
 * and therefore only checks the controller in isolation, this test wires the real
 * {@link AccountController} to a real {@link AccountServiceImpl} and mocks only the genuine
 * external boundaries: the persistence layer ({@link AccountRepository}) and the two remote
 * services ({@link AuthServiceClient}, {@link StatisticsServiceClient}). The real
 * {@link ErrorHandler} advice is registered so exception-to-HTTP mapping is covered too.
 *
 * <p>It locks the behaviours that have historically regressed when this code is edited:
 * <ul>
 *   <li><b>username / password validation</b> rejects bad input at the boundary before the
 *       service or auth-service is ever reached;</li>
 *   <li><b>the authorization boundary</b> — {@code /current} operations are scoped to the
 *       authenticated {@link java.security.Principal}, never to an attacker-controlled request body;</li>
 *   <li><b>error responses</b> — duplicate username and "account not found" surface as HTTP 400;</li>
 *   <li><b>auth-service linkage</b> — a new account creates exactly one auth user, and a
 *       rejected request (duplicate or invalid) creates none;</li>
 *   <li><b>statistics-service linkage</b> — a successful update pushes statistics keyed by the
 *       authenticated name, and a rejected update pushes none.</li>
 * </ul>
 */
public class AccountControllerServiceRegressionTest {

	private static final ObjectMapper mapper = new ObjectMapper();

	@InjectMocks
	private AccountServiceImpl accountService;

	@Mock
	private AccountRepository repository;

	@Mock
	private AuthServiceClient authClient;

	@Mock
	private StatisticsServiceClient statisticsClient;

	private MockMvc mockMvc;

	@Before
	public void setup() {
		initMocks(this);

		// Real controller -> real service; only the external boundaries above are mocked.
		AccountController controller = new AccountController();
		ReflectionTestUtils.setField(controller, "accountService", accountService);

		this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new ErrorHandler())
				.build();
	}

	// ----------------------------------------------------------------------------------------
	// Scenario 1: registration (POST /) — happy path through the service, with auth linkage
	// ----------------------------------------------------------------------------------------

	@Test
	public void shouldRegisterNewAccountThroughServiceAndTriggerAuthCreateUser() throws Exception {

		when(repository.findByName("johnsmith")).thenReturn(null);

		String json = mapper.writeValueAsString(validUser("johnsmith"));

		mockMvc.perform(post("/")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("johnsmith"))
				.andExpect(jsonPath("$.saving.amount").value(0))
				.andExpect(jsonPath("$.saving.currency").value(Currency.getDefault().name()))
				.andExpect(jsonPath("$.saving.deposit").value(false))
				.andExpect(jsonPath("$.saving.capitalization").value(false));

		// auth linkage: exactly one user created, carrying the submitted credentials
		ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
		verify(authClient, times(1)).createUser(userCaptor.capture());
		assertEquals("johnsmith", userCaptor.getValue().getUsername());
		assertEquals("password", userCaptor.getValue().getPassword());

		// and the account is persisted under the requested name
		ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
		verify(repository, times(1)).save(accountCaptor.capture());
		assertEquals("johnsmith", accountCaptor.getValue().getName());
	}

	// ----------------------------------------------------------------------------------------
	// Scenario 2: duplicate username (POST /) — 400, and auth linkage must NOT fire
	// ----------------------------------------------------------------------------------------

	@Test
	public void shouldRejectDuplicateUsernameAndNotTouchAuthOrPersistence() throws Exception {

		when(repository.findByName("existing")).thenReturn(new Account());

		String json = mapper.writeValueAsString(validUser("existing"));

		mockMvc.perform(post("/")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isBadRequest());

		// the duplicate check must short-circuit before any auth user or account is created
		verify(authClient, never()).createUser(any(User.class));
		verify(repository, never()).save(any(Account.class));
	}

	// ----------------------------------------------------------------------------------------
	// Scenario 3: invalid request — username/password rules and body validation, at the boundary
	// ----------------------------------------------------------------------------------------

	@Test
	public void shouldRejectRegistrationWhenUsernameTooShort() throws Exception {

		String json = mapper.writeValueAsString(validUser("ab")); // username min length is 3

		mockMvc.perform(post("/")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isBadRequest());

		// validation rejects before the service runs: neither persistence nor auth is touched
		verify(repository, never()).findByName(anyString());
		verify(authClient, never()).createUser(any(User.class));
	}

	@Test
	public void shouldRejectRegistrationWhenPasswordTooShort() throws Exception {

		User user = new User();
		user.setUsername("validname");
		user.setPassword("123"); // password min length is 6

		String json = mapper.writeValueAsString(user);

		mockMvc.perform(post("/")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isBadRequest());

		verify(repository, never()).findByName(anyString());
		verify(authClient, never()).createUser(any(User.class));
	}

	@Test
	public void shouldRejectCurrentAccountUpdateWhenBodyInvalid() throws Exception {

		Account invalid = new Account();
		invalid.setName("demo"); // no saving -> violates @NotNull on Account.saving

		String json = mapper.writeValueAsString(invalid);

		mockMvc.perform(put("/current")
						.principal(new UserPrincipal("demo"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isBadRequest());

		// validation rejects before the service runs: nothing saved, no statistics update
		verify(repository, never()).save(any(Account.class));
		verify(statisticsClient, never()).updateStatistics(anyString(), any(Account.class));
	}

	// ----------------------------------------------------------------------------------------
	// Scenario 4: current account update (PUT /current) — service path, linkage and authz scope
	// ----------------------------------------------------------------------------------------

	@Test
	public void shouldUpdateCurrentAccountThroughServiceAndTriggerStatistics() throws Exception {

		when(repository.findByName("demo")).thenReturn(new Account());

		String json = mapper.writeValueAsString(validAccountUpdate());

		mockMvc.perform(put("/current")
						.principal(new UserPrincipal("demo"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isOk());

		// statistics linkage fires once, keyed by the authenticated account name
		verify(repository, times(1)).save(any(Account.class));
		verify(statisticsClient, times(1)).updateStatistics(eq("demo"), any(Account.class));
	}

	@Test
	public void shouldScopeCurrentAccountUpdateToPrincipalNotRequestBody() throws Exception {

		when(repository.findByName("demo")).thenReturn(new Account());

		Account body = validAccountUpdate();
		body.setName("attacker"); // attacker-controlled body name must be ignored

		String json = mapper.writeValueAsString(body);

		mockMvc.perform(put("/current")
						.principal(new UserPrincipal("demo"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isOk());

		// the update is resolved from the principal, never from the body name (authz boundary)
		verify(repository).findByName("demo");
		verify(repository, never()).findByName("attacker");

		ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
		verify(statisticsClient, times(1)).updateStatistics(nameCaptor.capture(), any(Account.class));
		assertEquals("demo", nameCaptor.getValue());
	}

	@Test
	public void shouldRejectCurrentAccountUpdateWhenAccountMissing() throws Exception {

		when(repository.findByName("ghost")).thenReturn(null);

		String json = mapper.writeValueAsString(validAccountUpdate());

		mockMvc.perform(put("/current")
						.principal(new UserPrincipal("ghost"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isBadRequest());

		// a missing account is a 400 and must not push statistics
		verify(statisticsClient, never()).updateStatistics(anyString(), any(Account.class));
	}

	// ----------------------------------------------------------------------------------------
	// helpers
	// ----------------------------------------------------------------------------------------

	private User validUser(String username) {
		User user = new User();
		user.setUsername(username);
		user.setPassword("password");
		return user;
	}

	private Saving validSaving() {
		Saving saving = new Saving();
		saving.setAmount(new BigDecimal(1500));
		saving.setCurrency(Currency.USD);
		saving.setInterest(new BigDecimal("3.32"));
		saving.setDeposit(true);
		saving.setCapitalization(false);
		return saving;
	}

	private Account validAccountUpdate() {
		Item salary = new Item();
		salary.setTitle("Salary");
		salary.setAmount(new BigDecimal(9100));
		salary.setCurrency(Currency.USD);
		salary.setPeriod(TimePeriod.MONTH);
		salary.setIcon("wallet");

		Account account = new Account();
		account.setName("body-name");
		account.setNote("regression note");
		account.setSaving(validSaving());
		account.setIncomes(Collections.singletonList(salary));
		return account;
	}
}

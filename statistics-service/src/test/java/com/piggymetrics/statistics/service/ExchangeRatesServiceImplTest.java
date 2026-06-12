package com.piggymetrics.statistics.service;

import com.google.common.collect.ImmutableMap;
import com.piggymetrics.statistics.client.ExchangeRatesClient;
import com.piggymetrics.statistics.domain.Currency;
import com.piggymetrics.statistics.domain.ExchangeRatesContainer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class ExchangeRatesServiceImplTest {

	@InjectMocks
	private ExchangeRatesServiceImpl ratesService;

	@Mock
	private ExchangeRatesClient client;

	@Before
	public void setup() {
		initMocks(this);
	}

	@Test
	public void shouldReturnCurrentRatesWhenContainerIsEmptySoFar() {

		ExchangeRatesContainer container = new ExchangeRatesContainer();
		container.setRates(ImmutableMap.of(
				Currency.EUR.name(), new BigDecimal("0.8"),
				Currency.RUB.name(), new BigDecimal("80")
		));

		when(client.getRates(Currency.getBase())).thenReturn(container);

		Map<Currency, BigDecimal> result = ratesService.getCurrentRates();
		verify(client, times(1)).getRates(Currency.getBase());

		assertEquals(container.getRates().get(Currency.EUR.name()), result.get(Currency.EUR));
		assertEquals(container.getRates().get(Currency.RUB.name()), result.get(Currency.RUB));
		assertEquals(BigDecimal.ONE, result.get(Currency.USD));
	}

	@Test
	public void shouldNotRequestRatesWhenTodaysContainerAlreadyExists() {

		ExchangeRatesContainer container = new ExchangeRatesContainer();
		container.setRates(ImmutableMap.of(
				Currency.EUR.name(), new BigDecimal("0.8"),
				Currency.RUB.name(), new BigDecimal("80")
		));

		when(client.getRates(Currency.getBase())).thenReturn(container);

		// initialize container
		ratesService.getCurrentRates();

		// use existing container
		ratesService.getCurrentRates();

		verify(client, times(1)).getRates(Currency.getBase());
	}

	@Test
	public void shouldConvertCurrency() {

		ExchangeRatesContainer container = new ExchangeRatesContainer();
		container.setRates(ImmutableMap.of(
				Currency.EUR.name(), new BigDecimal("0.8"),
				Currency.RUB.name(), new BigDecimal("80")
		));

		when(client.getRates(Currency.getBase())).thenReturn(container);

		final BigDecimal amount = new BigDecimal(100);
		final BigDecimal expectedConvertionResult = new BigDecimal("1.25");

		BigDecimal result = ratesService.convert(Currency.RUB, Currency.USD, amount);

		assertTrue(expectedConvertionResult.compareTo(result) == 0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void shouldFailToConvertWhenAmountIsNull() {
		ratesService.convert(Currency.EUR, Currency.RUB, null);
	}

	/**
	 * Cache refresh: a container left over from a previous day is stale and must be
	 * replaced by a fresh request, and the value returned afterwards must be the
	 * refreshed one. Covers the {@code !container.getDate().equals(LocalDate.now())}
	 * branch that the happy-path tests never reach.
	 */
	@Test
	public void shouldRequestRatesAgainWhenContainerIsExpired() {

		ExchangeRatesContainer yesterday = new ExchangeRatesContainer();
		yesterday.setRates(ImmutableMap.of(
				Currency.EUR.name(), new BigDecimal("0.8"),
				Currency.RUB.name(), new BigDecimal("80")
		));
		yesterday.setDate(LocalDate.now().minusDays(1));

		ExchangeRatesContainer today = new ExchangeRatesContainer();
		today.setRates(ImmutableMap.of(
				Currency.EUR.name(), new BigDecimal("0.9"),
				Currency.RUB.name(), new BigDecimal("90")
		));

		when(client.getRates(Currency.getBase())).thenReturn(yesterday, today);

		// first call initializes the (already stale) container
		Map<Currency, BigDecimal> stale = ratesService.getCurrentRates();
		// second call must detect the date rollover and re-request
		Map<Currency, BigDecimal> refreshed = ratesService.getCurrentRates();

		verify(client, times(2)).getRates(Currency.getBase());

		assertTrue(new BigDecimal("0.8").compareTo(stale.get(Currency.EUR)) == 0);
		assertTrue(new BigDecimal("0.9").compareTo(refreshed.get(Currency.EUR)) == 0);
		assertTrue(new BigDecimal("90").compareTo(refreshed.get(Currency.RUB)) == 0);
	}

	/**
	 * Degradation / fallback: when the provider is unavailable the Feign fallback
	 * ({@link com.piggymetrics.statistics.client.ExchangeRatesClientFallback}) yields a
	 * container with an empty rates map. getCurrentRates() cannot build the rate table
	 * from it and fails fast. This pins the current degradation behaviour so any future
	 * hardening of the fallback path becomes a conscious, test-driven change.
	 */
	@Test(expected = NullPointerException.class)
	public void shouldFailToReturnRatesWhenProviderReturnsEmptyData() {

		ExchangeRatesContainer empty = new ExchangeRatesContainer();
		empty.setRates(Collections.emptyMap());

		when(client.getRates(Currency.getBase())).thenReturn(empty);

		ratesService.getCurrentRates();
	}

	/**
	 * Same degradation path reached through {@link ExchangeRatesServiceImpl#convert},
	 * which resolves rates via getCurrentRates() before computing the ratio.
	 */
	@Test(expected = NullPointerException.class)
	public void shouldFailToConvertWhenProviderReturnsEmptyData() {

		ExchangeRatesContainer empty = new ExchangeRatesContainer();
		empty.setRates(Collections.emptyMap());

		when(client.getRates(Currency.getBase())).thenReturn(empty);

		ratesService.convert(Currency.EUR, Currency.USD, new BigDecimal(100));
	}

	/**
	 * Conversion coupling: the ratio is rounded to 4 d.p. <em>before</em> being applied,
	 * so converting through a non-terminating ratio (1/3) is intentionally lossy
	 * (3 RUB -> 0.9999 USD, not 1.0000). Pinning this guards the rounding contract that
	 * the statistics amount normalization depends on.
	 */
	@Test
	public void shouldConvertUsingRatioRoundedToFourDecimals() {

		ExchangeRatesContainer container = new ExchangeRatesContainer();
		container.setRates(ImmutableMap.of(
				Currency.EUR.name(), new BigDecimal("0.8"),
				Currency.RUB.name(), new BigDecimal("3")
		));

		when(client.getRates(Currency.getBase())).thenReturn(container);

		BigDecimal result = ratesService.convert(Currency.RUB, Currency.USD, new BigDecimal(3));

		assertTrue(new BigDecimal("0.9999").compareTo(result) == 0);
	}

	@Test
	public void shouldConvertBetweenNonBaseCurrencies() {

		ExchangeRatesContainer container = new ExchangeRatesContainer();
		container.setRates(ImmutableMap.of(
				Currency.EUR.name(), new BigDecimal("0.8"),
				Currency.RUB.name(), new BigDecimal("80")
		));

		when(client.getRates(Currency.getBase())).thenReturn(container);

		assertTrue(new BigDecimal("10000").compareTo(
				ratesService.convert(Currency.EUR, Currency.RUB, new BigDecimal(100))) == 0);
		assertTrue(new BigDecimal("1").compareTo(
				ratesService.convert(Currency.RUB, Currency.EUR, new BigDecimal(100))) == 0);
	}

	@Test
	public void shouldConvertSameCurrencyWithoutChangingAmount() {

		ExchangeRatesContainer container = new ExchangeRatesContainer();
		container.setRates(ImmutableMap.of(
				Currency.EUR.name(), new BigDecimal("0.8"),
				Currency.RUB.name(), new BigDecimal("80")
		));

		when(client.getRates(Currency.getBase())).thenReturn(container);

		BigDecimal result = ratesService.convert(Currency.EUR, Currency.EUR, new BigDecimal("123.45"));

		assertTrue(new BigDecimal("123.45").compareTo(result) == 0);
	}
}
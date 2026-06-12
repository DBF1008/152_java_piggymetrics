package com.piggymetrics.statistics.service;

import com.google.common.collect.ImmutableMap;
import com.piggymetrics.statistics.client.ExchangeRatesClient;
import com.piggymetrics.statistics.domain.Currency;
import com.piggymetrics.statistics.domain.ExchangeRatesContainer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.lang.reflect.Field;
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

	@Test
	public void shouldRefreshCacheWhenContainerDateIsStale() throws Exception {
		// 设置过期的缓存（昨天的日期）
		ExchangeRatesContainer staleContainer = new ExchangeRatesContainer();
		staleContainer.setDate(LocalDate.now().minusDays(1));
		staleContainer.setRates(ImmutableMap.of(
				Currency.EUR.name(), new BigDecimal("0.9"),
				Currency.RUB.name(), new BigDecimal("90")
		));

		Field containerField = ExchangeRatesServiceImpl.class.getDeclaredField("container");
		containerField.setAccessible(true);
		containerField.set(ratesService, staleContainer);

		// 准备新的汇率数据
		ExchangeRatesContainer freshContainer = new ExchangeRatesContainer();
		freshContainer.setRates(ImmutableMap.of(
				Currency.EUR.name(), new BigDecimal("0.8"),
				Currency.RUB.name(), new BigDecimal("80")
		));

		when(client.getRates(Currency.getBase())).thenReturn(freshContainer);

		// 应该触发重新获取
		Map<Currency, BigDecimal> result = ratesService.getCurrentRates();

		verify(client, times(1)).getRates(Currency.getBase());
		assertEquals(new BigDecimal("0.8"), result.get(Currency.EUR));
		assertEquals(new BigDecimal("80"), result.get(Currency.RUB));
	}

	@Test(expected = NullPointerException.class)
	public void shouldThrowNPEWhenProviderReturnsEmptyRates() {
		ExchangeRatesContainer emptyContainer = new ExchangeRatesContainer();
		emptyContainer.setRates(Collections.emptyMap());

		when(client.getRates(Currency.getBase())).thenReturn(emptyContainer);

		// ImmutableMap 不允许 null value，会抛 NPE
		ratesService.getCurrentRates();
	}

	@Test(expected = NullPointerException.class)
	public void shouldThrowNPEOnConvertWhenProviderReturnsEmptyRates() {
		ExchangeRatesContainer emptyContainer = new ExchangeRatesContainer();
		emptyContainer.setRates(Collections.emptyMap());

		when(client.getRates(Currency.getBase())).thenReturn(emptyContainer);

		// convert 内部调用 getCurrentRates，会因空 rates 失败
		ratesService.convert(Currency.EUR, Currency.USD, new BigDecimal(100));
	}

	@Test(expected = RuntimeException.class)
	public void shouldPropagateExceptionWhenClientFails() {
		when(client.getRates(Currency.getBase()))
				.thenThrow(new RuntimeException("API unavailable"));

		ratesService.getCurrentRates();
	}

	@Test(expected = RuntimeException.class)
	public void shouldPropagateExceptionOnConvertWhenClientFails() {
		when(client.getRates(Currency.getBase()))
				.thenThrow(new RuntimeException("API unavailable"));

		ratesService.convert(Currency.EUR, Currency.USD, new BigDecimal(100));
	}

	@Test
	public void shouldConvertBetweenNonBaseCurrencies() {
		ExchangeRatesContainer container = new ExchangeRatesContainer();
		container.setRates(ImmutableMap.of(
				Currency.EUR.name(), new BigDecimal("0.8"),
				Currency.RUB.name(), new BigDecimal("80")
		));

		when(client.getRates(Currency.getBase())).thenReturn(container);

		// EUR → RUB: 100 EUR × (80/0.8) = 10000 RUB
		BigDecimal result = ratesService.convert(Currency.EUR, Currency.RUB, new BigDecimal(100));
		assertTrue(new BigDecimal("10000").compareTo(result) == 0);
	}

	@Test
	public void shouldReturnSameAmountWhenConvertingSameCurrency() {
		ExchangeRatesContainer container = new ExchangeRatesContainer();
		container.setRates(ImmutableMap.of(
				Currency.EUR.name(), new BigDecimal("0.8"),
				Currency.RUB.name(), new BigDecimal("80")
		));

		when(client.getRates(Currency.getBase())).thenReturn(container);

		BigDecimal amount = new BigDecimal("123.45");
		BigDecimal result = ratesService.convert(Currency.USD, Currency.USD, amount);

		assertTrue(amount.compareTo(result) == 0);
	}
}
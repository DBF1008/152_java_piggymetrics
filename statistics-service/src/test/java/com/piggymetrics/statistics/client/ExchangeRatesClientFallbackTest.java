package com.piggymetrics.statistics.client;

import com.piggymetrics.statistics.domain.Currency;
import com.piggymetrics.statistics.domain.ExchangeRatesContainer;
import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ExchangeRatesClientFallbackTest {

	private final ExchangeRatesClientFallback fallback = new ExchangeRatesClientFallback();

	@Test
	public void shouldReturnContainerWithEmptyRatesWhenApiFails() {
		ExchangeRatesContainer container = fallback.getRates(Currency.getBase());

		assertNotNull(container);
		assertEquals(Currency.getBase(), container.getBase());
		assertEquals(LocalDate.now(), container.getDate());
		assertNotNull(container.getRates());
		assertTrue(container.getRates().isEmpty());
	}

	@Test
	public void shouldReturnContainerWithCorrectBaseCurrency() {
		ExchangeRatesContainer container = fallback.getRates(Currency.EUR);

		// 即使请求的 base 是 EUR，降级时也固定返回 USD
		assertEquals(Currency.USD, container.getBase());
	}

	@Test
	public void shouldReturnContainerWithTodaysDate() {
		ExchangeRatesContainer container = fallback.getRates(Currency.getBase());

		// 降级容器的日期默认为今天，会污染当天缓存
		assertEquals(LocalDate.now(), container.getDate());
	}
}

package com.piggymetrics.statistics.client;

import com.piggymetrics.statistics.domain.Currency;
import com.piggymetrics.statistics.domain.ExchangeRatesContainer;
import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the degradation entry point: the Feign fallback that is used when the rates
 * provider is unavailable. It must return a well-formed container with the base currency
 * and an empty (but non-null) rates map, so downstream code sees "no rates" rather than a
 * malformed or null response.
 */
public class ExchangeRatesClientFallbackTest {

	private final ExchangeRatesClientFallback fallback = new ExchangeRatesClientFallback();

	@Test
	public void shouldReturnEmptyRatesContainerWhenProviderIsUnavailable() {

		ExchangeRatesContainer container = fallback.getRates(Currency.getBase());

		assertNotNull(container);
		assertEquals(Currency.getBase(), container.getBase());
		assertNotNull(container.getRates());
		assertTrue(container.getRates().isEmpty());
		assertEquals(LocalDate.now(), container.getDate());
	}
}

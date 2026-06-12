package com.piggymetrics.statistics.service;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.piggymetrics.statistics.client.ExchangeRatesClient;
import com.piggymetrics.statistics.domain.Account;
import com.piggymetrics.statistics.domain.Currency;
import com.piggymetrics.statistics.domain.ExchangeRatesContainer;
import com.piggymetrics.statistics.domain.Item;
import com.piggymetrics.statistics.domain.Saving;
import com.piggymetrics.statistics.domain.TimePeriod;
import com.piggymetrics.statistics.domain.timeseries.DataPoint;
import com.piggymetrics.statistics.domain.timeseries.ItemMetric;
import com.piggymetrics.statistics.domain.timeseries.StatisticMetric;
import com.piggymetrics.statistics.repository.DataPointRepository;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

/**
 * Exercises the statistics write path against the REAL {@link ExchangeRatesServiceImpl}
 * (only the Feign client and the repository are mocked). Unlike {@link StatisticsServiceImplTest},
 * which stubs convert()/getCurrentRates(), this test keeps the conversion <-> normalization
 * coupling intact: a change to the ratio/rounding logic that breaks the normalized amounts is
 * caught here. It also covers the degradation case where the provider returns empty data.
 */
public class StatisticsServiceImplRealRatesTest {

	@Mock
	private ExchangeRatesClient client;

	@Mock
	private DataPointRepository repository;

	private StatisticsServiceImpl statisticsService;

	@Before
	public void setup() {
		initMocks(this);

		ExchangeRatesServiceImpl ratesService = new ExchangeRatesServiceImpl();
		ReflectionTestUtils.setField(ratesService, "client", client);

		statisticsService = new StatisticsServiceImpl();
		ReflectionTestUtils.setField(statisticsService, "ratesService", ratesService);
		ReflectionTestUtils.setField(statisticsService, "repository", repository);
	}

	@Test
	public void shouldNormalizeAmountsUsingRealConversionWhenSavingDataPoint() {

		Account account = sampleAccount();

		ExchangeRatesContainer container = new ExchangeRatesContainer();
		container.setRates(ImmutableMap.of(
				Currency.EUR.name(), new BigDecimal("0.8"),
				Currency.RUB.name(), new BigDecimal("80")
		));

		when(client.getRates(Currency.getBase())).thenReturn(container);
		when(repository.save(any(DataPoint.class))).then(returnsFirstArg());

		DataPoint dataPoint = statisticsService.save("test", account);

		final BigDecimal expectedExpensesAmount = new BigDecimal("17.8861");
		final BigDecimal expectedIncomesAmount = new BigDecimal("298.9802");
		final BigDecimal expectedSavingAmount = new BigDecimal("1250");

		final BigDecimal expectedNormalizedSalaryAmount = new BigDecimal("298.9802");
		final BigDecimal expectedNormalizedVacationAmount = new BigDecimal("11.6361");
		final BigDecimal expectedNormalizedGroceryAmount = new BigDecimal("6.25");

		assertTrue(expectedExpensesAmount.compareTo(dataPoint.getStatistics().get(StatisticMetric.EXPENSES_AMOUNT)) == 0);
		assertTrue(expectedIncomesAmount.compareTo(dataPoint.getStatistics().get(StatisticMetric.INCOMES_AMOUNT)) == 0);
		assertTrue(expectedSavingAmount.compareTo(dataPoint.getStatistics().get(StatisticMetric.SAVING_AMOUNT)) == 0);

		ItemMetric salary = findByTitle(dataPoint.getIncomes(), "Salary");
		ItemMetric vacation = findByTitle(dataPoint.getExpenses(), "Vacation");
		ItemMetric grocery = findByTitle(dataPoint.getExpenses(), "Grocery");

		assertTrue(expectedNormalizedSalaryAmount.compareTo(salary.getAmount()) == 0);
		assertTrue(expectedNormalizedVacationAmount.compareTo(vacation.getAmount()) == 0);
		assertTrue(expectedNormalizedGroceryAmount.compareTo(grocery.getAmount()) == 0);

		Map<Currency, BigDecimal> expectedRates = ImmutableMap.of(
				Currency.EUR, new BigDecimal("0.8"),
				Currency.RUB, new BigDecimal("80"),
				Currency.USD, BigDecimal.ONE
		);
		assertEquals(expectedRates, dataPoint.getRates());

		verify(repository, times(1)).save(dataPoint);
	}

	/**
	 * Degradation propagated to the write path: when the provider returns empty data the
	 * real rates service cannot normalize amounts, and save() fails fast rather than
	 * persisting a half-built data point.
	 */
	@Test(expected = NullPointerException.class)
	public void shouldFailToSaveDataPointWhenProviderReturnsEmptyData() {

		ExchangeRatesContainer empty = new ExchangeRatesContainer();
		empty.setRates(Collections.emptyMap());

		when(client.getRates(Currency.getBase())).thenReturn(empty);

		statisticsService.save("test", sampleAccount());
	}

	private static Account sampleAccount() {

		Item salary = new Item();
		salary.setTitle("Salary");
		salary.setAmount(new BigDecimal(9100));
		salary.setCurrency(Currency.USD);
		salary.setPeriod(TimePeriod.MONTH);

		Item grocery = new Item();
		grocery.setTitle("Grocery");
		grocery.setAmount(new BigDecimal(500));
		grocery.setCurrency(Currency.RUB);
		grocery.setPeriod(TimePeriod.DAY);

		Item vacation = new Item();
		vacation.setTitle("Vacation");
		vacation.setAmount(new BigDecimal(3400));
		vacation.setCurrency(Currency.EUR);
		vacation.setPeriod(TimePeriod.YEAR);

		Saving saving = new Saving();
		saving.setAmount(new BigDecimal(1000));
		saving.setCurrency(Currency.EUR);
		saving.setInterest(new BigDecimal("3.2"));
		saving.setDeposit(true);
		saving.setCapitalization(false);

		Account account = new Account();
		account.setIncomes(ImmutableList.of(salary));
		account.setExpenses(ImmutableList.of(grocery, vacation));
		account.setSaving(saving);
		return account;
	}

	private static ItemMetric findByTitle(Set<ItemMetric> metrics, String title) {
		return metrics.stream()
				.filter(m -> m.getTitle().equals(title))
				.findFirst()
				.get();
	}
}

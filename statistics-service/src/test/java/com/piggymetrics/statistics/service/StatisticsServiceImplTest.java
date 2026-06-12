package com.piggymetrics.statistics.service;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.piggymetrics.statistics.domain.Account;
import com.piggymetrics.statistics.domain.Currency;
import com.piggymetrics.statistics.domain.Item;
import com.piggymetrics.statistics.domain.Saving;
import com.piggymetrics.statistics.domain.TimePeriod;
import com.piggymetrics.statistics.domain.timeseries.DataPoint;
import com.piggymetrics.statistics.domain.timeseries.ItemMetric;
import com.piggymetrics.statistics.domain.timeseries.StatisticMetric;
import com.piggymetrics.statistics.repository.DataPointRepository;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class StatisticsServiceImplTest {

	@InjectMocks
	private StatisticsServiceImpl statisticsService;

	@Mock
	private ExchangeRatesServiceImpl ratesService;

	@Mock
	private DataPointRepository repository;

	@Before
	public void setup() {
		initMocks(this);
	}

	@Test
	public void shouldFindDataPointListByAccountName() {
		final List<DataPoint> list = ImmutableList.of(new DataPoint());
		when(repository.findByIdAccount("test")).thenReturn(list);

		List<DataPoint> result = statisticsService.findByAccountName("test");
		assertEquals(list, result);
	}

	@Test(expected = IllegalArgumentException.class)
	public void shouldFailToFindDataPointWhenAccountNameIsNull() {
		statisticsService.findByAccountName(null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void shouldFailToFindDataPointWhenAccountNameIsEmpty() {
		statisticsService.findByAccountName("");
	}

	@Test
	public void shouldSaveDataPoint() {

		/**
		 * Given
		 */

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
		saving.setInterest(new BigDecimal(3.2));
		saving.setDeposit(true);
		saving.setCapitalization(false);

		Account account = new Account();
		account.setIncomes(ImmutableList.of(salary));
		account.setExpenses(ImmutableList.of(grocery, vacation));
		account.setSaving(saving);

		final Map<Currency, BigDecimal> rates = ImmutableMap.of(
				Currency.EUR, new BigDecimal("0.8"),
				Currency.RUB, new BigDecimal("80"),
				Currency.USD, BigDecimal.ONE
		);

		/**
		 * When
		 */

		when(ratesService.convert(any(Currency.class),any(Currency.class),any(BigDecimal.class)))
				.then(i -> ((BigDecimal)i.getArgument(2))
						.divide(rates.get(i.getArgument(0)), 4, RoundingMode.HALF_UP));

		when(ratesService.getCurrentRates()).thenReturn(rates);

		when(repository.save(any(DataPoint.class))).then(returnsFirstArg());

		DataPoint dataPoint = statisticsService.save("test", account);

		/**
		 * Then
		 */

		final BigDecimal expectedExpensesAmount = new BigDecimal("17.8861");
		final BigDecimal expectedIncomesAmount = new BigDecimal("298.9802");
		final BigDecimal expectedSavingAmount = new BigDecimal("1250");

		final BigDecimal expectedNormalizedSalaryAmount = new BigDecimal("298.9802");
		final BigDecimal expectedNormalizedVacationAmount = new BigDecimal("11.6361");
		final BigDecimal expectedNormalizedGroceryAmount = new BigDecimal("6.25");

		assertEquals(dataPoint.getId().getAccount(), "test");
		assertEquals(dataPoint.getId().getDate(), Date.from(LocalDate.now().atStartOfDay().atZone(ZoneId.systemDefault()).toInstant()));

		assertTrue(expectedExpensesAmount.compareTo(dataPoint.getStatistics().get(StatisticMetric.EXPENSES_AMOUNT)) == 0);
		assertTrue(expectedIncomesAmount.compareTo(dataPoint.getStatistics().get(StatisticMetric.INCOMES_AMOUNT)) == 0);
		assertTrue(expectedSavingAmount.compareTo(dataPoint.getStatistics().get(StatisticMetric.SAVING_AMOUNT)) == 0);

		ItemMetric salaryItemMetric = dataPoint.getIncomes().stream()
				.filter(i -> i.getTitle().equals(salary.getTitle()))
				.findFirst().get();

		ItemMetric vacationItemMetric = dataPoint.getExpenses().stream()
				.filter(i -> i.getTitle().equals(vacation.getTitle()))
				.findFirst().get();

		ItemMetric groceryItemMetric = dataPoint.getExpenses().stream()
				.filter(i -> i.getTitle().equals(grocery.getTitle()))
				.findFirst().get();

		assertTrue(expectedNormalizedSalaryAmount.compareTo(salaryItemMetric.getAmount()) == 0);
		assertTrue(expectedNormalizedVacationAmount.compareTo(vacationItemMetric.getAmount()) == 0);
		assertTrue(expectedNormalizedGroceryAmount.compareTo(groceryItemMetric.getAmount()) == 0);

		assertEquals(rates, dataPoint.getRates());

		verify(repository, times(1)).save(dataPoint);
	}

	@Test
	public void shouldSaveDataPointWithAllTimePeriods() {
		// 测试所有时间周期的归一化：YEAR, QUARTER, MONTH, DAY, HOUR
		Item yearlyItem = new Item();
		yearlyItem.setTitle("Yearly");
		yearlyItem.setAmount(new BigDecimal(36500));
		yearlyItem.setCurrency(Currency.USD);
		yearlyItem.setPeriod(TimePeriod.YEAR);

		Item quarterlyItem = new Item();
		quarterlyItem.setTitle("Quarterly");
		quarterlyItem.setAmount(new BigDecimal(9000));
		quarterlyItem.setCurrency(Currency.USD);
		quarterlyItem.setPeriod(TimePeriod.QUARTER);

		Item monthlyItem = new Item();
		monthlyItem.setTitle("Monthly");
		monthlyItem.setAmount(new BigDecimal(3000));
		monthlyItem.setCurrency(Currency.USD);
		monthlyItem.setPeriod(TimePeriod.MONTH);

		Item dailyItem = new Item();
		dailyItem.setTitle("Daily");
		dailyItem.setAmount(new BigDecimal(100));
		dailyItem.setCurrency(Currency.USD);
		dailyItem.setPeriod(TimePeriod.DAY);

		Item hourlyItem = new Item();
		hourlyItem.setTitle("Hourly");
		hourlyItem.setAmount(new BigDecimal(50));
		hourlyItem.setCurrency(Currency.USD);
		hourlyItem.setPeriod(TimePeriod.HOUR);

		Saving saving = new Saving();
		saving.setAmount(new BigDecimal(1000));
		saving.setCurrency(Currency.USD);
		saving.setInterest(new BigDecimal(2.0));
		saving.setDeposit(true);
		saving.setCapitalization(false);

		Account account = new Account();
		account.setIncomes(ImmutableList.of(yearlyItem, quarterlyItem, monthlyItem, dailyItem, hourlyItem));
		account.setExpenses(Collections.emptyList());
		account.setSaving(saving);

		when(ratesService.convert(any(Currency.class), any(Currency.class), any(BigDecimal.class)))
				.then(i -> i.getArgument(2)); // USD→USD 直接返回原金额

		when(ratesService.getCurrentRates()).thenReturn(ImmutableMap.of(
				Currency.EUR, new BigDecimal("0.8"),
				Currency.RUB, new BigDecimal("80"),
				Currency.USD, BigDecimal.ONE
		));

		when(repository.save(any(DataPoint.class))).then(returnsFirstArg());

		DataPoint dataPoint = statisticsService.save("test", account);

		// 验证 YEAR 归一化：36500 / 365.2425 ≈ 99.9335
		ItemMetric yearlyMetric = dataPoint.getIncomes().stream()
				.filter(i -> i.getTitle().equals("Yearly")).findFirst().get();
		assertTrue(new BigDecimal("99.9335").compareTo(yearlyMetric.getAmount()) == 0);

		// 验证 QUARTER 归一化：9000 / 91.3106 ≈ 98.5637
		ItemMetric quarterlyMetric = dataPoint.getIncomes().stream()
				.filter(i -> i.getTitle().equals("Quarterly")).findFirst().get();
		assertTrue(new BigDecimal("98.5637").compareTo(quarterlyMetric.getAmount()) == 0);

		// 验证 MONTH 归一化：3000 / 30.4368 ≈ 98.5649
		ItemMetric monthlyMetric = dataPoint.getIncomes().stream()
				.filter(i -> i.getTitle().equals("Monthly")).findFirst().get();
		assertTrue(new BigDecimal("98.5649").compareTo(monthlyMetric.getAmount()) == 0);

		// 验证 DAY 归一化：100 / 1 = 100
		ItemMetric dailyMetric = dataPoint.getIncomes().stream()
				.filter(i -> i.getTitle().equals("Daily")).findFirst().get();
		assertTrue(new BigDecimal("100").compareTo(dailyMetric.getAmount()) == 0);

		// 验证 HOUR 归一化：50 / 0.0416 ≈ 1201.9231
		ItemMetric hourlyMetric = dataPoint.getIncomes().stream()
				.filter(i -> i.getTitle().equals("Hourly")).findFirst().get();
		assertTrue(new BigDecimal("1201.9231").compareTo(hourlyMetric.getAmount()) == 0);

		verify(repository, times(1)).save(dataPoint);
	}

	@Test(expected = RuntimeException.class)
	public void shouldFailToSaveWhenRatesServiceIsUnavailable() {
		Item item = new Item();
		item.setTitle("Salary");
		item.setAmount(new BigDecimal(1000));
		item.setCurrency(Currency.USD);
		item.setPeriod(TimePeriod.MONTH);

		Saving saving = new Saving();
		saving.setAmount(new BigDecimal(500));
		saving.setCurrency(Currency.USD);
		saving.setInterest(new BigDecimal(1.5));
		saving.setDeposit(true);
		saving.setCapitalization(false);

		Account account = new Account();
		account.setIncomes(ImmutableList.of(item));
		account.setExpenses(Collections.emptyList());
		account.setSaving(saving);

		// 汇率服务故障
		when(ratesService.convert(any(Currency.class), any(Currency.class), any(BigDecimal.class)))
				.thenThrow(new RuntimeException("Exchange rates unavailable"));

		statisticsService.save("test", account);
	}

	@Test
	public void shouldNotPersistDataPointWhenRatesServiceFails() {
		Item item = new Item();
		item.setTitle("Salary");
		item.setAmount(new BigDecimal(1000));
		item.setCurrency(Currency.USD);
		item.setPeriod(TimePeriod.MONTH);

		Saving saving = new Saving();
		saving.setAmount(new BigDecimal(500));
		saving.setCurrency(Currency.USD);
		saving.setInterest(new BigDecimal(1.5));
		saving.setDeposit(true);
		saving.setCapitalization(false);

		Account account = new Account();
		account.setIncomes(ImmutableList.of(item));
		account.setExpenses(Collections.emptyList());
		account.setSaving(saving);

		when(ratesService.convert(any(Currency.class), any(Currency.class), any(BigDecimal.class)))
				.thenThrow(new RuntimeException("Exchange rates unavailable"));

		try {
			statisticsService.save("test", account);
		} catch (RuntimeException e) {
			// 预期异常
		}

		// 验证未写入数据库
		verify(repository, never()).save(any(DataPoint.class));
	}
}
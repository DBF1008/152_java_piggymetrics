package com.piggymetrics.notification.service;

import com.google.common.collect.ImmutableList;
import com.piggymetrics.notification.client.AccountServiceClient;
import com.piggymetrics.notification.domain.NotificationType;
import com.piggymetrics.notification.domain.Recipient;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import javax.mail.MessagingException;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.Executor;

import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class NotificationServiceImplTest {

	@InjectMocks
	private NotificationServiceImpl notificationService;

	@Mock
	private RecipientService recipientService;

	@Mock
	private AccountServiceClient client;

	@Mock
	private EmailService emailService;

	@Before
	public void setup() {
		initMocks(this);
		// Same-thread executor: tasks run synchronously inside the calling thread,
		// eliminating all async timing issues that made the old timeout()-based tests flaky.
		ReflectionTestUtils.setField(notificationService, "notificationExecutor", (Executor) Runnable::run);
	}

	// ==================== BACKUP tests ====================

	@Test
	public void shouldSendBackupToAllReadyRecipients() throws IOException, MessagingException {
		Recipient recipientA = new Recipient();
		recipientA.setAccountName("account-a");

		Recipient recipientB = new Recipient();
		recipientB.setAccountName("account-b");

		when(client.getAccount("account-a")).thenReturn("json-a");
		when(client.getAccount("account-b")).thenReturn("json-b");
		when(recipientService.findReadyToNotify(NotificationType.BACKUP))
			.thenReturn(ImmutableList.of(recipientA, recipientB));

		notificationService.sendBackupNotifications();

		verify(emailService).send(NotificationType.BACKUP, recipientA, "json-a");
		verify(emailService).send(NotificationType.BACKUP, recipientB, "json-b");
		verify(recipientService).markNotified(NotificationType.BACKUP, recipientA);
		verify(recipientService).markNotified(NotificationType.BACKUP, recipientB);
	}

	@Test
	public void shouldSendBackupEvenWhenGetAccountFailsForSomeRecipients() throws IOException, MessagingException {
		Recipient withError = new Recipient();
		withError.setAccountName("with-error");

		Recipient withNoError = new Recipient();
		withNoError.setAccountName("with-no-error");

		when(client.getAccount(withError.getAccountName())).thenThrow(new RuntimeException("feign timeout"));
		when(client.getAccount(withNoError.getAccountName())).thenReturn("json");
		when(recipientService.findReadyToNotify(NotificationType.BACKUP))
			.thenReturn(ImmutableList.of(withNoError, withError));

		notificationService.sendBackupNotifications();

		// Successful recipient: email sent AND marked as notified
		verify(emailService).send(NotificationType.BACKUP, withNoError, "json");
		verify(recipientService).markNotified(NotificationType.BACKUP, withNoError);

		// Failed recipient: neither email sent nor marked as notified
		verify(emailService, never()).send(eq(NotificationType.BACKUP), eq(withError), anyString());
		verify(recipientService, never()).markNotified(NotificationType.BACKUP, withError);
	}

	@Test
	public void shouldSendBackupEvenWhenEmailSendFailsForSomeRecipients() throws IOException, MessagingException {
		Recipient withError = new Recipient();
		withError.setAccountName("with-error");

		Recipient withNoError = new Recipient();
		withNoError.setAccountName("with-no-error");

		when(client.getAccount(withError.getAccountName())).thenReturn("json");
		when(client.getAccount(withNoError.getAccountName())).thenReturn("json");
		doThrow(new RuntimeException("SMTP error"))
			.when(emailService).send(NotificationType.BACKUP, withError, "json");
		when(recipientService.findReadyToNotify(NotificationType.BACKUP))
			.thenReturn(ImmutableList.of(withNoError, withError));

		notificationService.sendBackupNotifications();

		// Successful recipient: email sent AND marked as notified
		verify(emailService).send(NotificationType.BACKUP, withNoError, "json");
		verify(recipientService).markNotified(NotificationType.BACKUP, withNoError);

		// Failed recipient: email attempted but NOT marked as notified
		verify(emailService).send(NotificationType.BACKUP, withError, "json");
		verify(recipientService, never()).markNotified(NotificationType.BACKUP, withError);
	}

	@Test
	public void shouldNotMarkNotifiedWhenBackupFailsForAllRecipients() throws IOException, MessagingException {
		Recipient failA = new Recipient();
		failA.setAccountName("fail-a");

		Recipient failB = new Recipient();
		failB.setAccountName("fail-b");

		when(client.getAccount(anyString())).thenThrow(new RuntimeException("service unavailable"));
		when(recipientService.findReadyToNotify(NotificationType.BACKUP))
			.thenReturn(ImmutableList.of(failA, failB));

		notificationService.sendBackupNotifications();

		// No email sent, no one marked as notified, and no exception propagated
		verify(emailService, never()).send(any(), any(), anyString());
		verify(recipientService, never()).markNotified(any(NotificationType.class), any(Recipient.class));
	}

	// ==================== REMIND tests ====================

	@Test
	public void shouldSendRemindToAllReadyRecipients() throws IOException, MessagingException {
		Recipient recipientA = new Recipient();
		recipientA.setAccountName("account-a");

		Recipient recipientB = new Recipient();
		recipientB.setAccountName("account-b");

		when(recipientService.findReadyToNotify(NotificationType.REMIND))
			.thenReturn(ImmutableList.of(recipientA, recipientB));

		notificationService.sendRemindNotifications();

		verify(emailService).send(NotificationType.REMIND, recipientA, null);
		verify(emailService).send(NotificationType.REMIND, recipientB, null);
		verify(recipientService).markNotified(NotificationType.REMIND, recipientA);
		verify(recipientService).markNotified(NotificationType.REMIND, recipientB);
	}

	@Test
	public void shouldSendRemindEvenWhenEmailSendFailsForSomeRecipients() throws IOException, MessagingException {
		Recipient withError = new Recipient();
		withError.setAccountName("with-error");

		Recipient withNoError = new Recipient();
		withNoError.setAccountName("with-no-error");

		doThrow(new RuntimeException("SMTP error"))
			.when(emailService).send(NotificationType.REMIND, withError, null);
		when(recipientService.findReadyToNotify(NotificationType.REMIND))
			.thenReturn(ImmutableList.of(withNoError, withError));

		notificationService.sendRemindNotifications();

		// Successful recipient: email sent AND marked as notified
		verify(emailService).send(NotificationType.REMIND, withNoError, null);
		verify(recipientService).markNotified(NotificationType.REMIND, withNoError);

		// Failed recipient: email attempted but NOT marked as notified
		verify(emailService).send(NotificationType.REMIND, withError, null);
		verify(recipientService, never()).markNotified(NotificationType.REMIND, withError);
	}

	@Test
	public void shouldNotMarkNotifiedWhenRemindFailsForAllRecipients() throws IOException, MessagingException {
		Recipient failA = new Recipient();
		failA.setAccountName("fail-a");

		Recipient failB = new Recipient();
		failB.setAccountName("fail-b");

		doThrow(new RuntimeException("SMTP down"))
			.when(emailService).send(any(NotificationType.class), any(Recipient.class), any());
		when(recipientService.findReadyToNotify(NotificationType.REMIND))
			.thenReturn(ImmutableList.of(failA, failB));

		notificationService.sendRemindNotifications();

		verify(recipientService, never()).markNotified(any(NotificationType.class), any(Recipient.class));
	}

	@Test
	public void shouldDoNothingWhenNoRecipientsReady() throws IOException, MessagingException {
		when(recipientService.findReadyToNotify(NotificationType.BACKUP))
			.thenReturn(Collections.emptyList());
		when(recipientService.findReadyToNotify(NotificationType.REMIND))
			.thenReturn(Collections.emptyList());

		notificationService.sendBackupNotifications();
		notificationService.sendRemindNotifications();

		verify(emailService, never()).send(any(), any(), any());
		verify(recipientService, never()).markNotified(any(), any());
	}
}

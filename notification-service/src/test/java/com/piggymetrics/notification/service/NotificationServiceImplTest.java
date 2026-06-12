package com.piggymetrics.notification.service;

import com.google.common.collect.ImmutableList;
import com.piggymetrics.notification.client.AccountServiceClient;
import com.piggymetrics.notification.domain.NotificationType;
import com.piggymetrics.notification.domain.Recipient;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;

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
		// Run the per-recipient fan-out synchronously on the calling thread. {@code Runnable::run}
		// is an Executor that executes each task inline. This exercises the asynchronous code path
		// deterministically: sendXxxNotifications() returns only once every send/mark has happened,
		// so no timeout()/sleep/polling is needed and the negative assertions below can be proven,
		// not merely sampled at an arbitrary moment.
		notificationService.setExecutor(Runnable::run);
	}

	// ------------------------------------------------------------------ BACKUP

	@Test
	public void shouldSendBackupNotificationsToAllReadyRecipients() throws Exception {

		final String attachment = "json";

		Recipient first = recipient("first");
		Recipient second = recipient("second");

		when(client.getAccount("first")).thenReturn(attachment);
		when(client.getAccount("second")).thenReturn(attachment);
		when(recipientService.findReadyToNotify(NotificationType.BACKUP))
				.thenReturn(ImmutableList.of(first, second));

		notificationService.sendBackupNotifications();

		verify(emailService).send(NotificationType.BACKUP, first, attachment);
		verify(emailService).send(NotificationType.BACKUP, second, attachment);
		verify(recipientService).markNotified(NotificationType.BACKUP, first);
		verify(recipientService).markNotified(NotificationType.BACKUP, second);
	}

	@Test
	public void shouldMarkRecipientAsNotifiedAfterBackupEmailIsSent() throws Exception {

		final String attachment = "json";
		Recipient recipient = recipient("test");

		when(client.getAccount("test")).thenReturn(attachment);
		when(recipientService.findReadyToNotify(NotificationType.BACKUP))
				.thenReturn(ImmutableList.of(recipient));

		notificationService.sendBackupNotifications();

		// the recipient must be marked notified only *after* the email has actually been sent
		InOrder inOrder = inOrder(emailService, recipientService);
		inOrder.verify(emailService).send(NotificationType.BACKUP, recipient, attachment);
		inOrder.verify(recipientService).markNotified(NotificationType.BACKUP, recipient);
	}

	@Test
	public void shouldSendBackupNotificationsEvenWhenErrorsOccursForSomeRecipients() throws Exception {

		final String attachment = "json";

		Recipient withError = recipient("with-error");
		Recipient withNoError = recipient("with-no-error");

		// the failing recipient is listed first, proving a failure does not abort the others
		when(client.getAccount("with-error")).thenThrow(new RuntimeException());
		when(client.getAccount("with-no-error")).thenReturn(attachment);
		when(recipientService.findReadyToNotify(NotificationType.BACKUP))
				.thenReturn(ImmutableList.of(withError, withNoError));

		notificationService.sendBackupNotifications();

		// the healthy recipient is still fully processed
		verify(emailService).send(NotificationType.BACKUP, withNoError, attachment);
		verify(recipientService).markNotified(NotificationType.BACKUP, withNoError);

		// the failing recipient gets neither an email nor a "notified" mark
		verify(emailService, never()).send(NotificationType.BACKUP, withError, attachment);
		verify(recipientService, never()).markNotified(NotificationType.BACKUP, withError);
	}

	@Test
	public void shouldNotMarkBackupNotifiedWhenEmailSendingFails() throws Exception {

		final String attachment = "json";
		Recipient recipient = recipient("test");

		when(client.getAccount("test")).thenReturn(attachment);
		doThrow(new RuntimeException()).when(emailService)
				.send(NotificationType.BACKUP, recipient, attachment);
		when(recipientService.findReadyToNotify(NotificationType.BACKUP))
				.thenReturn(ImmutableList.of(recipient));

		notificationService.sendBackupNotifications();

		// the send was attempted, but a failed send must not advance lastNotified
		verify(emailService).send(NotificationType.BACKUP, recipient, attachment);
		verify(recipientService, never()).markNotified(NotificationType.BACKUP, recipient);
	}

	@Test
	public void shouldNotSendAnyBackupNotificationsWhenNoRecipientsAreReady() throws Exception {

		when(recipientService.findReadyToNotify(NotificationType.BACKUP))
				.thenReturn(ImmutableList.of());

		notificationService.sendBackupNotifications();

		verifyZeroInteractions(client, emailService);
		verify(recipientService, never()).markNotified(any(NotificationType.class), any(Recipient.class));
	}

	// ------------------------------------------------------------------ REMIND

	@Test
	public void shouldSendRemindNotificationsToAllReadyRecipients() throws Exception {

		Recipient first = recipient("first");
		Recipient second = recipient("second");

		when(recipientService.findReadyToNotify(NotificationType.REMIND))
				.thenReturn(ImmutableList.of(first, second));

		notificationService.sendRemindNotifications();

		verify(emailService).send(NotificationType.REMIND, first, null);
		verify(emailService).send(NotificationType.REMIND, second, null);
		verify(recipientService).markNotified(NotificationType.REMIND, first);
		verify(recipientService).markNotified(NotificationType.REMIND, second);

		// remind notifications carry no attachment, so the account service is never queried
		verifyZeroInteractions(client);
	}

	@Test
	public void shouldMarkRecipientAsNotifiedAfterRemindEmailIsSent() throws Exception {

		Recipient recipient = recipient("test");

		when(recipientService.findReadyToNotify(NotificationType.REMIND))
				.thenReturn(ImmutableList.of(recipient));

		notificationService.sendRemindNotifications();

		// the recipient must be marked notified only *after* the email has actually been sent
		InOrder inOrder = inOrder(emailService, recipientService);
		inOrder.verify(emailService).send(NotificationType.REMIND, recipient, null);
		inOrder.verify(recipientService).markNotified(NotificationType.REMIND, recipient);
	}

	@Test
	public void shouldSendRemindNotificationsEvenWhenErrorsOccursForSomeRecipients() throws Exception {

		Recipient withError = recipient("with-error");
		Recipient withNoError = recipient("with-no-error");

		// the failing recipient is listed first, proving a failure does not abort the others
		when(recipientService.findReadyToNotify(NotificationType.REMIND))
				.thenReturn(ImmutableList.of(withError, withNoError));
		doThrow(new RuntimeException()).when(emailService)
				.send(NotificationType.REMIND, withError, null);

		notificationService.sendRemindNotifications();

		// the healthy recipient is still fully processed
		verify(emailService).send(NotificationType.REMIND, withNoError, null);
		verify(recipientService).markNotified(NotificationType.REMIND, withNoError);

		// the failing recipient never gets a "notified" mark
		verify(recipientService, never()).markNotified(NotificationType.REMIND, withError);
	}

	@Test
	public void shouldNotSendAnyRemindNotificationsWhenNoRecipientsAreReady() throws Exception {

		when(recipientService.findReadyToNotify(NotificationType.REMIND))
				.thenReturn(ImmutableList.of());

		notificationService.sendRemindNotifications();

		verifyZeroInteractions(client, emailService);
		verify(recipientService, never()).markNotified(any(NotificationType.class), any(Recipient.class));
	}

	private Recipient recipient(String accountName) {
		Recipient recipient = new Recipient();
		recipient.setAccountName(accountName);
		return recipient;
	}
}

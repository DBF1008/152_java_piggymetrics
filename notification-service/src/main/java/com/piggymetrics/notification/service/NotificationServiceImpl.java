package com.piggymetrics.notification.service;

import com.piggymetrics.notification.client.AccountServiceClient;
import com.piggymetrics.notification.domain.NotificationType;
import com.piggymetrics.notification.domain.Recipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

@Service
public class NotificationServiceImpl implements NotificationService {

	private final Logger log = LoggerFactory.getLogger(getClass());

	@Autowired
	private AccountServiceClient client;

	@Autowired
	private RecipientService recipientService;

	@Autowired
	private EmailService emailService;

	/**
	 * Executor running the per-recipient notification tasks. Defaults to the shared
	 * {@link ForkJoinPool#commonPool()} - the same pool a bare
	 * {@code CompletableFuture.runAsync(task)} would use - so production behaviour is
	 * unchanged. It is a seam: tests inject a same-thread executor to make the
	 * asynchronous fan-out deterministic.
	 */
	private Executor executor = ForkJoinPool.commonPool();

	void setExecutor(Executor executor) {
		this.executor = executor;
	}

	@Override
	@Scheduled(cron = "${backup.cron}")
	public void sendBackupNotifications() {

		final NotificationType type = NotificationType.BACKUP;

		List<Recipient> recipients = recipientService.findReadyToNotify(type);
		log.info("found {} recipients for backup notification", recipients.size());

		recipients.forEach(recipient -> CompletableFuture.runAsync(() -> {
			try {
				String attachment = client.getAccount(recipient.getAccountName());
				emailService.send(type, recipient, attachment);
				recipientService.markNotified(type, recipient);
			} catch (Throwable t) {
				log.error("an error during backup notification for {}", recipient, t);
			}
		}, executor));
	}

	@Override
	@Scheduled(cron = "${remind.cron}")
	public void sendRemindNotifications() {

		final NotificationType type = NotificationType.REMIND;

		List<Recipient> recipients = recipientService.findReadyToNotify(type);
		log.info("found {} recipients for remind notification", recipients.size());

		recipients.forEach(recipient -> CompletableFuture.runAsync(() -> {
			try {
				emailService.send(type, recipient, null);
				recipientService.markNotified(type, recipient);
			} catch (Throwable t) {
				log.error("an error during remind notification for {}", recipient, t);
			}
		}, executor));
	}
}

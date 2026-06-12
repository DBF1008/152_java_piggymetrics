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
import java.util.stream.Collectors;

@Service
public class NotificationServiceImpl implements NotificationService {

	private final Logger log = LoggerFactory.getLogger(getClass());

	@Autowired
	private AccountServiceClient client;

	@Autowired
	private RecipientService recipientService;

	@Autowired
	private EmailService emailService;

	@Autowired(required = false)
	private Executor notificationExecutor = ForkJoinPool.commonPool();

	@Override
	@Scheduled(cron = "${backup.cron}")
	public void sendBackupNotifications() {

		final NotificationType type = NotificationType.BACKUP;

		List<Recipient> recipients = recipientService.findReadyToNotify(type);
		log.info("found {} recipients for backup notification", recipients.size());

		List<CompletableFuture<Void>> futures = recipients.stream()
			.map(recipient -> CompletableFuture.runAsync(() -> {
				try {
					String attachment = client.getAccount(recipient.getAccountName());
					emailService.send(type, recipient, attachment);
					recipientService.markNotified(type, recipient);
				} catch (Throwable t) {
					log.error("an error during backup notification for {}", recipient, t);
				}
			}, notificationExecutor))
			.collect(Collectors.toList());

		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
	}

	@Override
	@Scheduled(cron = "${remind.cron}")
	public void sendRemindNotifications() {

		final NotificationType type = NotificationType.REMIND;

		List<Recipient> recipients = recipientService.findReadyToNotify(type);
		log.info("found {} recipients for remind notification", recipients.size());

		List<CompletableFuture<Void>> futures = recipients.stream()
			.map(recipient -> CompletableFuture.runAsync(() -> {
				try {
					emailService.send(type, recipient, null);
					recipientService.markNotified(type, recipient);
				} catch (Throwable t) {
					log.error("an error during remind notification for {}", recipient, t);
				}
			}, notificationExecutor))
			.collect(Collectors.toList());

		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
	}
}

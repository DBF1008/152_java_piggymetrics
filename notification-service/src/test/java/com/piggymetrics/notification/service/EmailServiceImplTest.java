package com.piggymetrics.notification.service;

import com.piggymetrics.notification.domain.NotificationType;
import com.piggymetrics.notification.domain.Recipient;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class EmailServiceImplTest {

	@InjectMocks
	private EmailServiceImpl emailService;

	@Mock
	private JavaMailSender mailSender;

	@Mock
	private Environment env;

	@Captor
	private ArgumentCaptor<MimeMessage> captor;

	@Before
	public void setup() {
		initMocks(this);
		when(mailSender.createMimeMessage())
				.thenReturn(new MimeMessage(Session.getDefaultInstance(new Properties())));
	}

	/**
	 * The backup notification is the "with attachment" message shape: it must reach the
	 * account's mailbox, carry the configured subject/body with the account name spliced
	 * into the template, and ship exactly one attachment named from configuration.
	 */
	@Test
	public void shouldSendBackupEmail() throws MessagingException, IOException {

		final String subject = "Your backup is ready";
		final String textTemplate = "Hi {0}, your backup is attached";
		final String attachmentName = "backup.json";
		final String attachmentContent = "{\"name\":\"test\"}";

		Recipient recipient = new Recipient();
		recipient.setAccountName("test");
		recipient.setEmail("test@test.com");

		when(env.getProperty(NotificationType.BACKUP.getSubject())).thenReturn(subject);
		when(env.getProperty(NotificationType.BACKUP.getText())).thenReturn(textTemplate);
		when(env.getProperty(NotificationType.BACKUP.getAttachment())).thenReturn(attachmentName);

		emailService.send(NotificationType.BACKUP, recipient, attachmentContent);

		verify(mailSender).send(captor.capture());
		MimeMessage message = captor.getValue();

		// delivered to the account's address only — no stray cc/bcc
		assertSingleRecipient("test@test.com", message);
		// subject is taken verbatim from configuration
		assertEquals(subject, message.getSubject());
		// {0} in the template is replaced with the account name (interpolation regression guard)
		assertEquals("Hi test, your backup is attached", getText(message));
		// the backup shape carries exactly one attachment, named from configuration
		assertEquals(Collections.singletonList(attachmentName), getAttachmentFileNames(message));
	}

	/**
	 * The remind notification is the "no attachment" message shape: same recipient/subject/body
	 * guarantees as backup, but it must never produce an attachment even though the same code
	 * path handles both message types.
	 */
	@Test
	public void shouldSendRemindEmail() throws MessagingException, IOException {

		final String subject = "Don't forget to use PiggyMetrics";
		final String textTemplate = "Hi {0}, we miss you";

		Recipient recipient = new Recipient();
		recipient.setAccountName("test");
		recipient.setEmail("test@test.com");

		when(env.getProperty(NotificationType.REMIND.getSubject())).thenReturn(subject);
		when(env.getProperty(NotificationType.REMIND.getText())).thenReturn(textTemplate);

		emailService.send(NotificationType.REMIND, recipient, null);

		verify(mailSender).send(captor.capture());
		MimeMessage message = captor.getValue();

		assertSingleRecipient("test@test.com", message);
		assertEquals(subject, message.getSubject());
		// account name is spliced into the remind template as well
		assertEquals("Hi test, we miss you", getText(message));
		// no attachment argument -> no attachment part, regardless of configuration
		assertTrue("remind email must not carry an attachment", getAttachmentFileNames(message).isEmpty());
	}

	// --- helpers: walk the MimeMessage the way a mail client would, independent of multipart nesting ---

	private void assertSingleRecipient(String expectedEmail, MimeMessage message) throws MessagingException {
		Address[] recipients = message.getAllRecipients();
		assertEquals(1, recipients.length);
		assertEquals(expectedEmail, recipients[0].toString());
	}

	/** Returns the first non-attachment text part, descending through any nested multiparts. */
	private String getText(Part part) throws MessagingException, IOException {
		if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
			return null;
		}
		Object content = part.getContent();
		if (content instanceof String) {
			return (String) content;
		}
		if (content instanceof Multipart) {
			Multipart multipart = (Multipart) content;
			for (int i = 0; i < multipart.getCount(); i++) {
				String text = getText(multipart.getBodyPart(i));
				if (text != null) {
					return text;
				}
			}
		}
		return null;
	}

	/** Collects the file name of every attachment part, descending through any nested multiparts. */
	private List<String> getAttachmentFileNames(Part part) throws MessagingException, IOException {
		List<String> names = new ArrayList<>();
		Object content = part.getContent();
		if (content instanceof Multipart) {
			Multipart multipart = (Multipart) content;
			for (int i = 0; i < multipart.getCount(); i++) {
				BodyPart bodyPart = multipart.getBodyPart(i);
				if (Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition())) {
					names.add(bodyPart.getFileName());
				} else {
					names.addAll(getAttachmentFileNames(bodyPart));
				}
			}
		}
		return names;
	}
}

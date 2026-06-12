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

import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.junit.Assert.assertArrayEquals;
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

	@Test
	public void shouldSendBackupEmail() throws MessagingException, IOException {

		final String subject = "subject";
		final String text = "text {0}";
		final String attachment = "attachment.json";
		final String attachmentContent = "{\"name\":\"test\"}";

		Recipient recipient = new Recipient();
		recipient.setAccountName("test");
		recipient.setEmail("test@test.com");

		when(env.getProperty(NotificationType.BACKUP.getSubject())).thenReturn(subject);
		when(env.getProperty(NotificationType.BACKUP.getText())).thenReturn(text);
		when(env.getProperty(NotificationType.BACKUP.getAttachment())).thenReturn(attachment);

		emailService.send(NotificationType.BACKUP, recipient, attachmentContent);

		verify(mailSender).send(captor.capture());

		MimeMessage message = captor.getValue();

		// subject
		assertEquals(subject, message.getSubject());

		// recipient
		assertArrayEquals(
				new javax.mail.Address[]{new javax.mail.internet.InternetAddress("test@test.com")},
				message.getAllRecipients());

		// body with account-name interpolation
		Object content = message.getContent();
		assertTrue("Backup email should be multipart", content instanceof Multipart);
		Multipart multipart = (Multipart) content;

		assertEquals("Should have text part and attachment part", 2, multipart.getCount());

		String bodyText = extractText(content);
		assertEquals("text test", bodyText);

		// attachment filename
		BodyPart attachmentPart = multipart.getBodyPart(1);
		assertEquals(attachment, MimeUtility.decodeText(attachmentPart.getFileName()));

		// attachment content (ByteArrayResource is read back as InputStream)
		Object rawContent = attachmentPart.getContent();
		assertTrue("Attachment content should be an InputStream", rawContent instanceof InputStream);
		assertEquals(attachmentContent, readStream((InputStream) rawContent));
	}

	@Test
	public void shouldSendRemindEmail() throws MessagingException, IOException {

		final String subject = "subject";
		final String text = "text {0}";

		Recipient recipient = new Recipient();
		recipient.setAccountName("test");
		recipient.setEmail("test@test.com");

		when(env.getProperty(NotificationType.REMIND.getSubject())).thenReturn(subject);
		when(env.getProperty(NotificationType.REMIND.getText())).thenReturn(text);

		emailService.send(NotificationType.REMIND, recipient, null);

		verify(mailSender).send(captor.capture());

		MimeMessage message = captor.getValue();

		// subject
		assertEquals(subject, message.getSubject());

		// recipient
		assertArrayEquals(
				new javax.mail.Address[]{new javax.mail.internet.InternetAddress("test@test.com")},
				message.getAllRecipients());

		// body with account-name interpolation; no attachment added
		Object content = message.getContent();
		assertTrue(content instanceof Multipart);
		Multipart multipart = (Multipart) content;
		assertEquals("Remind email should have only the text part, no attachment", 1, multipart.getCount());

		String bodyText = extractText(content);
		assertEquals("text test", bodyText);
	}

	@Test
	public void shouldInterpolateAccountNameInBody() throws MessagingException, IOException {

		Recipient recipient = new Recipient();
		recipient.setAccountName("john_doe");
		recipient.setEmail("john@example.com");

		when(env.getProperty(NotificationType.REMIND.getSubject())).thenReturn("subj");
		when(env.getProperty(NotificationType.REMIND.getText())).thenReturn("Hey, {0}! Welcome back.");

		emailService.send(NotificationType.REMIND, recipient, null);

		verify(mailSender).send(captor.capture());

		MimeMessage message = captor.getValue();
		String bodyText = extractText(message.getContent());
		assertEquals("Hey, john_doe! Welcome back.", bodyText);
	}

	@Test
	public void shouldNotAddAttachmentWhenEmptyString() throws MessagingException, IOException {

		Recipient recipient = new Recipient();
		recipient.setAccountName("test");
		recipient.setEmail("test@test.com");

		when(env.getProperty(NotificationType.BACKUP.getSubject())).thenReturn("subj");
		when(env.getProperty(NotificationType.BACKUP.getText())).thenReturn("text {0}");

		emailService.send(NotificationType.BACKUP, recipient, "");

		verify(mailSender).send(captor.capture());

		MimeMessage message = captor.getValue();
		Object content = message.getContent();
		assertTrue(content instanceof Multipart);
		Multipart multipart = (Multipart) content;
		assertEquals("Empty attachment string should not produce an attachment part", 1, multipart.getCount());
	}

	@Test
	public void shouldSetCorrectAttachmentFilename() throws MessagingException, IOException {

		final String filename = "export.csv";

		Recipient recipient = new Recipient();
		recipient.setAccountName("test");
		recipient.setEmail("test@test.com");

		when(env.getProperty(NotificationType.BACKUP.getSubject())).thenReturn("subj");
		when(env.getProperty(NotificationType.BACKUP.getText())).thenReturn("text {0}");
		when(env.getProperty(NotificationType.BACKUP.getAttachment())).thenReturn(filename);

		emailService.send(NotificationType.BACKUP, recipient, "csv,data");

		verify(mailSender).send(captor.capture());

		MimeMessage message = captor.getValue();
		Object content = message.getContent();
		assertTrue(content instanceof Multipart);

		BodyPart attachmentPart = ((Multipart) content).getBodyPart(1);
		assertEquals(filename, MimeUtility.decodeText(attachmentPart.getFileName()));
	}

	/**
	 * Recursively extract the first String-typed body part from a potentially
	 * nested MIME structure. Returns the raw content if it is already a String.
	 */
	private String extractText(Object content) throws MessagingException, IOException {
		if (content instanceof String) {
			return (String) content;
		}
		if (content instanceof Multipart) {
			Multipart multipart = (Multipart) content;
			for (int i = 0; i < multipart.getCount(); i++) {
				String result = extractText(multipart.getBodyPart(i).getContent());
				if (result != null) {
					return result;
				}
			}
		}
		return null;
	}

	/**
	 * Read an InputStream fully into a String (UTF-8). Used for verifying
	 * attachment content which arrives as InputStream from ByteArrayResource.
	 */
	private String readStream(InputStream is) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[1024];
		int len;
		while ((len = is.read(buf)) != -1) {
			bos.write(buf, 0, len);
		}
		return bos.toString("UTF-8");
	}
}

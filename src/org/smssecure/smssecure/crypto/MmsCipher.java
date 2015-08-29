package org.smssecure.smssecure.crypto;

import android.content.Context;
import android.util.Log;

import org.smssecure.smssecure.mms.FileSlide;
import org.smssecure.smssecure.mms.TextTransport;
import org.smssecure.smssecure.protocol.WirePrefix;
import org.smssecure.smssecure.recipients.RecipientFormattingException;
import org.smssecure.smssecure.transport.UndeliverableMessageException;
import org.smssecure.smssecure.util.Util;
import org.whispersystems.libaxolotl.AxolotlAddress;
import org.whispersystems.libaxolotl.DuplicateMessageException;
import org.whispersystems.libaxolotl.InvalidMessageException;
import org.whispersystems.libaxolotl.LegacyMessageException;
import org.whispersystems.libaxolotl.NoSessionException;
import org.whispersystems.libaxolotl.SessionCipher;
import org.whispersystems.libaxolotl.protocol.CiphertextMessage;
import org.whispersystems.libaxolotl.protocol.WhisperMessage;
import org.whispersystems.libaxolotl.state.AxolotlStore;
import org.whispersystems.libaxolotl.util.guava.Optional;
import org.whispersystems.textsecure.api.push.TextSecureAddress;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import ws.com.google.android.mms.ContentType;
import ws.com.google.android.mms.pdu.EncodedStringValue;
import ws.com.google.android.mms.pdu.MultimediaMessagePdu;
import ws.com.google.android.mms.pdu.PduBody;
import ws.com.google.android.mms.pdu.PduComposer;
import ws.com.google.android.mms.pdu.PduParser;
import ws.com.google.android.mms.pdu.PduPart;
import ws.com.google.android.mms.pdu.RetrieveConf;
import ws.com.google.android.mms.pdu.SendReq;

public class MmsCipher {

  private static final String TAG = MmsCipher.class.getSimpleName();

  private final TextTransport textTransport = new TextTransport();
  private final AxolotlStore axolotlStore;

  public MmsCipher(AxolotlStore axolotlStore) {
    this.axolotlStore = axolotlStore;
  }

  public MultimediaMessagePdu decrypt(Context context, MultimediaMessagePdu pdu)
      throws InvalidMessageException, LegacyMessageException, DuplicateMessageException,
             NoSessionException
  {

    try {
      SessionCipher sessionCipher = new SessionCipher(axolotlStore, new AxolotlAddress(pdu.getFrom().getString(), TextSecureAddress.DEFAULT_DEVICE_ID));
      Optional<byte[]> ciphertext = getEncryptedData(pdu);

      if (!ciphertext.isPresent()) {
        throw new InvalidMessageException("No ciphertext present!");
      }

      byte[] decodedCiphertext = textTransport.getDecodedMessage(ciphertext.get());
      byte[] plaintext;

      if (decodedCiphertext == null) {
        throw new InvalidMessageException("failed to decode ciphertext");
      }

      try {
        plaintext = sessionCipher.decrypt(new WhisperMessage(decodedCiphertext));
      } catch (InvalidMessageException e) {
        // NOTE - For some reason, Sprint seems to append a single character to the
        // end of message text segments.  I don't know why, so here we just try
        // truncating the message by one if the MAC fails.
        if (ciphertext.get().length > 2) {
          Log.w(TAG, "Attempting truncated decrypt...");
          byte[] truncated = Util.trim(ciphertext.get(), ciphertext.get().length - 1);
          decodedCiphertext = textTransport.getDecodedMessage(truncated);
          plaintext = sessionCipher.decrypt(new WhisperMessage(decodedCiphertext));
        } else {
          throw e;
        }
      }

      MultimediaMessagePdu plaintextGenericPdu = (MultimediaMessagePdu) new PduParser(plaintext).parse();
      return new RetrieveConf(plaintextGenericPdu.getPduHeaders(), plaintextGenericPdu.getBody());
    } catch (IOException e) {
      throw new InvalidMessageException(e);
    }
  }

  public List<SendReq> encrypt(Context context, SendReq message, MasterSecret masterSecret)
      throws NoSessionException, RecipientFormattingException, UndeliverableMessageException
  {
    List<byte[]> pduBytesList = new ArrayList<>();
    List<SendReq> mmsesToSend = new ArrayList<>();

    EncodedStringValue[] encodedRecipient = message.getTo();
    String               recipientString  = encodedRecipient[0].getString();

    List<SendReq> toSendReqs = new ArrayList<>();;

    try {
      toSendReqs = FileSlide.splitSendReq(message,context, masterSecret);
    } catch (IOException|URISyntaxException e) {
      Log.e("FileSlide", "Multipart-splitter failed");
      throw new UndeliverableMessageException("Multipart-splitter failed");
    }
    if(toSendReqs.size() == 0)
      toSendReqs.add(message);

    for(SendReq splitsMMS : toSendReqs) {
      byte[] pduBytes = new PduComposer(context, splitsMMS).make();
      if (pduBytes == null) {
        throw new UndeliverableMessageException("PDU composition failed, null payload");
      }
      pduBytesList.add(pduBytes);
    }

    if(pduBytesList.size() < 1)
      throw new UndeliverableMessageException("PDU composition failed, null payload");

    if (!axolotlStore.containsSession(new AxolotlAddress(recipientString, TextSecureAddress.DEFAULT_DEVICE_ID))) {
      throw new NoSessionException("No session for: " + recipientString);
    }

    SessionCipher cipher = new SessionCipher(axolotlStore, new AxolotlAddress(recipientString, TextSecureAddress.DEFAULT_DEVICE_ID));

    for(byte[] input : pduBytesList) {

      CiphertextMessage ciphertextMessage = cipher.encrypt(input);
      byte[] encryptedPduBytes = textTransport.getEncodedMessage(ciphertextMessage.serialize());

      PduBody body = new PduBody();
      PduPart part = new PduPart();
      SendReq encryptedPdu = new SendReq(message.getPduHeaders(), body);

      part.setContentId((System.currentTimeMillis() + "").getBytes());
      part.setContentType(ContentType.TEXT_PLAIN.getBytes());
      part.setName((System.currentTimeMillis() + "").getBytes());
      part.setData(encryptedPduBytes);
      body.addPart(part);
      encryptedPdu.setSubject(new EncodedStringValue(WirePrefix.calculateEncryptedMmsSubject()));
      encryptedPdu.setBody(body);

      mmsesToSend.add(encryptedPdu);

    }
    return mmsesToSend;
  }


  private Optional<byte[]> getEncryptedData(MultimediaMessagePdu pdu) {
    for (int i=0;i<pdu.getBody().getPartsNum();i++) {
      if (new String(pdu.getBody().getPart(i).getContentType()).equals(ContentType.TEXT_PLAIN)) {
        return Optional.of(pdu.getBody().getPart(i).getData());
      }
    }

    return Optional.absent();
  }


}

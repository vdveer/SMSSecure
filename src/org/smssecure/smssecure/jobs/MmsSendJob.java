package org.smssecure.smssecure.jobs;

import android.content.Context;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.util.Log;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.MmsCipher;
import org.smssecure.smssecure.crypto.storage.SMSSecureAxolotlStore;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.MmsDatabase;
import org.smssecure.smssecure.database.NoSuchMessageException;
import org.smssecure.smssecure.jobs.requirements.MasterSecretRequirement;
import org.smssecure.smssecure.mms.ApnUnavailableException;
import org.smssecure.smssecure.mms.CompatMmsConnection;
import org.smssecure.smssecure.mms.FileSlide;
import org.smssecure.smssecure.mms.MediaConstraints;
import org.smssecure.smssecure.mms.MmsSendResult;
import org.smssecure.smssecure.mms.OutgoingLegacyMmsConnection;
import org.smssecure.smssecure.mms.OutgoingLollipopMmsConnection;
import org.smssecure.smssecure.mms.OutgoingMmsConnection;
import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.recipients.RecipientFormattingException;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.transport.InsecureFallbackApprovalException;
import org.smssecure.smssecure.transport.UndeliverableMessageException;
import org.smssecure.smssecure.util.Hex;
import org.smssecure.smssecure.util.NumberUtil;
import org.smssecure.smssecure.util.SMSSecurePreferences;
import org.smssecure.smssecure.util.SmilUtil;
import org.smssecure.smssecure.util.TelephonyUtil;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.jobqueue.requirements.NetworkRequirement;
import org.whispersystems.libaxolotl.NoSessionException;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ws.com.google.android.mms.MmsException;
import ws.com.google.android.mms.pdu.EncodedStringValue;
import ws.com.google.android.mms.pdu.PduComposer;
import ws.com.google.android.mms.pdu.PduHeaders;
import ws.com.google.android.mms.pdu.SendConf;
import ws.com.google.android.mms.pdu.SendReq;

public class MmsSendJob extends SendJob {
  private static final String TAG = MmsSendJob.class.getSimpleName();

  private final long messageId;

  public MmsSendJob(Context context, long messageId) {
    super(context, JobParameters.newBuilder()
                                .withGroupId("mms-operation")
                                .withRequirement(new NetworkRequirement(context))
                                .withRequirement(new MasterSecretRequirement(context))
                                .withPersistence()
                                .create());

    this.messageId = messageId;
  }

  @Override
  public void onAdded() {
    MmsDatabase database = DatabaseFactory.getMmsDatabase(context);
    database.markAsSending(messageId);
  }

  @Override
  public void onSend(MasterSecret masterSecret) throws MmsException, NoSuchMessageException, IOException {
    MmsDatabase database = DatabaseFactory.getMmsDatabase(context);
    SendReq     message  = database.getOutgoingMessage(masterSecret, messageId);

    try {
      validateDestinations(message);

      final List<byte[]>        pduBytes = getPduBytes(masterSecret, message);
      MmsSendResult result = null;
      int i = 0;
      Log.w("MMSsendJob", "Starting sending parts, remaining: " + pduBytes.size());
      for(byte[] part : pduBytes) {
        Log.w("MMSsendJob", "Starting sending part: "+i);
        final SendConf sendConf = new CompatMmsConnection(context).send(part);
        result = getSendResult(sendConf, message);
        Log.w("MMSsendJob", "Done sending part: "+i);
        i++;

      }
      if (result.isUpgradedSecure()) {
        database.markAsSecure(messageId);
      }

      database.markAsSent(messageId, result.getMessageId(), result.getResponseStatus());
      markPartsUploaded(messageId, message.getBody());
    } catch (UndeliverableMessageException | IOException e) {
      Log.w(TAG, e);
      database.markAsSentFailed(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    } catch (InsecureFallbackApprovalException e) {
      Log.w(TAG, e);
      database.markAsPendingInsecureSmsFallback(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    }
  }

  @Override
  public boolean onShouldRetryThrowable(Exception exception) {
    return false;
  }

  @Override
  public void onCanceled() {
    DatabaseFactory.getMmsDatabase(context).markAsSentFailed(messageId);
    notifyMediaMessageDeliveryFailed(context, messageId);
  }

  private List<byte[]> getPduBytes(MasterSecret masterSecret, SendReq message)
      throws IOException, UndeliverableMessageException, InsecureFallbackApprovalException
  {

    List<byte[]> messagesToSend = new ArrayList<>();
    List<SendReq> sendReqs = new ArrayList<>();

    String number = TelephonyUtil.getManager(context).getLine1Number();

    message = getResolvedMessage(masterSecret, message, MediaConstraints.MMS_CONSTRAINTS, true);
    message.setBody(SmilUtil.getSmilBody(message.getBody()));



    if (MmsDatabase.Types.isSecureType(message.getDatabaseMessageBox())) {
      Log.w(TAG, "Encrypting MMS...");
      sendReqs        = getEncryptedMessage(masterSecret, message);
    }
    else
      sendReqs.add(message);

    for(SendReq sendReq : sendReqs) {
      byte[] pduBytes = new PduComposer(context, sendReq).make();
      if (pduBytes == null) {
        throw new UndeliverableMessageException("PDU composition failed, null payload");
      }
      messagesToSend.add(pduBytes);
    }

    if(messagesToSend.size() < 1)
      throw new UndeliverableMessageException("PDU composition failed, null payload");

    return messagesToSend;
  }

  private MmsSendResult getSendResult(SendConf conf, SendReq message)
      throws UndeliverableMessageException
  {
    boolean upgradedSecure = false;

    if (MmsDatabase.Types.isSecureType(message.getDatabaseMessageBox())) {
      upgradedSecure = true;
    }

    if (conf == null) {
      throw new UndeliverableMessageException("No M-Send.conf received in response to send.");
    } else if (conf.getResponseStatus() != PduHeaders.RESPONSE_STATUS_OK) {
      throw new UndeliverableMessageException("Got bad response: " + conf.getResponseStatus());
    } else if (isInconsistentResponse(message, conf)) {
      throw new UndeliverableMessageException("Mismatched response!");
    } else {
      return new MmsSendResult(conf.getMessageId(), conf.getResponseStatus(), upgradedSecure, false);
    }
  }

  private List<SendReq> getEncryptedMessage(MasterSecret masterSecret, SendReq pdu)
      throws InsecureFallbackApprovalException, UndeliverableMessageException
  {
    try {
      MmsCipher cipher = new MmsCipher(new SMSSecureAxolotlStore(context, masterSecret));
      return cipher.encrypt(context, pdu, masterSecret);
    } catch (NoSessionException e) {
      throw new InsecureFallbackApprovalException(e);
    } catch (RecipientFormattingException e) {
      throw new AssertionError(e);
    }
  }

  private boolean isInconsistentResponse(SendReq message, SendConf response) {
    Log.w(TAG, "Comparing: " + Hex.toString(message.getTransactionId()));
    Log.w(TAG, "With:      " + Hex.toString(response.getTransactionId()));
    return !Arrays.equals(message.getTransactionId(), response.getTransactionId());
  }

  private void validateDestinations(EncodedStringValue[] destinations) throws UndeliverableMessageException {
    if (destinations == null) return;

    for (EncodedStringValue destination : destinations) {
      if (destination == null || !NumberUtil.isValidSmsOrEmail(destination.getString())) {
        throw new UndeliverableMessageException("Invalid destination: " +
                                                (destination == null ? null : destination.getString()));
      }
    }
  }

  private void validateDestinations(SendReq message) throws UndeliverableMessageException {
    validateDestinations(message.getTo());
    validateDestinations(message.getCc());
    validateDestinations(message.getBcc());

    if (message.getTo() == null && message.getCc() == null && message.getBcc() == null) {
      throw new UndeliverableMessageException("No to, cc, or bcc specified!");
    }
  }

  private void notifyMediaMessageDeliveryFailed(Context context, long messageId) {
    long       threadId   = DatabaseFactory.getMmsDatabase(context).getThreadIdForMessage(messageId);
    Recipients recipients = DatabaseFactory.getThreadDatabase(context).getRecipientsForThreadId(threadId);

    if (recipients != null) {
      MessageNotifier.notifyMessageDeliveryFailed(context, recipients, threadId);
    }
  }
}

/** 
 * Copyright (C) 2011 Whisper Systems
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.smssecure.smssecure.mms;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.net.Uri;
import android.provider.Telephony;
import android.support.annotation.DrawableRes;
import android.util.Log;

import com.afollestad.materialdialogs.AlertDialogWrapper;
import com.afollestad.materialdialogs.MaterialDialog;

import org.smssecure.smssecure.R;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.util.MediaUtil;
import org.smssecure.smssecure.util.ResUtil;
import org.smssecure.smssecure.util.SmilUtil;
import org.smssecure.smssecure.util.Util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import ws.com.google.android.mms.ContentType;
import ws.com.google.android.mms.pdu.PduBody;
import ws.com.google.android.mms.pdu.PduPart;
import ws.com.google.android.mms.pdu.SendReq;

public class FileSlide extends Slide {

  private boolean multiPart = false;
  private static String MULTIPART_FILENAME_REGEX = "(.+)@[0-9]{1,3}\\/[0-9]{1,3}";

  public FileSlide(Context context, File file, boolean multiPart) throws IOException, MediaTooLargeException {
    super(context, constructPartFromUri(context, file, multiPart));
    if(file.length() > MmsMediaConstraints.getMaxMmsPref())
      this.multiPart = multiPart;
  }

  public FileSlide(Context context, MasterSecret masterSecret, PduPart part) {
    super(context, masterSecret, part);
    multiPart = isMultiPart(part.getFilename() != null ? new String(part.getFilename()) : null);
    Log.w("FileRegex", "Multipart .+@[0-9]{1,3}\\/[0-9]{1,3}: " + (multiPart ? "matches" : "does not match"));
  }

  public static boolean isMultiPart(String filename){
    return filename != null ? Pattern.compile(MULTIPART_FILENAME_REGEX).matcher(filename).matches() : false;
  }

  public static String parseOriginalFilename(String filename){
    return Pattern.compile(MULTIPART_FILENAME_REGEX).matcher(filename).group(1);
  }

  @Override
  public boolean hasFile(){ return true; }

  @Override
  public boolean hasImage() { return true; }

  @Override
  public boolean isMultipart() { return multiPart; };

  @Override
  public @DrawableRes int getPlaceholderRes(Theme theme) {
    return ResUtil.getDrawableRes(theme, R.attr.conversation_attach);
  }

  public static PduPart constructPartFromUri(Context context, File file, boolean allowMultipart) throws IOException, MediaTooLargeException {
    PduPart part = new PduPart();

    if(file.length()> MmsMediaConstraints.getMaxMmsPref() && !allowMultipart) //TODO: depend on filesetting or ignore size when implementing multipart.
      throw new MediaTooLargeException();

    RandomAccessFile f = new RandomAccessFile(file, "r");
    byte[] fileData;
    try {
      int length = (int) f.length();
      fileData = new byte[length];
      f.readFully(fileData);

    } finally {
      f.close();
    }
    part.setDataUri(Uri.fromFile(file));
    part.setFilename(file.getName().getBytes());
    part.setContentId((System.currentTimeMillis() + "").getBytes());
    part.setContentType(ContentType.APP_DRM_CONTENT.getBytes());
    part.setName((System.currentTimeMillis() + "").getBytes());
    part.setData(fileData);
    return part;
  }

  public static void showWarningDialog(Context context, DialogInterface.OnClickListener onAcceptListener, String numberOfParts) {
    AlertDialogWrapper.Builder builder = new AlertDialogWrapper.Builder(context);
    builder.setTitle(R.string.multipart_mms_question_title);
    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setCancelable(true);
    builder.setMessage(context.getResources().getString(R.string.multipart_mms_question_message) + numberOfParts);
    builder.setPositiveButton(R.string.yes, onAcceptListener);
    builder.setNegativeButton(R.string.no, null);
    builder.show();
  }

  public static List<SendReq> splitSendReq(SendReq message, Context context, MasterSecret masterSecret) throws IOException, URISyntaxException {
    // TODO: refactor FileSlide.splitSendReq before merge to master...
    List<SendReq> result = new ArrayList<>();
    PduPart bigFile = null;

    PduBody originalBody = message.getBody();
    int numberOfParts = originalBody.getPartsNum();
    for(int i = 0; i < numberOfParts; i++){
      PduPart part = originalBody.getPart(i);
      if(ContentType.isDrmType(new String(part.getContentType())) && part.getDataSize() > MmsMediaConstraints.getMaxMmsPref())
        bigFile = part;
    }
    if(null == bigFile) {
      result.add(message);
    } else{
      long fileSize = bigFile.getDataSize();
      int parts =((((int)fileSize-1)/MmsMediaConstraints.getMaxMmsPref()) )+ 1;
      Log.w("FileSlicer", "Multipart slicing started, filesize: " + fileSize + ", parts: " + parts);
      if(bigFile.getDataUri() == null) {
        Log.w("FileSlicer", "No URI for file set; something is wrong here");
        throw new IOException("No URI for file set; something is wrong here");
      }
      File inputFile = new File(new URI(bigFile.getDataUri().toString()));
      RandomAccessFile raf = new RandomAccessFile(inputFile, "r");

      for(int i = 0; i < parts; i++){
        SendReq tempSendReq = null;
        if(i == 0) {
          PduBody copyBody = new PduBody();

          for(int j = 0; j < originalBody.getPartsNum(); j++){
            copyBody.addPart(originalBody.getPart(j));
          }
          // kuchkuch
          PduPart newPart = covertPart(bigFile, i, parts, MmsMediaConstraints.getMaxMmsPref(), context, masterSecret);
          // add data
          byte[] toRead = new byte[MmsMediaConstraints.getMaxMmsPref()];
          raf.read(toRead);
          newPart.setData(toRead);
          newPart.setDataSize(toRead.length);
          Log.w("FileSlicer", "Part: " + i + ", datalength: " + toRead.length);

          copyBody.removePart(copyBody.getPartIndex(bigFile));
          copyBody.addPart(newPart);
          tempSendReq = new SendReq(message.getPduHeaders(), copyBody,  message.getDatabaseMessageId(),
                  message.getDatabaseMessageBox(),
                  message.getSentTimestamp());
        }
        else{
          PduBody copyBody = new PduBody();
          PduPart newPart = covertPart(bigFile, i, parts, MmsMediaConstraints.getMaxMmsPref(), context, masterSecret);
          byte[] toRead = new byte[MmsMediaConstraints.getMaxMmsPref()];
          raf.read(toRead);
          newPart.setData(toRead);
          newPart.setDataSize(toRead.length);
          Log.w("FileSlicer", "Part: " + i + ", datalength: " + toRead.length);

          copyBody.addPart(newPart);
          tempSendReq = new SendReq(message.getPduHeaders(), copyBody, message.getDatabaseMessageId(),
                  message.getDatabaseMessageBox(),
                  message.getSentTimestamp());
        }
        tempSendReq.setBody(SmilUtil.getSmilBody(tempSendReq.getBody()));


        if(null != tempSendReq)
          result.add(tempSendReq);
      }
      raf.close();
      if(result.size() < 2)
        Log.e("Sanity check", "Multipart message with <2 parts");
    }
    return result;
  }

  private static PduPart covertPart(PduPart bigFile, int subPart, int maxParts, int maxSize, Context context, MasterSecret masterSecret)  {
    PduPart smallPart = new PduPart();
    //smallPart.setDataUri(bigFile.getDataUri());
    if (bigFile.getFilename() != null)
      smallPart.setFilename((new String(bigFile.getFilename()) + "@" + (subPart + 1) + "/" + maxParts).getBytes());
    smallPart.setContentId((System.currentTimeMillis() + "").getBytes());
    smallPart.setContentType(bigFile.getContentType());
    smallPart.setName((System.currentTimeMillis() + "").getBytes());


    return smallPart;
  }

}

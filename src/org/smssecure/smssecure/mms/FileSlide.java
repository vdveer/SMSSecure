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
import android.support.annotation.DrawableRes;
import android.util.Log;

import com.afollestad.materialdialogs.AlertDialogWrapper;

import org.smssecure.smssecure.R;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.util.ResUtil;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.regex.Pattern;

import ws.com.google.android.mms.ContentType;
import ws.com.google.android.mms.pdu.PduPart;

public class FileSlide extends Slide {

  private boolean multiPart = false;

  public FileSlide(Context context, File file, boolean multiPart) throws IOException, MediaTooLargeException {
    super(context, constructPartFromUri(context, file, multiPart));
    if(file.length() > MmsMediaConstraints.getMaxMmsPref())
      this.multiPart = multiPart;
  }

  public FileSlide(Context context, MasterSecret masterSecret, PduPart part) {
    super(context, masterSecret, part);
    multiPart = part.getFilename() != null ? Pattern.compile(".+@[0-9]{1,3}\\/[0-9]{1,3}").matcher(new String(part.getFilename())).matches() : false;// check for @ / string
    Log.w("FileRegex", "Multipart .+@[0-9]{1,3}\\/[0-9]{1,3}: " + (multiPart ? "matches" : "does not match"));
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
}

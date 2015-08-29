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

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;
import android.widget.Toast;

import org.smssecure.smssecure.ConversationActivity;
import org.smssecure.smssecure.R;
import org.smssecure.smssecure.components.ThumbnailView;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.providers.CaptureProvider;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.util.BitmapDecodingException;
import org.smssecure.smssecure.util.FutureTaskListener;
import org.smssecure.smssecure.util.MediaUtil;
import org.smssecure.smssecure.util.SMSSecurePreferences;
import org.smssecure.smssecure.util.SaveAttachmentTask;

import java.io.File;
import java.io.IOException;

import de.greenrobot.event.util.ErrorDialogManager;


public class AttachmentManager implements DialogInterface.OnClickListener{
  private final static String TAG = AttachmentManager.class.getSimpleName();

  private final Context            context;
  private final View               attachmentView;
  private final ThumbnailView      thumbnail;
  private final ImageView          removeButton;
  private final SlideDeck          slideDeck;
  private final AttachmentListener attachmentListener;
  private File                     file;

  private Uri captureUri;

  public AttachmentManager(Activity view, AttachmentListener listener) {
    this.attachmentView     = view.findViewById(R.id.attachment_editor);
    this.thumbnail          = (ThumbnailView)view.findViewById(R.id.attachment_thumbnail);
    this.removeButton       = (ImageView)view.findViewById(R.id.remove_image_button);
    this.slideDeck          = new SlideDeck();
    this.context            = view;
    this.attachmentListener = listener;
    this.removeButton.setOnClickListener(new RemoveButtonListener());
  }

  public void clear() {
    AlphaAnimation animation = new AlphaAnimation(1.0f, 0.0f);
    animation.setDuration(200);
    animation.setAnimationListener(new Animation.AnimationListener() {
      @Override public void onAnimationStart(Animation animation) {}
      @Override public void onAnimationRepeat(Animation animation) {}
      @Override public void onAnimationEnd(Animation animation) {
        slideDeck.clear();
        attachmentView.setVisibility(View.GONE);
        attachmentListener.onAttachmentChanged();
      }

    });

    attachmentView.startAnimation(animation);
  }

  public void clearIfFileSlides(){
    if(slideDeck.hasFileSlide()) {
      slideDeck.clear();
      attachmentView.setVisibility(View.GONE);
      attachmentListener.onAttachmentChanged();
    }
  }

  public void cleanup() {
    if (captureUri != null) CaptureProvider.getInstance(context).delete(captureUri);
    captureUri = null;
  }

  public void setImage(MasterSecret masterSecret, Uri image)
      throws IOException, BitmapDecodingException, MediaTooLargeException
  {
    if (MediaUtil.isGif(MediaUtil.getMimeType(context, image))) {
      setMedia(new GifSlide(context, masterSecret, image), masterSecret);
    } else {
      setMedia(new ImageSlide(context, masterSecret, image), masterSecret);
    }
  }

  public void setVideo(Uri video) throws IOException, MediaTooLargeException {
    setMedia(new VideoSlide(context, video));
  }

  public void setAudio(Uri audio) throws IOException, MediaTooLargeException {
    setMedia(new AudioSlide(context, audio));
  }

  public void setFile(final File file) throws IOException, MediaTooLargeException {

    if(file.length() > MmsMediaConstraints.getMaxMmsPref() && SMSSecurePreferences.getMultipartMMS(context)){
      this.file = file;
      String numberOfParts = String.valueOf((file.length()-1)/MmsMediaConstraints.getMaxMmsPref() + 1);
      FileSlide.showWarningDialog(context, this, numberOfParts);
    }else {
      setMedia(new FileSlide(context, file, false));
    }
  }

  public void setMedia(final Slide slide) {
    setMedia(slide, null);
  }

  public void setMedia(final Slide slide, @Nullable MasterSecret masterSecret) {
    slideDeck.clear();
    slideDeck.addSlide(slide);
    attachmentView.setVisibility(View.VISIBLE);
    thumbnail.setImageResource(slide, masterSecret);
    attachmentListener.onAttachmentChanged();
  }

  public boolean isAttachmentPresent() {
    return attachmentView.getVisibility() == View.VISIBLE;
  }

  @Override
  public void onClick(DialogInterface dialog, int which) {
    try {
      setMedia(new FileSlide(context, file, true));
    } catch (IOException|MediaTooLargeException e) {
      Log.w("AttachmentManager", e);
      Toast.makeText(context, "Error while attaching file", Toast.LENGTH_LONG).show();
    }}

  public SlideDeck getSlideDeck() {
    return slideDeck;
  }

  public static void selectVideo(Activity activity, int requestCode) {
    selectMediaType(activity, "video/*", requestCode);
  }

  public static void selectImage(Activity activity, int requestCode) {
    selectMediaType(activity, "image/*", requestCode);
  }

  public static void selectAudio(Activity activity, int requestCode) {
    selectMediaType(activity, "audio/*", requestCode);
  }

  public static void selectContactInfo(Activity activity, int requestCode) {
    Intent intent = new Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI);
    activity.startActivityForResult(intent, requestCode);
  }

  public void selectFile(final Activity activity, int requestCode) {

    new FileChooser(activity).setFileListener(new FileChooser.FileSelectedListener() {
      @Override
      public void fileSelected(final File file) {
        try {
          setFile(file);
        } catch (IOException ioe) {
          Toast.makeText(activity, "Error while attaching file", Toast.LENGTH_LONG).show();
          Log.w("AttachmentManager", ioe);
        } catch (MediaTooLargeException mtle) {
          Toast.makeText(activity, "File is too large to be attached", Toast.LENGTH_LONG).show();
          Log.w("AttachmentManager", mtle);
        }
      }
    }).showDialog();
  }

  public Uri getCaptureUri() {
    return captureUri;
  }

  public void setCaptureUri(Uri captureUri) {
    this.captureUri = captureUri;
  }

  public void capturePhoto(Activity activity, Recipients recipients, int requestCode) {
    try {
      Intent captureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
      if (captureIntent.resolveActivity(activity.getPackageManager()) != null) {
        captureUri = CaptureProvider.getInstance(context).createForExternal(recipients);
        captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, captureUri);
        activity.startActivityForResult(captureIntent, requestCode);
      }
    } catch (IOException ioe) {
      Log.w(TAG, ioe);
    }
  }

  private static void selectMediaType(Activity activity, String type, int requestCode) {
    final Intent intent = new Intent();
    intent.setType(type);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      intent.setAction(Intent.ACTION_OPEN_DOCUMENT);
      try {
        activity.startActivityForResult(intent, requestCode);
        return;
      } catch (ActivityNotFoundException anfe) {
        Log.w(TAG, "couldn't complete ACTION_OPEN_DOCUMENT, no activity found. falling back.");
      }
    }

    intent.setAction(Intent.ACTION_GET_CONTENT);
    try {
      activity.startActivityForResult(intent, requestCode);
    } catch (ActivityNotFoundException anfe) {
      Log.w(TAG, "couldn't complete ACTION_GET_CONTENT intent, no activity found. falling back.");
      Toast.makeText(activity, R.string.AttachmentManager_cant_open_media_selection, Toast.LENGTH_LONG).show();
    }
  }

  private class RemoveButtonListener implements View.OnClickListener {
    @Override
    public void onClick(View v) {
      clear();
      cleanup();
    }
  }

  public interface AttachmentListener {
    void onAttachmentChanged();
  }
}

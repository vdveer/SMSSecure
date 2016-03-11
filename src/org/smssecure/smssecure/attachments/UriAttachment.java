package org.smssecure.smssecure.attachments;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.smssecure.smssecure.ApplicationContext;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.util.MediaUtil;
import org.whispersystems.libaxolotl.util.guava.Optional;

import java.io.IOException;
import java.io.InputStream;

public class UriAttachment extends Attachment {

  private final @NonNull Uri dataUri;
  private final @NonNull Uri thumbnailUri;

  public UriAttachment(@NonNull Uri uri, @NonNull String contentType, int transferState, long size) {
    this(uri, uri, contentType, transferState, size, UriAttachment.getFilenameFromUri(uri));
  }

  public UriAttachment(@NonNull Uri dataUri, @NonNull Uri thumbnailUri,
                       @NonNull String contentType, int transferState, long size, @Nullable String fileName)
  {
    super(contentType, transferState, size, null, null, null, fileName);
    this.dataUri      = dataUri;
    this.thumbnailUri = thumbnailUri;
  }

  @Override
  @NonNull
  public Uri getDataUri() {
    return dataUri;
  }

  @Override
  @NonNull
  public Uri getThumbnailUri() {
    return thumbnailUri;
  }

  @Override
  public boolean equals(Object other) {
    return other != null && other instanceof UriAttachment && ((UriAttachment) other).dataUri.equals(this.dataUri);
  }

  @Override
  public int hashCode() {
    return dataUri.hashCode();
  }

  public static String getFilenameFromUri(Uri uri) {
    String scheme = uri.getScheme(), fileName = null;
    if (scheme.equals("file")) {
      fileName = uri.getLastPathSegment();
    } else if (scheme.equals("content")) {
      String[] proj = {MediaStore.Images.Media.TITLE};
      Cursor cursor = ApplicationContext.get().getContentResolver().query(uri, proj, null, null, null);
      if (cursor != null && cursor.getCount() != 0) {
        int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.TITLE);
        cursor.moveToFirst();
        fileName = cursor.getString(columnIndex);
      }
      if (cursor != null) {
        cursor.close();
      }
    }
    return fileName;
  }

}

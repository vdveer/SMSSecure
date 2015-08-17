package org.smssecure.smssecure.mms;

import android.content.Context;

import org.smssecure.smssecure.util.Util;


public class MmsMediaConstraints extends MediaConstraints {
  private static final int MAX_IMAGE_DIMEN_LOWMEM = 768;
  private static final int MAX_IMAGE_DIMEN        = 1024;
  public static int MAX_MESSAGE_SIZE       = 220 * 1024;


  @Override
  public int getImageMaxWidth(Context context) {
    return Util.isLowMemory(context) ? MAX_IMAGE_DIMEN_LOWMEM : MAX_IMAGE_DIMEN;
  }

  @Override
  public int getImageMaxHeight(Context context) {
    return getImageMaxWidth(context);
  }

  @Override
  public int getImageMaxSize() {
    return MAX_MESSAGE_SIZE;
  }

  @Override
  public int getGifMaxSize() {
    return MAX_MESSAGE_SIZE;
  }

  @Override
  public int getVideoMaxSize() {
    return MAX_MESSAGE_SIZE;
  }

  @Override
  public int getAudioMaxSize() {
    return MAX_MESSAGE_SIZE;
  }

  public static void setMaxSize(int kiloBytes){
    if(kiloBytes > 0)
      MAX_MESSAGE_SIZE = kiloBytes * 1024;
  }

}

package org.smssecure.smssecure.util;

import android.content.Context;
import android.os.PowerManager;

import java.util.UUID;

/**
 * Created by vdveer on 6-4-16.
 */
public class WakeLockUtil {

  private static PowerManager.WakeLock wakeLock = null;

  public static PowerManager.WakeLock aquireWakeLockFor(Context context) {
    if (wakeLock == null) {
      PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
      wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
              "SMSsecureWakeLock" + UUID.randomUUID().toString());
    }
    if (wakeLock != null && !wakeLock.isHeld()) {
      wakeLock.acquire();
    }

    return wakeLock;
  }

  public static boolean disableWakeLockIfActive(Context context) {
    if (wakeLock == null) {
      return false;
    }
    else if (wakeLock.isHeld()) {
      wakeLock.release();
      wakeLock = null;

      return true;
    }

    return false;
  }
}

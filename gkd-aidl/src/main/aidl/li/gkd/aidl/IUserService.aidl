package li.gkd.aidl;

import android.graphics.Bitmap;
import android.graphics.Rect;
import li.gkd.aidl.ISystemSurfaceListener;
import li.gkd.aidl.IInstallResultListener;
import android.content.Intent;
import android.content.IntentSender;

interface IUserService {
    void destroy() = 16777114;
    Bitmap takeScreenshot(in Rect crop, int rotation) = 1;
    void setSystemSurfaceListener(ISystemSurfaceListener listener) = 2;
    IntentSender createInstallResultSender(in Intent launchIntent, String callingPackage, int userId, IInstallResultListener listener) = 3;
    /**
     * Restrict sensorservice to [pkg] only, then auto-enable after [autoRestoreMs]
     * (server clamps to [1000, 30000]). Command strings are fixed server-side.
     */
    boolean sensorRestrict(String pkg, long autoRestoreMs) = 4;
    /** Re-enable sensorservice for all packages. Command is fixed server-side. */
    boolean sensorEnable() = 5;
    /**
     * Sample dumpsys sensorservice for [durationSec] seconds without freezing.
     * Returns a readable report of listener start/stop for [pkg] (Debug / calibrate).
     */
    String sensorCalibrate(String pkg, int durationSec) = 6;
}

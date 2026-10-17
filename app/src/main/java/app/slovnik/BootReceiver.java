package app.slovnik;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarms don't survive a reboot or an app update: plan the reminders again. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Notifications.scheduleAll(c);
        }
    }
}

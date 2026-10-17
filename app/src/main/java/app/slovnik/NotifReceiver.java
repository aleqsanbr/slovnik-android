package app.slovnik;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarms for reminders and the buttons inside them ("Show translation", "Remember", "Forgot"). Not exported. */
public class NotifReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        switch (a) {
            case Notifications.ACTION_ALARM:
                Notifications.onAlarm(c, i.getStringExtra("kind"));
                break;
            case Notifications.ACTION_REVEAL:
                Notifications.reveal(c, i.getStringExtra("id"));
                break;
            case Notifications.ACTION_GRADE:
                Notifications.grade(c, i.getStringExtra("id"), i.getStringExtra("grade"));
                break;
            default:
                break;
        }
    }
}

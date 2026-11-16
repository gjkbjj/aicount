package com.gakiji.aiaccount;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class UndoReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        long id = BillService.entryId(intent);
        if (id <= 0) return;
        Ledger.get(context).remove(id);
        var manager = context.getSystemService(NotificationManager.class);
        for (var status : manager.getActiveNotifications()) {
            if (BillService.CHANNEL.equals(status.getTag())
                    && status.getNotification().extras.getLong("ledger_entry") == id) {
                manager.cancel(BillService.CHANNEL, 1);
            }
        }
        BillService.changed(context);
    }
}

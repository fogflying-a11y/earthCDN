/*
 * Mantou Earth - Live your wallpaper with live earth
 * Copyright (C) 2015-2019 XiNGRZ <xxx@oxo.ooo>
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

package ooo.oxo.apps.earth.background;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.text.DateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import ooo.oxo.apps.earth.EarthSyncImpl;
import ooo.oxo.apps.earth.R;
import ooo.oxo.apps.earth.dao.Settings;
import ooo.oxo.apps.earth.provider.SettingsContract;

import static android.os.PowerManager.PARTIAL_WAKE_LOCK;
import static ooo.oxo.apps.earth.EarthSyncImpl.RESULT_ERROR;
import static ooo.oxo.apps.earth.EarthSyncImpl.RESULT_INSERTS;

public class EarthBackgroundService extends Service {

    private static final String TAG = "EarthBgService";
    private static final int NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_CHANNEL = "background";

    public static void start(Context context) {
        final Intent intent = new Intent(context, EarthBackgroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private EarthSyncImpl syncer;

    private NotificationManagerCompat nm;
    private PowerManager.WakeLock wakeLock;

    private HandlerThread handlerThread;
    private Handler handler;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    @SuppressLint("WakelockTimeout")
    public void onCreate() {
        super.onCreate();

        syncer = new EarthSyncImpl(this);

        nm = NotificationManagerCompat.from(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL,
                    getString(R.string.background_channel), NotificationManager.IMPORTANCE_MIN);
            channel.enableLights(false);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification().build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, createNotification().build());
        }

        final PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PARTIAL_WAKE_LOCK, "earth:background");
            wakeLock.acquire();
        }

        handlerThread = new HandlerThread("sync");
        handlerThread.start();

        handler = new Handler(handlerThread.getLooper());

        handler.post(this::run);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (wakeLock != null) {
            wakeLock.release();
        }

        handler.removeCallbacksAndMessages(null);
        handlerThread.quit();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @SuppressWarnings("ConstantConditions")
    private void run() {
        final Map<String, Long> synced = syncer.sync(false);

        String lastUpdate = null;

        if (synced.containsKey(RESULT_ERROR)) {
            long errorCode = synced.get(RESULT_ERROR);
            String time = DateFormat.getInstance().format(new Date());
            String detail = errorToDetail(errorCode);
            lastUpdate = getString(R.string.background_summary, time) + " (" + detail + ")";
            android.util.Log.w(TAG, "sync failed, code=" + errorCode + ", detail=" + detail);
        } else if (synced.containsKey(RESULT_INSERTS)) {
            final long insets = synced.get(RESULT_INSERTS);
            if (insets > 0) {
                lastUpdate = getString(R.string.background_summary,
                        DateFormat.getInstance().format(new Date()));
            }
        }

        nm.notify(NOTIFICATION_ID, createNotification()
                .setContentText(lastUpdate)
                .build());

        // Always schedule next run (success or failure) using user-configured interval
        schedule();
    }

    private String errorToDetail(long code) {
        if (code == EarthSyncImpl.ERROR_CDN_NOT_CONFIGURED) return "CDN not configured";
        if (code == EarthSyncImpl.ERROR_API_FAILED) return "API failed";
        if (code == EarthSyncImpl.ERROR_TILE_FETCH_FAILED) return "tile download failed";
        if (code == EarthSyncImpl.ERROR_SKIPPED_METERED) return "skipped (metered network)";
        if (code == EarthSyncImpl.ERROR_DB) return "database error";
        return "error";
    }

    private void schedule() {
        handler.postDelayed(this::run, getIntervalMs());
    }

    /**
     * Read the user-configured update interval from the ContentProvider.
     * Falls back to 10 minutes if the value cannot be read.
     */
    private long getIntervalMs() {
        try (Cursor cursor = getContentResolver().query(
                SettingsContract.CONTENT_URI, null, null, null, null)) {
            if (cursor != null && cursor.moveToNext()) {
                Settings settings = Settings.fromCursor(cursor);
                if (settings != null && settings.interval > 0) {
                    return settings.interval;
                }
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "failed to read interval, using default", e);
        }
        return TimeUnit.MINUTES.toMillis(10);
    }

    private NotificationCompat.Builder createNotification() {
        return new NotificationCompat.Builder(
                this, NOTIFICATION_CHANNEL)
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_earth_24dp)
                .setColor(getResources().getColor(R.color.primary))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentTitle(getString(R.string.background_title));
    }

}

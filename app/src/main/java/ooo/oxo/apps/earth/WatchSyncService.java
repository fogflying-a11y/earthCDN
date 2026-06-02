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

package ooo.oxo.apps.earth;

import android.app.Service;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import ooo.oxo.apps.earth.provider.EarthsContract;

/**
 * 用于观察地球变化并触发 {@link WatchTransferService} 同步到手表
 */
public class WatchSyncService extends Service {

    private static final String TAG = "WatchSyncService";

    private final AtomicInteger connected = new AtomicInteger();

    private HandlerThread handlerThread;
    private Handler handler;

    private final ContentObserver observer = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            Log.d(TAG, "new earth ready, triggering watch sync...");
            sync();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        getContentResolver().registerContentObserver(EarthsContract.LATEST_CONTENT_URI, false, observer);

        handlerThread = new HandlerThread("WatchSync");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        Log.d(TAG, "watch syncing service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.post(this::connectAndCheckNodes);
        return START_STICKY;
    }

    private void connectAndCheckNodes() {
        Task<Integer> nodesTask = Wearable.getNodeClient(this)
                .getConnectedNodes()
                .continueWith(task -> {
                    int count = connected.addAndGet(task.getResult().size());
                    if (count == 0) {
                        Log.d(TAG, "no watch connected, stopping...");
                        stopSelf();
                    } else {
                        Log.d(TAG, "connected watch: " + count);
                    }
                    return count;
                });

        try {
            Tasks.await(nodesTask);
        } catch (ExecutionException | InterruptedException e) {
            Log.e(TAG, "connection failed: " + e);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        getContentResolver().unregisterContentObserver(observer);

        handler.removeCallbacksAndMessages(null);
        handlerThread.quit();

        Log.d(TAG, "watch syncing service stopped");
    }

    private void sync() {
        startService(new Intent(this, WatchTransferService.class));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}

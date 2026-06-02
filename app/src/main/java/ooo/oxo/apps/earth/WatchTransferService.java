/*
 * Mantou Earth - Live your wallpaper with live earth
 * Copyright (C) 2015  XiNGRZ <xxx@oxo.ooo>
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
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutionException;

import ooo.oxo.apps.earth.provider.EarthsContract;

/**
 * 将最新的图片同步到手表上
 */
public class WatchTransferService extends Service {

    private static final String TAG = "WatchTransferService";

    private HandlerThread handlerThread;
    private Handler handler;

    @Override
    public void onCreate() {
        super.onCreate();
        handlerThread = new HandlerThread("WatchTransfer");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.post(this::doTransfer);
        return START_NOT_STICKY;
    }

    private void doTransfer() {
        Log.d(TAG, "transferring earth from phone to watch...");

        Bitmap image;

        try {
            image = Glide.with(this)
                    .asBitmap()
                    .load(EarthsContract.LATEST_CONTENT_URI
                            .buildUpon()
                            .appendQueryParameter("t", String.valueOf(System.currentTimeMillis()))
                            .build())
                    .submit(360, 360)
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "failed to prepare earth image");
            stopSelf();
            return;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.WEBP, 75, output);

        Asset asset = Asset.createFromBytes(output.toByteArray());

        PutDataRequest request = PutDataRequest.create("/earth");
        request.putAsset("earth", asset);

        try {
            Tasks.await(Wearable.getDataClient(this).putDataItem(request));
        } catch (ExecutionException | InterruptedException e) {
            Log.e(TAG, "failed to put data item", e);
        }

        Log.d(TAG, "done syncing");
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handlerThread.quit();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}

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

import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;

import ooo.oxo.apps.earth.dao.Earth;
import ooo.oxo.apps.earth.provider.EarthsContract;

public class WatchSyncService extends WearableListenerService {

    private static final String TAG = "WatchSyncService";

    @Override
    public void onDataChanged(DataEventBuffer events) {
        Log.v(TAG, "data incoming from phone");

        for (DataEvent event : events) {
            if (event.getType() == DataEvent.TYPE_CHANGED &&
                    event.getDataItem().getUri().getPath().equals("/earth")) {
                Asset asset = getAssetFromDataItem(event.getDataItem());
                if (asset != null) {
                    handleAsset(asset);
                }
            }
        }
    }

    private Asset getAssetFromDataItem(DataItem dataItem) {
        try {
            DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
            return dataMapItem.getDataMap().getAsset("earth");
        } catch (Exception e) {
            Log.w(TAG, "failed to extract asset from data item", e);
        }
        return null;
    }

    private void handleAsset(Asset asset) {
        if (asset == null) {
            throw new IllegalArgumentException("asset must not be null");
        }

        InputStream input = null;
        try {
            Task<InputStream> task = Wearable.getDataClient(this)
                    .getFdForAsset(asset)
                    .continueWith(t -> t.getResult().getInputStream());
            input = Tasks.await(task);
        } catch (ExecutionException | InterruptedException e) {
            Log.e(TAG, "failed to get asset", e);
            return;
        }

        if (input == null) {
            Log.e(TAG, "failed to get asset input stream");
            return;
        }

        File file = new File(getCacheDir(), "earth_" + System.currentTimeMillis());

        try {
            FileUtils.copyInputStreamToFile(input, file);
        } catch (IOException e) {
            Log.e(TAG, "failed to save asset", e);
            return;
        }

        Earth earth = new Earth(file.getAbsolutePath());
        getContentResolver().insert(EarthsContract.CONTENT_URI, earth.toContentValues());

        Log.d(TAG, "done syncing earth");
    }

}

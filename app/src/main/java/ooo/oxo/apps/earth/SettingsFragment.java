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

import android.database.Cursor;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import ooo.oxo.apps.earth.cdn.CloudinaryClient;
import ooo.oxo.apps.earth.provider.SettingsContract;

public class SettingsFragment extends PreferenceFragmentCompat {

    private static final String TAG = "SettingsFragment";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings, rootKey);
        setupSummaries();
        setupTestConnection();
    }

    private void setupSummaries() {
        EditTextPreference cloudNamePref = findPreference("cdn_cloud_name");
        if (cloudNamePref != null) {
            cloudNamePref.setOnPreferenceChangeListener((preference, newValue) -> {
                String val = (String) newValue;
                android.util.Log.d(TAG, "cloud_name changed to: '" + val + "'");
                preference.setSummary(val != null && !val.isEmpty() ? val : getString(R.string.cdn_cloud_name_summary));
                syncToContentProvider(SettingsContract.Columns.CDN_CLOUD_NAME, val);
                return true;
            });
        }

        androidx.preference.SwitchPreference debugPref = findPreference("debug");
        if (debugPref != null) {
            debugPref.setOnPreferenceChangeListener((preference, newValue) -> {
                Boolean val = (Boolean) newValue;
                android.util.Log.d(TAG, "debug changed to: " + val);
                // Write debug flag file FIRST (synchronous I/O).
                // The ContentProvider observer in :wallpaper process triggers draw(),
                // which reads this file. Writing first ensures the file is on disk
                // before the observer fires.
                if (getContext() != null) {
                    ooo.oxo.apps.earth.EarthWallpaperService.writeDebugFlag(getContext(), val);
                }
                syncToContentProvider(SettingsContract.Columns.DEBUG, val ? 1 : 0);
                return true;
            });
        }
    }

    private void syncToContentProvider(String column, Object value) {
        if (getContext() == null) return;
        android.content.ContentValues values = new android.content.ContentValues();
        if (value instanceof Integer) {
            values.put(column, (Integer) value);
        } else if (value instanceof String) {
            values.put(column, (String) value);
        } else if (value instanceof Boolean) {
            values.put(column, ((Boolean) value) ? 1 : 0);
        }
        int affected = getContext().getContentResolver().update(
                SettingsContract.CONTENT_URI, values, null, null);
        android.util.Log.d(TAG, "ContentProvider update " + column + " affected rows: " + affected);
    }

    private void setupTestConnection() {
        Preference testPref = findPreference("cdn_test_connection");
        if (testPref == null) return;

        testPref.setOnPreferenceClickListener(preference -> {
            testPref.setEnabled(false);
            testPref.setSummary(R.string.cdn_test_testing);

            new Thread(() -> {
                String cloudName = readCloudName();
                CloudinaryClient client = new CloudinaryClient(cloudName);
                CloudinaryClient.TestResult result = client.testConnection();

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        testPref.setEnabled(true);
                        testPref.setSummary(result.message);
                        Toast.makeText(getContext(),
                                result.success ? result.message : "✗ " + result.message,
                                result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG)
                                .show();
                    });
                }
            }).start();
            return true;
        });
    }

    private String readCloudName() {
        if (getContext() == null) return null;
        try (Cursor cursor = getContext().getContentResolver().query(
                SettingsContract.CONTENT_URI, null, null, null, null)) {
            if (cursor != null && cursor.moveToNext()) {
                int idx = cursor.getColumnIndex(SettingsContract.Columns.CDN_CLOUD_NAME);
                if (idx >= 0) {
                    return cursor.getString(idx);
                }
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "failed to read cloud_name", e);
        }
        return null;
    }
}

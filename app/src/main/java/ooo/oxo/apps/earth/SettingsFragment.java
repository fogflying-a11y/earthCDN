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

import android.os.Bundle;

import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;

public class SettingsFragment extends PreferenceFragmentCompat {

    private static final String TAG = "SettingsFragment";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings, rootKey);
        setupSummaries();
    }

    private void setupSummaries() {
        EditTextPreference cloudNamePref = findPreference("cdn_cloud_name");
        if (cloudNamePref != null) {
            cloudNamePref.setOnPreferenceChangeListener((preference, newValue) -> {
                String val = (String) newValue;
                android.util.Log.d(TAG, "cloud_name changed to: '" + val + "'");
                preference.setSummary(val != null && !val.isEmpty() ? val : getString(R.string.cdn_cloud_name_summary));
                // Write directly to ContentProvider (single source of truth)
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(ooo.oxo.apps.earth.provider.SettingsContract.Columns.CDN_CLOUD_NAME, val);
                if (getContext() != null) {
                    int affected = getContext().getContentResolver().update(
                            ooo.oxo.apps.earth.provider.SettingsContract.CONTENT_URI, values, null, null);
                    android.util.Log.d(TAG, "ContentProvider update affected rows: " + affected);
                }
                return true;
            });
        }
    }
}

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

package ooo.oxo.apps.earth.widget;

import android.app.Activity;
import android.view.Window;
import android.view.WindowManager;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class ImmersiveUtil {

    private static final int FLAG_KEEP_SCREEN_ON = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

    public static void enter(Activity activity) {
        activity.getWindow().addFlags(FLAG_KEEP_SCREEN_ON);

        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    public static void exit(Activity activity) {
        activity.getWindow().clearFlags(FLAG_KEEP_SCREEN_ON);

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
        controller.show(WindowInsetsCompat.Type.systemBars());
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), true);
    }

    public static boolean isEntered(Activity activity) {
        WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(
                activity.getWindow().getDecorView());
        if (insets == null) return false;
        return !insets.isVisible(WindowInsetsCompat.Type.statusBars())
                && !insets.isVisible(WindowInsetsCompat.Type.navigationBars());
    }

}

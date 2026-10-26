/* SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import lineageos.providers.LineageSettings;

/**
 * Resource opt-in and the same per-user switches used by SystemUI's wheel UI: the wheel UI, and
 * the home screen as its revised part.
 */
public final class WheelHome {
    private WheelHome() {}

    public static boolean enabled(Context context) {
        return context.getResources().getBoolean(R.bool.config_r1ListHome)
                && LineageSettings.Secure.getInt(
                                context.getContentResolver(), LineageSettings.Secure.WHEEL_UI, 1)
                        != 0
                && LineageSettings.Secure.getInt(
                                context.getContentResolver(), LineageSettings.Secure.WHEEL_HOME, 1)
                        != 0;
    }

    /**
     * Whether the system's wheel UI moves the focus (config_wheelFocus, its overlay), in the list
     * home or the stock grid alike.
     */
    public static boolean wheelFocus(Context context) {
        final int id = context.getResources().getIdentifier("config_wheelFocus", "bool", "android");
        return id != 0 && context.getResources().getBoolean(id);
    }

    /** Settings.Secure, set by the tour itself (cat.kenny.r1.wheel.settings) once it showed. */
    private static final String TOUR_DONE = "r1_wheel_tour_done";
    private static boolean sTourAsked;

    /**
     * The wheel tour, the first time home shows with the wheel UI on after setup: what turning,
     * pressing, holding and double-pressing do.
     */
    public static void startTourOnce(Context context) {
        if (sTourAsked) {
            return;
        }
        sTourAsked = true;
        if (Settings.Secure.getInt(context.getContentResolver(), TOUR_DONE, 0) != 0
                || Settings.Secure.getInt(context.getContentResolver(),
                        Settings.Secure.USER_SETUP_COMPLETE, 0) == 0
                || LineageSettings.Secure.getInt(context.getContentResolver(),
                        LineageSettings.Secure.WHEEL_UI, 1) == 0) {
            return;
        }
        try {
            context.startActivity(new Intent("cat.kenny.r1.wheel.action.TOUR")
                    .setPackage("cat.kenny.r1.wheel.settings")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            // Not on this build
        }
    }

    public static int columns(Context context) {
        return Math.max(
                1,
                Math.min(
                        2,
                        LineageSettings.Secure.getInt(
                                context.getContentResolver(),
                                LineageSettings.Secure.WHEEL_BUTTON_COLUMNS,
                                1)));
    }
}

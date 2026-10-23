/* SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3;

import android.content.Context;

import lineageos.providers.LineageSettings;

/** Resource opt-in and the same per-user switch used by SystemUI's wheel UI. */
public final class WheelHome {
    private WheelHome() {}

    public static boolean enabled(Context context) {
        return context.getResources().getBoolean(R.bool.config_r1ListHome)
                && LineageSettings.Secure.getInt(
                                context.getContentResolver(), LineageSettings.Secure.WHEEL_UI, 1)
                        != 0;
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

/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.quickstep;

import static com.android.systemui.shared.system.NavigationBarCompat.FLAG_DISABLE_QUICK_SCRUB;
import static com.android.systemui.shared.system.NavigationBarCompat.FLAG_DISABLE_SWIPE_UP;
import static com.android.systemui.shared.system.NavigationBarCompat.FLAG_HIDE_BACK_BUTTON;
import static com.android.systemui.shared.system.NavigationBarCompat.FLAG_SHOW_OVERVIEW_BUTTON;
import static com.android.systemui.shared.system.SettingsCompat.SWIPE_UP_SETTING_NAME;

import static com.android.launcher3.Utilities.getSystemProperty;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.support.annotation.WorkerThread;
import android.util.Log;

import com.android.launcher3.MainThreadExecutor;
import com.android.launcher3.util.UiThreadHelper;
import com.android.systemui.shared.recents.ISystemUiProxy;

import java.util.concurrent.ExecutionException;

/**
 * Sets overview interaction flags, such as:
 *
 *   - FLAG_DISABLE_QUICK_SCRUB
 *   - FLAG_DISABLE_SWIPE_UP
 *   - FLAG_HIDE_BACK_BUTTON
 *   - FLAG_SHOW_OVERVIEW_BUTTON
 *
 * @see com.android.systemui.shared.system.NavigationBarCompat.InteractionType and associated flags.
 */
public class OverviewInteractionState {

    private static final String TAG = "OverviewFlags";

    // We do not need any synchronization for this variable as its only written on UI thread.
    private static OverviewInteractionState INSTANCE;

    public static OverviewInteractionState getInstance(final Context context) {
        if (INSTANCE == null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                INSTANCE = new OverviewInteractionState(context.getApplicationContext());
            } else {
                try {
                    return new MainThreadExecutor().submit(
                            () -> OverviewInteractionState.getInstance(context)).get();
                } catch (InterruptedException|ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return INSTANCE;
    }

    private static final int MSG_SET_PROXY = 200;
    private static final int MSG_SET_BACK_BUTTON_VISIBLE = 201;
    private static final int MSG_SET_SWIPE_UP_ENABLED = 202;

    private final SwipeUpGestureEnabledSettingObserver mSwipeUpSettingObserver;

    private final Handler mUiHandler;
    private final Handler mBgHandler;

    // These are updated on the background thread
    private ISystemUiProxy mISystemUiProxy;
    private boolean mBackButtonVisible = true;
    private boolean mSwipeUpEnabled = true;

    private Runnable mOnSwipeUpSettingChangedListener;

    private OverviewInteractionState(Context context) {
        mUiHandler = new Handler(this::handleUiMessage);
        mBgHandler = new Handler(UiThreadHelper.getBackgroundLooper(), this::handleBgMessage);

        if (shouldIgnoreSwipeUpEnabledSettings()) {
            mSwipeUpSettingObserver = null;
            mSwipeUpEnabled = true;
        } else {
            mSwipeUpSettingObserver = new SwipeUpGestureEnabledSettingObserver(mUiHandler,
                    context.getContentResolver());
            mSwipeUpSettingObserver.register();
        }
    }

    public boolean isSwipeUpGestureEnabled() {
        return mSwipeUpEnabled;
    }

    public void setBackButtonVisible(boolean visible) {
        mUiHandler.removeMessages(MSG_SET_BACK_BUTTON_VISIBLE);
        mUiHandler.obtainMessage(MSG_SET_BACK_BUTTON_VISIBLE, visible ? 1 : 0, 0)
                .sendToTarget();
    }

    public void setSystemUiProxy(ISystemUiProxy proxy) {
        mBgHandler.obtainMessage(MSG_SET_PROXY, proxy).sendToTarget();
    }

    private boolean handleUiMessage(Message msg) {
        mBgHandler.obtainMessage(msg.what, msg.arg1, msg.arg2).sendToTarget();
        return true;
    }

    private boolean handleBgMessage(Message msg) {
        switch (msg.what) {
            case MSG_SET_PROXY:
                mISystemUiProxy = (ISystemUiProxy) msg.obj;
                break;
            case MSG_SET_BACK_BUTTON_VISIBLE:
                mBackButtonVisible = msg.arg1 != 0;
                break;
            case MSG_SET_SWIPE_UP_ENABLED:
                mSwipeUpEnabled = msg.arg1 != 0;
                if (mOnSwipeUpSettingChangedListener != null) {
                    mOnSwipeUpSettingChangedListener.run();
                }
                break;
        }
        applyFlags();
        return true;
    }

    public void setOnSwipeUpSettingChangedListener(Runnable listener) {
        mOnSwipeUpSettingChangedListener = listener;
    }

    @WorkerThread
    private void applyFlags() {
        if (mISystemUiProxy == null) {
            return;
        }

        int flags;
        if (mSwipeUpEnabled) {
            flags = mBackButtonVisible ? 0 : FLAG_HIDE_BACK_BUTTON;
        } else {
            flags = FLAG_DISABLE_SWIPE_UP | FLAG_DISABLE_QUICK_SCRUB | FLAG_SHOW_OVERVIEW_BUTTON;
        }
        try {
            mISystemUiProxy.setInteractionState(flags);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to update overview interaction flags", e);
        }
    }

    private class SwipeUpGestureEnabledSettingObserver extends ContentObserver {
        private Handler mHandler;
        private ContentResolver mResolver;

        SwipeUpGestureEnabledSettingObserver(Handler handler, ContentResolver resolver) {
            super(handler);
            mHandler = handler;
            mResolver = resolver;
        }

        public void register() {
            mResolver.registerContentObserver(Settings.Secure.getUriFor(SWIPE_UP_SETTING_NAME),
                    false, this);
            mSwipeUpEnabled = getValue();
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            mHandler.removeMessages(MSG_SET_SWIPE_UP_ENABLED);
            mHandler.obtainMessage(MSG_SET_SWIPE_UP_ENABLED, getValue() ? 1 : 0, 0).sendToTarget();
        }

        private boolean getValue() {
            return Settings.Secure.getInt(mResolver, SWIPE_UP_SETTING_NAME, 0) == 1;
        }
    }

    private boolean shouldIgnoreSwipeUpEnabledSettings() {
        String sdkInt = getSystemProperty("ro.product.first_api_level", "0");
        try {
            return Integer.parseInt(sdkInt) >= Build.VERSION_CODES.P;
        } catch (Exception e) {
            return false;
        }
    }
}

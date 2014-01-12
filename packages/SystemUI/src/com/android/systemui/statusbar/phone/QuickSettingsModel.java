/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.BluetoothStateChangeCallback;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteInfo;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.text.TextUtils;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.util.paranoid.DeviceUtils;
import com.android.internal.util.paranoid.LightbulbConstants;
import com.android.systemui.R;
import com.android.systemui.settings.BrightnessController.BrightnessStateChangeCallback;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.LocationController.LocationSettingsChangeCallback;
import com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockController.RotationLockControllerCallback;

import java.util.List;
import java.util.Set;

class QuickSettingsModel implements BluetoothStateChangeCallback,
        NetworkSignalChangedCallback,
        BatteryStateChangeCallback,
        BrightnessStateChangeCallback,
        RotationLockControllerCallback,
        LocationSettingsChangeCallback {
    // Sett InputMethoManagerService
    private static final String TAG_TRY_SUPPRESSING_IME_SWITCHER = "TrySuppressingImeSwitcher";

    /** Represents the state of a given attribute. */
    static class State {
        int iconId;
        String label;
        boolean enabled = false;
    }
    static class BatteryState extends State {
        int batteryLevel;
        boolean pluggedIn;
    }
    static class ActivityState extends State {
        boolean activityIn;
        boolean activityOut;
    }
    static class RSSIState extends ActivityState {
        int signalIconId;
        String signalContentDescription;
        int dataTypeIconId;
        String dataContentDescription;
    }
    static class WifiState extends ActivityState {
        String signalContentDescription;
        boolean connected;
    }
    static class UserState extends State {
        Drawable avatar;
    }
    static class BrightnessState extends State {
        boolean autoBrightness;
    }
    public static class BluetoothState extends State {
        boolean connected = false;
        String stateContentDescription;
    }
    public static class RotationLockState extends State {
        boolean visible = false;
    }

    /** The callback to update a given tile. */
    interface RefreshCallback {
        public void refreshView(QuickSettingsTileView view, State state);
    }

    public static class BasicRefreshCallback implements RefreshCallback {
        private final QuickSettingsBasicTile mView;
        private boolean mShowWhenEnabled;

        public BasicRefreshCallback(QuickSettingsBasicTile v) {
            mView = v;
        }
        public void refreshView(QuickSettingsTileView ignored, State state) {
            if (mShowWhenEnabled) {
                mView.setVisibility(state.enabled ? View.VISIBLE : View.GONE);
            }
            if (state.iconId != 0) {
                mView.setImageDrawable(null); // needed to flush any cached IDs
                mView.setImageResource(state.iconId);
            }
            if (state.label != null) {
                mView.setText(state.label);
            }
        }
        public BasicRefreshCallback setShowWhenEnabled(boolean swe) {
            mShowWhenEnabled = swe;
            return this;
        }
    }

    /** Broadcast receive to determine if there is an alarm set. */
    private BroadcastReceiver mAlarmIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_ALARM_CHANGED)) {
                onAlarmChanged(intent);
                onNextAlarmChanged();
            }
        }
    };

    /** Broadcast receive to determine if device boot is complete*/
    private BroadcastReceiver mBootReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
                refreshMobileNetworkTile();
            }
            context.unregisterReceiver(mBootReceiver);
        }
    };

    /** Broadcast receive to determine lightbulb state. */
    private BroadcastReceiver mLightbulbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mLightbulbActive = intent.getIntExtra(LightbulbConstants.EXTRA_CURRENT_STATE, 0) != 0;
            onLightbulbChanged();
        }
    };

    /** ContentObserver to watch Network State */
    private class NetworkObserver extends ContentObserver {
        public NetworkObserver(Handler handler) {
            super(handler);
        }

        @Override public void onChange(boolean selfChange) {
            onMobileNetworkChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.PREFERRED_NETWORK_MODE), false, this);
        }
    }

    /** Generic ContentObserver for quicksettings*/
    private class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override public void onChange(boolean selfChange) {
            onNextAlarmChanged();
            onBugreportChanged();
            onBrightnessLevelChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NEXT_ALARM_FORMATTED), false, this,
                    UserHandle.USER_ALL);
            cr.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BUGREPORT_IN_POWER_MENU), false, this);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false, this, mUserTracker.getCurrentUserId());
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false, this, mUserTracker.getCurrentUserId());
        }
    }

    /** Callback for changes to remote display routes. */
    private class RemoteDisplayRouteCallback extends MediaRouter.SimpleCallback {
        @Override
        public void onRouteAdded(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteChanged(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteRemoved(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo route) {
            updateRemoteDisplays();
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final CurrentUserTracker mUserTracker;
    private final SettingsObserver mSettingsObserver;
    private final NetworkObserver mMobileNetworkObserver;


    private final MediaRouter mMediaRouter;
    private final RemoteDisplayRouteCallback mRemoteDisplayRouteCallback;

    private final boolean mHasMobileData;
    protected boolean mLightbulbActive;

    private QuickSettingsTileView mUserTile;
    private RefreshCallback mUserCallback;
    private UserState mUserState = new UserState();

    private QuickSettingsTileView mTimeTile;
    private RefreshCallback mTimeCallback;
    private State mTimeState = new State();

    private QuickSettingsTileView mAlarmTile;
    private RefreshCallback mAlarmCallback;
    private State mAlarmState = new State();

    private QuickSettingsTileView mAirplaneModeTile;
    private RefreshCallback mAirplaneModeCallback;
    private State mAirplaneModeState = new State();

    private QuickSettingsTileView mWifiTile;
    private QuickSettingsTileView mWifiBackTile;
    private RefreshCallback mWifiCallback;
    private RefreshCallback mWifiBackCallback;
    private WifiState mWifiState = new WifiState();
    private WifiState mWifiBackState = new WifiState();

    private QuickSettingsTileView mRemoteDisplayTile;
    private RefreshCallback mRemoteDisplayCallback;
    private State mRemoteDisplayState = new State();

    private QuickSettingsTileView mRSSITile;
    private RefreshCallback mRSSICallback;
    private RSSIState mRSSIState = new RSSIState();

    private QuickSettingsTileView mBluetoothTile;
    private QuickSettingsTileView mBluetoothBackTile;
    private RefreshCallback mBluetoothCallback;
    private RefreshCallback mBluetoothBackCallback;
    private BluetoothState mBluetoothState = new BluetoothState();
    private BluetoothState mBluetoothBackState = new BluetoothState();

    private QuickSettingsTileView mBatteryTile;
    private RefreshCallback mBatteryCallback;
    private BatteryState mBatteryState = new BatteryState();

    private QuickSettingsTileView mLocationTile;
    private RefreshCallback mLocationCallback;
    private State mLocationState = new State();

    private QuickSettingsTileView mImeTile;
    private RefreshCallback mImeCallback = null;
    private State mImeState = new State();

    private QuickSettingsTileView mRotationLockTile;
    private RefreshCallback mRotationLockCallback;
    private RotationLockState mRotationLockState = new RotationLockState();

    private QuickSettingsTileView mBrightnessTile;
    private RefreshCallback mBrightnessCallback;
    private BrightnessState mBrightnessState = new BrightnessState();

    private QuickSettingsTileView mBugreportTile;
    private RefreshCallback mBugreportCallback;
    private State mBugreportState = new State();

    private QuickSettingsTileView mSettingsTile;
    private RefreshCallback mSettingsCallback;
    private State mSettingsState = new State();

    private QuickSettingsTileView mSslCaCertWarningTile;
    private RefreshCallback mSslCaCertWarningCallback;
    private State mSslCaCertWarningState = new State();

    private QuickSettingsTileView mMobileNetworkTile;
    private RefreshCallback mMobileNetworkCallback;
    private State mMobileNetworkState = new State();

    private QuickSettingsTileView mLightbulbTile;
    private RefreshCallback mLightbulbCallback;
    private State mLightbulbState = new State();


    private RotationLockController mRotationLockController;

    public QuickSettingsModel(Context context) {
        mContext = context;
        mHandler = new Handler();
        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onUserSwitched(int newUserId) {
                refreshRotationLockTile();
                onBrightnessLevelChanged();
                onNextAlarmChanged();
                onBugreportChanged();
                rebindMediaRouterAsCurrentUser();
            }
        };

        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.startObserving();

        mMobileNetworkObserver = new NetworkObserver(mHandler);
        mMobileNetworkObserver.startObserving();

        mMediaRouter = (MediaRouter)context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        rebindMediaRouterAsCurrentUser();

        mRemoteDisplayRouteCallback = new RemoteDisplayRouteCallback();

        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mHasMobileData = cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

        IntentFilter alarmIntentFilter = new IntentFilter();
        alarmIntentFilter.addAction(Intent.ACTION_ALARM_CHANGED);
        context.registerReceiver(mAlarmIntentReceiver, alarmIntentFilter);


        IntentFilter bootFilter = new IntentFilter();
        bootFilter.addAction(Intent.ACTION_BOOT_COMPLETED);
        context.registerReceiver(mBootReceiver, bootFilter);

        IntentFilter lightbulbFilter = new IntentFilter();
        lightbulbFilter.addAction(LightbulbConstants.ACTION_STATE_CHANGED);
        context.registerReceiver(mLightbulbReceiver, lightbulbFilter);
    }

    void updateResources() {
        refreshSettingsTile();
        refreshBatteryTile();
        refreshBluetoothTile();
        refreshBrightnessTile();
        refreshRotationLockTile();
        refreshRssiTile();
        refreshLocationTile();
        refreshMobileNetworkTile();
    }

    // Settings
    void addSettingsTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSettingsTile = view;
        mSettingsCallback = cb;
        refreshSettingsTile();
    }
    void refreshSettingsTile() {
        Resources r = mContext.getResources();
        mSettingsState.label = r.getString(R.string.quick_settings_settings_label);
        mSettingsCallback.refreshView(mSettingsTile, mSettingsState);
    }

    // User
    void addUserTile(QuickSettingsTileView view, RefreshCallback cb) {
        mUserTile = view;
        mUserCallback = cb;
        mUserCallback.refreshView(mUserTile, mUserState);
    }
    void setUserTileInfo(String name, Drawable avatar) {
        mUserState.label = name;
        mUserState.avatar = avatar;
        mUserCallback.refreshView(mUserTile, mUserState);
    }

    // Time
    void addTimeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mTimeTile = view;
        mTimeCallback = cb;
        mTimeCallback.refreshView(view, mTimeState);
    }

    // Alarm
    void addAlarmTile(QuickSettingsTileView view, RefreshCallback cb) {
        mAlarmTile = view;
        mAlarmCallback = cb;
        mAlarmCallback.refreshView(view, mAlarmState);
    }
    void onAlarmChanged(Intent intent) {
        mAlarmState.enabled = intent.getBooleanExtra("alarmSet", false);
        mAlarmCallback.refreshView(mAlarmTile, mAlarmState);
    }
    void onNextAlarmChanged() {
        final String alarmText = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.NEXT_ALARM_FORMATTED,
                UserHandle.USER_CURRENT);
        mAlarmState.label = alarmText;

        // When switching users, this is the only clue we're going to get about whether the
        // alarm is actually set, since we won't get the ACTION_ALARM_CHANGED broadcast
        mAlarmState.enabled = ! TextUtils.isEmpty(alarmText);

        mAlarmCallback.refreshView(mAlarmTile, mAlarmState);
    }

    // Airplane Mode
    void addAirplaneModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mAirplaneModeTile = view;
        mAirplaneModeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAirplaneModeState.enabled) {
                    setAirplaneModeState(false);
                } else {
                    setAirplaneModeState(true);
                }
            }
        });
        mAirplaneModeCallback = cb;
        int airplaneMode = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0);
        onAirplaneModeChanged(airplaneMode != 0);
    }
    private void setAirplaneModeState(boolean enabled) {
        // TODO: Sets the view to be "awaiting" if not already awaiting

        // Change the system setting
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON,
                                enabled ? 1 : 0);

        // Post the intent
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", enabled);
        mContext.sendBroadcast(intent);
    }
    // NetworkSignalChanged callback
    @Override
    public void onAirplaneModeChanged(boolean enabled) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();
        mAirplaneModeState.enabled = enabled;
        mAirplaneModeState.iconId = (enabled ?
                R.drawable.ic_qs_airplane_on :
                R.drawable.ic_qs_airplane_off);
        mAirplaneModeState.label = r.getString(R.string.quick_settings_airplane_mode_label);
        mAirplaneModeCallback.refreshView(mAirplaneModeTile, mAirplaneModeState);
    }

    // Wifi
    void addWifiTile(QuickSettingsTileView view, RefreshCallback cb) {
        mWifiTile = view;
        mWifiCallback = cb;
        mWifiCallback.refreshView(mWifiTile, mWifiState);
    }
    void addWifiBackTile(QuickSettingsTileView view, RefreshCallback cb) {
        mWifiBackTile = view;
        mWifiBackCallback = cb;
        mWifiCallback.refreshView(mWifiBackTile, mWifiBackState);
    }
    // Remove the double quotes that the SSID may contain
    public static String removeDoubleQuotes(String string) {
        if (string == null) return null;
        final int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"') && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }
    // Remove the period from the network name
    public static String removeTrailingPeriod(String string) {
        if (string == null) return null;
        final int length = string.length();
        if (string.endsWith(".")) {
            return string.substring(0, length - 1);
        }
        return string;
    }
    // NetworkSignalChanged callback
    @Override
    public void onWifiSignalChanged(boolean enabled, int wifiSignalIconId,
            boolean activityIn, boolean activityOut,
            String wifiSignalContentDescription, String enabledDesc) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();

        boolean wifiConnected = enabled && (wifiSignalIconId > 0) && (enabledDesc != null);
        boolean wifiNotConnected = (wifiSignalIconId > 0) && (enabledDesc == null);
        mWifiState.enabled = enabled;
        mWifiState.connected = wifiConnected;
        mWifiState.activityIn = enabled && activityIn;
        mWifiState.activityOut = enabled && activityOut;
        if (wifiConnected) {
            mWifiState.iconId = wifiSignalIconId;
            mWifiState.label = removeDoubleQuotes(enabledDesc);
            mWifiState.signalContentDescription = wifiSignalContentDescription;

            mWifiBackState.iconId = wifiSignalIconId;
            mWifiBackState.label = getWifiIpAddr();
            mWifiBackState.signalContentDescription = wifiSignalContentDescription;
        } else if (wifiNotConnected) {
            mWifiState.iconId = R.drawable.ic_qs_wifi_0;
            mWifiState.label = r.getString(R.string.quick_settings_wifi_label);
            mWifiState.signalContentDescription = r.getString(R.string.accessibility_no_wifi);

            mWifiBackState.iconId = mWifiState.iconId;
            mWifiBackState.label = mWifiState.label;
            mWifiBackState.signalContentDescription = mWifiState.signalContentDescription;
        } else {
            mWifiState.iconId = R.drawable.ic_qs_wifi_no_network;
            mWifiState.label = r.getString(R.string.quick_settings_wifi_off_label);
            mWifiState.signalContentDescription = r.getString(R.string.accessibility_wifi_off);

            mWifiBackState.iconId = mWifiState.iconId;
            mWifiBackState.label = mWifiState.label;
            mWifiBackState.signalContentDescription = mWifiState.signalContentDescription;
        }
        mWifiCallback.refreshView(mWifiTile, mWifiState);
        mWifiBackCallback.refreshView(mWifiBackTile, mWifiBackState);
    }

    String getWifiIpAddr() {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int ip = wifiInfo.getIpAddress();

        String ipString = String.format(
            "%d.%d.%d.%d",
            (ip & 0xff),
            (ip >> 8 & 0xff),
            (ip >> 16 & 0xff),
            (ip >> 24 & 0xff));

        return ipString;
    }

    boolean deviceHasMobileData() {
        return mHasMobileData;
    }

    boolean deviceSupportsLTE() {
        return DeviceUtils.deviceSupportsLte(mContext);
    }

    boolean deviceHasCameraFlash() {
        return DeviceUtils.deviceSupportsCameraFlash();
    }

    // RSSI
    void addRSSITile(QuickSettingsTileView view, RefreshCallback cb) {
        mRSSITile = view;
        mRSSICallback = cb;
        mRSSICallback.refreshView(mRSSITile, mRSSIState);
    }
    // NetworkSignalChanged callback
    @Override
    public void onMobileDataSignalChanged(
            boolean enabled, int mobileSignalIconId, String signalContentDescription,
            int dataTypeIconId, boolean activityIn, boolean activityOut,
            String dataContentDescription,String enabledDesc) {
        if (deviceHasMobileData()) {
            // TODO: If view is in awaiting state, disable
            Resources r = mContext.getResources();
            mRSSIState.signalIconId = enabled && (mobileSignalIconId > 0)
                    ? mobileSignalIconId
                    : R.drawable.ic_qs_signal_no_signal;
            mRSSIState.signalContentDescription = enabled && (mobileSignalIconId > 0)
                    ? signalContentDescription
                    : r.getString(R.string.accessibility_no_signal);
            mRSSIState.dataTypeIconId = enabled && (dataTypeIconId > 0) && !mWifiState.enabled
                    ? dataTypeIconId
                    : 0;
            mRSSIState.activityIn = enabled && activityIn;
            mRSSIState.activityOut = enabled && activityOut;
            mRSSIState.dataContentDescription = enabled && (dataTypeIconId > 0) && !mWifiState.enabled
                    ? dataContentDescription
                    : r.getString(R.string.accessibility_no_data);
            mRSSIState.label = enabled
                    ? removeTrailingPeriod(enabledDesc)
                    : r.getString(R.string.quick_settings_rssi_emergency_only);
            mRSSICallback.refreshView(mRSSITile, mRSSIState);
        }
    }

    void refreshRssiTile() {
        if (mRSSITile != null) {
            // We reinflate the original view due to potential styling changes that may have
            // taken place due to a configuration change.
            mRSSITile.reinflateContent(LayoutInflater.from(mContext));
        }
    }

    // Mobile Network
    void addMobileNetworkTile(QuickSettingsTileView view, RefreshCallback cb) {
        mMobileNetworkTile = view;
        mMobileNetworkCallback = cb;
        mMobileNetworkCallback.refreshView(view, mMobileNetworkState);
    }

    void onMobileNetworkChanged() {
        if (deviceHasMobileData()) {
            mMobileNetworkState.label = getNetworkType(mContext.getResources());
            mMobileNetworkState.iconId = getNetworkTypeIcon();
            mMobileNetworkState.enabled = true;
            mMobileNetworkCallback.refreshView(mMobileNetworkTile, mMobileNetworkState);
        }
    }

    void refreshMobileNetworkTile() {
        onMobileNetworkChanged();
    }

    protected void toggleMobileNetworkState() {
        TelephonyManager tm = (TelephonyManager)
            mContext.getSystemService(Context.TELEPHONY_SERVICE);
        boolean usesQcLte = SystemProperties.getBoolean(
                        "ro.config.qc_lte_network_modes", false);
        int network = getCurrentPreferredNetworkMode(mContext);
        switch(network) {
            case Phone.NT_MODE_GLOBAL:
            case Phone.NT_MODE_LTE_WCDMA:
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
                // 2G Only
                tm.toggleMobileNetwork(Phone.NT_MODE_GSM_ONLY);
                break;
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
                // 2G Only
                tm.toggleMobileNetwork(Phone.NT_MODE_CDMA_NO_EVDO);
                break;
            case Phone.NT_MODE_GSM_ONLY:
                // 3G Only
                tm.toggleMobileNetwork(Phone.NT_MODE_WCDMA_ONLY);
                break;
            case Phone.NT_MODE_CDMA_NO_EVDO:
                // 3G Only
                tm.toggleMobileNetwork(Phone.NT_MODE_EVDO_NO_CDMA);
                break;
            case Phone.NT_MODE_WCDMA_ONLY:
                // 2G/3G
                tm.toggleMobileNetwork(Phone.NT_MODE_WCDMA_PREF);
                break;
            case Phone.NT_MODE_EVDO_NO_CDMA:
                // 2G/3G
                tm.toggleMobileNetwork(Phone.NT_MODE_CDMA);
                break;
            case Phone.NT_MODE_WCDMA_PREF:
            case Phone.NT_MODE_GSM_UMTS:
                // LTE
                if (deviceSupportsLTE()) {
                    if (usesQcLte) {
                        tm.toggleMobileNetwork(Phone.NT_MODE_LTE_CDMA_AND_EVDO);
                    } else {
                        tm.toggleMobileNetwork(Phone.NT_MODE_LTE_GSM_WCDMA);
                    }
                } else {
                    tm.toggleMobileNetwork(Phone.NT_MODE_GSM_ONLY);
                }
                break;
            case Phone.NT_MODE_CDMA:
                tm.toggleMobileNetwork(Phone.NT_MODE_LTE_CDMA_AND_EVDO);
                break;
        }
    }

    private String getNetworkType(Resources r) {
        int network = getCurrentPreferredNetworkMode(mContext);
        switch (network) {
            case Phone.NT_MODE_GLOBAL:
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
            case Phone.NT_MODE_LTE_WCDMA:
            case Phone.NT_MODE_GSM_UMTS:
            case Phone.NT_MODE_WCDMA_ONLY:
            case Phone.NT_MODE_GSM_ONLY:
            case Phone.NT_MODE_WCDMA_PREF:
            case Phone.NT_MODE_EVDO_NO_CDMA:
            case Phone.NT_MODE_CDMA_NO_EVDO:
            case Phone.NT_MODE_CDMA:
                return r.getString(R.string.quick_settings_network_type);
        }
        return r.getString(R.string.quick_settings_network_unknown);
    }

    private int getNetworkTypeIcon() {
        int network = getCurrentPreferredNetworkMode(mContext);
        switch (network) {
            case Phone.NT_MODE_GLOBAL:
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
            case Phone.NT_MODE_LTE_WCDMA:
                return R.drawable.ic_qs_lte_on;
            case Phone.NT_MODE_WCDMA_ONLY:
            case Phone.NT_MODE_EVDO_NO_CDMA:
                return R.drawable.ic_qs_3g_on;
            case Phone.NT_MODE_GSM_ONLY:
            case Phone.NT_MODE_CDMA_NO_EVDO:
                return R.drawable.ic_qs_2g_on;
            case Phone.NT_MODE_WCDMA_PREF:
            case Phone.NT_MODE_GSM_UMTS:
            case Phone.NT_MODE_CDMA:
                return R.drawable.ic_qs_2g3g_on;
        }
        return R.drawable.ic_qs_unexpected_network;
    }

    public static int getCurrentPreferredNetworkMode(Context context) {
        int network = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.PREFERRED_NETWORK_MODE, -1);
        return network;
    }

    // Bluetooth
    void addBluetoothTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBluetoothTile = view;
        mBluetoothCallback = cb;

        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothState.enabled = adapter.isEnabled();
        mBluetoothState.connected =
                (adapter.getConnectionState() == BluetoothAdapter.STATE_CONNECTED);
        onBluetoothStateChange(mBluetoothState);
    }
    void addBluetoothBackTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBluetoothBackTile = view;
        mBluetoothBackCallback = cb;

        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothBackState.enabled = adapter.isEnabled();
        mBluetoothBackState.connected =
                (adapter.getConnectionState() == BluetoothAdapter.STATE_CONNECTED);
        onBluetoothStateChange(mBluetoothBackState);
    }
    boolean deviceSupportsBluetooth() {
        return (BluetoothAdapter.getDefaultAdapter() != null);
    }
    // BluetoothController callback
    @Override
    public void onBluetoothStateChange(boolean on) {
        mBluetoothState.enabled = on;
        onBluetoothStateChange(mBluetoothState);
    }
    public void onBluetoothStateChange(BluetoothState bluetoothStateIn) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();
        mBluetoothState.enabled = bluetoothStateIn.enabled;
        mBluetoothState.connected = bluetoothStateIn.connected;
        if (mBluetoothState.enabled) {
            if (mBluetoothState.connected) {
                mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_on;
                mBluetoothState.stateContentDescription = r.getString(R.string.accessibility_desc_connected);
            } else {
                mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_not_connected;
                mBluetoothState.stateContentDescription = r.getString(R.string.accessibility_desc_on);
            }
            mBluetoothState.label = r.getString(R.string.quick_settings_bluetooth_label);
        } else {
            mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_off;
            mBluetoothState.label = r.getString(R.string.quick_settings_bluetooth_off_label);
            mBluetoothState.stateContentDescription = r.getString(R.string.accessibility_desc_off);
        }

        // Back tile: Show paired devices
        if (mBluetoothBackTile != null) {
            mBluetoothBackState.iconId = mBluetoothState.iconId;

            final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            Set<BluetoothDevice> btDevices = adapter.getBondedDevices();
            if (btDevices.size() == 1) {
                // Show a generic label about the number of paired bluetooth devices
                mBluetoothBackState.label = 
                    r.getString(R.string.quick_settings_bluetooth_number_paired, btDevices.size());
            } else {
                mBluetoothBackState.label = r.getString(R.string.quick_settings_bluetooth_disabled);
            }
        }

        mBluetoothCallback.refreshView(mBluetoothTile, mBluetoothState);

        if (mBluetoothBackTile != null) {
            mBluetoothBackCallback.refreshView(mBluetoothBackTile, mBluetoothBackState);
        }
    }
    void refreshBluetoothTile() {
        if (mBluetoothTile != null) {
            onBluetoothStateChange(mBluetoothState.enabled);
        }
    }

    // Battery
    void addBatteryTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBatteryTile = view;
        mBatteryCallback = cb;
        mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
    }
    // BatteryController callback
    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn) {
        mBatteryState.batteryLevel = level;
        mBatteryState.pluggedIn = pluggedIn;
        mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
    }
    void refreshBatteryTile() {
        if (mBatteryCallback == null) {
            return;
        }
        mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
    }

    // Location
    void addLocationTile(QuickSettingsTileView view, RefreshCallback cb) {
        mLocationTile = view;
        mLocationCallback = cb;
        mLocationCallback.refreshView(mLocationTile, mLocationState);
    }

    void refreshLocationTile() {
        if (mLocationTile != null) {
            onLocationSettingsChanged(mLocationState.enabled);
        }
    }

    @Override
    public void onLocationSettingsChanged(boolean locationEnabled) {
        int textResId = locationEnabled ? R.string.quick_settings_location_label
                : R.string.quick_settings_location_off_label;
        String label = mContext.getText(textResId).toString();
        int locationIconId = locationEnabled
                ? R.drawable.ic_qs_location_on : R.drawable.ic_qs_location_off;
        mLocationState.enabled = locationEnabled;
        mLocationState.label = label;
        mLocationState.iconId = locationIconId;
        mLocationCallback.refreshView(mLocationTile, mLocationState);
    }

    // Bug report
    void addBugreportTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBugreportTile = view;
        mBugreportCallback = cb;
        onBugreportChanged();
    }
    // SettingsObserver callback
    public void onBugreportChanged() {
        final ContentResolver cr = mContext.getContentResolver();
        boolean enabled = false;
        try {
            enabled = (Settings.Global.getInt(cr, Settings.Global.BUGREPORT_IN_POWER_MENU) != 0);
        } catch (SettingNotFoundException e) {
        }

        mBugreportState.enabled = enabled && mUserTracker.isCurrentUserOwner();
        mBugreportCallback.refreshView(mBugreportTile, mBugreportState);
    }

    // Remote Display
    void addRemoteDisplayTile(QuickSettingsTileView view, RefreshCallback cb) {
        mRemoteDisplayTile = view;
        mRemoteDisplayCallback = cb;
        final int[] count = new int[1];
        mRemoteDisplayTile.setOnPrepareListener(new QuickSettingsTileView.OnPrepareListener() {
            @Override
            public void onPrepare() {
                mMediaRouter.addCallback(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                        mRemoteDisplayRouteCallback,
                        MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
                updateRemoteDisplays();
            }
            @Override
            public void onUnprepare() {
                mMediaRouter.removeCallback(mRemoteDisplayRouteCallback);
            }
        });

        updateRemoteDisplays();
    }

    private void rebindMediaRouterAsCurrentUser() {
        mMediaRouter.rebindAsUser(mUserTracker.getCurrentUserId());
    }

    private void updateRemoteDisplays() {
        MediaRouter.RouteInfo connectedRoute = mMediaRouter.getSelectedRoute(
                MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY);
        boolean enabled = connectedRoute != null 
                && connectedRoute.matchesTypes(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY);
        boolean connecting;
        if (enabled) {
            connecting = connectedRoute.isConnecting();
        } else {
            connectedRoute = null;
            connecting = false;
          enabled = mMediaRouter.isRouteAvailable(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                    MediaRouter.AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE);
        }

        mRemoteDisplayState.enabled = enabled;
        if (connectedRoute != null) {
            mRemoteDisplayState.label = connectedRoute.getName().toString();
            mRemoteDisplayState.iconId = connecting ?
                    com.android.internal.R.drawable.ic_media_route_connecting_holo_dark :
                    com.android.internal.R.drawable.ic_media_route_on_holo_dark;
        } else {
            mRemoteDisplayState.label = mContext.getString(
                    R.string.quick_settings_remote_display_no_connection_label);
            mRemoteDisplayState.iconId =
                    com.android.internal.R.drawable.ic_media_route_off_holo_dark;
        }
        mRemoteDisplayCallback.refreshView(mRemoteDisplayTile, mRemoteDisplayState);
    }

    // IME
    void addImeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mImeTile = view;
        mImeCallback = cb;
        mImeCallback.refreshView(mImeTile, mImeState);
    }
    /* This implementation is taken from
       InputMethodManagerService.needsToShowImeSwitchOngoingNotification(). */
    private boolean needsToShowImeSwitchOngoingNotification(InputMethodManager imm) {
        List<InputMethodInfo> imis = imm.getEnabledInputMethodList();
        final int N = imis.size();
        if (N > 2) return true;
        if (N < 1) return false;
        int nonAuxCount = 0;
        int auxCount = 0;
        InputMethodSubtype nonAuxSubtype = null;
        InputMethodSubtype auxSubtype = null;
        for(int i = 0; i < N; ++i) {
            final InputMethodInfo imi = imis.get(i);
            final List<InputMethodSubtype> subtypes = imm.getEnabledInputMethodSubtypeList(imi,
                    true);
            final int subtypeCount = subtypes.size();
            if (subtypeCount == 0) {
                ++nonAuxCount;
            } else {
                for (int j = 0; j < subtypeCount; ++j) {
                    final InputMethodSubtype subtype = subtypes.get(j);
                    if (!subtype.isAuxiliary()) {
                        ++nonAuxCount;
                        nonAuxSubtype = subtype;
                    } else {
                        ++auxCount;
                        auxSubtype = subtype;
                    }
                }
            }
        }
        if (nonAuxCount > 1 || auxCount > 1) {
            return true;
        } else if (nonAuxCount == 1 && auxCount == 1) {
            if (nonAuxSubtype != null && auxSubtype != null
                    && (nonAuxSubtype.getLocale().equals(auxSubtype.getLocale())
                            || auxSubtype.overridesImplicitlyEnabledSubtype()
                            || nonAuxSubtype.overridesImplicitlyEnabledSubtype())
                    && nonAuxSubtype.containsExtraValueKey(TAG_TRY_SUPPRESSING_IME_SWITCHER)) {
                return false;
            }
            return true;
        }
        return false;
    }
    void onImeWindowStatusChanged(boolean visible) {
        InputMethodManager imm =
                (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> imis = imm.getInputMethodList();

        mImeState.enabled = (visible && needsToShowImeSwitchOngoingNotification(imm));
        mImeState.label = getCurrentInputMethodName(mContext, mContext.getContentResolver(),
                imm, imis, mContext.getPackageManager());
        if (mImeCallback != null) {
            mImeCallback.refreshView(mImeTile, mImeState);
        }
    }
    private static String getCurrentInputMethodName(Context context, ContentResolver resolver,
            InputMethodManager imm, List<InputMethodInfo> imis, PackageManager pm) {
        if (resolver == null || imis == null) return null;
        final String currentInputMethodId = Settings.Secure.getString(resolver,
                Settings.Secure.DEFAULT_INPUT_METHOD);
        if (TextUtils.isEmpty(currentInputMethodId)) return null;
        for (InputMethodInfo imi : imis) {
            if (currentInputMethodId.equals(imi.getId())) {
                final InputMethodSubtype subtype = imm.getCurrentInputMethodSubtype();
                final CharSequence summary = subtype != null
                        ? subtype.getDisplayName(context, imi.getPackageName(),
                                imi.getServiceInfo().applicationInfo)
                        : context.getString(R.string.quick_settings_ime_label);
                return summary.toString();
            }
        }
        return null;
    }

    // Rotation lock
    void addRotationLockTile(QuickSettingsTileView view,
            RotationLockController rotationLockController,
            RefreshCallback cb) {
        mRotationLockTile = view;
        mRotationLockCallback = cb;
        mRotationLockController = rotationLockController;
        onRotationLockChanged();
    }
    void onRotationLockChanged() {
        onRotationLockStateChanged(mRotationLockController.isRotationLocked(),
                mRotationLockController.isRotationLockAffordanceVisible());
    }
    @Override
    public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible) {
        mRotationLockState.visible = affordanceVisible;
        mRotationLockState.enabled = rotationLocked;
        mRotationLockState.iconId = rotationLocked
                ? R.drawable.ic_qs_rotation_locked
                : R.drawable.ic_qs_auto_rotate;
        mRotationLockState.label = rotationLocked
                ? mContext.getString(R.string.quick_settings_rotation_locked_label)
                : mContext.getString(R.string.quick_settings_rotation_unlocked_label);
        mRotationLockCallback.refreshView(mRotationLockTile, mRotationLockState);
    }
    void refreshRotationLockTile() {
        if (mRotationLockTile != null) {
            onRotationLockChanged();
        }
    }

    // Brightness
    void addBrightnessTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBrightnessTile = view;
        mBrightnessCallback = cb;
        onBrightnessLevelChanged();
    }
    @Override
    public void onBrightnessLevelChanged() {
        Resources r = mContext.getResources();
        int mode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                mUserTracker.getCurrentUserId());
        mBrightnessState.autoBrightness =
                (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        mBrightnessState.iconId = mBrightnessState.autoBrightness
                ? R.drawable.ic_qs_brightness_auto_on
                : R.drawable.ic_qs_brightness_auto_off;
        mBrightnessState.label = r.getString(R.string.quick_settings_brightness_label);
        mBrightnessCallback.refreshView(mBrightnessTile, mBrightnessState);
    }
    void refreshBrightnessTile() {
        onBrightnessLevelChanged();
    }

    // SSL CA Cert warning.
    public void addSslCaCertWarningTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSslCaCertWarningTile = view;
        mSslCaCertWarningCallback = cb;
        // Set a sane default while we wait for the AsyncTask to finish (no cert).
        setSslCaCertWarningTileInfo(false, true);
    }
    public void setSslCaCertWarningTileInfo(boolean hasCert, boolean isManaged) {
        Resources r = mContext.getResources();
        mSslCaCertWarningState.enabled = hasCert;
        if (isManaged) {
            mSslCaCertWarningState.iconId = R.drawable.ic_qs_certificate_info;
        } else {
            mSslCaCertWarningState.iconId = android.R.drawable.stat_notify_error;
        }
        mSslCaCertWarningState.label = r.getString(R.string.ssl_ca_cert_warning);
        mSslCaCertWarningCallback.refreshView(mSslCaCertWarningTile, mSslCaCertWarningState);
    }

    // Lightbulb
    void addLightbulbTile(QuickSettingsTileView view, RefreshCallback cb) {
        mLightbulbTile = view;
        mLightbulbCallback = cb;
        onLightbulbChanged();
    }

    void onLightbulbChanged() {
        if (mLightbulbActive) {
            mLightbulbState.iconId = R.drawable.ic_qs_lightbulb_on;
            mLightbulbState.label = mContext.getString(R.string.quick_settings_lightbulb_label);
        } else {
            mLightbulbState.iconId = R.drawable.ic_qs_lightbulb_off;
            mLightbulbState.label = mContext.getString(R.string.quick_settings_lightbulb_off_label);
        }
        mLightbulbState.enabled = mLightbulbActive;
        mLightbulbCallback.refreshView(mLightbulbTile, mLightbulbState);
    }
}

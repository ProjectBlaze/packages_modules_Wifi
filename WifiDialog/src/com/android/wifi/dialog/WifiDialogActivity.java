/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wifi.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.wifi.WifiContext;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Main Activity of the WifiDialog application. All dialogs should be created and managed from here.
 */
public class WifiDialogActivity extends Activity  {
    private static final String TAG = "WifiDialog";
    private static final String KEY_DIALOG_INTENTS = "KEY_DIALOG_INTENTS";

    private @Nullable WifiContext mWifiContext;
    private @Nullable WifiManager mWifiManager;
    private boolean mIsVerboseLoggingEnabled;
    private int mGravity = Gravity.NO_GRAVITY;

    private @NonNull SparseArray<Intent> mIntentsPerId = new SparseArray<>();
    private @NonNull SparseArray<Dialog> mActiveDialogsPerId = new SparseArray<>();

    // Broadcast receiver for listening to ACTION_CLOSE_SYSTEM_DIALOGS
    private BroadcastReceiver mCloseSystemDialogsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mIsVerboseLoggingEnabled) {
                Log.v(TAG, "ACTION_CLOSE_SYSTEM_DIALOGS received, cancelling all dialogs.");
            }
            for (int i = 0; i < mActiveDialogsPerId.size(); i++) {
                Dialog dialog = mActiveDialogsPerId.valueAt(i);
                if (dialog.isShowing()) {
                    dialog.cancel();
                }
            }
        }
    };

    private WifiContext getWifiContext() {
        if (mWifiContext == null) {
            mWifiContext = new WifiContext(this);
        }
        return mWifiContext;
    }

    /**
     * Override the default Resources with the Resources from the active ServiceWifiResources APK.
     */
    @Override
    public Resources getResources() {
        return getWifiContext().getResources();
    }

    // TODO(b/215605937): Remove these getXxxId() methods with the actual resource ID references
    //                    once the build system is fixed to allow importing ServiceWifiResources.
    private int getStringId(@NonNull String name) {
        Resources res = getResources();
        return res.getIdentifier(
                name, "string", getWifiContext().getWifiOverlayApkPkgName());
    }

    private int getIntegerId(@NonNull String name) {
        Resources res = getResources();
        return res.getIdentifier(
                name, "integer", getWifiContext().getWifiOverlayApkPkgName());
    }

    private int getLayoutId(@NonNull String name) {
        Resources res = getResources();
        return res.getIdentifier(
                name, "layout", getWifiContext().getWifiOverlayApkPkgName());
    }

    private int getViewId(@NonNull String name) {
        Resources res = getResources();
        return res.getIdentifier(
                name, "id", getWifiContext().getWifiOverlayApkPkgName());
    }

    private WifiManager getWifiManager() {
        if (mWifiManager == null) {
            mWifiManager = getSystemService(WifiManager.class);
        }
        return mWifiManager;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        mIsVerboseLoggingEnabled = getWifiManager().isVerboseLoggingEnabled();
        if (mIsVerboseLoggingEnabled) {
            Log.v(TAG, "Creating WifiDialogActivity.");
        }
        mGravity = getResources().getInteger(getIntegerId("config_wifiDialogGravity"));
        List<Intent> receivedIntents = new ArrayList<>();
        if (savedInstanceState != null) {
            if (mIsVerboseLoggingEnabled) {
                Log.v(TAG, "Restoring WifiDialog saved state.");
            }
            receivedIntents.addAll(savedInstanceState.getParcelableArrayList(KEY_DIALOG_INTENTS));
        } else {
            receivedIntents.add(getIntent());
        }
        for (Intent intent : receivedIntents) {
            int dialogId = intent.getIntExtra(WifiManager.EXTRA_DIALOG_ID, -1);
            if (dialogId < 0) {
                if (mIsVerboseLoggingEnabled) {
                    Log.v(TAG, "Received Intent with negative dialogId=" + dialogId);
                }
                continue;
            }
            mIntentsPerId.put(dialogId, intent);
        }
    }

    /**
     * Create and display a dialog for the currently held Intents.
     */
    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(
                mCloseSystemDialogsReceiver, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        ArraySet<Integer> invalidDialogIds = new ArraySet<>();
        for (int i = 0; i < mIntentsPerId.size(); i++) {
            int dialogId = mIntentsPerId.keyAt(i);
            if (!createAndShowDialogForIntent(dialogId, mIntentsPerId.get(dialogId))) {
                invalidDialogIds.add(dialogId);
            }
        }
        invalidDialogIds.forEach(this::removeIntentAndPossiblyFinish);
    }

    /**
     * Create and display a dialog for a new Intent received by a pre-existing WifiDialogActivity.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) {
            return;
        }
        int dialogId = intent.getIntExtra(WifiManager.EXTRA_DIALOG_ID, -1);
        if (dialogId < 0) {
            if (mIsVerboseLoggingEnabled) {
                Log.v(TAG, "Received Intent with negative dialogId=" + dialogId);
            }
            return;
        }
        mIntentsPerId.put(dialogId, intent);
        if (!createAndShowDialogForIntent(dialogId, intent)) {
            removeIntentAndPossiblyFinish(dialogId);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(mCloseSystemDialogsReceiver);
        // Dismiss and remove any active Dialogs to prevent window leaking.
        for (int i = 0; i < mActiveDialogsPerId.size(); i++) {
            Dialog dialog = mActiveDialogsPerId.valueAt(i);
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
        mActiveDialogsPerId.clear();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        ArrayList<Intent> intentList = new ArrayList<>();
        for (int i = 0; i < mIntentsPerId.size(); i++) {
            intentList.add(mIntentsPerId.valueAt(i));
        }
        outState.putParcelableArrayList(KEY_DIALOG_INTENTS, intentList);
        super.onSaveInstanceState(outState);
    }

    /**
     * Remove the Intent and corresponding Dialog of the given dialogId and finish the Activity if
     * there are no dialogs left to show.
     */
    private void removeIntentAndPossiblyFinish(int dialogId) {
        mIntentsPerId.remove(dialogId);
        Dialog dialog = mActiveDialogsPerId.get(dialogId);
        mActiveDialogsPerId.remove(dialogId);
        if (dialog != null && dialog.isShowing()) {
            dialog.cancel();
        }
        if (mIsVerboseLoggingEnabled) {
            Log.v(TAG, "Dialog id " + dialogId + " removed.");
        }
        if (mIntentsPerId.size() == 0) {
            if (mIsVerboseLoggingEnabled) {
                Log.v(TAG, "No dialogs left to show, finishing.");
            }
            finishAndRemoveTask();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            // Kill the process now instead of waiting indefinitely for ActivityManager to kill it.
            Process.killProcess(android.os.Process.myPid());
        }
    }

    /**
     * Creates and shows a dialog for the given dialogId and Intent.
     * Returns {@code true} if the dialog was successfully created, {@code false} otherwise.
     */
    private @Nullable boolean createAndShowDialogForIntent(int dialogId, @NonNull Intent intent) {
        Dialog dialog = null;
        int dialogType = intent.getIntExtra(
                WifiManager.EXTRA_DIALOG_TYPE, WifiManager.DIALOG_TYPE_UNKNOWN);
        switch (dialogType) {
            case WifiManager.DIALOG_TYPE_P2P_INVITATION_RECEIVED:
                dialog = createP2pInvitationReceivedDialog(
                        dialogId,
                        intent.getStringExtra(WifiManager.EXTRA_P2P_DEVICE_NAME),
                        intent.getBooleanExtra(WifiManager.EXTRA_P2P_PIN_REQUESTED, false),
                        intent.getStringExtra(WifiManager.EXTRA_P2P_DISPLAY_PIN));
                break;
            default:
                if (mIsVerboseLoggingEnabled) {
                    Log.v(TAG, "Could not create dialog with id= " + dialogId
                            + " for unknown type: " + dialogType);
                }
                break;
        }
        if (dialog == null) {
            return false;
        }
        mActiveDialogsPerId.put(dialogId, dialog);
        if (mGravity != Gravity.NO_GRAVITY) {
            dialog.getWindow().setGravity(mGravity);
        }
        dialog.show();
        if (mIsVerboseLoggingEnabled) {
            Log.v(TAG, "Showing dialog " + dialogId);
        }
        return true;
    }

    /**
     * Returns a P2P Invitation Received Dialog for the given Intent, or {@code null} if no Dialog
     * could be created.
     */
    private @Nullable Dialog createP2pInvitationReceivedDialog(
            final int dialogId,
            final @NonNull String deviceName,
            final boolean isPinRequested,
            @Nullable String displayPin) {
        if (TextUtils.isEmpty(deviceName)) {
            if (mIsVerboseLoggingEnabled) {
                Log.v(TAG, "Could not create P2P Invitation Received dialog with null or empty"
                        + " device name."
                        + " id=" + dialogId
                        + " deviceName=" + deviceName
                        + " isPinRequested=" + isPinRequested
                        + " displayPin=" + displayPin);
            }
            return null;
        }

        final View textEntryView = LayoutInflater.from(this)
                .inflate(getLayoutId("wifi_p2p_dialog"), null);
        ViewGroup group = textEntryView.findViewById(getViewId("info"));
        addRowToP2pDialog(group, getStringId("wifi_p2p_from_message"), deviceName);

        final EditText pinEditText;
        if (isPinRequested) {
            textEntryView.findViewById(getViewId("enter_pin_section")).setVisibility(View.VISIBLE);
            pinEditText = textEntryView.findViewById(getViewId("wifi_p2p_wps_pin"));
            pinEditText.setVisibility(View.VISIBLE);
        } else {
            pinEditText = null;
        }
        if (displayPin != null) {
            addRowToP2pDialog(group, getStringId("wifi_p2p_show_pin_message"), displayPin);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(getStringId("wifi_p2p_invitation_to_connect_title")))
                .setView(textEntryView)
                .setPositiveButton(getStringId("accept"), (dialogPositive, which) -> {
                    String pin = null;
                    if (pinEditText != null) {
                        pin = pinEditText.getText().toString();
                    }
                    if (mIsVerboseLoggingEnabled) {
                        Log.v(TAG, "P2P Invitation Received Dialog id=" + dialogId
                                + " accepted with pin=" + pin);
                    }
                    getWifiManager().replyToP2pInvitationReceivedDialog(dialogId, true, pin);
                })
                .setNegativeButton(getStringId("decline"), (dialogNegative, which) -> {
                    if (mIsVerboseLoggingEnabled) {
                        Log.v(TAG, "P2P Invitation Received dialog id=" + dialogId
                                + " declined.");
                    }
                    getWifiManager().replyToP2pInvitationReceivedDialog(dialogId, false, null);
                })
                .setOnCancelListener((dialogCancel) -> {
                    if (mIsVerboseLoggingEnabled) {
                        Log.v(TAG, "P2P Invitation Received dialog id=" + dialogId
                                + " cancelled.");
                    }
                    getWifiManager().replyToP2pInvitationReceivedDialog(dialogId, false, null);
                })
                .setOnDismissListener((dialogDismiss) -> {
                    if (mIsVerboseLoggingEnabled) {
                        Log.v(TAG, "P2P Invitation Received dialog id=" + dialogId
                                + " dismissed.");
                    }
                    removeIntentAndPossiblyFinish(dialogId);
                })
                .create();
        dialog.setCanceledOnTouchOutside(false);
        if ((getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_APPLIANCE)
                == Configuration.UI_MODE_TYPE_APPLIANCE) {
            // For appliance devices, add a key listener which accepts.
            dialog.setOnKeyListener((dialogKey, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
                    // TODO: Plumb this response to framework.
                    dialog.dismiss();
                    return true;
                }
                return true;
            });
        }
        if (mIsVerboseLoggingEnabled) {
            Log.v(TAG, "Created P2P Invitation Received dialog."
                    + " id=" + dialogId
                    + " deviceName=" + deviceName
                    + " isPinRequested=" + isPinRequested
                    + " displayPin=" + displayPin);
        }
        return dialog;
    }

    /**
     * Helper method to add a row to a ViewGroup for a P2P Invitation Received/Sent Dialog.
     */
    private void addRowToP2pDialog(ViewGroup group, int stringId, String value) {
        View row = LayoutInflater.from(this)
                .inflate(getLayoutId("wifi_p2p_dialog_row"), group, false);
        ((TextView) row.findViewById(getViewId("name"))).setText(getString(stringId));
        ((TextView) row.findViewById(getViewId("value"))).setText(value);
        group.addView(row);
    }
}
package com.unifyid.example.gaitsample;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Switch;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class SettingsActivity extends AppCompatActivity {
    SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        prefs = getSharedPreferences(MainActivity.PREFERENCE_USER, MODE_PRIVATE);
        prefs.getBoolean(MainActivity.PREFERENCE_KEY_UNLOCK_ON_GAIT_AUTHENTICATED, false);
        bindToService();
    }

    void bindToService() {
        Intent serviceIntent = new Intent(this, GaitService.class);

        String userName = prefs.getString(MainActivity.PREFERENCE_KEY_USERNAME, "");
        serviceIntent.putExtra(GaitService.INTENT_USER_ID, userName);
        ContextCompat.startForegroundService(this, serviceIntent);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
    }

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            binder = (GaitService.LocalBinder) iBinder;
            service = binder.getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {

        }
    };


    public void onUnlockOnGaitAuthenticatedClicked(View view) {
        Switch s = (Switch) view;
        boolean unlockOnAuthenticated = s.isChecked();
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean(MainActivity.PREFERENCE_KEY_UNLOCK_ON_GAIT_AUTHENTICATED, unlockOnAuthenticated);
    }

    public void onTrainClicked(View view) {
        service.trainModel();
    }

    private GaitService service = null;
    GaitService.LocalBinder binder = null;

}

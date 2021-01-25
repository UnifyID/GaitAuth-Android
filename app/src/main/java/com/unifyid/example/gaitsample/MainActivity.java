package com.unifyid.example.gaitsample;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.prefs.Preferences;

import id.unify.sdk.gaitauth.AuthenticationResult;
import id.unify.sdk.gaitauth.GaitAuth;
import id.unify.sdk.gaitauth.GaitFeature;
import id.unify.sdk.gaitauth.GaitModel;
import id.unify.sdk.gaitauth.GaitQuantileConfig;
import id.unify.sdk.gaitauth.GaitScore;

public class MainActivity extends AppCompatActivity implements GaitService.StatusListener {


    public static final String STOP_TRACKING = "stopTracking";
    public static final String START_TRAINING = "startTraining";

    public static final String PREFERENCE_USER = "user";
    public static final String PREFERENCE_KEY_USERNAME = "userName";
    public static final String PREFERENCE_KEY_USERPASSWORD = "password";
    public static final String PREFERENCE_KEY_SECRET = "secret";
    public static final String PREFERENCE_KEY_UNLOCK_ON_GAIT_AUTHENTICATED = "unlockongaitauthenticated";

    TextView txtSecret;
    EditText txtPassword;
    ListView lvSecretsListview;
    ImageView imageLock;
    Button btnToggleSecret;
    TextView txtStatus;

    EditText editSecretName, editSecretValue;
    Button btnAddSecret;

    boolean isLocked = true;
    private boolean isBound = false;
    private GaitService service = null;
    GaitService.LocalBinder binder = null;
    String userName = "";
    String password = "";
    String secret = "";



    boolean unlockOnGaitAuthenticated = false;

    HashMap<String, String> secrets = new HashMap<String, String>();
    HashAdapter hashAdapter;




    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        secrets.put("x", "y");
        setContentView(R.layout.activity_main);
        SharedPreferences preferences = getSharedPreferences(PREFERENCE_USER, MODE_PRIVATE);
        String userName = preferences.getString(PREFERENCE_KEY_USERNAME, "");
        unlockOnGaitAuthenticated = preferences.getBoolean(PREFERENCE_KEY_UNLOCK_ON_GAIT_AUTHENTICATED, false);


        txtSecret = findViewById(R.id.txtSecret);
        txtStatus = findViewById(R.id.txtStatus);
        txtPassword = findViewById(R.id.txtPassword);
        imageLock = findViewById(R.id.imageLock);
        btnToggleSecret = findViewById(R.id.btnToggleSecret);
        btnAddSecret = findViewById(R.id.btnAddSecret);
        editSecretValue = findViewById(R.id.editSecretValue);
        editSecretName = findViewById(R.id.editSecretName);
        lvSecretsListview = findViewById(R.id.lvSecretList);
        hashAdapter = new HashAdapter(this.secrets);
        lvSecretsListview.setAdapter(hashAdapter);

    }


    @Override
    protected void onStart() {
        super.onStart();
        if (loadUser() == "") {
            Intent intent = new Intent(this, UserSetup.class);
            intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_NO_HISTORY);
            startActivity(intent);
            return;
        } else {
            bindToService();
            loadSecrets();
        }
    }

    public void onSettingsClicked(View view) {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveSecrets();
    }

    boolean isAuthentiated() {
        String enteredPassword = txtPassword.getText().toString();
        return enteredPassword.equals(password);
    }

    void unlockIfAuthenticated() {
        if (isAuthentiated() || service.isAuthenticated())
            showSecret();
    }

    final static String SECRETS_FILE_NAME = "secrets.bin";

    void saveSecrets() {
        try {
            File storageDirectory = getApplicationContext().getExternalFilesDir("");
            if (storageDirectory == null)
                storageDirectory = getApplicationContext().getFilesDir();
            File secretsFile = new File(storageDirectory.toString() + "/" + SECRETS_FILE_NAME);
            secretsFile.createNewFile();
            FileOutputStream fos = new FileOutputStream(secretsFile);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(secrets);
            oos.close();
        } catch (IOException exc) {
            exc.printStackTrace();
        }
    }

    void loadSecrets() {
        SharedPreferences prefs = getSharedPreferences(PREFERENCE_USER, MODE_PRIVATE);
        password = prefs.getString(PREFERENCE_KEY_USERPASSWORD, "");
        File storageDirectory = getApplicationContext().getExternalFilesDir("");
        if (storageDirectory == null)
            storageDirectory = getApplicationContext().getFilesDir();
        File secretsFile = new File(storageDirectory.toString() + "/" + SECRETS_FILE_NAME);
        if (secretsFile.exists() && secretsFile.canRead()) {
            try {
                FileInputStream fis = new FileInputStream(secretsFile);
                ObjectInputStream ois = new ObjectInputStream(fis);
                HashMap<String, String> secrets = (HashMap<String, String>) ois.readObject();
                ois.close();
                this.secrets.clear();
                this.secrets.putAll(secrets);
                hashAdapter.notifyDataSetChanged();

            } catch (IOException exc) {
                exc.printStackTrace();
            } catch (ClassNotFoundException exc) {

            }
        }
    }

    void saveSecret() {
        String secret = txtSecret.getText().toString();
        if (secret != "") {
            SharedPreferences prefs = getSharedPreferences(PREFERENCE_USER, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(PREFERENCE_KEY_SECRET, secret);
            editor.commit();
        }
    }

    void loadSecret() {
        SharedPreferences prefs = getSharedPreferences(PREFERENCE_USER, MODE_PRIVATE);
        password = prefs.getString(PREFERENCE_KEY_USERPASSWORD, "");
        secret = prefs.getString(PREFERENCE_KEY_SECRET, "");
        txtSecret.setText(secret);
    }

    void hideSecret() {
        txtPassword.setVisibility(View.VISIBLE);
        imageLock.setVisibility(View.VISIBLE);
        lvSecretsListview.setVisibility(View.INVISIBLE);
        txtPassword.setText("");
        btnToggleSecret.setText(getString(R.string.label_unlock));
        editSecretName.setVisibility(View.INVISIBLE);
        editSecretValue.setVisibility(View.INVISIBLE);
        btnAddSecret.setVisibility(View.INVISIBLE);
        hideKeyboard();
    }

    void showSecret() {
        txtPassword.setVisibility(View.INVISIBLE);
        imageLock.setVisibility(View.INVISIBLE);
        lvSecretsListview.setVisibility(View.VISIBLE);
        txtPassword.setText("");
        btnToggleSecret.setText(getString(R.string.label_lock));
        editSecretName.setVisibility(View.VISIBLE);
        editSecretValue.setVisibility(View.VISIBLE);
        btnAddSecret.setVisibility(View.VISIBLE);
        hideKeyboard();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Activity.INPUT_METHOD_SERVICE);
        View view = getCurrentFocus();
        if (view != null)
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);

    }

    public void onToggleSecretClicked(View view) {
        if (isLocked) {
            isLocked = !isAuthentiated();
        } else {
            isLocked = true;
            saveSecret();
        }
        Resources resources = getResources();
        if (isLocked)
            hideSecret();
        else
            showSecret();
    }

    String loadUser() {
        SharedPreferences preferences = getSharedPreferences(PREFERENCE_USER, MODE_PRIVATE);
        userName = preferences.getString(PREFERENCE_KEY_USERNAME, "");
        return userName;
    }

    public void onDeleteSecretClicked(View view) {
        String key = (String) view.getTag();
        secrets.remove(key);
        hashAdapter.notifyDataSetChanged();
    }

    public void onAddNewSecret(View view) {
        String secret = editSecretValue.getText().toString();
        String name = editSecretName.getText().toString();
        if (name.length() > 0 && secret.length() > 0) {
            secrets.put(name, secret);
            hashAdapter.notifyDataSetChanged();
            editSecretValue.setText("");
            editSecretName.setText("");
        }
    }

    void bindToService() {
        Intent launchIntent = getIntent();
        Intent serviceIntent = new Intent(this, GaitService.class);
        serviceIntent.putExtra(GaitService.INTENT_USER_ID, userName);
        ContextCompat.startForegroundService(this, serviceIntent);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
    }

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            binder = (GaitService.LocalBinder) iBinder;
            service = binder.getService();
            service.setStatusListener(MainActivity.this);
            isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            isBound = false;
        }
    };


    @Override
    public void onAuthenticationStatusChanged(AuthenticationResult.Status status) {

    }

    @Override
    public void onScoresUpdated(List<GaitScore> scores) {
        if (unlockOnGaitAuthenticated)
            unlockIfAuthenticated();
    }

    public void onTrainingStatusUpdated(final GaitModel.Status status) {
        txtStatus.setText(String.format("%s %s", status.toString(), new Date()));

    }


    @Override
    public void onGaitFeatureAdded(GaitFeature feature) {

    }

    @Override
    public void onMessage(String[] message) {

        StringBuilder sb = new StringBuilder();
        for (String s : message) {
            sb.append(String.format("%s\n", s));
        }
        txtStatus.setText(sb.toString());
    }
}
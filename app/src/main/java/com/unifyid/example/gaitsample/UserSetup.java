package com.unifyid.example.gaitsample;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

public class UserSetup extends Activity {
    TextView txtUserName, txtPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_setup);
        txtUserName = findViewById(R.id.txtUserName);
        txtPassword = findViewById(R.id.txtPassword);
    }

    public void onSaveUserClicked(View view) {
        String userName = txtUserName.getText().toString();
        String password = txtPassword.getText().toString();
        if ((userName == "") || (password == "")) {
            return;
        }

        SharedPreferences preferences = getSharedPreferences(MainActivity.PREFERENCE_USER, MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(MainActivity.PREFERENCE_KEY_USERNAME, userName);
        editor.putString(MainActivity.PREFERENCE_KEY_USERPASSWORD, password);
        editor.commit();

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_NO_HISTORY);
        startActivity(intent);

    }
}

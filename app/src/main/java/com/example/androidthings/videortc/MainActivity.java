package com.example.androidthings.videortc;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        findViewById(R.id.connectBtn).setOnClickListener(arg0 -> {
            Intent myIntent = new Intent(this, CallActivity.class);
            startActivity(myIntent);
        });
    }
}
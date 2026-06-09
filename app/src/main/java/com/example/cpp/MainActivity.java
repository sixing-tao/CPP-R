package com.example.cppr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_OVERLAY_PERMISSION = 100;
    private static final int REQUEST_CODE_MEDIA_PROJECTION = 101;

    private Button startButton;
    private Button appSelectionButton;
    private TextView statusText;
    private MediaProjectionManager mediaProjectionManager;
    private boolean servicesRunning = false;
    private String selectedApp = "default";
    private OkHttpClient httpClient;
    private static final String API_BASE_URL = "http://8.216.41.236:5005";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startButton = findViewById(R.id.btn_start_service);
        appSelectionButton = findViewById(R.id.btn_app_selection);
        statusText = findViewById(R.id.tv_status);

        httpClient = new OkHttpClient();

        int purple = android.graphics.Color.parseColor("#9C27B0");
        startButton.setTextColor(android.graphics.Color.WHITE);
        appSelectionButton.setTextColor(android.graphics.Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            startButton.setBackgroundTintList(ColorStateList.valueOf(purple));
            appSelectionButton.setBackgroundTintList(ColorStateList.valueOf(purple));
        }

        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        startButton.setOnClickListener(v -> {
            if (servicesRunning) {
                stopPrivacyServices();
                servicesRunning = false;
                updateUiForServiceState(false);
                Toast.makeText(this, "Privacy monitor stopped", Toast.LENGTH_SHORT).show();
            } else {
                checkPermissionsAndStartService();
            }
        });

        appSelectionButton.setOnClickListener(v -> showAppSelectionDialog());
    }

    private void checkPermissionsAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION);
        } else {
            requestScreenCapturePermission();
        }
    }

    private void requestScreenCapturePermission() {
        startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(),
                REQUEST_CODE_MEDIA_PROJECTION
        );
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                requestScreenCapturePermission();
            } else {
                Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK) {
                Log.d("MainActivity", "Screenshot permission granted. Starting service.");

                Intent privacyIntent = new Intent(this, PrivacyService.class);
                privacyIntent.putExtra("resultCode", resultCode);
                privacyIntent.putExtra("data", data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(privacyIntent);
                } else {
                    startService(privacyIntent);
                }

                Intent floatingIntent = new Intent(this, FloatingWindowService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(floatingIntent);
                } else {
                    startService(floatingIntent);
                }

                servicesRunning = true;
                updateUiForServiceState(true);
                Toast.makeText(this, "Privacy monitor started", Toast.LENGTH_SHORT).show();
            } else {
                Log.d("MainActivity", "Screenshot permission denied.");
                statusText.setText("Service stopped (permission denied)");
                Toast.makeText(this, "Screenshot permission required", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void stopPrivacyServices() {
        try { stopService(new Intent(this, PrivacyService.class)); } catch (Exception ignore) {}
        try { stopService(new Intent(this, FloatingWindowService.class)); } catch (Exception ignore) {}
    }

    private void updateUiForServiceState(boolean running) {
        if (running) {
            statusText.setText("Service running");
            startButton.setText("Stop Privacy Monitor");
            int red = android.graphics.Color.parseColor("#E53935");
            startButton.setTextColor(android.graphics.Color.WHITE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                startButton.setBackgroundTintList(ColorStateList.valueOf(red));
            }
        } else {
            statusText.setText("Service stopped");
            startButton.setText(getString(R.string.start_privacy_service));
            int purple = android.graphics.Color.parseColor("#9C27B0");
            startButton.setTextColor(android.graphics.Color.WHITE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                startButton.setBackgroundTintList(ColorStateList.valueOf(purple));
            }
        }
    }

    private void showAppSelectionDialog() {
        String[] apps = {
            getString(R.string.select_app_default),
            getString(R.string.app_guazi),
            getString(R.string.app_iqiyi),
            getString(R.string.app_ctrip),
            getString(R.string.app_uc)
        };

        new AlertDialog.Builder(this)
                .setTitle("Select App")
                .setItems(apps, (dialog, which) -> {
                    String newApp;
                    String newAppText = apps[which];
                    switch (which) {
                        case 1: newApp = "guazi"; break;
                        case 2: newApp = "aiqiyi"; break;  // note: backend key is "aiqiyi"
                        case 3: newApp = "ctrip"; break;
                        case 4: newApp = "uc"; break;
                        default: newApp = "default"; break;
                    }
                    switchKnowledgeBase(newApp, newAppText);
                })
                .show();
    }

    private void switchKnowledgeBase(String knowledgeBase, String displayText) {
        new AsyncTask<String, Void, Boolean>() {
            private String errorMessage = "";

            @Override
            protected void onPreExecute() {
                appSelectionButton.setText("Switching...");
                appSelectionButton.setEnabled(false);
            }

            @Override
            protected Boolean doInBackground(String... params) {
                try {
                    JSONObject jsonBody = new JSONObject();
                    jsonBody.put("knowledge_base", params[0]);

                    RequestBody body = RequestBody.create(
                        MediaType.parse("application/json; charset=utf-8"),
                        jsonBody.toString()
                    );

                    Request request = new Request.Builder()
                        .url(API_BASE_URL + "/set-knowledge-base")
                        .post(body)
                        .addHeader("Content-Type", "application/json")
                        .build();

                    Response response = httpClient.newCall(request).execute();
                    String responseBody = response.body().string();

                    if (response.isSuccessful()) {
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        boolean success = jsonResponse.getBoolean("success");
                        if (!success) {
                            errorMessage = jsonResponse.getString("error");
                        }
                        return success;
                    } else {
                        errorMessage = "HTTP error: " + response.code();
                        return false;
                    }
                } catch (Exception e) {
                    errorMessage = "Request failed: " + e.getMessage();
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean success) {
                appSelectionButton.setEnabled(true);
                if (success) {
                    selectedApp = knowledgeBase;
                    appSelectionButton.setText(displayText);
                    Toast.makeText(MainActivity.this, "Switched to: " + displayText, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Switch failed: " + errorMessage, Toast.LENGTH_LONG).show();
                }
            }
        }.execute(knowledgeBase);
    }
}

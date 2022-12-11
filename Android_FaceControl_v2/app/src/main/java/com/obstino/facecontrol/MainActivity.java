package com.obstino.facecontrol;

import static java.lang.Math.max;
import static java.lang.Math.signum;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {
    String TAG = "FaceControl.MainActivity";
    int REQUESTCODE_CAMERA = 1;
    FaceControlService serviceInst;

    //final static int COMMAND_STARTCAMERA = 1000;
    final static int COMMAND_STARTSTOP = 100;
    final static int COMMAND_FACETEST_START = 101;
    final static int COMMAND_FACETEST_STOP = 102;

    ImageButton button_facegesturetest;
    ImageButton button_startstop;

    static final int MSG_CAMERAOFF = 1;
    static final int MSG_STARTED = 2;
    static final int MSG_STOPPED = 3;
    static final int MSG_FACETEST_STARTED = 4;
    static final int MSG_FACETEST_STOPPED = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SharedPreferences prefs;
        prefs = getSharedPreferences(SettingsActivity.MY_PREFS_NAME, MODE_PRIVATE);

        // if this is our first run, open IntroActivity
        if(prefs.getBoolean("FirstRun", true)) {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean("FirstRun", false);
            ed.apply();
            Intent intent = new Intent(getApplicationContext(), IntroActivity.class);
            startActivity(intent);
        }

        /*
        if(prefs.getBoolean("ShowEULA", true)) {
            AlertDialog.Builder dlgAlert  = new AlertDialog.Builder(this);
            String message;
            String link = "<a href=\"...\">End User License Agreement</a>";
            message = "In order to use FaceControl, you need to agree to the " + link + "\n(explains appropriate use etc.:)";
            dlgAlert.setMessage(message);
            dlgAlert.setTitle("FaceControl End User License Agreement");
            dlgAlert.setPositiveButton("Agree", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    SharedPreferences.Editor ed = prefs.edit();
                    ed.putBoolean("ShowEULA", false);
                    ed.apply();
                }
            });
            dlgAlert.setNegativeButton("Disagree", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // User disagreed.. exit!
                    finish();
                    System.exit(0);
                }
            });
            dlgAlert.setCancelable(false);
            dlgAlert.create().show();
        }*/

        // register broadcast receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(myBroadcastReceiver,
                new IntentFilter(SettingsActivity.BROADCAST_EVENT_NAME));

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Log.i(TAG, "Asking for camera permission");
                AlertDialog.Builder dlgAlert  = new AlertDialog.Builder(this);
                dlgAlert.setMessage("Please enable camera access in order for FaceControl to detect facial gestures (camera switches)");
                dlgAlert.setTitle("FaceControl request");
                dlgAlert.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUESTCODE_CAMERA);
                    }
                });
                dlgAlert.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) { }
                });
                dlgAlert.setCancelable(true);
                dlgAlert.create().show();
            }
        } else Log.i(TAG, "Camera permission already granted");

        if (!isAccessibilityServiceEnabled(getApplicationContext(), FaceControlService.class)) {
            showEnableAccessibilityService();
        } else {
            Log.i(TAG, "Accessbility service already enabled :)");
        }

        findViewById(R.id.button_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getApplicationContext(), SettingsActivity.class);
                startActivity(intent);
            }
        });

        button_facegesturetest = findViewById(R.id.button_facegesturetest);
        button_facegesturetest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FaceControlService service;
                service = FaceControlService.sharedServiceInst;
                if(service == null) {
                    showEnableAccessibilityService();
                    //Toast.makeText(getApplicationContext(), "Please first enable the accessibility service!", Toast.LENGTH_LONG).show();
                } else {
                    // disable buttons so we don't send duplicate messages
                    button_startstop.setEnabled(false);
                    button_facegesturetest.setEnabled(false);

                    Log.i(TAG, "Calling startService with COMMAND_FACETEST_START");
                    Intent intent = new Intent(getApplicationContext(), FaceControlService.class);
                    intent.putExtra("command", COMMAND_FACETEST_START);
                    startService(intent);
                }

                /* else if(service.cameraThread == null) {
                    Toast.makeText(getApplicationContext(), "Please first assign camera switches (for example smile and eyebrow), press 'Start', and then press 'Sensitivity & test'", Toast.LENGTH_LONG).show();
                } else {
                    Intent intent = new Intent(getApplicationContext(), GestureCalibrationActivity.class);
                    startActivity(intent);
                }*/
            }
        });

        FaceControlService service = FaceControlService.sharedServiceInst;

        button_startstop = findViewById(R.id.button_startstop);
        if(service == null || service.startState != StartState.started) {
            button_startstop.setImageResource(R.drawable.main_start);
        } else {
            button_startstop.setImageResource(R.drawable.main_stop);
        }

        button_startstop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FaceControlService service = FaceControlService.sharedServiceInst;
                if(service == null) {
                    button_startstop.setImageResource(R.drawable.main_start);

                    AlertDialog.Builder dlgAlert  = new AlertDialog.Builder(MainActivity.this);
                    dlgAlert.setMessage("Error starting: FaceControl app needs to be enabled in Accessibility Settings in order for it to work (perform tap gestures, draw on screen, etc.)");
                    dlgAlert.setTitle("FaceControl request");
                    dlgAlert.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        }
                    });
                    dlgAlert.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) { }
                    });
                    dlgAlert.setCancelable(true);
                    dlgAlert.create().show();
                } else {
                    button_startstop.setEnabled(false);   // disable until we are sure it is already started
                    button_facegesturetest.setEnabled(false);   // disable bcoz we don't want any new start commands

                    Log.i(TAG, "Calling startService with COMMAND_STARTSTOP");
                    Intent intent = new Intent(getApplicationContext(), FaceControlService.class);
                    intent.putExtra("command", COMMAND_STARTSTOP);
                    startService(intent);
                }
                /*else {
                    if(service.startState == StartState.stopped) {
                        Log.i(TAG, "Stopped; calling service.startAll");
                        service.startAll(false);
                        //button_startstop.setText("Stop");
                        button_startstop.setImageResource(R.drawable.main_stop);
                    } else if(service.startState == StartState.started) {
                        Log.i(TAG, "Started; calling service.stopAll");
                        service.stopAll();
                        //button_startstop.setText("Start");
                        button_startstop.setImageResource(R.drawable.main_start);
                    } else if(service.startState == StartState.stopping) {
                        Log.i(TAG, "Stopping; showing toast");
                        Toast.makeText(getApplicationContext(), "FaceControl is stopping... Try again in a moment.", Toast.LENGTH_SHORT).show();
                    }
                }*/
            }
        });

        if(service != null) {
            // First disable "start/stop" button and "calibration&test" button
            button_startstop.setEnabled(false);
            button_facegesturetest.setEnabled(false);
            // Now send a broadcast to stop FaceTest ... this is in case we are returning from that activity
            // Just so we stop everything that is running unnecessarily
            // (e.g. it stops camera in case we use only physical switches)
            Log.i(TAG, "Calling startService with COMMAND_FACETEST_STOP");
            Intent intent = new Intent(getApplicationContext(), FaceControlService.class);
            intent.putExtra("command", COMMAND_FACETEST_STOP);
            startService(intent);
        }

        findViewById(R.id.button_intro).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getApplicationContext(), IntroActivity.class);
                startActivity(intent);
            }
        });

        findViewById(R.id.button_feedback).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String subject = "FaceControl v2.0";
                Intent intent = new Intent(Intent.ACTION_VIEW);
                Uri data = Uri.parse("mailto:feedback@obstino.org?subject=" + Uri.encode(subject));
                intent.setData(data);
                startActivity(intent);
            }
        });

        findViewById(R.id.button_donate).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Uri uri = Uri.parse("https://www.obstino.org/facecontrol/donate.php");
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                startActivity(intent);
            }
        });
    }

    void showEnableAccessibilityService() {
        Log.i(TAG, "accessibility service not enabled. opening settings");
        AlertDialog.Builder dlgAlert  = new AlertDialog.Builder(this);
        dlgAlert.setMessage("FaceControl app needs to be enabled in Accessibility Settings in order for it to work (perform tap gestures, draw on screen, etc.)");
        dlgAlert.setTitle("FaceControl request");
        dlgAlert.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        dlgAlert.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) { }
        });
        dlgAlert.setCancelable(true);
        dlgAlert.create().show();
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(myBroadcastReceiver);

        super.onDestroy();
    }

    private BroadcastReceiver myBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int msg = intent.getIntExtra("msg", 0);
            Log.i(TAG, "Received onReceive broadcast value " + msg);

            switch(msg) {
                case MSG_CAMERAOFF:
                    // reset button states
                    button_startstop.setEnabled(true);
                    button_facegesturetest.setEnabled(true);
                    button_startstop.setImageResource(R.drawable.main_start);

                    //Toast.makeText(getApplicationContext(), "Start error: You are using camera switches but haven't enabled camera permission.", Toast.LENGTH_LONG).show();

                    AlertDialog.Builder dlgAlert  = new AlertDialog.Builder(MainActivity.this);
                    dlgAlert.setMessage("Error starting: You are using camera switches but haven't enabled camera permission.\n"
                            +"Please enable camera access in order for FaceControl to detect facial gestures (camera switches)");
                    dlgAlert.setTitle("FaceControl request");
                    dlgAlert.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUESTCODE_CAMERA);
                        }
                    });
                    dlgAlert.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) { }
                    });
                    dlgAlert.setCancelable(true);
                    dlgAlert.create().show();
                    break;

                case MSG_STARTED:
                    // reset button states
                    button_startstop.setImageResource(R.drawable.main_stop);
                    button_startstop.setEnabled(true);
                    button_facegesturetest.setEnabled(true);
                    break;
                case MSG_STOPPED:
                    // reset button states
                    button_startstop.setImageResource(R.drawable.main_start);
                    button_startstop.setEnabled(true);
                    button_facegesturetest.setEnabled(true);
                    break;

                case MSG_FACETEST_STARTED:
                    button_startstop.setEnabled(true);
                    button_facegesturetest.setEnabled(true);

                    Log.i(TAG, "got MSG_FACETEST_STARTED; opening faceTest...");
                    Intent gestureIntent = new Intent(getApplicationContext(), GestureCalibrationActivity.class);
                    startActivity(gestureIntent);
                    break;

                case MSG_FACETEST_STOPPED:
                    button_startstop.setEnabled(true);
                    button_facegesturetest.setEnabled(true);

                    FaceControlService service = FaceControlService.sharedServiceInst;
                    if(service == null || service.startState != StartState.started) {
                        button_startstop.setImageResource(R.drawable.main_start);
                    } else {
                        button_startstop.setImageResource(R.drawable.main_stop);
                    }

                    Log.i(TAG, "got MSG_FACETEST_STOPPED. everything ready to be used now");
                    break;
            }
        }
    };

    /*
     ** Based on {@link com.android.settingslib.accessibility.AccessibilityUtils#getEnabledServicesFromSettings(Context,int)}
     ** @see <a href="https://github.com/android/platform_frameworks_base/blob/d48e0d44f6676de6fd54fd8a017332edd6a9f096/packages/SettingsLib/src/com/android/settingslib/accessibility/AccessibilityUtils.java#L55">AccessibilityUtils</a>
     */
    public static boolean isAccessibilityServiceEnabled(Context context, Class<?> accessibilityService) {
        ComponentName expectedComponentName = new ComponentName(context, accessibilityService);

        String enabledServicesSetting = Settings.Secure.getString(context.getContentResolver(),  Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServicesSetting == null)
            return false;

        TextUtils.SimpleStringSplitter colonSplitter = new TextUtils.SimpleStringSplitter(':');
        colonSplitter.setString(enabledServicesSetting);

        while (colonSplitter.hasNext()) {
            String componentNameString = colonSplitter.next();
            ComponentName enabledService = ComponentName.unflattenFromString(componentNameString);

            if (enabledService != null && enabledService.equals(expectedComponentName))
                return true;
        }

        return false;
    }
}

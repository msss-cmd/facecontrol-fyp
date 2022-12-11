package com.obstino.facecontrol;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.NavUtils;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

public class GestureCalibrationActivity extends AppCompatActivity {
    String TAG = "FaceControl.GestureCalibrationActivity";
    static Thread drawThread = null;
    static boolean initStop;
    //Drawing drawing;
    ImageView imageView;
    Handler mHandler = new Handler();

    Bitmap faceNotFoundBitmap;
    Bitmap smileBitmap;
    Bitmap eyebrowBitmap;
    Bitmap leftWinkBitmap;
    Bitmap rightWinkBitmap;
    Bitmap lookLeftBitmap;
    Bitmap lookRightBitmap;
    //Bitmap lookUpBitmap;
    Bitmap mouthOpenBitmap;

    TextView textview_smile;
    TextView textview_eyebrow;
    TextView textview_mouth;
    TextView textview_lookleft;
    TextView textview_lookright;
    //TextView textview_lookup;
    TextView textview_wink;

    SeekBar seekbar_smile;
    SeekBar seekbar_eyebrow;
    SeekBar seekbar_mouth;
    SeekBar seekbar_lookleft;
    SeekBar seekbar_lookright;
    //SeekBar seekbar_lookup;
    SeekBar seekbar_wink;
    EditText edittext_wink;

    SwitchCompat switch_lowres;

    SharedPreferences prefs;

    static boolean DEFAULT_LOWRES_MODE = false;
    static int DEFAULT_SMILE_SENSITIVITY = 4;
    static int DEFAULT_EYEBROW_SENSITIVITY = 4;
    static int DEFAULT_MOUTH_SENSITIVITY = 4;
    static int DEFAULT_LOOKLEFT_SENSITIVITY = 4;
    static int DEFAULT_LOOKRIGHT_SENSITIVITY = 4;
    static int DEFAULT_LOOKUP_SENSITIVITY = 4;
    static int DEFAULT_WINK_SENSITIVITY = 4;
    static float DEFAULT_WINK_MINDURATION = 0.1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gesture_calibration);

        initStop = false;

        /*
        // register broadcast receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(myBroadcastReceiver,
                new IntentFilter(SettingsActivity.BROADCAST_EVENT_NAME));
        */

        imageView = findViewById(R.id.imageview_bitmap);
        prepareBitmaps();
        startDrawThread();

        prefs = getSharedPreferences(SettingsActivity.MY_PREFS_NAME, MODE_PRIVATE);

        switch_lowres = findViewById(R.id.switch_lowres);
        switch_lowres.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SharedPreferences.Editor ed = prefs.edit();
                ed.putBoolean("LowResMode", isChecked);
                ed.apply();

                FaceControlService service;
                service = FaceControlService.sharedServiceInst;
                if(service != null) {
                    service.setLowResMode(isChecked);
                }
            }
        });
        switch_lowres.setChecked(prefs.getBoolean("LowResMode", DEFAULT_LOWRES_MODE));

        textview_smile = findViewById(R.id.textview_smile);
        seekbar_smile = findViewById(R.id.seekbar_smile);
        textview_eyebrow = findViewById(R.id.textview_eyebrow);
        seekbar_eyebrow = findViewById(R.id.seekbar_eyebrow);
        textview_mouth = findViewById(R.id.textview_mouth);
        seekbar_mouth = findViewById(R.id.seekbar_mouth);
        textview_lookleft = findViewById(R.id.textview_lookleft);
        seekbar_lookleft = findViewById(R.id.seekbar_lookleft);
        textview_lookright = findViewById(R.id.textview_lookright);
        seekbar_lookright = findViewById(R.id.seekbar_lookright);
        //textview_lookup = findViewById(R.id.textview_lookup);
        //seekbar_lookup = findViewById(R.id.seekbar_lookup);
        textview_wink = findViewById(R.id.textview_wink1);
        seekbar_wink = findViewById(R.id.seekbar_wink);
        edittext_wink = findViewById(R.id.edittext_wink);

        seekbar_smile.setMax(8);
        seekbar_smile.setOnSeekBarChangeListener(onSeekBarChangeListener);
        seekbar_smile.setProgress(prefs.getInt("smile_sensitivity", DEFAULT_SMILE_SENSITIVITY));

        seekbar_eyebrow.setMax(8);
        seekbar_eyebrow.setOnSeekBarChangeListener(onSeekBarChangeListener);
        seekbar_eyebrow.setProgress(prefs.getInt("eyebrow_sensitivity", DEFAULT_EYEBROW_SENSITIVITY));

        seekbar_mouth.setMax(8);
        seekbar_mouth.setOnSeekBarChangeListener(onSeekBarChangeListener);
        seekbar_mouth.setProgress(prefs.getInt("mouth_sensitivity", DEFAULT_MOUTH_SENSITIVITY));

        seekbar_lookleft.setMax(8);
        seekbar_lookleft.setOnSeekBarChangeListener(onSeekBarChangeListener);
        seekbar_lookleft.setProgress(prefs.getInt("lookleft_sensitivity", DEFAULT_LOOKLEFT_SENSITIVITY));

        seekbar_lookright.setMax(8);
        seekbar_lookright.setOnSeekBarChangeListener(onSeekBarChangeListener);
        seekbar_lookright.setProgress(prefs.getInt("lookright_sensitivity", DEFAULT_LOOKRIGHT_SENSITIVITY));

        /*seekbar_lookup.setMax(8);
        seekbar_lookup.setOnSeekBarChangeListener(onSeekBarChangeListener);
        seekbar_lookup.setProgress(prefs.getInt("lookup_sensitivity", DEFAULT_LOOKUP_SENSITIVITY));*/

        seekbar_wink.setMax(8);
        seekbar_wink.setOnSeekBarChangeListener(onSeekBarChangeListener);
        seekbar_wink.setProgress(prefs.getInt("wink_sensitivity", DEFAULT_WINK_SENSITIVITY));

        String str;
        str = String.format(Locale.getDefault(), "%.1f", prefs.getFloat("wink_minduration", DEFAULT_WINK_MINDURATION));
        edittext_wink.setText(str);
        edittext_wink.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override
            public void afterTextChanged(Editable s) {
                String text = edittext_wink.getText().toString();
                text = text.replace(",", ".");

                if(text.length() > 0) {
                    try {
                        float f = Float.parseFloat(text);
                        Log.i(TAG, "Found float " + String.format(Locale.getDefault(), "%f", f));

                        SharedPreferences.Editor ed = prefs.edit();
                        ed.putFloat("wink_minduration", f);
                        ed.apply();

                        reinitGestureRecognizer();
                    } catch (Exception e) {
                        Log.i(TAG, "afterTextChanged exception " + e.toString());
                    }
                }
            }
        });
    }
    /*
    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy called, unregistering BroadcastReceiver");
        LocalBroadcastManager.getInstance(this).unregisterReceiver(myBroadcastReceiver);
        super.onDestroy();
    }

    private BroadcastReceiver myBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int msg = intent.getIntExtra("msg", 0);
            Log.i(TAG, "Received onReceive broadcast value " + msg);

            if (msg == MainActivity.MSG_FACETEST_STOPPED) {
                TODO: navigate to MainActivity
            }
        }
    };*/

    SeekBar.OnSeekBarChangeListener onSeekBarChangeListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            SharedPreferences.Editor ed = prefs.edit();

            if(seekBar == seekbar_smile) {
                textview_smile.setText(String.format("Smile sensitivity: %d", progress));
                ed.putInt("smile_sensitivity", progress);
            } else if(seekBar == seekbar_eyebrow) {
                textview_eyebrow.setText(String.format("Eyebrow raise sensitivity: %d", progress));
                ed.putInt("eyebrow_sensitivity", progress);
            } else if(seekBar == seekbar_mouth) {
                textview_mouth.setText(String.format("Mouth open sensitivity: %d", progress));
                ed.putInt("mouth_sensitivity", progress);
            } else if(seekBar == seekbar_lookleft) {
                textview_lookleft.setText(String.format("Look left sensitivity: %d", progress));
                ed.putInt("lookleft_sensitivity", progress);
            } else if(seekBar == seekbar_lookright) {
                textview_lookright.setText(String.format("Look right sensitivity: %d", progress));
                ed.putInt("lookright_sensitivity", progress);
            } /*else if(seekBar == seekbar_lookup) {
                textview_lookup.setText(String.format("Look up sensitivity: %d",progress));
                ed.putInt("lookup_sensitivity", progress);
            } */ else if(seekBar == seekbar_wink) {
                textview_wink.setText(String.format("Eye wink sensitivity: %d", progress));
                ed.putInt("wink_sensitivity", progress);
            }

            ed.apply();
            reinitGestureRecognizer();
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {

        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {

        }
    };

    void reinitGestureRecognizer() {
        FaceControlService service = FaceControlService.sharedServiceInst;
        if(service != null && service.gestureRecognizer != null) {
            service.gestureRecognizer.initializeSensitivities();
        }
    }

    void prepareBitmaps() {
        int w = 480; int h = 480;
        FaceControlService service = FaceControlService.sharedServiceInst;
        synchronized (service.drawLock) {
            if (service.drawBitmap != null)
                w = h = service.drawBitmap.getHeight();

            faceNotFoundBitmap = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.face_not_found),
                    w, h, true);

            smileBitmap = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.smile),
                    w, h, true);

            eyebrowBitmap = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.eyebrow),
                    w, h, true);

            leftWinkBitmap = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.left_wink),
                    w, h, true);

            rightWinkBitmap = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.right_wink),
                    w, h, true);

            lookLeftBitmap = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.look_left),
                    w, h, true);

            lookRightBitmap = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.look_right),
                    w, h, true);

            /*lookUpBitmap = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.look_up),
                    w, h, true);*/

            mouthOpenBitmap = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.mouth_open),
                    w, h, true);
        }
    }

    void startDrawThread() {
        while(initStop) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if(drawThread == null) {
            Log.i(TAG, "running draw thread");
            drawThread = new Thread(Thr_DrawUpdater);
            drawThread.start();
        } else Log.i(TAG, "thread already running");
    }

    Runnable Thr_DrawUpdater = new Runnable() {
        @Override
        public void run() {
            while(true) {
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            FaceControlService service = FaceControlService.sharedServiceInst;
                            if(service == null)
                                return;
                            if(service.gestureRecognizer == null)
                                return;
                            if(service.cameraThread == null)
                                return;

                            Bitmap drawBitmap;
                            synchronized (service.drawLock) {
                                if(initStop)
                                    return;

                                if(!service.gestureRecognizer.faceFound()) {
                                    drawBitmap = faceNotFoundBitmap;
                                } else if(service.gestureRecognizer.smileFound()) {
                                    drawBitmap = smileBitmap;
                                } else if(service.gestureRecognizer.eyebrowFound()) {
                                    drawBitmap = eyebrowBitmap;
                                } else if(service.gestureRecognizer.leftWinkFound()) {
                                    drawBitmap = leftWinkBitmap;
                                } else if(service.gestureRecognizer.rightWinkFound()) {
                                    drawBitmap = rightWinkBitmap;
                                } else if(service.gestureRecognizer.lookLeftFound()) {
                                    drawBitmap = lookLeftBitmap;
                                } else if(service.gestureRecognizer.lookRightFound()) {
                                    drawBitmap = lookRightBitmap;
                                }/* else if(service.gestureRecognizer.lookUpFound()) {
                                    drawBitmap = lookUpBitmap;
                                } */else if(service.gestureRecognizer.mouthOpenFound()) {
                                    drawBitmap = mouthOpenBitmap;
                                }
                                else {
                                    //drawBitmap = EyeCenterFinder.myDrawBitmap;
                                    drawBitmap = service.drawBitmap;
                                }

                                if(drawBitmap != null)
                                    imageView.setImageBitmap(drawBitmap);
                            }
                        }
                    }, 0);
                    //drawing.postInvalidate();

                if(initStop) {
                    Log.i(TAG, "Stopping Thr_DrawUpdater");
                    drawThread = null;
                    break;
                }

                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                //Log.i(TAG, "Updating drawing");
            }
        }
    };

    @Override
    protected void onPause() {
        // TODO: (!!!!) handle onPause() case
        super.onPause();
        Log.i(TAG, "onPause called");

        FaceControlService service = FaceControlService.sharedServiceInst;
        synchronized (service.drawLock) {
            initStop = true;
        }

        service.setActivityForeground(false);
        NavUtils.navigateUpFromSameTask(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume called");

        startDrawThread();

        FaceControlService service = FaceControlService.sharedServiceInst;
        service.setActivityForeground(true);
    }
}
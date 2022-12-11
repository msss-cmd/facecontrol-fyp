package com.obstino.facecontrol;

import androidx.appcompat.app.AppCompatActivity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;

import java.util.Locale;

public class ScanSettingsActivity extends AppCompatActivity {
    String TAG = "FaceControl.ScanSettingsActivity";
    SharedPreferences prefs;

    EditText edittext_scanbandspeed;
    EditText edittext_scanlinespeed;
    EditText edittext_scaninitialpause;
    EditText edittext_scanbandwidth;
    EditText edittext_scanloopcount;
    EditText edittext_autoselectpause;
    EditText edittext_autoselectperiod;

    RadioButton radio_keyboard_gridscan;
    RadioButton radio_keyboard_pointscan;

    static float DEFAULT_SCANBANDSPEED = 3.5f;  // used to be 5.0! xD
    static float DEFAULT_SCANLINESPEED = 0.7f;
    static float DEFAULT_SCANINITIALPAUSE = 1.0f;
    static float DEFAULT_SCANBANDWIDTH = 3.0f;
    static int DEFAULT_SCANLOOPCOUNT = 2;
    static float DEFAULT_AUTOSELECTPAUSE = 1.0f;
    static float DEFAULT_AUTOSELECTPERIOD = 1.5f;
    static boolean DEFAULT_GRIDSCAN_MODE = false;    // by default we DON'T scan the grid

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String str;

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_settings);

        prefs = getSharedPreferences(SettingsActivity.MY_PREFS_NAME, MODE_PRIVATE);

        str = String.format(Locale.getDefault(), "%.1f", prefs.getFloat("ScanBandSpeed", DEFAULT_SCANBANDSPEED));
        edittext_scanbandspeed = findViewById(R.id.edittext_scanbandspeed);
        edittext_scanbandspeed.setText(str);
        edittext_scanbandspeed.addTextChangedListener(new MyTextWatcher(edittext_scanbandspeed));

        str = String.format(Locale.getDefault(), "%.1f", prefs.getFloat("ScanLineSpeed", DEFAULT_SCANLINESPEED));
        edittext_scanlinespeed = findViewById(R.id.edittext_scanlinespeed);
        edittext_scanlinespeed.setText(str);
        edittext_scanlinespeed.addTextChangedListener(new MyTextWatcher(edittext_scanlinespeed));

        str = String.format(Locale.getDefault(), "%.1f", prefs.getFloat("ScanInitialPause", DEFAULT_SCANINITIALPAUSE));
        edittext_scaninitialpause = findViewById(R.id.edittext_scaninitialpause);
        edittext_scaninitialpause.setText(str);
        edittext_scaninitialpause.addTextChangedListener(new MyTextWatcher(edittext_scaninitialpause));

        str = String.format(Locale.getDefault(), "%.1f", prefs.getFloat("ScanBandWidth", DEFAULT_SCANBANDWIDTH));
        edittext_scanbandwidth = findViewById(R.id.edittext_scanbandwidth);
        edittext_scanbandwidth.setText(str);
        edittext_scanbandwidth.addTextChangedListener(new MyTextWatcher(edittext_scanbandwidth));

        str = String.format(Locale.getDefault(), "%d", prefs.getInt("ScanLoopCount", DEFAULT_SCANLOOPCOUNT));
        edittext_scanloopcount = findViewById(R.id.edittext_scanloopcount);
        edittext_scanloopcount.setText(str);
        edittext_scanloopcount.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override
            public void afterTextChanged(Editable s) {
                String text = edittext_scanloopcount.getText().toString();
                if(text.length() > 0) {
                    SharedPreferences.Editor ed = prefs.edit();
                    ed.putInt("ScanLoopCount", Integer.parseInt(text));
                    ed.apply();
                }
            }
        });

        str = String.format(Locale.getDefault(), "%.1f", prefs.getFloat("AutoSelectPause", DEFAULT_AUTOSELECTPAUSE));
        edittext_autoselectpause = findViewById(R.id.edittext_autoselectpause);
        edittext_autoselectpause.setText(str);
        edittext_autoselectpause.addTextChangedListener(new MyTextWatcher(edittext_autoselectpause));

        str = String.format(Locale.getDefault(), "%.1f", prefs.getFloat("AutoSelectPeriod", DEFAULT_AUTOSELECTPERIOD));
        edittext_autoselectperiod = findViewById(R.id.edittext_autoselectperiod);
        edittext_autoselectperiod.setText(str);
        edittext_autoselectperiod.addTextChangedListener(new MyTextWatcher(edittext_autoselectperiod));

        radio_keyboard_gridscan = findViewById(R.id.radio_keyboard_gridscan);
        radio_keyboard_pointscan = findViewById(R.id.radio_keyboard_pointscan);
        if(prefs.getBoolean("keyboard_gridscan", DEFAULT_GRIDSCAN_MODE)) {
            radio_keyboard_gridscan.setChecked(true);
            radio_keyboard_pointscan.setChecked(false);
        } else {
            radio_keyboard_gridscan.setChecked(false);
            radio_keyboard_pointscan.setChecked(true);
        }
    }

    public void myClickHandler(View view) {
        SharedPreferences.Editor ed = prefs.edit();

        if(view == radio_keyboard_gridscan) {
            ed.putBoolean("keyboard_gridscan", true);
        } else if(view == radio_keyboard_pointscan) {
            ed.putBoolean("keyboard_gridscan", false);
        }

        ed.apply();

        FaceControlService service;
        service = FaceControlService.sharedServiceInst;
        if(service != null && service.elementSelector != null) {
            service.elementSelector.setKeyboardMode();
        }
    }

    class MyTextWatcher implements TextWatcher {
        EditText editText;

        MyTextWatcher(EditText editText) {
            this.editText = editText;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {

        }

        @Override
        public void afterTextChanged(Editable s) {
            String text = editText.getText().toString();
            text = text.replace(",", ".");

            if(text.length() > 0) {
                try {
                    float f = Float.parseFloat(text);
                    Log.i(TAG, "Found float " + String.format(Locale.getDefault(), "%f", f));

                    SharedPreferences.Editor ed = prefs.edit();
                    if(editText == edittext_scanbandspeed)
                        ed.putFloat("ScanBandSpeed", f);
                    else if(editText == edittext_scanlinespeed)
                        ed.putFloat("ScanLineSpeed", f);
                    else if(editText == edittext_scaninitialpause)
                        ed.putFloat("ScanInitialPause", f);
                    else if(editText == edittext_scanbandwidth)
                        ed.putFloat("ScanBandWidth", f);
                    else if(editText == edittext_autoselectpause)
                        ed.putFloat("AutoSelectPause", f);
                    else if(editText == edittext_autoselectperiod)
                        ed.putFloat("AutoSelectPeriod", f);

                    ed.apply();

                    FaceControlService service = FaceControlService.sharedServiceInst;
                    if(service != null && service.elementSelector != null) {
                        service.elementSelector.updateScanSettings();
                    }
                } catch (Exception e) {
                    Log.i(TAG, "afterTextChanged exception " + e.toString());
                }
            }
        }
    }
}

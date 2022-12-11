package com.obstino.facecontrol;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class SettingsActivity extends AppCompatActivity {
    String TAG = "FaceControl.SettingsActivity";

    SwitchElement [] switchElements = {
            new SwitchElement("Smile", SwitchElement.SwitchType.smile),
            new SwitchElement("Eyebrow raise", SwitchElement.SwitchType.eyebrow),
            new SwitchElement("Eye look left", SwitchElement.SwitchType.look_left),
            new SwitchElement("Eye look right", SwitchElement.SwitchType.look_right),
            new SwitchElement("Left eye wink", SwitchElement.SwitchType.left_wink),
            new SwitchElement("Right eye wink", SwitchElement.SwitchType.right_wink),
            new SwitchElement("Mouth open", SwitchElement.SwitchType.mouth_open),
            new SwitchElement("External switch", SwitchElement.SwitchType.external),
            new SwitchElement("None", SwitchElement.SwitchType.none),
    };

    static class ActionSwitch {
        SwitchAction switchAction;
        String actionSwitchText; // e.g. "Select/Scan switch"
        String sharedPrefsName;	// e.g. "selectswitch_id"
        int sharedPrefsDefault;	// e.g. DEFAULT_SELECTSWITCH

        TextView textview_actionswitch;
        Spinner spinner_actionswitch;
        Button button_actionexternalswitch;
        TextView textview_actionexternalswitch; // used inside updateExternalSwitchTextviews

        ActionSwitch(SwitchAction switchAction, String actionSwitchText, String sharedPrefsName, int sharedPrefsDefault) {
            this.switchAction = switchAction;
            this.actionSwitchText = actionSwitchText;
            this.sharedPrefsName = sharedPrefsName;
            this.sharedPrefsDefault = sharedPrefsDefault;
        }
    }

    static ActionSwitch[] actionSwitchArray = {
            new ActionSwitch(SwitchAction.select, "Select/Scan:", "selectswitch_id", SwitchElement.SwitchType.smile.getValue()),
            new ActionSwitch(SwitchAction.next,"Next/Menu:", "nextswitch_id", SwitchElement.SwitchType.eyebrow.getValue()),
            new ActionSwitch(SwitchAction.prev, "Previous/Menu:", "prevswitch_id", SwitchElement.SwitchType.none.getValue()),
            new ActionSwitch(SwitchAction.scroll_down, "Scroll down:", "scrolldownswitch_id", SwitchElement.SwitchType.none.getValue()),
            new ActionSwitch(SwitchAction.scroll_up, "Scroll up:", "scrollupswitch_id", SwitchElement.SwitchType.none.getValue()),
            new ActionSwitch(SwitchAction.scroll_left, "Scroll left:", "scrollleftswitch_id", SwitchElement.SwitchType.none.getValue()),
            new ActionSwitch(SwitchAction.scroll_right, "Scroll right:", "scrollrightswitch_id", SwitchElement.SwitchType.none.getValue()),
            new ActionSwitch(SwitchAction.zoom_in, "Zoom in:", "zoominswitch_id", SwitchElement.SwitchType.none.getValue()),
            new ActionSwitch(SwitchAction.zoom_out, "Zoom out:", "zoomoutswitch_id", SwitchElement.SwitchType.none.getValue()),
            new ActionSwitch(SwitchAction.rotate_left, "Rotate left:", "rotateleftswitch_id", SwitchElement.SwitchType.none.getValue()),
            new ActionSwitch(SwitchAction.rotate_right, "Rotate right:", "rotaterightswitch_id", SwitchElement.SwitchType.none.getValue()),
            new ActionSwitch(SwitchAction.home, "HOME:", "homeswitch_id", SwitchElement.SwitchType.none.getValue()),
            new ActionSwitch(SwitchAction.back, "BACK:", "backswitch_id", SwitchElement.SwitchType.none.getValue()),
            new ActionSwitch(SwitchAction.recents, "RECENTS:", "recentsswitch_id", SwitchElement.SwitchType.none.getValue()),
            new ActionSwitch(SwitchAction.notifications, "NOTIFICATIONS:", "notificationsswitch_id", SwitchElement.SwitchType.none.getValue()),
            new ActionSwitch(SwitchAction.settings, "SETTINGS:", "settingsswitch_id", SwitchElement.SwitchType.none.getValue()),
            new ActionSwitch(SwitchAction.assistant, "Voice Assistant:", "assistantswitch_id", SwitchElement.SwitchType.none.getValue()),
            new ActionSwitch(SwitchAction.volume_up, "Volume Up:", "volumeupswitch_id", SwitchElement.SwitchType.none.getValue()),
            new ActionSwitch(SwitchAction.volume_down, "Volume Down:", "volumedownswitch_id", SwitchElement.SwitchType.none.getValue()),
    };

    static String BROADCAST_EVENT_NAME = "my-broadcast-event";
    public static final int MSG_SWITCHSET = 0;

    SwitchCompat switch_singleswitch;
    SwitchCompat switch_unlock;
    RadioButton radio_menu_small,
                radio_menu_medium,
                radio_menu_big;
    SwitchCompat switch_assistantpause;
    SwitchCompat switch_eyesclosedpause;

    SharedPreferences prefs;
    static String MY_PREFS_NAME = "FaceControl_Settings";
    static boolean DEFAULT_SWITCHUNLOCK = false;
    static boolean DEFAULT_ASSISTANTPAUSE = true;
    static boolean DEFAULT_EYESCLOSEDPAUSE = false;
    static boolean DEFAULT_SINGLESWITCHMODE = false;
    static int DEFAULT_MENUSIZE = 1;    // 0=small, 1=medium, 2=big

    static class SwitchElement {
        enum SwitchType {
            // shared preferences will actually store some positive integer (keycode) for external switch,
            // while negative values are used for camera switches;
            //    Button press for learning the external switch will set shared preference value to "external (=0)"
            // and accessibility service will simply replace that 0 with the keycode it found and send us a broadcast
            external_waitset(0), // we set this when we're waiting for it to be set to a keycode value
            external(-1),
            none(-2),
            smile(-3),
            eyebrow(-4),
            left_wink(-5),
            right_wink(-6),
            look_left(-7),
            look_right(-8),
            mouth_open(-9);

            private final int value;

            SwitchType(final int newValue) {
                value = newValue;
            }
            public int getValue() { return value; }
        }

        String switchDescr;
        SwitchType switchType;

        SwitchElement(String switchDescr, SwitchType switchType) {
            this.switchDescr = switchDescr;
            this.switchType = switchType;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // register broadcast receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(myBroadcastReceiver,
                new IntentFilter(BROADCAST_EVENT_NAME));

        prefs = getSharedPreferences(MY_PREFS_NAME, MODE_PRIVATE);

        ((Button)findViewById(R.id.button_default)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder dlgAlert  = new AlertDialog.Builder(SettingsActivity.this);
                dlgAlert.setMessage(
                        "Are you sure you want to reset all settings to default values?");
                dlgAlert.setTitle("FaceControl settings");
                dlgAlert.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        resetToDefault();
                    }
                });
                dlgAlert.setNegativeButton("No", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) { }
                });
                dlgAlert.setCancelable(true);
                dlgAlert.create().show();
            }
        });

        ((Button)findViewById(R.id.button_scansettings)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getApplicationContext(), ScanSettingsActivity.class);
                startActivity(intent);
            }
        });

        radio_menu_small = findViewById(R.id.radio_menu_small);
        radio_menu_medium = findViewById(R.id.radio_menu_medium);
        radio_menu_big = findViewById(R.id.radio_menu_big);
        int menuSize = prefs.getInt("MenuSize", DEFAULT_MENUSIZE);

        switch(menuSize) {
            case 0:
                radio_menu_small.setChecked(true);
                break;
            case 1:
                radio_menu_medium.setChecked(true);
                break;
            case 2:
                radio_menu_big.setChecked(true);
                break;
        }

        switch_unlock = findViewById(R.id.switch_unlock);
        switch_unlock.setChecked(prefs.getBoolean("SwitchUnlock", DEFAULT_SWITCHUNLOCK));
        switch_unlock.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SharedPreferences.Editor ed = prefs.edit();
                ed.putBoolean("SwitchUnlock", isChecked);
                ed.apply();
                FaceControlService service = FaceControlService.sharedServiceInst;
                if(service != null && service.elementSelector != null) {
                    service.elementSelector.setSwitchUnlock(isChecked);
                }

                if(isChecked) {
                    AlertDialog.Builder dlgAlert  = new AlertDialog.Builder(SettingsActivity.this);
                    dlgAlert.setMessage(
                            "Warning! This mode only wakes up the device from sleep, and it can't unlock by default.\n"
                            + "To unlock screen, you have to disable screen lock protection in Android settings.");
                    dlgAlert.setTitle("FaceControl settings");
                    dlgAlert.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) { }
                    });
                    dlgAlert.setCancelable(true);
                    dlgAlert.create().show();
                }
            }
        });

        switch_assistantpause = findViewById(R.id.switch_assistantpause);
        switch_assistantpause.setChecked(prefs.getBoolean("AssistantPause", DEFAULT_ASSISTANTPAUSE));
        switch_assistantpause.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SharedPreferences.Editor ed = prefs.edit();
                ed.putBoolean("AssistantPause", isChecked);
                ed.apply();

                FaceControlService service = FaceControlService.sharedServiceInst;
                if(service != null) {
                    service.setAssistantPause(isChecked);
                }
            }
        });

        switch_eyesclosedpause = findViewById(R.id.switch_eyesclosedpause);
        switch_eyesclosedpause.setChecked(prefs.getBoolean("EyesClosedPause", DEFAULT_EYESCLOSEDPAUSE));
        switch_eyesclosedpause.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SharedPreferences.Editor ed = prefs.edit();
                ed.putBoolean("EyesClosedPause", isChecked);
                ed.apply();

                FaceControlService service = FaceControlService.sharedServiceInst;
                if(service != null) {
                    service.setEyesClosedPause(isChecked);
                }
            }
        });

        switch_singleswitch = findViewById(R.id.switch_singleswitch);
        switch_singleswitch.setChecked(prefs.getBoolean("SingleSwitchMode", DEFAULT_SINGLESWITCHMODE));
        switch_singleswitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                // we store SingleSwitchMode in preferences, but in case service is running we directly update it
                SharedPreferences.Editor ed = prefs.edit();
                ed.putBoolean("SingleSwitchMode", isChecked);
                ed.apply();
                FaceControlService service = FaceControlService.sharedServiceInst;
                if(service != null && service.elementSelector != null) {
                    service.elementSelector.setSingleSwitchMode(isChecked);
                }
            }
        });

        ArrayAdapter<String> arrayAdapter;
        arrayAdapter = new ArrayAdapter<String>(this, R.layout.support_simple_spinner_dropdown_item) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                //((TextView)v).setTextSize(...);
                ((TextView)v).setGravity(Gravity.END);
                //((TextView)v).setTextColor(Color.argb(255, 0, 0, 0));
                return v;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                return v;
            }
        };

        for(SwitchElement element : switchElements)
            arrayAdapter.add(element.switchDescr);
/*
        // Switch settings' controls
        spinner_selectswitch = findViewById(R.id.spinner_selectswitch);
        button_selectexternalswitch = findViewById(R.id.button_selectexternalswitch);
        textview_selectexternalswitch = findViewById(R.id.textview_selectexternalswitch);
        setupActionSwitchControls(
                spinner_selectswitch,
                button_selectexternalswitch,
                textview_selectexternalswitch,
                "selectswitch_id",
                DEFAULT_SELECTSWITCH_ID,
                switchElements,
                arrayAdapter
            );

        spinner_nextswitch = findViewById(R.id.spinner_nextswitch);
        button_nextexternalswitch = findViewById(R.id.button_nextexternalswitch);
        textview_nextexternalswitch = findViewById(R.id.textview_nextexternalswitch);
        setupActionSwitchControls(
                spinner_nextswitch,
                button_nextexternalswitch,
                textview_nextexternalswitch,
                "nextswitch_id",
                DEFAULT_NEXTSWITCH_ID,
                switchElements,
                arrayAdapter
            );

        spinner_prevswitch = findViewById(R.id.spinner_prevswitch);
        button_prevexternalswitch = findViewById(R.id.button_prevexternalswitch);
        textview_prevexternalswitch = findViewById(R.id.textview_prevexternalswitch);
        setupActionSwitchControls(
                spinner_prevswitch,
                button_prevexternalswitch,
                textview_prevexternalswitch,
                "prevswitch_id",
                DEFAULT_PREVSWITCH_ID,
                switchElements,
                arrayAdapter
        );
*/
        // Dodajmo en set elementov temu constraint layoutu :) !!!
        float scale = getResources().getDisplayMetrics().density;

        ConstraintLayout constraintLayout = findViewById(R.id.constraint_layout);

        View topElement = findViewById(R.id.textview_switchsettings); //findViewById(R.id.button_prevexternalswitch);

        for(ActionSwitch actionSwitch : actionSwitchArray) {
            TextView textview_actionswitch = new TextView(this);
            textview_actionswitch.setText(actionSwitch.actionSwitchText);
            int textview_actionswitch_id = View.generateViewId();
            textview_actionswitch.setId(textview_actionswitch_id);
            constraintLayout.addView(textview_actionswitch);

            Spinner spinner_actionswitch = new Spinner(this);
            int spinner_actionswitch_id = View.generateViewId();
            spinner_actionswitch.setId(spinner_actionswitch_id);
            constraintLayout.addView(spinner_actionswitch);

            Button button_actionexternalswitch = new Button(this);
            int button_actionexternalswitch_id = View.generateViewId();
            button_actionexternalswitch.setId(button_actionexternalswitch_id);
            button_actionexternalswitch.setText("Set External Switch");
            constraintLayout.addView(button_actionexternalswitch);

            TextView textview_actionexternalswitch = new TextView(this);
            textview_actionexternalswitch.setText("(KEYCODE_SPACE)");
            int textview_actionexternalswitch_id = View.generateViewId();
            textview_actionexternalswitch.setId(textview_actionexternalswitch_id);
            constraintLayout.addView(textview_actionexternalswitch);

            ConstraintSet set = new ConstraintSet();
            set.clone(constraintLayout);

            set.connect(textview_actionswitch_id, ConstraintSet.TOP, topElement.getId(), ConstraintSet.BOTTOM);
            set.connect(textview_actionswitch_id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START);
            set.connect(textview_actionswitch_id, ConstraintSet.END, spinner_actionswitch_id, ConstraintSet.START);

            set.connect(spinner_actionswitch_id, ConstraintSet.TOP, topElement.getId(), ConstraintSet.BOTTOM);
            set.connect(spinner_actionswitch_id, ConstraintSet.START, textview_actionswitch_id, ConstraintSet.END);
            set.connect(spinner_actionswitch_id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);

            set.connect(button_actionexternalswitch_id, ConstraintSet.TOP, textview_actionswitch_id, ConstraintSet.BOTTOM);
            set.connect(button_actionexternalswitch_id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START);
            set.connect(button_actionexternalswitch_id, ConstraintSet.END, textview_actionexternalswitch_id, ConstraintSet.START);

            set.connect(textview_actionexternalswitch_id, ConstraintSet.TOP, button_actionexternalswitch_id, ConstraintSet.TOP);
            set.connect(textview_actionexternalswitch_id, ConstraintSet.BOTTOM, button_actionexternalswitch_id, ConstraintSet.BOTTOM);
            set.connect(textview_actionexternalswitch_id, ConstraintSet.START, button_actionexternalswitch_id, ConstraintSet.END);
            set.connect(textview_actionexternalswitch_id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);

            int[] chain1_ids = {textview_actionswitch_id, spinner_actionswitch_id};
            set.createHorizontalChain(
                    textview_actionswitch_id, ConstraintSet.LEFT,
                    spinner_actionswitch_id, ConstraintSet.RIGHT,
                    chain1_ids,
                    null,
                    ConstraintSet.CHAIN_PACKED);

            int[] chain2_ids = {button_actionexternalswitch_id, textview_actionexternalswitch_id};
            set.createHorizontalChain(
                    button_actionexternalswitch_id, ConstraintSet.LEFT,
                    textview_actionexternalswitch_id, ConstraintSet.RIGHT,
                    chain2_ids,
                    null,
                    ConstraintSet.CHAIN_PACKED);

            set.applyTo(constraintLayout);

            actionSwitch.textview_actionswitch = textview_actionswitch;
            actionSwitch.spinner_actionswitch = spinner_actionswitch;
            actionSwitch.button_actionexternalswitch = button_actionexternalswitch;
            actionSwitch.textview_actionexternalswitch = textview_actionexternalswitch;

            topElement = button_actionexternalswitch;
        }

        for(ActionSwitch actionSwitch: actionSwitchArray) {
            ConstraintLayout.LayoutParams layoutParams;

            layoutParams = (ConstraintLayout.LayoutParams) actionSwitch.textview_actionswitch.getLayoutParams();
            layoutParams.topMargin = (int) (20.0f * scale);
            layoutParams.rightMargin = (int)(5.0f * scale);
            actionSwitch.textview_actionswitch.setLayoutParams(layoutParams);

            layoutParams = (ConstraintLayout.LayoutParams) actionSwitch.spinner_actionswitch.getLayoutParams();
            //layoutParams.leftMargin = (int) (20.0f * scale);
            layoutParams.topMargin = (int) (20.0f * scale);
            actionSwitch.spinner_actionswitch.setLayoutParams(layoutParams);

            layoutParams = (ConstraintLayout.LayoutParams) actionSwitch.button_actionexternalswitch.getLayoutParams();
            layoutParams.topMargin = (int) (10.0f * scale);
            actionSwitch.button_actionexternalswitch.setLayoutParams(layoutParams);

            layoutParams = (ConstraintLayout.LayoutParams) actionSwitch.textview_actionexternalswitch.getLayoutParams();
            layoutParams.leftMargin = (int) (10.0f * scale);
            actionSwitch.textview_actionexternalswitch.setLayoutParams(layoutParams);

            setupActionSwitchControls(
                    actionSwitch.spinner_actionswitch,
                    actionSwitch.button_actionexternalswitch,
                    actionSwitch.textview_actionexternalswitch,
                    actionSwitch.sharedPrefsName,
                    actionSwitch.sharedPrefsDefault,
                    switchElements,
                    arrayAdapter
            );
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(myBroadcastReceiver);
    }

    void resetToDefault()
    {
        SharedPreferences.Editor ed = prefs.edit();

        ed.putBoolean("SwitchUnlock", DEFAULT_SWITCHUNLOCK);
        ed.putBoolean("SingleSwitchMode", DEFAULT_SINGLESWITCHMODE);
        ed.putInt("MenuSize", DEFAULT_MENUSIZE);

        for(ActionSwitch actionSwitch : actionSwitchArray)
            ed.putInt(actionSwitch.sharedPrefsName, actionSwitch.sharedPrefsDefault);

        ed.putFloat("ScanBandSpeed", ScanSettingsActivity.DEFAULT_SCANBANDSPEED);
        ed.putFloat("ScanLineSpeed", ScanSettingsActivity.DEFAULT_SCANLINESPEED);
        ed.putFloat("ScanInitialPause", ScanSettingsActivity.DEFAULT_SCANINITIALPAUSE);
        ed.putFloat("ScanBandWidth", ScanSettingsActivity.DEFAULT_SCANBANDWIDTH);
        ed.putInt("ScanLoopCount", ScanSettingsActivity.DEFAULT_SCANLOOPCOUNT);
        ed.putFloat("AutoSelectPause", ScanSettingsActivity.DEFAULT_AUTOSELECTPAUSE);
        ed.putFloat("AutoSelectPeriod", ScanSettingsActivity.DEFAULT_AUTOSELECTPERIOD);
        ed.putBoolean("keyboard_gridscan", ScanSettingsActivity.DEFAULT_GRIDSCAN_MODE);

        ed.putInt("smile_sensitivity", GestureCalibrationActivity.DEFAULT_SMILE_SENSITIVITY);
        ed.putInt("eyebrow_sensitivity", GestureCalibrationActivity.DEFAULT_EYEBROW_SENSITIVITY);
        ed.putInt("mouth_sensitivity", GestureCalibrationActivity.DEFAULT_MOUTH_SENSITIVITY);
        ed.putInt("lookleft_sensitivity", GestureCalibrationActivity.DEFAULT_LOOKLEFT_SENSITIVITY);
        ed.putInt("lookright_sensitivity", GestureCalibrationActivity.DEFAULT_LOOKRIGHT_SENSITIVITY);
        ed.putInt("wink_sensitivity", GestureCalibrationActivity.DEFAULT_WINK_SENSITIVITY);
        ed.putFloat("wink_minduration", GestureCalibrationActivity.DEFAULT_WINK_MINDURATION);

        ed.apply();

        // #########################
        // ##### Update views ######
        // #########################

        // First Update spinners
        for(ActionSwitch actionSwitch : actionSwitchArray) {
            int actionswitch_id = actionSwitch.sharedPrefsDefault;
            //if(actionswitch_id >= 0)
            //    actionswitch_id = SwitchElement.SwitchType.external.getValue();
            for(int k = 0; k < switchElements.length; k++) {
                if (switchElements[k].switchType.getValue() == actionswitch_id) {
                    actionSwitch.spinner_actionswitch.setSelection(k);
                    break;
                }
            }
        }

        // Update switches
        switch_singleswitch.setChecked(DEFAULT_SINGLESWITCHMODE);
        switch_unlock.setChecked(DEFAULT_SWITCHUNLOCK);

        // Update radio buttons
        int menuSize = DEFAULT_MENUSIZE;
        switch(menuSize) {
            case 0:
                radio_menu_small.setChecked(true);
                break;
            case 1:
                radio_menu_medium.setChecked(true);
                break;
            case 2:
                radio_menu_big.setChecked(true);
                break;
        }

        // ##################################
        // ##### Update service values ######
        // ##################################

        FaceControlService service = FaceControlService.sharedServiceInst;
        if(service != null) {
            if(service.elementSelector != null) {
                service.elementSelector.setSwitchUnlock(DEFAULT_SWITCHUNLOCK);
                service.elementSelector.setSingleSwitchMode(DEFAULT_SINGLESWITCHMODE);
                service.elementSelector.prepareMenu();
                service.elementSelector.setKeyboardMode();
                service.elementSelector.updateScanSettings();
            }

            if(service.gestureRecognizer != null) {
                service.gestureRecognizer.initializeSensitivities();
            }
        }
    }

    void setupActionSwitchControls(
            Spinner spinner_actionswitch,
            Button button_actionexternalswitch,
            TextView textview_actionexternalswitch,
            String actionSwitchPrefsName,
            int DEFAULT_ACTIONSWITCH_ID,
            SwitchElement [] switchElements,
            ArrayAdapter<String> arrayAdapter)
    {
        button_actionexternalswitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences.Editor ed = prefs.edit();
                ed.putInt(actionSwitchPrefsName, SwitchElement.SwitchType.external_waitset.getValue());
                ed.apply();
                Toast.makeText(getApplicationContext(), "Please activate the switch", Toast.LENGTH_LONG).show();
            }
        });


        spinner_actionswitch.setAdapter(arrayAdapter);
        spinner_actionswitch.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Log.i(TAG, "Item selected: " + switchElements[position].switchDescr);
                if(switchElements[position].switchType == SwitchElement.SwitchType.external) {
                    button_actionexternalswitch.setVisibility(View.VISIBLE);
                    textview_actionexternalswitch.setVisibility(View.VISIBLE);
                    if(prefs.getInt(actionSwitchPrefsName, DEFAULT_ACTIONSWITCH_ID) < 0) {
                        SharedPreferences.Editor ed = prefs.edit();
                        ed.putInt(actionSwitchPrefsName, SwitchElement.SwitchType.external.getValue());
                        ed.apply();
                    }
                    updateExternalSwitchTextviews();
                } else {
                    button_actionexternalswitch.setVisibility(View.GONE);
                    textview_actionexternalswitch.setVisibility(View.GONE);
                    SharedPreferences.Editor ed = prefs.edit();
                    ed.putInt(actionSwitchPrefsName, switchElements[position].switchType.getValue());
                    ed.apply();
                }
                checkSpinnersForCameraSwitchesAndControlCamera();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        // but wait, theres more!!
        int actionswitch_id = prefs.getInt(actionSwitchPrefsName, DEFAULT_ACTIONSWITCH_ID);
        if(actionswitch_id >= 0)
            actionswitch_id = SwitchElement.SwitchType.external.getValue();
        for(int k = 0; k < switchElements.length; k++) {
            if(switchElements[k].switchType.getValue() == actionswitch_id) {
                spinner_actionswitch.setSelection(k);
                break;
            }
        }
    }

    void checkSpinnersForCameraSwitchesAndControlCamera() {
        FaceControlService service;
        service = FaceControlService.sharedServiceInst;
        if(service == null || service.startState != StartState.started)
            return;

        // we use cameraSwitchIdList to see if we should start camera or not
        ArrayList<Integer> cameraSwitchIdList = new ArrayList<>();
        cameraSwitchIdList.add(SwitchElement.SwitchType.smile.getValue());
        cameraSwitchIdList.add(SwitchElement.SwitchType.eyebrow.getValue());
        cameraSwitchIdList.add(SwitchElement.SwitchType.left_wink.getValue());
        cameraSwitchIdList.add(SwitchElement.SwitchType.right_wink.getValue());
        cameraSwitchIdList.add(SwitchElement.SwitchType.look_left.getValue());
        cameraSwitchIdList.add(SwitchElement.SwitchType.look_right.getValue());
        cameraSwitchIdList.add(SwitchElement.SwitchType.mouth_open.getValue());

        boolean cameraSwitchFound = false;
        for(ActionSwitch actionSwitch : actionSwitchArray) {
            int switchIdNumber = prefs.getInt(actionSwitch.sharedPrefsName, actionSwitch.sharedPrefsDefault);
            if(cameraSwitchIdList.contains(switchIdNumber)) {
                cameraSwitchFound = true;
                break;
            }
        }

        if(cameraSwitchFound) {
            Log.i(TAG, "There are some camera switches. Starting camera");
            service.startCamera();
        } else {
            Log.i(TAG, "There are no camera switches. Stopping camera");
            service.stopCamera();
        }

        /*int selectswitch_id = prefs.getInt("selectswitch_id", DEFAULT_SELECTSWITCH_ID);
        int nextswitch_id = prefs.getInt("nextswitch_id", DEFAULT_NEXTSWITCH_ID);
        if(cameraSwitchIdList.contains(selectswitch_id) || cameraSwitchIdList.contains(nextswitch_id)) {
            Log.i(TAG, "There are some camera switches. Starting camera");
            service.startCamera();
        } else {
            Log.i(TAG, "There are no camera switches. Stopping camera");
            service.stopCamera();
        }*/
    }

    public void myClickHandler(View view) {
        boolean updateMenuSize = false;
        SharedPreferences.Editor ed = prefs.edit();

        if(view == radio_menu_small) {
            ed.putInt("MenuSize", 0);
            updateMenuSize = true;
        } else if(view == radio_menu_medium) {
            ed.putInt("MenuSize", 1);
            updateMenuSize = true;
        }if(view == radio_menu_big) {
            ed.putInt("MenuSize", 2);
            updateMenuSize = true;
        }

        ed.apply();

        if(updateMenuSize) {
            FaceControlService service;
            service = FaceControlService.sharedServiceInst;
            if(service != null && service.elementSelector != null) {
                service.elementSelector.prepareMenu();
            }
        }
    }

    void updateExternalSwitchTextviews() {
        for(ActionSwitch actionSwitch: actionSwitchArray) {
            int id = prefs.getInt(actionSwitch.sharedPrefsName, actionSwitch.sharedPrefsDefault);
            actionSwitch.textview_actionexternalswitch.setText(id > 0 ? KeyEvent.keyCodeToString(id) : "Not assigned");
        }
        /*
        int id;
        id = prefs.getInt("selectswitch_id", DEFAULT_SELECTSWITCH_ID);
        textview_selectexternalswitch.setText(id > 0 ? KeyEvent.keyCodeToString(id) : "Not assigned");

        id = prefs.getInt("nextswitch_id", DEFAULT_NEXTSWITCH_ID);
        textview_nextexternalswitch.setText(id>0?KeyEvent.keyCodeToString(id):"Not assigned");
        */
    }

    private BroadcastReceiver myBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int msg = intent.getIntExtra("msg", 0);
            Log.i(TAG, "Received onReceive broadcast value " + msg);
            if(msg == MSG_SWITCHSET) {
                // Update "external switch"-textviews :)
                updateExternalSwitchTextviews();
            }
        }
    };
}
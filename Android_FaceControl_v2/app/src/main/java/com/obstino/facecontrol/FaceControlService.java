package com.obstino.facecontrol;

import static android.content.Context.POWER_SERVICE;
import static java.lang.Math.PI;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.signum;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.AudioManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Stack;

enum ProcessingImage {
    // When .ready, onImageAvailable gives us the Bitmap and sets status to .preprocessing
// When we detect .preprocessing, we start ML code; when ML code finishes, it sets status to .processing
// When we are .processing, we do some image and feature processing and then change status to .drawing
    ready,  // ready to receive new Bitmap object
    preprocessing,  // signal to start ML code
    processing, // processing the Bitmap
    drawing,    // drawing the Bitmap
    done    // done drawing the Bitmap
}

enum SwitchAction {
    select,   // used to initiate actions (scan start & stop scan line, and also select )
    next,   // used to select menu items (home, back, recents, notifications, scroll, zoom,...)
            // NOTE: If no switch is assigned to menu, it will auto-select (scan) from items,
            // until it gets to end of list (then menu disappears)
    prev,   // either opens menu or selects previous item
    scroll_down,
    scroll_up,
    scroll_left,
    scroll_right,
    zoom_in,
    zoom_out,
    rotate_left,
    rotate_right,
    home,
    back,
    recents,
    notifications,
    settings,
    assistant,
    volume_up,
    volume_down
}

class SwitchId {
    String sharedPrefsName;	// e.g. "selectswitch_id"
    int sharedPrefsDefault;	// e.g. SettingsActivity.DEFAULT_SELECTSWITCH
    SwitchAction switchAction;  // e.g. SwitchAction.select

    SwitchId(String sharedPrefsName, int sharedPrefsDefault, SwitchAction switchAction) {
        this.sharedPrefsName = sharedPrefsName;
        this.sharedPrefsDefault = sharedPrefsDefault;
        this.switchAction = switchAction;
    }
};

enum StartState {
    started,
    stopping,
    stopped
}

enum CameraState {
    ready,  // ready to receive signal to open camera
    opened, // successfully opened camera using CameraCapture class
    error   // error opening camera
}

// Start state to restore after exiting "Calibration & Test"
enum TestReturnState {
    none,
    stopcamera,
    stopall
};

public class FaceControlService extends AccessibilityService {
    static FaceControlService sharedServiceInst = null;
    Bitmap drawBitmap = null;
    final Object drawLock = new Object();

    String TAG = "FaceControl.Service";
    Context context;
    ElementSelector elementSelector;
    SharedPreferences prefs;

    boolean screenOff = false;
    boolean activityForeground = false; // disable switch control when GestureTestActivity is opened

    boolean assistantPause; // if true, switches are temp. disabled when assistant opens

    boolean eyesClosedPause;    // if true, switch control is paused/resumed when both eyes closed for 5s
    TextToSpeech textToSpeech;
    boolean textToSpeechReady = false;
    boolean pauseSwitchControl = false; // switch control paused if true

    boolean lowResMode = false;

    Thread cameraThread;
    //boolean started = false;

    Thread startStateThread;
    StartState startState = StartState.stopped;
    CameraState cameraState = CameraState.ready;
    TestReturnState testReturnState = TestReturnState.none;
    boolean initStop = false;

    final Object lock = new Object();
    Bitmap cameraBitmap = null;
    Bitmap cameraScaledBitmap = null;
    Bitmap eyeBitmap = null;
    ProcessingImage processingImage;
    List<Face> faceList;

    int sensorOrientationDegrees;
    GestureRecognizer gestureRecognizer;

    int SX, SY;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Service connected!");

        context = this;
        sharedServiceInst = this;
        prefs = getSharedPreferences(SettingsActivity.MY_PREFS_NAME, Context.MODE_PRIVATE);
        registerScreenBroadcast();
        getScreenResolution();
        createElementSelector();
        //runStartStateThread(); // must run this *after* creating elementSelector
        gestureRecognizer = new GestureRecognizer(context);
        addView();

        setLowResMode(prefs.getBoolean("LowResMode", GestureCalibrationActivity.DEFAULT_LOWRES_MODE));
        setAssistantPause(prefs.getBoolean("AssistantPause", SettingsActivity.DEFAULT_ASSISTANTPAUSE));
        setEyesClosedPause(prefs.getBoolean("EyesClosedPause", SettingsActivity.DEFAULT_EYESCLOSEDPAUSE));

        textToSpeech = new TextToSpeech(context, onInitListener);
        // AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        // now retrieve window content

        //AccessibilityNodeInfo nodeInfo = getRootInActiveWindow();
        //Log.i(TAG, "Node info: " + nodeInfo.toString());
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(screenBroadcastReceiver);

        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }

        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null) {
            switch (intent.getIntExtra("command", -1)) {
                /*case MainActivity.COMMAND_STARTCAMERA:
                    Log.i(TAG, "COMMAND_STARTCAMERA received");
                    if(cameraThread == null) {
                        Log.i(TAG, "Creating camera thread");
                        cameraThread = new Thread(Thr_Camera);
                        cameraThread.start();
                    }
                    break;*/
                case MainActivity.COMMAND_STARTSTOP: {
                    if (startState == StartState.stopped) {
                        Log.i(TAG, "Stopped; calling service.startAll");
                        // startAll also sends a broadcast back to UI thread
                        Thread thread = new Thread_startAll(false);
                        thread.start();
                        //startAll(false);
                    } else if (startState == StartState.started) {
                        Log.i(TAG, "Started; calling service.stopAll");
                        // stopAll also sends a broadcast back to UI thread
                        Thread thread = new Thread_stopAll(false);
                        thread.start();
                        //stopAll();
                    } // else if(service.startState == StartState.stopping) { ... }

                } break;

                case MainActivity.COMMAND_FACETEST_START: {
                    Thread thread = new Thread(faceTestStart);
                    thread.start();
                } break;
                case MainActivity.COMMAND_FACETEST_STOP: {
                    Thread thread = new Thread(faceTestStop);
                    thread.start();
                } break;
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    void speak(String text) {
        if(textToSpeechReady) {
            textToSpeech.speak(text,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "speak_id");
        }
    }

    TextToSpeech.OnInitListener onInitListener = new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            if(status == TextToSpeech.SUCCESS) {
                int result = textToSpeech.setLanguage(Locale.US);
                if(result == TextToSpeech.LANG_NOT_SUPPORTED ||
                   result == TextToSpeech.LANG_MISSING_DATA) {
                    Log.i(TAG, "Text to speech onInit error");
                } else {
                    textToSpeechReady = true;
                }
            }
        }
    };
    /*void runStartStateThread() {
        if(startStateThread == null) {
            startStateThread = new Thread(Thr_StartState);
            startStateThread.start();
        }
    }

    Runnable Thr_StartState = new Runnable() {
        @Override
        public void run() {
            while(true) {
                // code changes start state from 'stopping' to 'stopped' when appropriate
                if (startState == StartState.stopping) {
                    if (elementSelector != null) {
                        if (!initStop && elementSelector.initStop == 0) {
                            Log.i(TAG, "NOW we're fully stopped");
                            startState = StartState.stopped;
                        }
                    }
                }

                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    };*/

    void togglePauseState() {
        synchronized (lock) {
            pauseSwitchControl = !pauseSwitchControl;
            if(pauseSwitchControl) {
                elementSelector.setIdleState();
                speak("Face Control paused");
            } else {
                speak("Face Control resumed");
            }
        }
    }

    void clearPauseState() {
        synchronized (lock) {
            pauseSwitchControl = false;
        }
    }

    void setAssistantPause(boolean enable) {
        synchronized(lock) {
            assistantPause = enable;
        }
    }

    void setEyesClosedPause(boolean enable) {
        synchronized(lock) {
            eyesClosedPause = enable;

            if(!eyesClosedPause) {
                // clear pause state (resume FaceControl) in case user disabled this functionality
                clearPauseState();
            }
        }
    }

    void setLowResMode(boolean mode) {
        synchronized(lock) {
            lowResMode = mode;
        }
    }

    void setActivityForeground(boolean foreground) {
        synchronized (lock) {
            activityForeground = foreground;
            if(foreground && elementSelector != null) {
                elementSelector.setIdleState();
            }
        }
    }

    void registerScreenBroadcast() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenBroadcastReceiver, intentFilter);
    }

    private BroadcastReceiver screenBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String intentAction = intent.getAction();
            if(intentAction.equals(Intent.ACTION_SCREEN_OFF)) {
                Log.i(TAG, "Received screen broadcast ACTION_SCREEN_OFF");
                screenOff = true;
            } else if(intentAction.equals(Intent.ACTION_SCREEN_ON)) {
                Log.i(TAG, "Received screen broadcast ACTION_SCREEN_ON");
                screenOff = false;
            }
        }
    };

    void searchThroughNode(AccessibilityNodeInfo nodeInfo) {
        if(nodeInfo == null)
            Log.i(TAG, "nodeInfo = null");
        Log.i(TAG, "nodeInfo: " + nodeInfo.toString());
        for(int k = 0; k < nodeInfo.getChildCount(); k++)
            searchThroughNode(nodeInfo.getChild(k));
    }

    String lastActivePackage;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {

        if(event.getPackageName() == null)
            return;

        if(assistantPause) {
            AccessibilityNodeInfo activeWindowRoot = getRootInActiveWindow();
            if (activeWindowRoot != null && activeWindowRoot.getPackageName() != null) {
                String activePackage = activeWindowRoot.getPackageName().toString();
                if (!activePackage.equals(lastActivePackage)) {
                    Log.i(TAG, "active package = " + activePackage);

                    List<String> assistantPackages = Arrays.asList(
                            "com.samsung.android.bixby.agent",  // Bixby
                            "com.google.android.googlequicksearchbox",  // Google Assistant
                            "com.amazon.dee.app"    // Alexa
                    );

                    // check if previous opened window was an assistant (& current isn't another assistant)
                    if (assistantPackages.contains(lastActivePackage) && !assistantPackages.contains(activePackage)) {
                        // disable assistant timeout!
                        setActivityForeground(false);
                        elementSelector.setIdleState();
                    }

                    lastActivePackage = activePackage;
                    // check if current opened window is an assistant; enable timeout if yes
                    if (assistantPackages.contains(activePackage)) {
                        if (!activityForeground) {
                            setActivityForeground(true);
                            elementSelector.startAssistantTimeout();
                        }
                    }
                }
            }
        }
        //Log.i(TAG, "Got event " + event.toString());

        if(event.getPackageName().equals("com.google.android.inputmethod.latin") &&
            event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        {
            //if(elementSelector != null)
            //    elementSelector.updateKeyboard();
        }
    }

    void handleCameraError() {
        Log.i(TAG, "ERROR OPENING CAMERA");

        /*
        // We don't need this code because 'all' hasn't started in the first place
        stopAll();
        while(startState != StartState.stopped) {   // TODO (!) Implement startState function not thread
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }*/

        sendBroadcastMessage(MainActivity.MSG_CAMERAOFF);
    }

    class Thread_startAll extends Thread {
        boolean faceTest;

        Thread_startAll(boolean faceTest) {
            this.faceTest = faceTest;
        }

        @Override
        public void run() {
            // this function assumes that its caller will check the "startState" variable before calling it
            Log.i(TAG, "(NOT) STARTING ALL");

            if(faceTest) {
                Log.i(TAG, "Force-starting camera");
                if(!startCamera()) {
                    testReturnState = TestReturnState.none;
                    handleCameraError(); // send error broadcast
                    return;
                }
            } else {
                ArrayList<Integer> cameraValues = new ArrayList<>();
                cameraValues.add(SettingsActivity.SwitchElement.SwitchType.smile.getValue());
                cameraValues.add(SettingsActivity.SwitchElement.SwitchType.eyebrow.getValue());
                cameraValues.add(SettingsActivity.SwitchElement.SwitchType.left_wink.getValue());
                cameraValues.add(SettingsActivity.SwitchElement.SwitchType.right_wink.getValue());
                cameraValues.add(SettingsActivity.SwitchElement.SwitchType.look_left.getValue());
                cameraValues.add(SettingsActivity.SwitchElement.SwitchType.look_right.getValue());
                cameraValues.add(SettingsActivity.SwitchElement.SwitchType.mouth_open.getValue());

                for (SettingsActivity.ActionSwitch actionSwitch : SettingsActivity.actionSwitchArray) {
                    int switchIdNumber = prefs.getInt(actionSwitch.sharedPrefsName, actionSwitch.sharedPrefsDefault);
                    if (cameraValues.contains(switchIdNumber)) {
                        Log.i(TAG, "Yes we have a camera switch");
                        if(!startCamera()) {
                            handleCameraError(); // send error broadcast
                            return;
                        }
                        break;
                    }
                }
            }

            elementSelector.start();
            //started = true;
            startState = StartState.started;    // todo: make sure this assumption won't cause a bug

            if(!faceTest) {
                // send broadcast telling that it all started
                sendBroadcastMessage(MainActivity.MSG_STARTED);
            } else {
                sendBroadcastMessage(MainActivity.MSG_FACETEST_STARTED);
            }
        }
    }

    class Thread_stopAll extends Thread {
        boolean faceTest;

        Thread_stopAll(boolean faceTest) {
            this.faceTest = faceTest;
        }

        @Override
        public void run() {
            // this function assumes that its caller will check "startState" variable before calling it
            Log.i(TAG, "STOPPING ALL");
            clearPauseState();
            stopCamera();
            elementSelector.stop();

            startState = StartState.stopping;

            while(true) {
                // code changes start state from 'stopping' to 'stopped' when appropriate
                if (elementSelector != null) {
                    if (!initStop && elementSelector.initStop == 0) {
                        Log.i(TAG, "NOW we're fully stopped");
                        startState = StartState.stopped;
                        break;  // exit the while loop
                    }
                }

                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            if(!faceTest) {
                // send broadcast telling that it all stopped
                sendBroadcastMessage(MainActivity.MSG_STOPPED);
            } else {
                testReturnState = TestReturnState.none;
                sendBroadcastMessage(MainActivity.MSG_FACETEST_STOPPED);
            }
        }
    }

    void sendBroadcastMessage(int message) {
        Intent intent = new Intent(SettingsActivity.BROADCAST_EVENT_NAME);
        intent.putExtra("msg", message);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    Runnable faceTestStart = new Runnable() {
        @Override
        public void run() {
            if(startState == StartState.started) {
                if(cameraThread != null) {
                    testReturnState = TestReturnState.none;
                    sendBroadcastMessage(MainActivity.MSG_FACETEST_STARTED);
                }
                else {
                    if(startCamera()) {
                        testReturnState = TestReturnState.stopcamera;
                        sendBroadcastMessage(MainActivity.MSG_FACETEST_STARTED);
                    }
                    else {
                        testReturnState = TestReturnState.none;
                        sendBroadcastMessage(MainActivity.MSG_CAMERAOFF);
                    }
                }
            } else {
                testReturnState = TestReturnState.stopall;
                // starts all (force-starts camera because of faceTest mode) and sends broadcast
                Thread thread = new Thread_startAll(true);
                thread.start();
            }
        }
    };

    Runnable faceTestStop = new Runnable() {
        @Override
        public void run() {
            switch(testReturnState) {
                case none:
                    Log.i(TAG, "Returning state: none");
                    testReturnState = TestReturnState.none;
                    sendBroadcastMessage(MainActivity.MSG_FACETEST_STOPPED);
                    break;
                case stopcamera:
                    Log.i(TAG, "Returning state: stopcamera");
                    stopCamera();
                    testReturnState = TestReturnState.none;
                    sendBroadcastMessage(MainActivity.MSG_FACETEST_STOPPED);
                    break;
                case stopall:
                    Log.i(TAG, "Returning state: stopall");
                    Thread thread = new Thread_stopAll(true);
                    thread.start();
                    break;
            }
        }
    };

    boolean startCamera() {
        if(cameraThread == null) {
            // put cameraState to 'ready'... continue when it changes to 'opened' or 'error'
            cameraState = CameraState.ready;

            Log.i(TAG, "Creating camera thread");
            cameraThread = new Thread(Thr_Camera);
            cameraThread.start();

            // wait for cameraState to change
            while (cameraState == CameraState.ready) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            if (cameraState == CameraState.error) {
                // wait for cameraThread to stop (just to keep everything synchronous)
                while (cameraThread != null) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                return false;   // tell'em about the error
            } else {
                return true;    // tell'em about the success
            }
        } else {
            if(cameraState == CameraState.opened)
                return true;
            else if(cameraState == CameraState.error)
                return false;
            else // this case shouldn't ever occur
                return false;
        }
    }

    void stopCamera() {
        if(cameraThread != null) {
            Log.i(TAG, "setting initStop and waiting for thread to finish");
            initStop = true;

            // wait for camera to stop
            while(cameraThread != null) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            cameraState = CameraState.ready;
            Log.i(TAG, "thread finished");
        } else {
            Log.i(TAG, "what?? cameraThread = null");
        }
    }


    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        Intent intent;

        Log.i(TAG, "onKeyEvent: " + KeyEvent.keyCodeToString(event.getKeyCode()));

        /*int selectSwitchId;
        int nextSwitchId;
        selectSwitchId = prefs.getInt("selectswitch_id", SettingsActivity.DEFAULT_SELECTSWITCH_ID);
        nextSwitchId = prefs.getInt("nextswitch_id", SettingsActivity.DEFAULT_NEXTSWITCH_ID);
        */

        switch(event.getAction()) {
            case KeyEvent.ACTION_DOWN: {
                int keyId = event.getKeyCode();
                for(SettingsActivity.ActionSwitch actionSwitch: SettingsActivity.actionSwitchArray) {
                    if (keyId == prefs.getInt(actionSwitch.sharedPrefsName, actionSwitch.sharedPrefsDefault)) {
                        elementSelector.activateSwitch(actionSwitch.switchAction);
                        return true;
                    }
                }

                for(SettingsActivity.ActionSwitch actionSwitch: SettingsActivity.actionSwitchArray) {
                    int switchIdNumber = prefs.getInt(actionSwitch.sharedPrefsName, actionSwitch.sharedPrefsDefault);
                    if (switchIdNumber == 0) {
                        SharedPreferences.Editor ed = prefs.edit();
                        ed.putInt(actionSwitch.sharedPrefsName, keyId);
                        ed.apply();

                        sendBroadcastMessage(SettingsActivity.MSG_SWITCHSET);
                        /*intent = new Intent(SettingsActivity.BROADCAST_EVENT_NAME);
                        intent.putExtra("msg", SettingsActivity.MSG_SWITCHSET);
                        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);*/
                        return true;
                    }
                }
            } break;

            case KeyEvent.ACTION_UP: {
                int keyId = event.getKeyCode();
                for(SettingsActivity.ActionSwitch actionSwitch: SettingsActivity.actionSwitchArray) {
                    int switchIdNumber = prefs.getInt(actionSwitch.sharedPrefsName, actionSwitch.sharedPrefsDefault);
                    if(switchIdNumber == keyId)
                        return true;
                }
            } break;
        }
        return super.onKeyEvent(event);
    }

    @Override
    public void onInterrupt() {

    }

    Runnable Thr_Camera = new Runnable() {
        @Override
        public void run() {
            int frameCounter;
            long time1, time2;

            gestureRecognizer.firstRun = true;
            gestureRecognizer.g_FPS = 0;

            CameraCapture cameraCapture = new CameraCapture(getApplicationContext());
            boolean ret = cameraCapture.openCamera(onImageAvailableListener);
            if(!ret) {
                cameraState = CameraState.error;
                cameraThread = null;
                return;
            } else {
                cameraState = CameraState.opened;
            }

            sensorOrientationDegrees = cameraCapture.getSensorOrientationDegrees();
            cameraCapture.startRepeatCapture();

            initFaceDetector();

            processingImage = ProcessingImage.ready;
            frameCounter = 0;
            time1 = time2 = SystemClock.elapsedRealtime();

            while(true) {
                // Wait until we have an image to process (todo: alternatively make a looper)
                if(initStop) {
                    Log.i(TAG, "Stopping camera thread");
                    cameraCapture.stop();
                    initStop = false;
                    cameraThread = null;
                    break;
                }

                while(true) {
                    synchronized (lock) {
                        if(processingImage == ProcessingImage.preprocessing)
                            break;
                    }
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                //Log.i(TAG, "calling startDetector");
                startDetector();
                // wait for ML callback to set status to .processing
                while(true) {
                    synchronized (lock) {
                        if(processingImage == ProcessingImage.processing)
                            break;
                    }
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                processImage();

                processingImage = ProcessingImage.ready;

                frameCounter++;
                time2 = SystemClock.elapsedRealtime();
                if(time2 - time1 > 1000) {
                    gestureRecognizer.g_FPS = frameCounter;
                    frameCounter = 0;
                    time1 = SystemClock.elapsedRealtime();
                    Log.i(TAG, "g_FPS = " + gestureRecognizer.g_FPS);
                }
            }
        }
    };

    void processImage()
    {
        // Do some feature processing
        if(faceList.size() > 0) {
            if(!gestureRecognizer.faceFound)
                gestureRecognizer.faceFound = true;

            gestureRecognizer.detectSmile(faceList);
            gestureRecognizer.detectEyebrowRaise(faceList);
            gestureRecognizer.detectEyeClosed(faceList);
            gestureRecognizer.detectEyeGaze(faceList, cameraScaledBitmap);
            gestureRecognizer.detectMouthOpen(faceList);

            if(gestureRecognizer.g_FPS > 0 && gestureRecognizer.firstRun)
                gestureRecognizer.firstRun = false;
        } else {
            gestureRecognizer.firstRun = true;

            if(gestureRecognizer.faceFound)
                gestureRecognizer.faceFound = false;
        }
    }

    FaceDetector detector;

    void initFaceDetector()
    {
        FaceDetectorOptions options =
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                        //.setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                        .build();
        detector = FaceDetection.getClient(options);
    }

    void startDetector() {
        InputImage image = InputImage.fromBitmap(cameraScaledBitmap, 0);
        detector.process(image)
                .addOnSuccessListener(onSuccessListener)
                .addOnFailureListener(onFailureListener);
    }

    OnSuccessListener<List<Face>> onSuccessListener = new OnSuccessListener<List<Face>>() {
        @Override
        public void onSuccess(List<Face> faces) {
            // Log.i(TAG, "onSuccess");
            faceList = faces;
            // Draw the image
            synchronized (lock) {
                processingImage = ProcessingImage.processing;
            }
        }
    };

    OnFailureListener onFailureListener = new OnFailureListener() {
        @Override
        public void onFailure(@NonNull Exception e) {
            // Task failed with an exception
            Log.i(TAG, "onFailure");
            synchronized (lock) {
                processingImage = ProcessingImage.done;
            }
            e.printStackTrace();
        }
    };

    int getCameraRotation() {
        int deviceOrientationDegrees;

        int deviceOrientation = ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        switch(deviceOrientation) {
            case Surface.ROTATION_0:
                deviceOrientationDegrees = 0;
                break;
            case Surface.ROTATION_90:
                deviceOrientationDegrees = 90;
                break;
            case Surface.ROTATION_180:
                deviceOrientationDegrees = 180;
                break;
            case Surface.ROTATION_270:
                deviceOrientationDegrees = 270;
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + deviceOrientation);
        }

        // formula source: https://developer.android.com/training/camera2/camera-preview-large-screens (although mod had to be done)
        int rotation = (sensorOrientationDegrees + deviceOrientationDegrees + 360) % 360;
        return rotation;
    }

    ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        Image image;
        Bitmap tmpBitmap;

        @Override
        public void onImageAvailable(ImageReader reader) {
            int deviceOrientation, deviceOrientationDegrees;

            //Log.i(TAG, "onImageAvailable");
            image = reader.acquireLatestImage();
            if(image == null) {
                Log.i(TAG, "image=null");
                return;
            }

            synchronized (lock) {
                if (processingImage != ProcessingImage.ready) {
                    image.close();
                    return;
                }
            }

            // Convert from Image to Bitmap object
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.capacity()];
            buffer.get(bytes);
            tmpBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, null);

            // Close the Image
            image.close();

            // Now rotate the bitmap
            int rotation = getCameraRotation();

            Matrix rot_matrix;
            rot_matrix = new Matrix();
            rot_matrix.postRotate(rotation);
            Bitmap tmpCameraBitmap = Bitmap.createBitmap(tmpBitmap, 0, 0, tmpBitmap.getWidth(), tmpBitmap.getHeight(), rot_matrix, false);

            Bitmap tmpCameraScaledBitmap;
            synchronized (lock) {
                if (lowResMode) {    // todo: check shared preferences for lowResMode
                    Matrix scale_matrix;
                    scale_matrix = new Matrix();
                    scale_matrix.postScale(0.5f, 0.5f);
                    tmpCameraScaledBitmap = Bitmap.createBitmap(tmpCameraBitmap, 0, 0, tmpCameraBitmap.getWidth(), tmpCameraBitmap.getHeight(), scale_matrix, false);
                } else {
                    tmpCameraScaledBitmap = tmpCameraBitmap;
                }
            }

            synchronized (drawLock) {
                cameraBitmap = tmpCameraBitmap;
                cameraScaledBitmap = tmpCameraScaledBitmap;
                drawBitmap = cameraBitmap;
            }
            //cameraBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.a);
            synchronized(lock) {
                processingImage = ProcessingImage.preprocessing;
            }
        }
    };

    void getScreenResolution() { // saves it into SX and SY
        WindowManager windowManager = (WindowManager)getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        Point point = new Point();
        display.getRealSize(point);
        SX = point.x;
        SY = point.y;
    }

    void addView() {
        int LAYOUT_FLAG;
        //if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        //} else {
        //    LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_PHONE;
        //}
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                SX,
                SY,
                //WindowManager.LayoutParams.MATCH_PARENT,
                //WindowManager.LayoutParams.MATCH_PARENT,
                LAYOUT_FLAG,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_FULLSCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.LEFT | Gravity.TOP;
        layoutParams.setTitle("ElementSelectorView");
        WindowManager windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
        try {
            windowManager.addView(elementSelector, layoutParams);
            Log.i(TAG, "successfully added view");
        } catch(Exception e) {
            Log.i(TAG, "addView exception: " + e.getMessage().toString());
        }
    }

    void removeView() {
        WindowManager windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
        try {
            windowManager.removeView(elementSelector);
            Log.i(TAG, "Sucessfully removed view");
        } catch(Exception e) {
            Log.i(TAG, "removeView exception: " + e.getMessage().toString());
        }
    }

    private void createElementSelector() {
        if(elementSelector != null)
            return;

        Log.i(TAG, "createElementSelector is called");
        // Create view for drawing
        elementSelector = new ElementSelector(context);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.i(TAG, "onConfigurationChange called");
        super.onConfigurationChanged(newConfig);
        getScreenResolution();  // updates SX and SY values
        if(elementSelector != null) {
            removeView();
            addView();
            elementSelector.setScreenRotated();
        }
    }

    GestureDescription createTapAndHold(PointF point)
    {
        int duration = 2000; // 2s
        Path clickPath = new Path();
        clickPath.moveTo(point.x, point.y);
        GestureDescription.StrokeDescription clickStroke =
                new GestureDescription.StrokeDescription(clickPath, 0, duration);
        GestureDescription.Builder clickBuilder = new GestureDescription.Builder();
        clickBuilder.addStroke(clickStroke);
        return clickBuilder.build();
    }

    GestureDescription createClick(PointF point)
    {
        int duration = 10; // 1ms
        Path clickPath = new Path();
        clickPath.moveTo(point.x, point.y);
        GestureDescription.StrokeDescription clickStroke =
                new GestureDescription.StrokeDescription(clickPath, 0, duration);
        GestureDescription.Builder clickBuilder = new GestureDescription.Builder();
        clickBuilder.addStroke(clickStroke);
        return clickBuilder.build();
    }

    GestureDescription createSwipe(PointF a, PointF b, int duration)
    {
        // int duration = 500; // 500ms to have a slower swipe
        Path swipePath = new Path();
        swipePath.moveTo(a.x, a.y);
        swipePath.lineTo(b.x, b.y);
        GestureDescription.StrokeDescription clickStroke =
                new GestureDescription.StrokeDescription(swipePath, 0, duration);
        GestureDescription.Builder clickBuilder = new GestureDescription.Builder();
        clickBuilder.addStroke(clickStroke);
        return clickBuilder.build();
    }

    GestureDescription createZoom(PointF a1, PointF a2, PointF b1, PointF b2, int duration)
    {
        //int duration = 500;

        Path zoomPath1 = new Path();
        zoomPath1.moveTo(a1.x, a1.y);
        zoomPath1.lineTo(a2.x, a2.y);
        GestureDescription.StrokeDescription zoomStroke1 =
                new GestureDescription.StrokeDescription(zoomPath1, 0, duration);

        Path zoomPath2 = new Path();
        zoomPath2.moveTo(b1.x, b1.y);
        zoomPath2.lineTo(b2.x, b2.y);
        GestureDescription.StrokeDescription zoomStroke2 =
                new GestureDescription.StrokeDescription(zoomPath2, 0, duration);

        GestureDescription.Builder clickBuilder = new GestureDescription.Builder();
        clickBuilder.addStroke(zoomStroke1);
        clickBuilder.addStroke(zoomStroke2);
        return clickBuilder.build();
    }
}

class Keyboard {
    String TAG = "FaceControl.Keyboard";
    public ArrayList <Row> rows;
    public int activeRow;
    public int activeGroup;
    public int activeElement;
    public static int NONE_SELECTED = -1;   // used for activeRow, activeGroup, activeElement
    public static int INACTIVE = -2;        // -||-
    public Rect rootBounds;

    static class Row {
        ArrayList<Group> groups;
        Row() {
            groups = new ArrayList<>();
        }
    }

    static class Group {
        ArrayList<Element> elements;
        Group() {
            elements = new ArrayList<>();
        }
    }

    static class Element {
        Rect boundsInScreen;
        // optionally add text description
        Element(Rect boundsInScreen) {
            this.boundsInScreen = boundsInScreen;
        }
    }

    private ArrayList <Element> fullList;

    Keyboard(Rect rootBounds) {
        fullList = new ArrayList<>();
        rows = new ArrayList<>();
        activeRow = 0;
        activeGroup = INACTIVE;
        activeElement = INACTIVE;
        this.rootBounds = rootBounds;
    }

    public int getRowCount() {
        return rows.size();
    }

    public int getRowGroupCount(int row) {
        return rows.get(row).groups.size();
    }

    public int getRowGroupElementCount(int row, int group) {
        return rows.get(row).groups.get(group).elements.size();
    }

    public Element getElement(int row, int group, int element) {
        // todo sanity check for row, group and element
        if(element < 0)
            return null;

        return rows.get(row).groups.get(group).elements.get(element);
    }

    public ArrayList<Element> getFullGroup(int row, int group) {
        // todo sanity check for row and group numbers
        ArrayList<Element> fullGroup;
        fullGroup = rows.get(row).groups.get(group).elements;
        return fullGroup;
    }

    public ArrayList<Element> getFullRow(int row) {
        // todo sanity check for row number
        ArrayList<Element> fullRow = new ArrayList<>();
        for(Group group : rows.get(row).groups) {
            for (Element element : group.elements) {
                fullRow.add(element);
            }
        }

        return fullRow;
    }

    public ArrayList<Element> getFullGrid() {
        ArrayList<Element> fullGrid = new ArrayList<>();
        for (Row row : rows) {
            for (Group group : row.groups) {
                for (Element element : group.elements) {
                    fullGrid.add(element);
                }
            }
        }

        return fullGrid;
    }

    public void addElement(Rect boundsInScreen) {
        fullList.add(new Element(boundsInScreen));
    }

    public void selectNextRow() {
        if(activeRow + 1 > rows.size() - 1)
            activeRow = NONE_SELECTED;
        else activeRow++;
    }

    public void selectPrevRow() {
        if(activeRow == NONE_SELECTED)
            activeRow =  rows.size() - 1;
        else activeRow--;
    }

    public void selectNextGroup() {
        Row row = rows.get(activeRow);
        List<Group> groups = row.groups;
        if(activeGroup + 1 > groups.size() - 1)
            activeGroup = NONE_SELECTED;
        else activeGroup++;
    }

    public void selectPrevGroup() {
        Row row = rows.get(activeRow);
        List<Group> groups = row.groups;
        if(activeGroup == NONE_SELECTED)
            activeGroup = groups.size() - 1;
        else activeGroup--;
    }

    public void selectNextElement() {
        // todo: sanity check for activeRow & activeGroup values
        Row row = rows.get(activeRow);
        Group group = row.groups.get(activeGroup);
        List<Element> elements = group.elements;
        if(activeElement + 1 > elements.size() - 1)
            activeElement = NONE_SELECTED;
        else activeElement++;
    }

    public void selectPrevElement() {
        // todo: sanity check for activeRow & activeGroup values
        Row row = rows.get(activeRow);
        Group group = row.groups.get(activeGroup);
        List<Element> elements = group.elements;
        if(activeElement == NONE_SELECTED)
            activeElement = elements.size() - 1;
        else activeElement--;
    }

    public void groupElements() {
        ArrayList<ArrayList<Element>> tmpRows1 = new ArrayList<>();
        ArrayList<ArrayList<Element>> tmpRows2 = new ArrayList<>();

        Integer last_ypos = null;
        for(int k = 0; k < fullList.size(); k++) {
            int ypos = fullList.get(k).boundsInScreen.top;
            if(last_ypos == null || ypos != last_ypos) {
                last_ypos = ypos;
                tmpRows1.add(new ArrayList<Element>());
            }
            // add next element to last row that was created
            tmpRows1.get(tmpRows1.size()-1).add(new Element(fullList.get(k).boundsInScreen));
        }

        // Now group rows with less than 3 elements
        ArrayList<Element> groupRow = null;
        for(int k = 0; k < tmpRows1.size(); k++) {
            ArrayList<Element> row;
            row = tmpRows1.get(k);
            if(row.size() >= 3) {
                tmpRows2.add(row);
            } else {
                if(groupRow == null)
                    groupRow = new ArrayList<Element>();

                groupRow.addAll(row);
            }
        }
        if(groupRow != null)
            tmpRows2.add(groupRow);

        /*
        The way we will group elements is like this:
            #6:	    3+3
            #7: 	4+3
            #8:	    4+4
            #9:	    3+3+3
            #10:	4+3+3
            #11:	4+4+3
            #12:	4+4+4
            #13:	4+3+3+3
            #14:	4+4+3+3
        */
        for(int k = 0; k < tmpRows2.size(); k++) {
            ArrayList<Element> tmpRow = tmpRows2.get(k);
            Log.i(TAG, String.format("In row %d we have %d elements", k, tmpRow.size()));
            switch(tmpRow.size()) {
                case 6:
                    rows.add(new Row());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(0));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(1));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(2));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(3));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(4));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(5));
                    break;
                case 7:
                    rows.add(new Row());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(0));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(1));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(2));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(3));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(4));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(5));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(6));
                    break;
                case 8:
                    rows.add(new Row());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(0));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(1));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(2));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(3));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(4));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(5));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(6));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(7));
                    break;
                case 9:
                    rows.add(new Row());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(0));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(1));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(2));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(3));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(4));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(5));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(6));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(7));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(8));
                    break;
                case 10:
                    rows.add(new Row());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(0));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(1));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(2));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(3));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(4));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(5));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(6));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(7));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(8));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(9));
                    break;
                case 11:
                    rows.add(new Row());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(0));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(1));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(2));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(3));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(4));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(5));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(6));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(7));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(8));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(9));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(10));
                    break;
                case 12:
                    rows.add(new Row());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(0));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(1));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(2));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(3));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(4));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(5));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(6));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(7));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(8));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(9));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(10));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(11));
                    break;
                case 13:
                    rows.add(new Row());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(0));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(1));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(2));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(3));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(4));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(5));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(6));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(7));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(8));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(9));
                    rows.get(k).groups.get(3).elements.add(tmpRow.get(10));
                    rows.get(k).groups.get(3).elements.add(tmpRow.get(11));
                    rows.get(k).groups.get(3).elements.add(tmpRow.get(12));
                    break;
                case 14:
                    rows.add(new Row());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.add(new Group());
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(0));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(1));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(2));
                    rows.get(k).groups.get(0).elements.add(tmpRow.get(3));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(4));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(5));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(6));
                    rows.get(k).groups.get(1).elements.add(tmpRow.get(7));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(8));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(9));
                    rows.get(k).groups.get(2).elements.add(tmpRow.get(10));
                    rows.get(k).groups.get(3).elements.add(tmpRow.get(11));
                    rows.get(k).groups.get(3).elements.add(tmpRow.get(12));
                    rows.get(k).groups.get(3).elements.add(tmpRow.get(13));
                    break;

                default:
                    if(tmpRow.size() < 6) {
                        // for less than 6, make just one group with all the elements from the row
                        rows.add(new Row());
                        rows.get(k).groups.add(new Group());
                        for(Element e : tmpRow)
                            rows.get(k).groups.get(0).elements.add(e);
                    } else {
                        // for greater than 14 just make groups of 4 plus another group with remaining elements
                        rows.add(new Row());
                        for(int j = 0; j < tmpRow.size(); j++) {
                            if(j % 4 == 0)
                                rows.get(k).groups.add(new Group());

                            rows.get(k).groups.get(rows.get(k).groups.size()-1).elements.add(new Element(tmpRow.get(j).boundsInScreen));
                        }
                    }
                    break;
            }
        }
    }
}

class ElementSelector extends ViewGroup {
    Context context;
    FaceControlService service;
    Paint paint;
    String TAG = "FaceControl.ElementSelector";
    float scale;
    float scale_cm;

    Thread keyboardThread = null;
    Keyboard keyboard = null;   // null if keyboard isn't opened
    boolean gridScan;   // if false, stop keyboard thread

    Thread homeThread = null;
    boolean homeOpened = false;

    Thread updaterThread = null;

    int activeThreads = 0;
    int initStop = 0;   // when we want to stop threads, we set it to number of threads

    SharedPreferences prefs;

    int THREAD_SLEEPTIME = 10;  // Thread sleep time in milliseconds

    final Object lock = new Object();

    enum MenuType {
        vertical,
        horizontal
    }

    static class MenuItem {
        enum ItemType {
            // single switch menu items
            select_point,
            open_menu,
            // main/home menu items
            tap,
            gestures,
            globals,
            next_page,
            previous_page,
            // gestures menu items
            drag,
            tap_and_hold,
            scroll,
            zoom_rotate,
            // drag menu items
            drag_done,
            drag_redo,
            drag_cancel,
            // scroll menu items
            scroll_down,
            scroll_up,
            scroll_left,
            scroll_right,
            // zoom/rotate menu items
            zoom_in,
            zoom_out,
            rotate_left,
            rotate_right,
            // global actions menu items
            back,
            home,
            recents,
            notifications,
            settings,
            // stop gestures' sub-submenu item
            stop,
            // keyboard menu item (together with select_point)
            hide_keyboard,
        }

        ItemType itemType;
        Bitmap bmpItem;
        RectF rect = new RectF();  // rectangle defining position of the item
        String itemText;

        MenuItem(ItemType itemType, String itemText, Bitmap bmpItem) {
            this.itemType = itemType;
            this.itemText = itemText;
            this.bmpItem = bmpItem;
        }
    }

    enum SelectorState {
        // switch timeout state when assistant opens
        assistant_timeout,
        // when keyboard is opened we set it to 'keyboard'
        keyboard,
        // scan used to select first point (e.g. for tap)
        xscan1a,
        xscan2a,
        yscan1a,
        yscan2a,
        // scan used to select second point (for drag)
        xscan1b,
        xscan2b,
        yscan1b,
        yscan2b,

        singleswitch_menu,  // menu opened when select switch is pressed in single switch mode
        main1_menu, // menu opened when first point is selected through select switch presses
        main2_menu, // menu opened when no point is selected and next-item switch is pressed (but also opened from singleswitch_menu)
        home1_menu, // same as main menu 1, but includes previous/next page buttons
        home2_menu, // home-extension of main 2 menu
        gestures_menu,  // menu with gestures
        drag_menu,  // menu containing done/redo/cancel for drag operation
        scroll_menu,  // menu containing scroll actions
        zoom_menu,    // menu containing zoom/rotate actions
        globals_menu,   // menu with global actions
        keyboard_menu,  // menu opened when we want to close keyboard

        stop_menu,

        idle
    }

    // Single switch menu bitmaps
    Bitmap bmSelectPoint;
    Bitmap bmOpenMenu;
    // Main/home menu bitmaps
    Bitmap bmTap;
    Bitmap bmGestures;
    Bitmap bmGlobals;
    Bitmap bmNextPage;  // used only for home_menu
    Bitmap bmPreviousPage;  // used only for home_menu
    // Gestures menu bitmaps
    Bitmap bmDrag;
    Bitmap bmTapAndHold;
    Bitmap bmScroll;
    Bitmap bmZoomRotate;
    // Drag menu bitmaps
    Bitmap bmDragDone;
    Bitmap bmDragRedo;
    Bitmap bmDragCancel;
    // Scroll menu bitmaps
    Bitmap bmScrollDown;
    Bitmap bmScrollUp;
    Bitmap bmScrollLeft;
    Bitmap bmScrollRight;
    // Zoom/rotate menu bitmaps
    Bitmap bmZoomIn;
    Bitmap bmZoomOut;
    Bitmap bmRotateLeft;
    Bitmap bmRotateRight;
    // Global actions menu bitmaps
    Bitmap bmBack;
    Bitmap bmHome;
    Bitmap bmRecents;
    Bitmap bmNotifications;
    Bitmap bmSettings;
    // One item menu bitmap (used to stop scroll/zoom/rotate)
    Bitmap bmStop;
    // Hide keyboard bitmap
    Bitmap bmHideKeyboard;

    ArrayList<MenuItem> singleSwitchMenu;
    ArrayList<MenuItem> mainMenu1;
    ArrayList<MenuItem> homeMenu1;
    ArrayList<MenuItem> mainMenu2;   // menu opened when next-switch is pressed
    ArrayList<MenuItem> homeMenu2;  // same as menu 2 but with next/previous page buttons
    ArrayList<MenuItem> gesturesMenu;
    ArrayList<MenuItem> dragMenu;
    ArrayList<MenuItem> scrollMenu;
    ArrayList<MenuItem> zoomMenu;
    ArrayList<MenuItem> globalsMenu;
    ArrayList<MenuItem> stopMenu;
    ArrayList<MenuItem> keyboardMenu;

    Paint scanLine12Paint;
    Paint scanLine34Paint;
    Paint scanBandPaint;

    Paint bmPaint;
    Paint activeRectPaint;
    Paint noneRectPaint;

    Paint textPaint;
    Paint arrowPaint;
    Paint greenPaint;
    Paint redPaint;
    Paint keyboardTextPaint;
    Paint textRectPaint;
    String keyboardText = "Select to open keyboard menu";

    Paint assistantTextPaint;
    String assistantText = "Switches disabled (%d)";

    // Constants:
    float SELECTION_SIZE_DP = 6.0f;
    float LINE_SIZE_DP = 10.0f;
    float ARROW_SIZE_DP = 50.0f;
    float CIRCLE_RADIUS_DP = 20.0f;   // drag-preview circle radius

    float TEXT_SIZE_DP = 20.0f; // menu item text size
    float ITEM_SIZE_DP; // programmatically set this value to one of the following three
    float ITEM_SIZE_SMALL_DP = 35.0f; // menu item size
    float ITEM_SIZE_MEDIUM_DP = 50.0f; // menu item size
    float ITEM_SIZE_BIG_DP = 65.0f; // menu item size

    // Single switch constants:
    float SELECT_INITIAL_PAUSE = 0.5f;
    float SELECT_PERIOD = 1.0f;

    float MENU_TIMEOUT = 7.0f;  // time window in which some switch must be activated (either menu/select switch)

    float SCANLINE_WIDTH_DP = 4.0f;
    float SCAN1_CMPERSEC = 3.0f;
    float SCAN2_CMPERSEC = 0.5f;
    float SCAN_BAND_WIDTH_CM = 3.0f;
    float SCAN_INITIAL_PAUSE = 1.0f;    // when scan begins (either x or y scan) there's an initial pause

    float GESTURE_PERIOD = 0.5f;    // gesture repetition period in seconds
    float SCROLL_LENGTH_CM = 1.0f; // gesture length in centimeters
    float ZOOM_IN_OFFSET_CM = 3.0f;    // how far two fingers are initially when performing zoom in
    float ZOOM_OUT_OFFSET_CM = 3.0f;    // how far two fingers are initially when performing zoom out
    float ZOOM_LENGTH_CM = 1.0f; // gesture length in centimeters
    float ROTATE_OFFSET_CM = 3.0f;
    float ROTATE_ANGLE = (float) (PI * 10.0f/180.0f);

    // ######################
    // ## State variables: ##
    // ######################
    int ASSISTANT_TIMEOUT_PERIOD = 10; // assistant timeout period
    int assistantTimeLeft;	// time left before switches are enabled
    long assistant_t0;	// clock timestamp [ms] at beginning of timeout

    PowerManager.WakeLock wakeLock;
    boolean switchUnlock;
    boolean singleSwitchMode;
    boolean initialSelectPause;
    long selectTime;

    SelectorState selectorState = SelectorState.idle;
    float scanLine1_pos,
        scanLine2_pos,
        scanLine3_pos,  // this is always x position
        scanLine4_pos;  // this is always y position
    float scanLine_dir;
    boolean initialScanPause;
    long initialScanPauseTime;
    int maxLoopCount;
    int scanLoopCount;
    PointF point1, point2;  // used for tap and drag

    long menuTime;  // used for timing the MENU_TIMEOUT
    int activeMenuItem; // active menu item number (corresponds to number in arraylist)
    MenuItem.ItemType activeGesture; // gesture selected in gestures menu which is going to be repeatedly applied

    ElementSelector(Context context) {
        super(context);
        this.context = context;
        service = (FaceControlService)context;

        prefs = service.prefs;

        scale = getResources().getDisplayMetrics().density;
        scale_cm = 10 * TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 1, getResources().getDisplayMetrics());

        updateScanSettings();
        prepareMenu();

        setSingleSwitchMode(prefs.getBoolean("SingleSwitchMode", SettingsActivity.DEFAULT_SINGLESWITCHMODE));
        setSwitchUnlock(false); // setSwitchUnlock(prefs.getBoolean("SwitchUnlock", SettingsActivity.DEFAULT_SWITCHUNLOCK));

        // Create drawing objects
        paint = new Paint();
        paint.setARGB(255, 255, 0, 0);
        paint.setTextSize(20.0f * scale);

        scanLine12Paint = new Paint();
        scanLine12Paint.setARGB(200, 0xFF, 0x66, 0x00);   // orange (not very opaque)
        scanLine12Paint.setStrokeWidth(SCANLINE_WIDTH_DP * scale);

        scanLine34Paint = new Paint();
        scanLine34Paint.setARGB(255, 0xFF, 0x00, 0x00);   // red
        scanLine34Paint.setStrokeWidth(SCANLINE_WIDTH_DP * scale);

        scanBandPaint = new Paint();
        scanBandPaint.setARGB(75, 0xFF, 0x66, 0x00);    // orange (very opaque)
        scanBandPaint.setStyle(Paint.Style.FILL);

        bmPaint = new Paint();

        activeRectPaint = new Paint();
        activeRectPaint.setStyle(Paint.Style.STROKE);
        activeRectPaint.setStrokeWidth(ITEM_SIZE_DP/10*scale);
        activeRectPaint.setARGB(255, 0, 255, 0);

        noneRectPaint = new Paint();
        noneRectPaint.setStyle(Paint.Style.STROKE);
        noneRectPaint.setStrokeWidth(ITEM_SIZE_DP/10*scale);
        noneRectPaint.setARGB(255, 255, 0, 0);

        textPaint = new Paint();
        textPaint.setTextSize(TEXT_SIZE_DP*scale);
        textPaint.setARGB(255, 0, 0, 0);

        arrowPaint = new Paint();
        arrowPaint.setARGB(255, 127, 127, 127);
        arrowPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        arrowPaint.setStrokeWidth(LINE_SIZE_DP*scale);

        greenPaint = new Paint();
        greenPaint.setARGB(255, 0, 255, 0);
        greenPaint.setStyle(Paint.Style.STROKE);
        greenPaint.setStrokeWidth(SELECTION_SIZE_DP*scale);

        redPaint = new Paint();
        redPaint.setARGB(255, 255, 0, 0);
        redPaint.setStyle(Paint.Style.STROKE);
        redPaint.setStrokeWidth(SELECTION_SIZE_DP*scale);

        textRectPaint = new Paint();
        textRectPaint.setARGB(200, 255, 255, 255);
        textRectPaint.setStyle(Paint.Style.FILL);
        //updaterThread = new Thread(Thr_ViewUpdater);
        //updaterThread.start();
    }

    void start() {
        synchronized(lock) {
            setIdleState();

            if(updaterThread == null) {
                activeThreads++;
                updaterThread = new Thread(Thr_ViewUpdater);
                updaterThread.start();
            }

            if(homeThread == null) {
                activeThreads++;
                homeThread = new Thread(Thr_Home);
                homeThread.start();
            }

            setKeyboardMode();
            setSwitchUnlock(prefs.getBoolean("SwitchUnlock", SettingsActivity.DEFAULT_SWITCHUNLOCK));
        }
    }

    void stop() {
        initStop = activeThreads;
        setSwitchUnlock(false);
        Log.i(TAG, "Setting initStop=" + initStop);
    }

    void setKeyboardMode() {
        gridScan = service.prefs.getBoolean("keyboard_gridscan", ScanSettingsActivity.DEFAULT_GRIDSCAN_MODE);
        if(gridScan) {
            if(keyboardThread == null) {
                activeThreads++;
                keyboardThread = new Thread(Thr_Keyboard);
                keyboardThread.start();
            }
        }
    }

    Runnable Thr_Home = new Runnable() {
        @Override
        public void run() {
            Intent intent= new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo defaultLauncher= service.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            String nameOfLauncherPkg = defaultLauncher.activityInfo.packageName;
            Log.i(TAG, "nameOfLauncherPkg = " + nameOfLauncherPkg);

            while(true) {

                synchronized (lock) {
                    if (initStop > 0) {
                        initStop--;
                        activeThreads--;
                        homeThread = null;
                        break;
                    }
                }

                AccessibilityNodeInfo nodeInfo = service.getRootInActiveWindow();
                if(nodeInfo != null) {
                    synchronized(lock) {
                        if(nodeInfo.getPackageName().equals(nameOfLauncherPkg)) //"com.sec.android.app.launcher"))
                            homeOpened = true;
                        else homeOpened = false;
                    }
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    };

    void searchForKeys(AccessibilityNodeInfo rootNode) {
        if(rootNode.getChildCount() > 0) {
            for(int k = 0; k < rootNode.getChildCount(); k++)
                searchForKeys(rootNode.getChild(k));
        } else {
            AccessibilityNodeInfo node;
            Rect boundsInScreen = new Rect();
            node = rootNode;
            if(node.getContentDescription() != null) {
                node.getBoundsInScreen(boundsInScreen);
                Log.i(TAG, "Key content: " + node.getContentDescription() + "; boundsInScreen: " + boundsInScreen);
                keyboard.addElement(boundsInScreen);
            }
        }
    }

    Runnable Thr_Keyboard = new Runnable() {
        @Override
        public void run() {
            boolean keyboardFound;

            while(true) {

                synchronized (lock) {
                    if (initStop > 0) {
                        initStop--;
                        activeThreads--;
                        keyboardThread = null;
                        break;
                    } else if(!gridScan) {
                        // if grid-scan mode is OFF (point scan is ON) then exit this thread
                        activeThreads--;
                        keyboardThread = null;
                        setIdleState();
                        break;
                    }
                }

                synchronized (lock) {
                    if (selectorState == SelectorState.idle) {
                        // this happens if we selected some "keyboard_menu" item or similar
                        keyboard = null;
                    }
                }

                keyboardFound = false;
                List<AccessibilityWindowInfo> windowInfoList = service.getWindows();
                //Log.i(TAG, "there are " + windowInfoList.size() + " windows");
                for (int j = 0; j < windowInfoList.size(); j++) {
                    if (windowInfoList.get(j).getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        AccessibilityNodeInfo rootNode;
                        rootNode = windowInfoList.get(j).getRoot();
                        try {
                            keyboardFound = true;
                            if(keyboard == null) {
                                if(rootNode.getChildCount() > 0) {
                                    Log.i(TAG, "Searching through input method children");
                                    Rect rootBounds = new Rect();
                                    windowInfoList.get(j).getBoundsInScreen(rootBounds);
                                    keyboard = new Keyboard(rootBounds);
                                    searchForKeys(rootNode);
                                    keyboard.groupElements();

                                    synchronized (lock) {
                                        initialSelectPause = true;
                                        selectTime = SystemClock.elapsedRealtime();
                                        selectorState = SelectorState.keyboard;
                                    }
                                }
                            }
                            /*if (rootNode.getChildCount() > 0) {
                                rootNode = rootNode.getChild(0);
                                keyboardFound = true;
                                if(keyboard == null) {
                                    keyboard = new Keyboard();
                                    for (int k = 0; k < rootNode.getChildCount(); k++) {
                                        AccessibilityNodeInfo node;
                                        Rect boundsInScreen = new Rect();
                                        node = rootNode.getChild(k);
                                        if(node.getContentDescription() != null) {
                                            node.getBoundsInScreen(boundsInScreen);
                                            keyboard.addElement(boundsInScreen);
                                        }
                                        //if (rootNode.getChild(k) != null)
                                        //    Log.i(TAG, "child " + k + " content: " + rootNode.getChild(k).getContentDescription()); // todo: remove crash when lang. changed (child=null)
                                    }
                                    keyboard.groupElements();

                                    synchronized (lock) {
                                        selectorState = SelectorState.keyboard;
                                    }
                                }
                            }*/
                        } catch (Exception e) {
                            Log.i(TAG, "Exception " + e.toString());
                            keyboardFound = false;
                            keyboard = null;
                        }
                    }
                }

                if(!keyboardFound && keyboard != null) {
                    synchronized (lock) {
                        keyboard = null;
                        setIdleState();
                    }
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    };

    void setSingleSwitchMode(boolean mode) {
        synchronized (lock) {
            this.singleSwitchMode = mode;
        }
    }

    void setSwitchUnlock(boolean mode) {
        synchronized (lock) {
            this.switchUnlock = mode;
            if(mode) {
                if(wakeLock == null) {
                    PowerManager powerManager = (PowerManager) service.getSystemService(POWER_SERVICE);
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                            "FaceControl::MyWakelockTag");
                    wakeLock.acquire();
                }
            } else {
                if(wakeLock != null) {
                    wakeLock.release();
                    wakeLock = null;
                }
            }
        }
    }

    void updateScanSettings() {
        synchronized (lock) {
            setIdleState();
            SCAN1_CMPERSEC = prefs.getFloat("ScanBandSpeed", ScanSettingsActivity.DEFAULT_SCANBANDSPEED);
            SCAN2_CMPERSEC = prefs.getFloat("ScanLineSpeed", ScanSettingsActivity.DEFAULT_SCANLINESPEED);
            SCAN_INITIAL_PAUSE = prefs.getFloat("ScanInitialPause", ScanSettingsActivity.DEFAULT_SCANINITIALPAUSE);
            SCAN_BAND_WIDTH_CM = prefs.getFloat("ScanBandWidth", ScanSettingsActivity.DEFAULT_SCANBANDWIDTH);
            SELECT_INITIAL_PAUSE = prefs.getFloat("AutoSelectPause", ScanSettingsActivity.DEFAULT_AUTOSELECTPAUSE);
            SELECT_PERIOD = prefs.getFloat("AutoSelectPeriod", ScanSettingsActivity.DEFAULT_AUTOSELECTPERIOD);
        }
    }

    void prepareMenu() {
        synchronized (lock) {
            switch (prefs.getInt("MenuSize", SettingsActivity.DEFAULT_MENUSIZE)) {
                case 0:
                    ITEM_SIZE_DP = ITEM_SIZE_SMALL_DP;
                    break;
                case 1:
                    ITEM_SIZE_DP = ITEM_SIZE_MEDIUM_DP;
                    break;
                case 2:
                    ITEM_SIZE_DP = ITEM_SIZE_BIG_DP;
                    break;
            }

            // ##################################
            // ### Load the menu item bitmaps ###
            // ##################################
            // Single switch menu bitmaps
            if(bmSelectPoint != null) bmSelectPoint.recycle();
            if(bmOpenMenu != null) bmOpenMenu.recycle();
            // Main/home menu bitmaps
            if (bmTap != null) bmTap.recycle();
            if (bmGestures != null) bmGestures.recycle();
            if (bmGlobals != null) bmGlobals.recycle();
            if (bmNextPage != null) bmNextPage.recycle();
            if (bmPreviousPage != null) bmPreviousPage.recycle();
            // Gestures menu bitmaps
            if (bmDrag != null) bmDrag.recycle();
            if (bmTapAndHold != null) bmTapAndHold.recycle();
            if (bmScroll != null) bmScroll.recycle();
            if (bmZoomRotate != null) bmZoomRotate.recycle();
            // Drag menu bitmaps
            if (bmDragDone != null) bmDragDone.recycle();
            if (bmDragRedo != null) bmDragRedo.recycle();
            if (bmDragCancel != null) bmDragCancel.recycle();
            // Scroll menu bitmaps
            if (bmScrollDown != null) bmScrollDown.recycle();
            if (bmScrollUp != null) bmScrollUp.recycle();
            if (bmScrollLeft != null) bmScrollLeft.recycle();
            if (bmScrollRight != null) bmScrollRight.recycle();
            // Zoom/rotate menu bitmaps
            if (bmZoomIn != null) bmZoomIn.recycle();
            if (bmZoomOut != null) bmZoomOut.recycle();
            if (bmRotateLeft != null) bmRotateLeft.recycle();
            if (bmRotateRight != null) bmRotateRight.recycle();
            // Global actions menu bitmaps
            if (bmBack != null) bmBack.recycle();
            if (bmHome != null) bmHome.recycle();
            if (bmRecents != null) bmRecents.recycle();
            if (bmNotifications != null) bmNotifications.recycle();
            if (bmSettings != null) bmSettings.recycle();
            // One item menu bitmap (used to stop scroll/zoom/rotate)
            if (bmStop != null) bmStop.recycle();
            // Hide keyboard bitmap
            if (bmHideKeyboard != null) bmHideKeyboard.recycle();

            // Single switch menu bitmaps
            bmSelectPoint = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.select_point),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmOpenMenu = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.menu),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            // Main/home menu bitmaps
            bmTap = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.tap),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmGestures = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.gestures),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmGlobals = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.global_action),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmNextPage = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.next_page),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmPreviousPage = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.previous_page),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            // Gestures menu bitmaps
            bmDrag = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.drag),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmTapAndHold = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.tap_and_hold),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmScroll = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.scroll),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmZoomRotate = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.zoom_rotate),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            // Drag menu bitmaps
            bmDragDone = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.drag_done),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmDragRedo = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.drag_redo),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmDragCancel = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.drag_cancel),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            // Scroll menu bitmaps
            bmScrollDown = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.scroll_down),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmScrollUp = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.scroll_up),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmScrollLeft = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.scroll_left),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmScrollRight = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.scroll_right),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            // Zoom/rotate menu bitmaps
            bmZoomIn = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.zoom_in),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmZoomOut = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.zoom_out),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmRotateLeft = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.rotate_left),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmRotateRight = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.rotate_right),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            // Global actions menu bitmaps
            bmBack = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.back),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmHome = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.home),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmRecents = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.recents),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmNotifications = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.notifications),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            bmSettings = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.settings),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            // One item menu bitmap (used to stop scroll/zoom/rotate)
            bmStop = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.stop),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            // Hide keyboard bitmap
            bmHideKeyboard = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeResource(getResources(), R.drawable.hide_keyboard),
                    (int) (ITEM_SIZE_DP * scale), (int) (ITEM_SIZE_DP * scale), true);

            // ########################
            // ### Create the menus ###
            // ########################

            singleSwitchMenu = new ArrayList<>();
            mainMenu1 = new ArrayList<>();
            mainMenu2 = new ArrayList<>();
            homeMenu1 = new ArrayList<>();
            homeMenu2 = new ArrayList<>();
            gesturesMenu = new ArrayList<>();
            dragMenu = new ArrayList<>();
            scrollMenu = new ArrayList<>();
            zoomMenu = new ArrayList<>();
            globalsMenu = new ArrayList<>();
            stopMenu = new ArrayList<>();
            keyboardMenu = new ArrayList<>();

            // Add menu items in order that they will be drawn
            // Single switch menu:
            singleSwitchMenu.add(new MenuItem(MenuItem.ItemType.select_point, "Select point", bmSelectPoint));
            singleSwitchMenu.add(new MenuItem(MenuItem.ItemType.open_menu, "Open menu", bmOpenMenu));
            setMenuRects(singleSwitchMenu, MenuType.horizontal);
            // Main menu 1:
            mainMenu1.add(new MenuItem(MenuItem.ItemType.tap, "Tap", bmTap));
            mainMenu1.add(new MenuItem(MenuItem.ItemType.gestures, "Gestures", bmGestures));
            mainMenu1.add(new MenuItem(MenuItem.ItemType.globals, "Global actions", bmGlobals));
            setMenuRects(mainMenu1, MenuType.horizontal);
            // Main menu 2:
            mainMenu2.add(new MenuItem(MenuItem.ItemType.globals, "Global actions", bmGlobals));
            mainMenu2.add(new MenuItem(MenuItem.ItemType.scroll, "Scroll", bmScroll));
            mainMenu2.add(new MenuItem(MenuItem.ItemType.zoom_rotate, "Zoom/Rotate", bmZoomRotate));
            setMenuRects(mainMenu2, MenuType.horizontal);
            // Home menu:
            homeMenu1.add(new MenuItem(MenuItem.ItemType.tap, "Tap", bmTap));
            homeMenu1.add(new MenuItem(MenuItem.ItemType.next_page, "Next Page", bmNextPage));
            homeMenu1.add(new MenuItem(MenuItem.ItemType.previous_page, "Previous Page", bmPreviousPage));
            homeMenu1.add(new MenuItem(MenuItem.ItemType.gestures, "Gestures", bmGestures));
            homeMenu1.add(new MenuItem(MenuItem.ItemType.globals, "Global actions", bmGlobals));
            setMenuRects(homeMenu1, MenuType.horizontal);
            // Home menu 2:
            homeMenu2.add(new MenuItem(MenuItem.ItemType.next_page, "Next Page", bmNextPage));
            homeMenu2.add(new MenuItem(MenuItem.ItemType.previous_page, "Previous Page", bmPreviousPage));
            homeMenu2.add(new MenuItem(MenuItem.ItemType.globals, "Global actions", bmGlobals));
            homeMenu2.add(new MenuItem(MenuItem.ItemType.scroll, "Scroll", bmScroll));
            homeMenu2.add(new MenuItem(MenuItem.ItemType.zoom_rotate, "Zoom/Rotate", bmZoomRotate));
            setMenuRects(homeMenu2, MenuType.horizontal);
            // Gestures menu:
            gesturesMenu.add(new MenuItem(MenuItem.ItemType.drag, "Drag", bmDrag));
            gesturesMenu.add(new MenuItem(MenuItem.ItemType.tap_and_hold, "Tap and hold", bmTapAndHold));
            gesturesMenu.add(new MenuItem(MenuItem.ItemType.scroll, "Scroll", bmScroll));
            gesturesMenu.add(new MenuItem(MenuItem.ItemType.zoom_rotate, "Zoom/Rotate", bmZoomRotate));
            setMenuRects(gesturesMenu, MenuType.horizontal);
            // Drag menu:
            dragMenu.add(new MenuItem(MenuItem.ItemType.drag_done, "Drag", bmDragDone));
            dragMenu.add(new MenuItem(MenuItem.ItemType.drag_redo, "Redo", bmDragRedo));
            dragMenu.add(new MenuItem(MenuItem.ItemType.drag_cancel, "Cancel", bmDragCancel));
            setMenuRects(dragMenu, MenuType.horizontal);
            // Scroll menu:
            scrollMenu.add(new MenuItem(MenuItem.ItemType.scroll_down, "Scroll down", bmScrollDown));
            scrollMenu.add(new MenuItem(MenuItem.ItemType.scroll_up, "Scroll up", bmScrollUp));
            scrollMenu.add(new MenuItem(MenuItem.ItemType.scroll_left, "Scroll left", bmScrollLeft));
            scrollMenu.add(new MenuItem(MenuItem.ItemType.scroll_right, "Scroll right", bmScrollRight));
            setMenuRects(scrollMenu, MenuType.horizontal);
            // Zoom/Rotate menu:
            zoomMenu.add(new MenuItem(MenuItem.ItemType.zoom_in, "Zoom in", bmZoomIn));
            zoomMenu.add(new MenuItem(MenuItem.ItemType.zoom_out, "Zoom out", bmZoomOut));
            zoomMenu.add(new MenuItem(MenuItem.ItemType.rotate_left, "Rotate left", bmRotateLeft));
            zoomMenu.add(new MenuItem(MenuItem.ItemType.rotate_right, "Rotate right", bmRotateRight));
            setMenuRects(zoomMenu, MenuType.horizontal);
            // Global actions menu:
            globalsMenu.add(new MenuItem(MenuItem.ItemType.back, "Back", bmBack));
            globalsMenu.add(new MenuItem(MenuItem.ItemType.home, "Home", bmHome));
            globalsMenu.add(new MenuItem(MenuItem.ItemType.recents, "Recents", bmRecents));
            globalsMenu.add(new MenuItem(MenuItem.ItemType.notifications, "Notifications", bmNotifications));
            globalsMenu.add(new MenuItem(MenuItem.ItemType.settings, "Settings", bmSettings));
            setMenuRects(globalsMenu, MenuType.horizontal);
            // Stop gestures single item menu:
            stopMenu.add(new MenuItem(MenuItem.ItemType.stop, "Stop gesture", bmStop));
            setMenuRects(stopMenu, MenuType.horizontal);
            // Keyboard menu:
            keyboardMenu.add(new MenuItem(MenuItem.ItemType.select_point, "Select point", bmSelectPoint));
            keyboardMenu.add(new MenuItem(MenuItem.ItemType.hide_keyboard, "Hide keyboard", bmHideKeyboard));
            setMenuRects(keyboardMenu, MenuType.horizontal);

            // Prepare keyboard text paint
            keyboardTextPaint = new Paint();
            keyboardTextPaint.setARGB(255, 0, 0, 0);
            setTextSizeForWidth(keyboardTextPaint, (float)service.SX*0.8f, keyboardText);
            keyboardTextPaint.setTextAlign(Paint.Align.CENTER);

            // Prepare assistant timeout text paint
            assistantTextPaint = new Paint();
            assistantTextPaint.setARGB(255, 0, 0, 0);
            setTextSizeForWidth(assistantTextPaint, (float)service.SX*0.8f, assistantText);
            assistantTextPaint.setTextAlign(Paint.Align.CENTER);
        }
    }

    void drawMenu(Canvas canvas, ArrayList<MenuItem> menu) {
        for (MenuItem item : menu)
            canvas.drawBitmap(item.bmpItem, item.rect.left, item.rect.top, bmPaint);

        if(activeMenuItem == ITEM_NONE) {
            RectF rect;
            rect = new RectF(menu.get(0).rect.left, menu.get(0).rect.top, menu.get(menu.size()-1).rect.right, menu.get(0).rect.bottom);
            canvas.drawRect(rect, noneRectPaint);

            String text;
            if(menuStack.size() <= 1)
                text = "Close";
            else text = "Go Back";

            float w = textPaint.measureText(text);
            float h = textPaint.getTextSize();
            canvas.drawRect(rect.left, rect.top - h, rect.left + w, rect.top, textRectPaint);
            canvas.drawText(text, rect.left, rect.top, textPaint);
        } else {
            MenuItem activeItem = menu.get(activeMenuItem);
            canvas.drawRect(activeItem.rect, activeRectPaint);
            float w = textPaint.measureText(activeItem.itemText);
            float h = textPaint.getTextSize();
            canvas.drawRect(activeItem.rect.left, activeItem.rect.bottom, activeItem.rect.left + w, activeItem.rect.bottom + h, textRectPaint);
            canvas.drawText(activeItem.itemText, activeItem.rect.left, activeItem.rect.bottom + h, textPaint);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        //Log.i(TAG, "dispatchDraw called @ " + SystemClock.elapsedRealtime());

        /*canvas.drawText("Hello world", (float)service.SX/2, y, paint);
        y += 7 * dir;
        if(y >= service.SY) {
            dir = -1;
        } else if(y <= 0) {
            dir = +1;
        }*/

        synchronized (lock) {   // lock access to state variables

            switch(selectorState) {
                case assistant_timeout: {
                    String text = String.format(assistantText, assistantTimeLeft);
                    float w = assistantTextPaint.measureText(text);
                    float h = assistantTextPaint.getTextSize();
                    float m = h * 0.3f;
                    canvas.drawRect((float) service.SX / 2 - w / 2, (float) service.SY / 2 - h, (float) service.SX / 2 + w / 2, (float) service.SY / 2 + m, textRectPaint);
                    canvas.drawText(text, (float) service.SX / 2, (float) service.SY / 2, assistantTextPaint);
                } break;

                case keyboard: {
                    int activeRow = keyboard.activeRow;
                    int activeGroup = keyboard.activeGroup;
                    int activeElement = keyboard.activeElement;

                    if (activeRow == Keyboard.INACTIVE) {
                        // todo: first draw all elements beyond full grid + the grid, then draw just green selection
                    } else if (activeRow == Keyboard.NONE_SELECTED) {
                        for (Keyboard.Element element : keyboard.getFullGrid())
                            canvas.drawRect(element.boundsInScreen, redPaint);

                        float w = keyboardTextPaint.measureText(keyboardText);
                        float h = keyboardTextPaint.getTextSize();
                        canvas.drawRect((float) service.SX / 2 - w / 2, keyboard.rootBounds.top - h, (float) service.SX / 2 + w / 2, keyboard.rootBounds.top, textRectPaint);
                        canvas.drawText(keyboardText, (float) service.SX / 2, keyboard.rootBounds.top, keyboardTextPaint);
                    } else {
                        if (activeGroup == Keyboard.INACTIVE) {
                            for (Keyboard.Element element : keyboard.getFullGrid())
                                canvas.drawRect(element.boundsInScreen, redPaint);

                            for (Keyboard.Element element : keyboard.getFullRow(activeRow))
                                canvas.drawRect(element.boundsInScreen, greenPaint);
                        } else if (activeGroup == Keyboard.NONE_SELECTED) {
                            for (Keyboard.Element element : keyboard.getFullRow(activeRow))
                                canvas.drawRect(element.boundsInScreen, redPaint);
                        } else {
                            if (activeElement == Keyboard.INACTIVE) {
                                for (Keyboard.Element element : keyboard.getFullRow(activeRow))
                                    canvas.drawRect(element.boundsInScreen, redPaint);

                                for (Keyboard.Element element : keyboard.getFullGroup(activeRow, activeGroup))
                                    canvas.drawRect(element.boundsInScreen, greenPaint);
                            } else if (activeElement == Keyboard.NONE_SELECTED) {
                                for (Keyboard.Element element : keyboard.getFullGroup(activeRow, activeGroup))
                                    canvas.drawRect(element.boundsInScreen, redPaint);
                            } else {
                                for (Keyboard.Element element : keyboard.getFullGroup(activeRow, activeGroup))
                                    canvas.drawRect(element.boundsInScreen, redPaint);

                                canvas.drawRect(keyboard.getElement(activeRow, activeGroup, activeElement).boundsInScreen, greenPaint);
                            }
                        }
                    }
                } break;

                case xscan1a:
                case xscan1b:
                    // when selecting second point, draw a circle around first point
                    if(selectorState == SelectorState.xscan1b)
                        canvas.drawCircle(point1.x, point1.y, CIRCLE_RADIUS_DP*scale, arrowPaint);

                    // draw the two vertical lines with the band in between
                    canvas.drawLine(scanLine1_pos, 0, scanLine1_pos, service.SY, scanLine12Paint);
                    canvas.drawRect(scanLine1_pos, 0, scanLine2_pos, service.SY, scanBandPaint);
                    canvas.drawLine(scanLine2_pos, 0, scanLine2_pos, service.SY, scanLine12Paint);
                    break;
                case xscan2a:
                case xscan2b:
                    // when selecting second point, draw a circle around first point
                    if(selectorState == SelectorState.xscan2b)
                        canvas.drawCircle(point1.x, point1.y, CIRCLE_RADIUS_DP*scale, arrowPaint);

                    // draw the two vertical lines with third (vertical) line in between
                    canvas.drawLine(scanLine1_pos, 0, scanLine1_pos, service.SY, scanLine12Paint);
                    canvas.drawLine(scanLine2_pos, 0, scanLine2_pos, service.SY, scanLine12Paint);
                    canvas.drawLine(scanLine3_pos, 0, scanLine3_pos, service.SY, scanLine34Paint);
                    break;
                case yscan1a:
                case yscan1b:
                    // when selecting second point, draw a circle around first point
                    if(selectorState == SelectorState.yscan1b)
                        canvas.drawCircle(point1.x, point1.y, CIRCLE_RADIUS_DP*scale, arrowPaint);

                    // draw the third (vertical) line
                    canvas.drawLine(scanLine3_pos, 0, scanLine3_pos, service.SY, scanLine34Paint);
                    // draw the two horizontal lines with the band in between
                    canvas.drawLine(0, scanLine1_pos, service.SX, scanLine1_pos, scanLine12Paint);
                    canvas.drawRect(0, scanLine1_pos, service.SX, scanLine2_pos, scanBandPaint);
                    canvas.drawLine(0, scanLine2_pos, service.SX, scanLine2_pos, scanLine12Paint);
                    break;
                case yscan2a:
                case yscan2b:
                    // when selecting second point, draw a circle around first point
                    if(selectorState == SelectorState.yscan2b)
                        canvas.drawCircle(point1.x, point1.y, CIRCLE_RADIUS_DP*scale, arrowPaint);

                    // draw the third (vertical) line
                    canvas.drawLine(scanLine3_pos, 0, scanLine3_pos, service.SY, scanLine34Paint);
                    // draw the two horizontal lines with fourth (horizontal) line in between
                    canvas.drawLine(0, scanLine1_pos, service.SX, scanLine1_pos, scanLine12Paint);
                    canvas.drawLine(0, scanLine2_pos, service.SX, scanLine2_pos, scanLine12Paint);
                    canvas.drawLine(0, scanLine4_pos, service.SX, scanLine4_pos, scanLine34Paint);
                    break;

                case singleswitch_menu:
                    drawMenu(canvas, singleSwitchMenu);
                    break;
                case main1_menu:
                    drawMenu(canvas, mainMenu1);
                    break;
                case main2_menu:
                    drawMenu(canvas, mainMenu2);
                    break;
                case home1_menu:
                    drawMenu(canvas, homeMenu1);
                    break;
                case home2_menu:
                    drawMenu(canvas, homeMenu2);
                    break;
                case gestures_menu:
                    drawMenu(canvas, gesturesMenu);
                    break;
                case drag_menu:
                    canvas.drawCircle(point1.x, point1.y, CIRCLE_RADIUS_DP*scale, arrowPaint);
                    drawArrow(arrowPaint, canvas, point1, point2, ARROW_SIZE_DP*scale);
                    drawMenu(canvas, dragMenu);
                    break;
                case scroll_menu:
                    drawMenu(canvas, scrollMenu);
                    break;
                case zoom_menu:
                    drawMenu(canvas, zoomMenu);
                    break;
                case globals_menu:
                    drawMenu(canvas, globalsMenu);
                    break;
                case stop_menu:
                    drawMenu(canvas, stopMenu);
                    break;
                case keyboard_menu:
                    drawMenu(canvas, keyboardMenu);
                    break;
            }
        }

        //canvas.drawBitmap(bmCursor, x, y, null);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return true;
        //return super.onTouchEvent(event);
    }

    void dispatchMenuItem(MenuItem.ItemType itemType) {
        selectTime = SystemClock.elapsedRealtime();
        initialSelectPause = true;  // used only in single switch mode

        switch(itemType) {
            // ### Keyboard Menu Item ###
            case hide_keyboard:
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                setIdleState();
                break;
            // ################################
            // ### SINGLE SWITCH MENU ITEMS ###
            // ################################
            case select_point:
                initScanLines();
                scanLoopCount = 0;
                maxLoopCount = prefs.getInt("ScanLoopCount", ScanSettingsActivity.DEFAULT_SCANLOOPCOUNT);
                selectorState = SelectorState.xscan1a;
                menuStack.clear();
                break;
            case open_menu:
                SelectorState nextMenu = SelectorState.main2_menu;
                // open home menu 2 if home screen is opened
                if(homeOpened)
                    nextMenu = SelectorState.home2_menu;
                setNextMenu(nextMenu);
                break;

            // ############################
            // ### MAIN/HOME MENU ITEMS ###
            // ############################
            case tap:
                service.dispatchGesture(service.createClick(point1), null, null);
                setIdleState();
                break;
            case gestures:
                setNextMenu(SelectorState.gestures_menu);
                break;
            case globals:
                setNextMenu(SelectorState.globals_menu);
                break;
            case next_page: {
                PointF point1 = new PointF((float) service.SX * 0.8f, (float) service.SY/2);
                PointF point2 = new PointF((float) service.SX * 0.2f, (float) service.SY/2);
                service.dispatchGesture(service.createSwipe(point1, point2,1000), null, null);
                setIdleState();
            } break;
            case previous_page: {
                PointF point1 = new PointF((float) service.SX * 0.2f, (float) service.SY/2);
                PointF point2 = new PointF((float) service.SX * 0.8f, (float) service.SY/2);
                service.dispatchGesture(service.createSwipe(point1, point2, 1000), null, null);
                setIdleState();
            } break;

            // ###########################
            // ### GESTURES MENU ITEMS ###
            // ###########################
            case drag:
                initScanLines();
                selectorState = SelectorState.xscan1b;
                break;
            case tap_and_hold:
                service.dispatchGesture(service.createTapAndHold(point1), null, null);
                setIdleState();
                break;
            case scroll:
                setNextMenu(SelectorState.scroll_menu);
                break;
            case zoom_rotate:
                setNextMenu(SelectorState.zoom_menu);
                break;

            // #######################
            // ### DRAG MENU ITEMS ###
            // #######################
            case drag_done:
                service.dispatchGesture(service.createSwipe(point1, point2, 2000), null, null);
                setIdleState();
                break;
            case drag_redo:
                initScanLines();
                selectorState = SelectorState.xscan1b;
                break;
            case drag_cancel:
                menuStack.clear();
                setIdleState();
                break;

            // #########################
            // ### SCROLL MENU ITEMS ###
            // #########################
            case scroll_down:
                activeGesture = MenuItem.ItemType.scroll_down;
                selectorState = SelectorState.stop_menu;
                activeMenuItem = 0;
                break;
            case scroll_up:
                activeGesture = MenuItem.ItemType.scroll_up;
                selectorState = SelectorState.stop_menu;
                activeMenuItem = 0;
                break;
            case scroll_left:
                activeGesture = MenuItem.ItemType.scroll_left;
                selectorState = SelectorState.stop_menu;
                activeMenuItem = 0;
                break;
            case scroll_right:
                activeGesture = MenuItem.ItemType.scroll_right;
                selectorState = SelectorState.stop_menu;
                activeMenuItem = 0;
                break;

            // ##############################
            // ### ZOOM/ROTATE MENU ITEMS ###
            // ##############################
            case zoom_in:
                activeGesture = MenuItem.ItemType.zoom_in;
                selectorState = SelectorState.stop_menu;
                activeMenuItem = 0;
                break;
            case zoom_out:
                activeGesture = MenuItem.ItemType.zoom_out;
                selectorState = SelectorState.stop_menu;
                activeMenuItem = 0;
                break;
            case rotate_left:
                activeGesture = MenuItem.ItemType.rotate_left;
                selectorState = SelectorState.stop_menu;
                activeMenuItem = 0;
                break;
            case rotate_right:
                activeGesture = MenuItem.ItemType.rotate_right;
                selectorState = SelectorState.stop_menu;
                activeMenuItem = 0;
                break;

            // #################################
            // ### GLOBAL ACTIONS MENU ITEMS ###
            // #################################
            case back:
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                setIdleState();
                break;
            case home:
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                setIdleState();
                break;
            case recents:
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
                setIdleState();
                break;
            case notifications:
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
                setIdleState();
                break;
            case settings:
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
                setIdleState();
                break;

            default:
                setIdleState();
                break;
        }
    }

    void initScanLines() {
        initialScanPause = true;
        initialScanPauseTime = SystemClock.elapsedRealtime();
        scanLine_dir = +1;  // move to right initially
        scanLine1_pos = -scale_cm * SCAN_BAND_WIDTH_CM/2;
        scanLine2_pos = scale_cm * SCAN_BAND_WIDTH_CM/2;
    }

    void startAssistantTimeout() {
        synchronized (lock) {
            selectorState = SelectorState.assistant_timeout;
            assistant_t0 = SystemClock.elapsedRealtime();
            assistantTimeLeft = ASSISTANT_TIMEOUT_PERIOD;
        }
    }

    void activateSwitch(SwitchAction switchAction) {
        synchronized (lock) {   // lock access to state variables
            if(service.screenOff) {
                if(switchUnlock) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                    return;
                }
            }

            // disable switch control when GestureTestActivity is opened
            if(service.activityForeground || service.pauseSwitchControl)
                return;

            switch(switchAction) {

                // ###################
                // ##### SELECT ######
                // ###################
                case select:
                    switch (selectorState) {
                        case keyboard: {
                            int activeRow = keyboard.activeRow;
                            int activeGroup = keyboard.activeGroup;
                            int activeElement = keyboard.activeElement;

                            initialSelectPause = true;
                            selectTime = SystemClock.elapsedRealtime();

                            if (activeRow == Keyboard.INACTIVE) {
                                keyboard.activeRow = 0; // todo: make sure this is correct or should I activate another grid??
                            } else if (activeRow == Keyboard.NONE_SELECTED) {
                                /*keyboard.activeRow = Keyboard.INACTIVE;
                                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                                setIdleState();*/
                                setNextMenu(SelectorState.keyboard_menu);
                            } else {
                                if (activeGroup == Keyboard.INACTIVE) {
                                    keyboard.activeGroup = 0; // alternatively set it to Keyboard.NONE_SELECTED
                                    if(keyboard.getRowGroupCount(activeRow) == 1) {
                                        // if we have a single-group row, directly access its first element
                                        keyboard.activeElement = 0;
                                    }
                                } else if (activeGroup == Keyboard.NONE_SELECTED) {
                                    keyboard.activeGroup = Keyboard.INACTIVE;
                                } else {
                                    if (activeElement == Keyboard.INACTIVE) {
                                        keyboard.activeElement = 0; // alternatively set it to Keyboard.NONE_SELECTED
                                    } else if (activeElement == Keyboard.NONE_SELECTED) {
                                        keyboard.activeElement = Keyboard.INACTIVE;
                                        if(keyboard.getRowGroupCount(activeRow) == 1) {
                                            // if we have a single-group row then also set activeGroup to INACTIVE
                                            // (this goes back to selecting active rows)
                                            keyboard.activeGroup = Keyboard.INACTIVE;
                                        }
                                    } else {
                                        Keyboard.Element element = keyboard.getElement(activeRow, activeGroup, activeElement);
                                        PointF point = new PointF();
                                        point.x = (float)(element.boundsInScreen.right + element.boundsInScreen.left)/2;
                                        point.y = (float)(element.boundsInScreen.bottom + element.boundsInScreen.top)/2;
                                        service.dispatchGesture(service.createClick(point), null, null);

                                        keyboard = null;
                                        setIdleState();
                                    }
                                }
                            }
                        } break;

                        case idle:
                            // TODO: Make sure these lines really don't do anything...
                            /*if(keyboard != null) {
                                initialSelectPause = true;
                                selectTime = SystemClock.elapsedRealtime();
                                selectorState = SelectorState.keyboard;
                            } else */if(keyboard == null && singleSwitchMode) {
                                setNextMenu(SelectorState.singleswitch_menu);
                            } else {
                                initScanLines();
                                scanLoopCount = 0;
                                maxLoopCount = prefs.getInt("ScanLoopCount", ScanSettingsActivity.DEFAULT_SCANLOOPCOUNT);
                                selectorState = SelectorState.xscan1a;
                            }
                            break;

                        case xscan1a:
                        case xscan1b:
                            // if lines were moving right, let the third line preserve this direction; (same for left)
                            if(scanLine_dir == +1) {
                                scanLine3_pos = Math.max(scanLine1_pos, 0);
                                scanLine_dir = +1;
                            } else if(scanLine_dir == -1) {
                                scanLine3_pos = Math.min(scanLine2_pos, service.SX);
                                scanLine_dir = -1;
                            }

                            if(selectorState == SelectorState.xscan1a)
                                selectorState = SelectorState.xscan2a;
                            else if(selectorState == SelectorState.xscan1b)
                                selectorState = SelectorState.xscan2b;
                            break;
                        case xscan2a:
                        case xscan2b:
                            initialScanPause = true;
                            initialScanPauseTime = SystemClock.elapsedRealtime();
                            scanLine1_pos = -scale_cm * SCAN_BAND_WIDTH_CM/2;
                            scanLine2_pos = scale_cm * SCAN_BAND_WIDTH_CM/2;
                            scanLine_dir = +1;

                            if(selectorState == SelectorState.xscan2a)
                                selectorState = SelectorState.yscan1a;
                            else if(selectorState == SelectorState.xscan2b)
                                selectorState = SelectorState.yscan1b;
                            break;

                        case yscan1a:
                        case yscan1b:
                            // if lines were moving down, let the fourth line preserve this direction; (same for up)
                            if(scanLine_dir == +1) {
                                scanLine4_pos = Math.max(scanLine1_pos, 0);
                                scanLine_dir = +1;
                            } else if(scanLine_dir == -1) {
                                scanLine4_pos = Math.min(scanLine2_pos, service.SY);
                                scanLine_dir = -1;
                            }

                            if(selectorState == SelectorState.yscan1a)
                                selectorState = SelectorState.yscan2a;
                            if(selectorState == SelectorState.yscan1b)
                                selectorState = SelectorState.yscan2b;
                            break;
                        case yscan2a:
                        case yscan2b:
                            if(selectorState == SelectorState.yscan2a) {
                                point1 = new PointF(scanLine3_pos, scanLine4_pos);
                                // open main menu
                                SelectorState nextMenu;
                                nextMenu = SelectorState.main1_menu;
                                // open home menu 1 if home screen is opened
                                if(homeOpened)
                                    nextMenu = SelectorState.home1_menu;
                                setNextMenu(nextMenu);
                            } else if(selectorState == SelectorState.yscan2b) {
                                point2 = new PointF(scanLine3_pos, scanLine4_pos);
                                // open drag menu (and also draw an arrow)
                                //menuTime = SystemClock.elapsedRealtime();
                                selectTime = SystemClock.elapsedRealtime();
                                activeMenuItem = 0;
                                selectorState = SelectorState.drag_menu;
                            }
                            break;

                        case singleswitch_menu:
                            if(activeMenuItem == ITEM_NONE)
                                setIdleState();
                            else dispatchMenuItem(singleSwitchMenu.get(activeMenuItem).itemType);
                            break;
                        case main1_menu:
                            if(activeMenuItem == ITEM_NONE)
                                setIdleState();
                            else dispatchMenuItem(mainMenu1.get(activeMenuItem).itemType);
                            break;
                        case main2_menu:
                            if(activeMenuItem == ITEM_NONE) {
                                if(menuStack.size() <= 1)
                                    setIdleState();
                                else setPreviousMenu(); // used to return to singleswitch_menu
                            }
                            else dispatchMenuItem(mainMenu2.get(activeMenuItem).itemType);
                            break;
                        case home1_menu:
                            if(activeMenuItem == ITEM_NONE)
                                setIdleState();
                            else dispatchMenuItem(homeMenu1.get(activeMenuItem).itemType);
                            break;
                        case home2_menu:
                            if(activeMenuItem == ITEM_NONE) {
                                if(menuStack.size() <= 1)
                                    setIdleState();
                                else setPreviousMenu(); // used to return to singleswitch_menu
                            }
                            else dispatchMenuItem(homeMenu2.get(activeMenuItem).itemType);
                            break;
                        case gestures_menu:
                            if(activeMenuItem == ITEM_NONE)
                                setPreviousMenu();
                            else dispatchMenuItem(gesturesMenu.get(activeMenuItem).itemType);
                            break;
                        case drag_menu:
                            if(activeMenuItem == ITEM_NONE)
                                setPreviousMenu();
                            else dispatchMenuItem(dragMenu.get(activeMenuItem).itemType);
                            break;
                        case scroll_menu:
                            if(activeMenuItem == ITEM_NONE)
                                setPreviousMenu();
                            else dispatchMenuItem(scrollMenu.get(activeMenuItem).itemType);
                            break;
                        case zoom_menu:
                            if(activeMenuItem == ITEM_NONE)
                                setPreviousMenu();
                            else dispatchMenuItem(zoomMenu.get(activeMenuItem).itemType);
                            break;
                        case globals_menu:
                            if(activeMenuItem == ITEM_NONE)
                                setPreviousMenu();
                            else dispatchMenuItem(globalsMenu.get(activeMenuItem).itemType);
                            break;
                        case stop_menu:
                            setIdleState();
                            break;
                        case keyboard_menu:
                            if(activeMenuItem == ITEM_NONE)
                                setIdleState();
                            else dispatchMenuItem(keyboardMenu.get(activeMenuItem).itemType);
                            break;
                    }
                    break;

                // ###############################
                // ###### [NEXT/PREV]/MENU #######
                // ###############################
                case prev:
                case next:
                    switch(selectorState) {
                        case keyboard:
                            if(keyboard.activeRow == Keyboard.INACTIVE) {
                                // todo: keyboard.selectNextGrid();
                            } else if(keyboard.activeRow == Keyboard.NONE_SELECTED) {
                                if(switchAction == SwitchAction.next) keyboard.selectNextRow();
                                if(switchAction == SwitchAction.prev) keyboard.selectPrevRow();
                            } else {
                                if(keyboard.activeGroup == Keyboard.INACTIVE) {
                                    if(switchAction == SwitchAction.next) keyboard.selectNextRow();
                                    if(switchAction == SwitchAction.prev) keyboard.selectPrevRow();
                                } else if(keyboard.activeGroup == Keyboard.NONE_SELECTED) {
                                    if(switchAction == SwitchAction.next) keyboard.selectNextGroup();
                                    if(switchAction == SwitchAction.prev) keyboard.selectPrevGroup();
                                } else {
                                    if(keyboard.activeElement == Keyboard.INACTIVE) {
                                        if(switchAction == SwitchAction.next) keyboard.selectNextGroup();
                                        if(switchAction == SwitchAction.prev) keyboard.selectPrevGroup();
                                    } else if(keyboard.activeElement == Keyboard.NONE_SELECTED) {
                                        if(switchAction == SwitchAction.next) keyboard.selectNextElement();
                                        if(switchAction == SwitchAction.prev) keyboard.selectPrevElement();
                                    } else {
                                        if(switchAction == SwitchAction.next) keyboard.selectNextElement();
                                        if(switchAction == SwitchAction.prev) keyboard.selectPrevElement();
                                    }
                                }
                            }
                            break;

                        case main1_menu:
                            menuTime = SystemClock.elapsedRealtime();
                            if(switchAction == SwitchAction.next) selectNextMenuItem(mainMenu1.size());
                            if(switchAction == SwitchAction.prev) selectPrevMenuItem(mainMenu1.size());
                            break;
                        case main2_menu:
                            menuTime = SystemClock.elapsedRealtime();
                            if(switchAction == SwitchAction.next) selectNextMenuItem(mainMenu2.size());
                            if(switchAction == SwitchAction.prev) selectPrevMenuItem(mainMenu2.size());
                            break;
                        case home1_menu:
                            menuTime = SystemClock.elapsedRealtime();
                            if(switchAction == SwitchAction.next) selectNextMenuItem(homeMenu1.size());
                            if(switchAction == SwitchAction.prev) selectPrevMenuItem(homeMenu1.size());
                            break;
                        case home2_menu:
                            menuTime = SystemClock.elapsedRealtime();
                            if(switchAction == SwitchAction.next) selectNextMenuItem(homeMenu2.size());
                            if(switchAction == SwitchAction.prev) selectPrevMenuItem(homeMenu2.size());
                            break;
                        case gestures_menu:
                            menuTime = SystemClock.elapsedRealtime();
                            if(switchAction == SwitchAction.next) selectNextMenuItem(gesturesMenu.size());
                            if(switchAction == SwitchAction.prev) selectPrevMenuItem(gesturesMenu.size());
                            break;
                        case drag_menu:
                            menuTime = SystemClock.elapsedRealtime();
                            if(switchAction == SwitchAction.next) selectNextMenuItem(dragMenu.size());
                            if(switchAction == SwitchAction.prev) selectPrevMenuItem(dragMenu.size());
                            break;
                        case scroll_menu:
                            menuTime = SystemClock.elapsedRealtime();
                            if(switchAction == SwitchAction.next) selectNextMenuItem(scrollMenu.size());
                            if(switchAction == SwitchAction.prev) selectPrevMenuItem(scrollMenu.size());
                            break;
                        case zoom_menu:
                            menuTime = SystemClock.elapsedRealtime();
                            if(switchAction == SwitchAction.next) selectNextMenuItem(zoomMenu.size());
                            if(switchAction == SwitchAction.prev) selectPrevMenuItem(zoomMenu.size());
                            break;
                        case globals_menu:
                            menuTime = SystemClock.elapsedRealtime();
                            if(switchAction == SwitchAction.next) selectNextMenuItem(globalsMenu.size());
                            if(switchAction == SwitchAction.prev) selectPrevMenuItem(globalsMenu.size());
                            break;

                        case stop_menu:
                            menuTime = SystemClock.elapsedRealtime();   // here menu time is used to time repeated gesture period
                            activeMenuItem = 0;
                            break;

                        case keyboard_menu:
                            menuTime = SystemClock.elapsedRealtime();
                            if(switchAction == SwitchAction.next) selectNextMenuItem(keyboardMenu.size());
                            if(switchAction == SwitchAction.prev) selectPrevMenuItem(keyboardMenu.size());
                            break;

                        default:    // this gets executed when selectorState is "idle"
                            SelectorState nextMenu = SelectorState.main2_menu;
                            // open home menu 2 if home screen is opened
                            if(homeOpened)
                                nextMenu = SelectorState.home2_menu;
                            setNextMenu(nextMenu);
                            break;
                    }
                    break;

                // #############################
                // ### DIRECT SWITCH ACTIONS ###
                // #############################
                case scroll_down:
                    dispatchMenuItem(MenuItem.ItemType.scroll_down);
                    break;
                case scroll_up:
                    dispatchMenuItem(MenuItem.ItemType.scroll_up);
                    break;
                case scroll_left:
                    dispatchMenuItem(MenuItem.ItemType.scroll_left);
                    break;
                case scroll_right:
                    dispatchMenuItem(MenuItem.ItemType.scroll_right);
                    break;
                case zoom_in:
                    dispatchMenuItem(MenuItem.ItemType.zoom_in);
                    break;
                case zoom_out:
                    dispatchMenuItem(MenuItem.ItemType.zoom_out);
                    break;
                case rotate_left:
                    dispatchMenuItem(MenuItem.ItemType.rotate_left);
                    break;
                case rotate_right:
                    dispatchMenuItem(MenuItem.ItemType.rotate_right);
                    break;
                case home:
                    dispatchMenuItem(MenuItem.ItemType.home);
                    break;
                case back:
                    dispatchMenuItem(MenuItem.ItemType.back);
                    break;
                case recents:
                    dispatchMenuItem(MenuItem.ItemType.recents);
                    break;
                case notifications:
                    dispatchMenuItem(MenuItem.ItemType.notifications);
                    break;
                case settings:
                    dispatchMenuItem(MenuItem.ItemType.settings);
                    break;
                case assistant:
                    service.startActivity(new Intent(Intent.ACTION_VOICE_COMMAND)
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                case volume_up: {
                    AudioManager audio = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND);
                } break;
                case volume_down: {
                    AudioManager audio = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND);
                } break;
            }
        }
    }

    Stack<SelectorState> menuStack = new Stack<>();

    int ITEM_NONE = -1;

    void selectNextMenuItem(int numItems) {
        if(activeMenuItem + 1 > numItems - 1)
            activeMenuItem = ITEM_NONE;
        else activeMenuItem++;
    }

    void selectPrevMenuItem(int numItems) {
        if(activeMenuItem == ITEM_NONE)
            activeMenuItem = numItems - 1;
        else activeMenuItem--;
    }

    void setIdleState() {
        menuStack.clear();
        selectorState = SelectorState.idle;
    }

    void setPreviousMenu() {
        menuStack.pop();

        initialSelectPause = true;
        selectTime = SystemClock.elapsedRealtime();
        activeMenuItem = 0;
        selectorState = menuStack.peek();
    }

    void setNextMenu(SelectorState selectorMenu)
    {
        menuStack.push(selectorMenu);

        initialSelectPause = true;
        selectTime = SystemClock.elapsedRealtime();
        menuTime = SystemClock.elapsedRealtime();
        activeMenuItem = 0;
        selectorState = selectorMenu;
    }


    void setMenuRects(ArrayList<MenuItem> menu, MenuType menuType) {
        // For now, let's draw them centered in the middle of screen
        int numItems;
        float x_offset, y_offset;
        float width, height;
        numItems = menu.size();

        if(menuType == MenuType.horizontal) {
            width = numItems * ITEM_SIZE_DP * scale;
            height = 1 * ITEM_SIZE_DP * scale;
            x_offset = (float) service.SX / 2 - width / 2;
            y_offset = (float) service.SY / 2 - height / 2;

            for (int k = 0; k < menu.size(); k++) {
                MenuItem item = menu.get(k);
                item.rect.left = x_offset + (ITEM_SIZE_DP * scale) * k;
                item.rect.right = x_offset + (ITEM_SIZE_DP * scale) * (k + 1);
                item.rect.top = y_offset;
                item.rect.bottom = y_offset + ITEM_SIZE_DP * scale;
            }
        } else if(menuType == MenuType.vertical) {
            height = numItems * ITEM_SIZE_DP * scale;
            width = 1 * ITEM_SIZE_DP * scale;
            x_offset = 0;
            y_offset = (float) service.SY / 2 - height / 2;

            for (int k = 0; k < menu.size(); k++) {
                MenuItem item = menu.get(k);
                item.rect.left = x_offset;
                item.rect.right = x_offset + width;
                item.rect.top = y_offset + (ITEM_SIZE_DP * scale) * k;
                item.rect.bottom = y_offset + (ITEM_SIZE_DP * scale) * (k + 1);
            }
        }
    }

    void setScreenRotated()
    {
        // reset the element selector to initial state
        synchronized (lock) {
            setIdleState();
            prepareMenu();
        }
    }

    void drawArrow(Paint paint, Canvas canvas, PointF p1, PointF p2, float L) {
        float fsin, fcos;
        double d;

        if(p1.equals(p2))
            return;

        d = Math.sqrt(Math.pow(p2.x - p1.x, 2) + Math.pow(p2.y - p1.y, 2));
        fsin = (p2.y - p1.y)/(float)d;
        fcos = (p2.x - p1.x)/(float)d;
        PointF p3 = new PointF(p2.x - L/2*(fsin + fcos), p2.y + L/2*(fcos - fsin));
        PointF p4 = new PointF(p2.x + L/2*(fsin - fcos), p2.y - L/2*(fsin + fcos));

        canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint);

        Path path = new Path();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.moveTo(p2.x, p2.y);
        path.lineTo(p3.x, p3.y);
        path.lineTo(p4.x, p4.y);
        path.close();

        canvas.drawPath(path, paint);
    }

    // https://stackoverflow.com/questions/12166476/android-canvas-drawtext-set-font-size-from-width
    private static void setTextSizeForWidth(Paint paint, float desiredWidth, String text) {
        // Pick a reasonably large value for the test. Larger values produce
        // more accurate results, but may cause problems with hardware
        // acceleration. But there are workarounds for that, too; refer to
        // http://stackoverflow.com/questions/6253528/font-size-too-large-to-fit-in-cache
        final float testTextSize = 48f;

        // Get the bounds of the text, using our testTextSize.
        paint.setTextSize(testTextSize);
        Rect bounds = new Rect();
        paint.getTextBounds(text, 0, text.length(), bounds);

        // Calculate the desired size as a proportion of our testTextSize.
        float desiredTextSize = testTextSize * desiredWidth / bounds.width();

        // Set the paint for that size.
        paint.setTextSize(desiredTextSize);
    }

    void implementMenuTimeout() {
        if((float)(SystemClock.elapsedRealtime() - menuTime)/1000 >= MENU_TIMEOUT)
            setIdleState();
    }

    void menuAutoSelect(ArrayList<MenuItem> menu)
    {
        if(singleSwitchMode) {
            if (initialSelectPause) {
                if ((float) (SystemClock.elapsedRealtime() - selectTime) / 1000.0f < SELECT_INITIAL_PAUSE)
                    return;
                else {
                    initialSelectPause = false;
                    selectTime = SystemClock.elapsedRealtime();
                    return;
                }
            } else {
                if ((float) (SystemClock.elapsedRealtime() - selectTime) / 1000.0f >= SELECT_PERIOD) {
                    selectNextMenuItem(menu.size()); //activeMenuItem = (activeMenuItem + 1) % menu.size(); // circular selection
                    selectTime = SystemClock.elapsedRealtime();
                    return;  // exits the routine
                }
            }
        }
    }

    void keyboardAutoSelect()
    {
        if(singleSwitchMode) {
            if (initialSelectPause) {
                if ((float) (SystemClock.elapsedRealtime() - selectTime) / 1000.0f < SELECT_INITIAL_PAUSE)
                    return;
                else {
                    initialSelectPause = false;
                    selectTime = SystemClock.elapsedRealtime();
                    return;
                }
            } else {
                if ((float) (SystemClock.elapsedRealtime() - selectTime) / 1000.0f >= SELECT_PERIOD) {
                    if(keyboard.activeElement != Keyboard.INACTIVE)
                        keyboard.selectNextElement();
                    else if(keyboard.activeGroup != Keyboard.INACTIVE)
                        keyboard.selectNextGroup();
                    else if(keyboard.activeRow != Keyboard.INACTIVE)
                        keyboard.selectNextRow();

                    selectTime = SystemClock.elapsedRealtime();
                    return;  // exits the routine
                }
            }
        }
    }

    Runnable Thr_ViewUpdater = new Runnable() {
        @Override
        public void run() {
            long t2, t1;
            double iterationPeriod;

            t1 = t2 = SystemClock.elapsedRealtimeNanos();

            while(true) {
                if(initStop > 0) {
                    synchronized (lock) {
                        initStop--;
                        activeThreads--;
                        updaterThread = null;
                        setIdleState();
                        postInvalidate();
                        break;
                    }
                }

                try {
                    Thread.sleep(THREAD_SLEEPTIME);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                t2 = SystemClock.elapsedRealtimeNanos();
                iterationPeriod = (t2-t1)/1E9;
                t1 = SystemClock.elapsedRealtimeNanos();
                //Log.i(TAG, "Iteration period = " + String.format("%.2f", 1000*iterationPeriod));

                synchronized (lock) {   // lock access to state variables
                    switch(selectorState) {
                        case assistant_timeout:
                            long dt = SystemClock.elapsedRealtime() - assistant_t0;
                            assistantTimeLeft = ASSISTANT_TIMEOUT_PERIOD - (int)((double)dt/1000.0);
                            if(assistantTimeLeft <= 0) {
                                // exit assistant_timeout state and re-enable switches
                                setIdleState();
                                service.activityForeground = false;
                            }
                            break;
                        case keyboard:
                            keyboardAutoSelect();
                            break;
                        case singleswitch_menu:
                            menuAutoSelect(singleSwitchMenu);
                            break;

                        case xscan1a:
                        case xscan1b:
                            if(initialScanPause) {
                                if((float)(SystemClock.elapsedRealtime() - initialScanPauseTime)/1000.0f < SCAN_INITIAL_PAUSE)
                                    break;  // exits the routine (and therefore doesn't move the lines)
                                else
                                    initialScanPause = false;
                            }

                            scanLine1_pos += scanLine_dir * SCAN1_CMPERSEC * scale_cm * iterationPeriod;
                            scanLine2_pos += scanLine_dir * SCAN1_CMPERSEC * scale_cm * iterationPeriod;
                            if(scanLine_dir == +1) {
                                if(scanLine2_pos >= (service.SX + scale_cm*SCAN_BAND_WIDTH_CM/2)) {
                                    scanLine_dir = -1;
                                }
                            } else if(scanLine_dir == -1) {
                                if(scanLine1_pos <= (0 - scale_cm*SCAN_BAND_WIDTH_CM/2)) {
                                    scanLine_dir = +1;

                                    if(selectorState == SelectorState.xscan1a)
                                        scanLoopCount++;
                                }
                            }

                            if(selectorState == SelectorState.xscan1a &&
                                scanLoopCount >= maxLoopCount &&
                                scanLine1_pos >= 0) {
                                // Scan band looped enough; stop point scan
                                setIdleState();
                            }

                            break;
                        case xscan2a:
                        case xscan2b:
                            scanLine3_pos += scanLine_dir * SCAN2_CMPERSEC * scale_cm * iterationPeriod;
                            if(scanLine_dir == +1) {
                                if(scanLine3_pos >= Math.min(scanLine2_pos, service.SX)) {
                                    scanLine_dir = -1;
                                }
                            } else if(scanLine_dir == -1) {
                                if(scanLine3_pos <= Math.max(scanLine1_pos, 0)) {
                                    scanLine_dir = +1;
                                }
                            }
                            break;
                        case yscan1a:
                        case yscan1b:
                            if(initialScanPause) {
                                if((float)(SystemClock.elapsedRealtime() - initialScanPauseTime)/1000.0f < SCAN_INITIAL_PAUSE)
                                    break;  // exits the routine (and therefore doesn't move the lines)
                                else
                                    initialScanPause = false;
                            }

                            scanLine1_pos += scanLine_dir * SCAN1_CMPERSEC * scale_cm * iterationPeriod;
                            scanLine2_pos += scanLine_dir * SCAN1_CMPERSEC * scale_cm * iterationPeriod;
                            if(scanLine_dir == +1) {
                                if(scanLine2_pos >= (service.SY + scale_cm*SCAN_BAND_WIDTH_CM/2)) {
                                    scanLine_dir = -1;
                                }
                            } else if(scanLine_dir == -1) {
                                if(scanLine1_pos <= (0 - scale_cm*SCAN_BAND_WIDTH_CM/2)) {
                                    scanLine_dir = +1;
                                }
                            }
                            break;
                        case yscan2a:
                        case yscan2b:
                            scanLine4_pos += scanLine_dir * SCAN2_CMPERSEC * scale_cm * iterationPeriod;
                            if(scanLine_dir == +1) {
                                if(scanLine4_pos >= Math.min(scanLine2_pos, service.SY)) {
                                    scanLine_dir = -1;
                                }
                            } else if(scanLine_dir == -1) {
                                if(scanLine4_pos <= Math.max(scanLine1_pos, 0)) {
                                    scanLine_dir = +1;
                                }
                            }
                            break;

                        case main1_menu:
                            menuAutoSelect(mainMenu1);
                            if(!singleSwitchMode) implementMenuTimeout();
                            break;
                        case main2_menu:
                            menuAutoSelect(mainMenu2);
                            if(!singleSwitchMode) implementMenuTimeout();
                        case home1_menu:
                            menuAutoSelect(homeMenu1);
                            if(!singleSwitchMode) implementMenuTimeout();
                        case home2_menu:
                            menuAutoSelect(homeMenu2);
                            if(!singleSwitchMode) implementMenuTimeout();
                            break;
                        case gestures_menu:
                            menuAutoSelect(gesturesMenu);
                            break;
                        case drag_menu:
                            menuAutoSelect(dragMenu);
                            break;
                        case scroll_menu:
                            menuAutoSelect(scrollMenu);
                            break;
                        case zoom_menu:
                            menuAutoSelect(zoomMenu);
                            break;
                        case globals_menu:
                            menuAutoSelect(globalsMenu);
                            break;

                        case keyboard_menu:
                            menuAutoSelect(keyboardMenu);
                            break;

                        case stop_menu:
                            PointF pt1, pt2, pt3, pt4;

                            //REMOVE menuAutoSelect(stopMenu) here!!!
                            //menuAutoSelect(stopMenu);

                            if((float)(SystemClock.elapsedRealtime() - menuTime)/1000 >= GESTURE_PERIOD) {
                                menuTime = SystemClock.elapsedRealtime();   // reset the timer
                                // perform the gesture
                                switch(activeGesture) {
                                    case scroll_down:
                                        pt1 = new PointF((float)service.SX/2, (float)service.SY/2);
                                        pt2 = new PointF(pt1.x, pt1.y - SCROLL_LENGTH_CM*scale_cm);
                                        service.dispatchGesture(service.createSwipe(pt1, pt2, (int)(1000*GESTURE_PERIOD*0.75)), null, null);
                                        break;
                                    case scroll_up:
                                        pt1 = new PointF((float)service.SX/2, (float)service.SY/2);
                                        pt2 = new PointF(pt1.x, pt1.y + SCROLL_LENGTH_CM*scale_cm);
                                        service.dispatchGesture(service.createSwipe(pt1, pt2, (int)(1000*GESTURE_PERIOD*0.75)), null, null);
                                        break;
                                    case scroll_left:
                                        pt1 = new PointF((float)service.SX/2, (float)service.SY/2);
                                        pt2 = new PointF(pt1.x + SCROLL_LENGTH_CM*scale_cm, pt1.y);
                                        service.dispatchGesture(service.createSwipe(pt1, pt2, (int)(1000*GESTURE_PERIOD*0.75)), null, null);
                                        break;
                                    case scroll_right:
                                        pt1 = new PointF((float)service.SX/2, (float)service.SY/2);
                                        pt2 = new PointF(pt1.x - SCROLL_LENGTH_CM*scale_cm, pt1.y);
                                        service.dispatchGesture(service.createSwipe(pt1, pt2, (int)(1000*GESTURE_PERIOD*0.75)), null, null);
                                        break;
                                    case zoom_in:
                                        pt1 = new PointF((float)service.SX/2 - scale_cm*ZOOM_IN_OFFSET_CM/2, (float)service.SY/2);
                                        pt2 = new PointF(pt1.x - scale_cm*ZOOM_LENGTH_CM/2, (float)service.SY/2);
                                        pt3 = new PointF((float)service.SX/2 + scale_cm*ZOOM_IN_OFFSET_CM/2, (float)service.SY/2);
                                        pt4 = new PointF(pt3.x + scale_cm*ZOOM_LENGTH_CM/2, (float)service.SY/2);
                                        service.dispatchGesture(service.createZoom(pt1, pt2, pt3, pt4, (int)(1000*GESTURE_PERIOD*0.75)), null, null);
                                        break;
                                    case zoom_out:
                                        pt1 = new PointF((float)service.SX/2 - scale_cm*ZOOM_OUT_OFFSET_CM/2, (float)service.SY/2);
                                        pt2 = new PointF(pt1.x + scale_cm*ZOOM_LENGTH_CM/2, (float)service.SY/2);
                                        pt3 = new PointF((float)service.SX/2 + scale_cm*ZOOM_OUT_OFFSET_CM/2, (float)service.SY/2);
                                        pt4 = new PointF(pt3.x - scale_cm*ZOOM_LENGTH_CM/2, (float)service.SY/2);
                                        service.dispatchGesture(service.createZoom(pt1, pt2, pt3, pt4, (int)(1000*GESTURE_PERIOD*0.75)), null, null);
                                        break;
                                    case rotate_left:
                                        pt1 = new PointF((float)service.SX/2 + scale_cm*ROTATE_OFFSET_CM/2 * (float)Math.cos(0),
                                                        (float)service.SY/2 + scale_cm*ROTATE_OFFSET_CM/2 * (float)Math.sin(0));
                                        pt2 = new PointF((float)service.SX/2 + scale_cm*ROTATE_OFFSET_CM/2 * (float)Math.cos(0+ROTATE_ANGLE),
                                                        (float)service.SY/2 + scale_cm*ROTATE_OFFSET_CM/2 * (float)Math.sin(0+ROTATE_ANGLE));

                                        pt3 = new PointF((float)service.SX/2 + scale_cm*ROTATE_OFFSET_CM/2 * (float)Math.cos(PI+0),
                                                        (float)service.SY/2 + scale_cm*ROTATE_OFFSET_CM/2 * (float)Math.sin(PI+0));
                                        pt4 = new PointF((float)service.SX/2 + scale_cm*ROTATE_OFFSET_CM/2 * (float)Math.cos(PI+ROTATE_ANGLE),
                                                        (float)service.SY/2 + scale_cm*ROTATE_OFFSET_CM/2 * (float)Math.sin(PI+ROTATE_ANGLE));

                                        service.dispatchGesture(service.createZoom(pt1, pt2, pt3, pt4, (int)(1000*GESTURE_PERIOD*0.75)), null, null);
                                        break;
                                    case rotate_right:
                                        pt1 = new PointF((float)service.SX/2 + scale_cm*ROTATE_OFFSET_CM/2 * (float)Math.cos(0),
                                                (float)service.SY/2 + scale_cm*ROTATE_OFFSET_CM/2 * (float)Math.sin(0));
                                        pt2 = new PointF((float)service.SX/2 + scale_cm*ROTATE_OFFSET_CM/2 * (float)Math.cos(0-ROTATE_ANGLE),
                                                (float)service.SY/2 + scale_cm*ROTATE_OFFSET_CM/2 * (float)Math.sin(0-ROTATE_ANGLE));

                                        pt3 = new PointF((float)service.SX/2 + scale_cm*ROTATE_OFFSET_CM/2 * (float)Math.cos(PI+0),
                                                (float)service.SY/2 + scale_cm*ROTATE_OFFSET_CM/2 * (float)Math.sin(PI+0));
                                        pt4 = new PointF((float)service.SX/2 + scale_cm*ROTATE_OFFSET_CM/2 * (float)Math.cos(PI-ROTATE_ANGLE),
                                                (float)service.SY/2 + scale_cm*ROTATE_OFFSET_CM/2 * (float)Math.sin(PI-ROTATE_ANGLE));

                                        service.dispatchGesture(service.createZoom(pt1, pt2, pt3, pt4, (int)(1000*GESTURE_PERIOD*0.75)), null, null);
                                        break;
                                }
                            }
                            break;
                    }
                }

                //Log.i(TAG, "calling postInvalidate @ " + SystemClock.elapsedRealtime());
                postInvalidate();
            }
        }
    };
}

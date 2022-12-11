package com.obstino.facecontrol;

import static java.lang.Math.PI;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.SystemClock;
import android.util.Log;

import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;

import java.util.List;

class HighpassFilter {
    private double f_sampling;  // sampling frequency
    private double f_cutoff;
    private double prev_x = 0;
    private double prev_y = 0;

    HighpassFilter(double f_sampling, double f_cutoff) {
        this.f_sampling = f_sampling;
        this.f_cutoff = f_cutoff;
        prev_x = 0;
        prev_y = 0;
    }

    void setSamplingFrequency(double f_sampling) {
        this.f_sampling = f_sampling;
    }

    void setCutoffFrequency(double f_cutoff) {
        this.f_cutoff = f_cutoff;
    }

    double computeSample(double x) {
        double alpha;
        double y;

        alpha = (double)f_sampling/(2*PI*f_cutoff + (double)f_sampling);
        y = alpha*prev_y + alpha*(x - prev_x);

        prev_y = y;
        prev_x = x;

        return y;
    }

    double chargeSample(double x) {
        // It takes 5*Tau seconds to 'charge' a RC filter; Tau=1/(2*PI*fc) [sec] and 5 sec = 5*fs samples
        double ret = 0;
        for (int k = 0; k < (int)(5.0*f_sampling / (2.0*PI*f_cutoff)); k++)
            ret = computeSample(x);
        return ret;
    }
}

class LowpassFilter {
    private double f_sampling;  // sampling frequency
    private double f_cutoff;
    private double prev_y = 0;

    LowpassFilter(double f_sampling, double f_cutoff) {
        this.f_sampling = f_sampling;
        this.f_cutoff = f_cutoff;
        prev_y = 0;
    }

    void setSamplingFrequency(double f_sampling) {
        this.f_sampling = f_sampling;
    }

    void setCutoffFrequency(double f_cutoff) {
        this.f_cutoff = f_cutoff;
    }

    double computeSample(double x) {
        double alpha;
        double y;

        alpha = 2*PI*f_cutoff / (2*PI*f_cutoff + f_sampling);
        y = alpha*x + (1 - alpha)*prev_y;

        prev_y = y;
        return y;
    }

    double chargeSample(double x) {
        // It takes 5*Tau seconds to 'charge' a RC filter; Tau=1/(2*PI*fc) [sec] and 5 sec = 5*fs samples
        double ret = 0;
        for (int k = 0; k < (int)(5.0*f_sampling / (2.0*PI*f_cutoff)); k++)
            ret = computeSample(x);
        return ret;
    }
}

public class GestureRecognizer {
    String TAG = "FaceControl.GestureRecognizer";
    FaceControlService service;
    int g_FPS;
    boolean firstRun;

    ToneGenerator toneGenerator;

    GestureRecognizer(Context context) {
        service = (FaceControlService)context;
        g_FPS = 0;
        initializeSensitivities();
        toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
    }

    void initializeSensitivities() {
        SMILE_THRESH_INDEX = service.prefs.getInt("smile_sensitivity", GestureCalibrationActivity.DEFAULT_SMILE_SENSITIVITY);
        EYEBROW_THRESH_INDEX = service.prefs.getInt("eyebrow_sensitivity", GestureCalibrationActivity.DEFAULT_EYEBROW_SENSITIVITY);
        MOUTHOPEN_THRESH_INDEX = service.prefs.getInt("mouth_sensitivity", GestureCalibrationActivity.DEFAULT_MOUTH_SENSITIVITY);
        EYEGAZE_LEFT_THRESH_INDEX = service.prefs.getInt("lookleft_sensitivity", GestureCalibrationActivity.DEFAULT_LOOKLEFT_SENSITIVITY);
        EYEGAZE_RIGHT_THRESH_INDEX = service.prefs.getInt("lookright_sensitivity", GestureCalibrationActivity.DEFAULT_LOOKRIGHT_SENSITIVITY);
        EYEGAZE_UP_THRESH_INDEX = service.prefs.getInt("lookup_sensitivity", GestureCalibrationActivity.DEFAULT_LOOKUP_SENSITIVITY);
        EYECLOSED_THRESH_INDEX = service.prefs.getInt("wink_sensitivity", GestureCalibrationActivity.DEFAULT_WINK_SENSITIVITY);
        EYECLOSED_MIN_TIME_MS = 1000*service.prefs.getFloat("wink_minduration", GestureCalibrationActivity.DEFAULT_WINK_MINDURATION);
        firstRun = true;

        gazeLeftWaitStop = false;
        gazeRightWaitStop = false;
        gazeUpWaitStop = false;
        lookLeftFound = false;
        lookRightFound = false;
        lookUpFound = false;
    }

    double EYEGAZE_UP_THRESHOLD_CONSTANT = 0.20;    // percentage of deviation from eye center
    double EYEGAZE_LEFTRIGHT_THRESHOLD_CONSTANT = 0.25;    // percentage of deviation from eye center
    double EYEGAZE_STOP_PERCENTAGE = 0.60;
    double EYEGAZE_CHARGETIME = 0.15;   // time it takes for eye detection to occur
    LowpassFilter lowpass_eyegaze_fx = new LowpassFilter(30.0, 5.0/(2*PI*EYEGAZE_CHARGETIME));
    LowpassFilter lowpass_eyegaze_fy = new LowpassFilter(30.0, 5.0/(2*PI*EYEGAZE_CHARGETIME));

    boolean gazeLeftWaitStop = false;
    boolean gazeRightWaitStop = false;
    boolean gazeUpWaitStop = false;

    int EYEGAZE_LEFT_THRESH_INDEX;
    int EYEGAZE_RIGHT_THRESH_INDEX;
    double[] EYEGAZE_LEFTRIGHT_THRESHOLDS = {
            1.6 * EYEGAZE_LEFTRIGHT_THRESHOLD_CONSTANT,
            1.45 * EYEGAZE_LEFTRIGHT_THRESHOLD_CONSTANT,
            1.3 * EYEGAZE_LEFTRIGHT_THRESHOLD_CONSTANT,
            1.15 * EYEGAZE_LEFTRIGHT_THRESHOLD_CONSTANT,
            1.0 * EYEGAZE_LEFTRIGHT_THRESHOLD_CONSTANT, // this one is default
            0.9 * EYEGAZE_LEFTRIGHT_THRESHOLD_CONSTANT,
            0.8 * EYEGAZE_LEFTRIGHT_THRESHOLD_CONSTANT,
            0.7 * EYEGAZE_LEFTRIGHT_THRESHOLD_CONSTANT,
            0.6 * EYEGAZE_LEFTRIGHT_THRESHOLD_CONSTANT,
    };

    int EYEGAZE_UP_THRESH_INDEX;
    double[] EYEGAZE_UP_THRESHOLDS = {
            1.6 * EYEGAZE_UP_THRESHOLD_CONSTANT,
            1.45 * EYEGAZE_UP_THRESHOLD_CONSTANT,
            1.3 * EYEGAZE_UP_THRESHOLD_CONSTANT,
            1.15 * EYEGAZE_UP_THRESHOLD_CONSTANT,
            1.0 * EYEGAZE_UP_THRESHOLD_CONSTANT, // this one is default
            0.9 * EYEGAZE_UP_THRESHOLD_CONSTANT,
            0.8 * EYEGAZE_UP_THRESHOLD_CONSTANT,
            0.7 * EYEGAZE_UP_THRESHOLD_CONSTANT,
            0.6 * EYEGAZE_UP_THRESHOLD_CONSTANT,
    };

    void detectEyeGaze(List<Face> faces, Bitmap cameraBitmap) {
        // todo: remove all code detecting "gaze up" :D (but for now it doesn't really matter)

        double fx, fy;
        double thresh_left = EYEGAZE_LEFTRIGHT_THRESHOLDS[EYEGAZE_LEFT_THRESH_INDEX];
        double thresh_right = EYEGAZE_LEFTRIGHT_THRESHOLDS[EYEGAZE_RIGHT_THRESH_INDEX];
        double thresh_up = EYEGAZE_UP_THRESHOLDS[EYEGAZE_UP_THRESH_INDEX];

        if(g_FPS == 0)
            return;

        if(faces.size() == 0)
            return;

        Face face = faces.get(0);

        EyeCenterFinder eyeCenterFinder = new EyeCenterFinder(cameraBitmap, face);
        PointF c = eyeCenterFinder.findMeanEyeCenter(true);
        PointF d;
        if(c != null) {
            d = new PointF(-1 + 2 * c.x, -1 + 2 * c.y);
        } else {
            d = new PointF(0.0f, 0.0f);
        }

        lowpass_eyegaze_fx.setSamplingFrequency(g_FPS);
        lowpass_eyegaze_fy.setSamplingFrequency(g_FPS);
        if(!firstRun) {
            fx = lowpass_eyegaze_fx.computeSample(d.x);
            fy = lowpass_eyegaze_fy.computeSample(d.y);
        } else {
            fx = lowpass_eyegaze_fx.chargeSample(d.x);
            fy = lowpass_eyegaze_fy.chargeSample(d.y);
        }

        // If eyes are closed, don't detect eye gaze!! (false detections occur)
        Float pl = face.getLeftEyeOpenProbability();
        Float pr = face.getRightEyeOpenProbability();
        //Log.i(TAG, String.format(Locale.getDefault(), "pl=%.2f; pr=%.2f", pl, pr));
        if(pl == null || pr == null || pl < 0.90 || pr < 0.90) {
            fx = lowpass_eyegaze_fx.chargeSample(0);
            fy = lowpass_eyegaze_fy.chargeSample(0);
        }

        // Log.i(TAG, String.format("fx=%.2f", fx));

        if(service.elementSelector != null) {
            if (Math.abs(fx) > Math.min(thresh_left, thresh_right) && !gazeLeftWaitStop && !gazeRightWaitStop /*&& !gazeUpWaitStop*/) {
                if (fx > 0 && Math.abs(fx) > thresh_left) {
                    lookLeftFoundTime = SystemClock.elapsedRealtime();
                    lookLeftFound = true;
                    gazeLeftWaitStop = true;
                    Log.i(TAG, "LOOKING LEFT");
                    int lookLeftId = SettingsActivity.SwitchElement.SwitchType.look_left.getValue();
                    for(SettingsActivity.ActionSwitch actionSwitch: SettingsActivity.actionSwitchArray) {
                        if(lookLeftId == service.prefs.getInt(actionSwitch.sharedPrefsName, actionSwitch.sharedPrefsDefault)) {
                            service.elementSelector.activateSwitch(actionSwitch.switchAction);
                            break;
                        }
                    }
                } else if(fx < 0 && Math.abs(fx) > thresh_right){
                    lookRightFoundTime = SystemClock.elapsedRealtime();
                    lookRightFound = true;
                    gazeRightWaitStop = true;
                    Log.i(TAG, "LOOKING RIGHT");
                    int lookRightId = SettingsActivity.SwitchElement.SwitchType.look_right.getValue();
                    for(SettingsActivity.ActionSwitch actionSwitch: SettingsActivity.actionSwitchArray) {
                        if(lookRightId == service.prefs.getInt(actionSwitch.sharedPrefsName, actionSwitch.sharedPrefsDefault)) {
                            service.elementSelector.activateSwitch(actionSwitch.switchAction);
                            break;
                        }
                    }
                }
            }/* else if (Math.abs(fy) > thresh_up && !gazeLeftWaitStop && !gazeRightWaitStop && !gazeUpWaitStop) {
                lookUpFoundTime = SystemClock.elapsedRealtime();
                lookUpFound = true;
                gazeUpWaitStop = true;
                Log.i(TAG, "LOOKING UP");
                int lookUpId = SettingsActivity.SwitchElement.SwitchType.look_up.getValue();
                for(SettingsActivity.ActionSwitch actionSwitch: SettingsActivity.actionSwitchArray) {
                    if(lookUpId == service.prefs.getInt(actionSwitch.sharedPrefsName, actionSwitch.sharedPrefsDefault)) {
                        service.elementSelector.activateSwitch(actionSwitch.switchAction);
                        break;
                    }
                }
            }*/
        }

        if(gazeLeftWaitStop && fx <= EYEGAZE_LEFTRIGHT_THRESHOLD_CONSTANT*EYEGAZE_STOP_PERCENTAGE)
            gazeLeftWaitStop = false;
        if(gazeRightWaitStop && fx >= -EYEGAZE_LEFTRIGHT_THRESHOLD_CONSTANT*EYEGAZE_STOP_PERCENTAGE)
            gazeRightWaitStop = false;
        /*if(gazeUpWaitStop && fy >= -EYEGAZE_UP_THRESHOLD_CONSTANT*EYEGAZE_STOP_PERCENTAGE)
            gazeUpWaitStop = false;*/
    }

    // we use low fc=0.1Hz because a slow smile will still be registered (i.e. rising slope won't get differentiated)
    HighpassFilter highpass_smile_Lx = new HighpassFilter(30, 0.1); // prej je bilo 0.5
    HighpassFilter highpass_smile_Ly = new HighpassFilter(30, 0.1);
    HighpassFilter highpass_translation_X2 = new HighpassFilter(30, 0.3);   // 0.3 gives 2.2Tau ~= 1sec
    HighpassFilter highpass_translation_Y2 = new HighpassFilter(30, 0.3);
    double SMILE_THRESHOLD_CONSTANT = 0.12; // 0.09
    double SMILE_STOP_PERCENTAGE = 0.2;
    boolean smileWaitStop = false;
    LowpassFilter lowpass_smile = new LowpassFilter(30, 2.0); // fc=2.0Hz ==> rise time (90%) = 2.2/(2pi*2.0) = 0.175 sec

    int SMILE_THRESH_INDEX;
    double[] SMILE_THRESHOLDS = {
            2.0 * SMILE_THRESHOLD_CONSTANT,
            1.75 * SMILE_THRESHOLD_CONSTANT,
            1.5 * SMILE_THRESHOLD_CONSTANT,
            1.25 * SMILE_THRESHOLD_CONSTANT,
            1.0 * SMILE_THRESHOLD_CONSTANT, // this one is default
            0.9 * SMILE_THRESHOLD_CONSTANT,
            0.8 * SMILE_THRESHOLD_CONSTANT,
            0.7 * SMILE_THRESHOLD_CONSTANT,
            0.6 * SMILE_THRESHOLD_CONSTANT,
    };

    void detectSmile(List<Face> faces) {
        double Lx;
        //double Ly;
        double Lnorm;
        double dx = 0;
        //double dy = 0;
        double Tx, Ty;  // translation points
        double dTx, dTy, dT; // translation differentials
        double factor;
        double lpf;
        double Dnorm;
        double thresh = SMILE_THRESHOLDS[SMILE_THRESH_INDEX]; //SMILE_THRESHOLD_CONSTANT;
        Face face;

        if(g_FPS == 0)
            return;

        if(faces.size() == 0)
            return;

        face = faces.get(0);

        FaceContour lowerLipBottom = face.getContour(FaceContour.LOWER_LIP_BOTTOM);
        if(lowerLipBottom == null)
            return;

        FaceContour upperLipTop = face.getContour(FaceContour.UPPER_LIP_TOP);
        if(upperLipTop == null)
            return;

        FaceContour leftEye = face.getContour(FaceContour.LEFT_EYE);
        if(leftEye == null)
            return;

        FaceContour noseBottom = face.getContour(FaceContour.NOSE_BOTTOM);
        if(noseBottom == null)
            return;

        List<PointF> noseBottomPoints = noseBottom.getPoints();
        List<PointF> lowerLipBottomPoints = lowerLipBottom.getPoints();
        List<PointF> upperLipTopPoints = upperLipTop.getPoints();
        List<PointF> leftEyePoints = leftEye.getPoints();

        PointF pt_noseBottomLeft = noseBottomPoints.get(0); // around left nostril basically
        PointF pt_leftMouth = upperLipTopPoints.get(0);
        PointF pt_rightMouth = upperLipTopPoints.get(10);
        PointF pt_bottomMouth = lowerLipBottomPoints.get(4);
        PointF pt_leftEyeLeft = leftEyePoints.get(0);
        PointF pt_leftEyeRight = leftEyePoints.get(8);

        // Lnorm = eye width
        Lnorm = Math.sqrt(Math.pow(pt_leftEyeRight.x - pt_leftEyeLeft.x, 2) + Math.pow(pt_leftEyeRight.y - pt_leftEyeLeft.y, 2)); //pt_leftEyeRight.x - pt_leftEyeLeft.x;

        // Lx = rightmost MINUS leftmost lip corner
        Lx = Math.sqrt(Math.pow(pt_rightMouth.x - pt_leftMouth.x, 2) + Math.pow(pt_rightMouth.y - pt_leftMouth.y, 2)); //pt_rightMouth.x - pt_leftMouth.x;
        Tx = pt_noseBottomLeft.x;
        Ty = pt_noseBottomLeft.y;

        // Ly = middle bottom lip (y) MINUS leftmost (y) lip corner
        //Ly = pt_bottomMouth.y - pt_leftMouth.y; // idea: make it max(diff_bottomToleftMouth, diff_bottomTorightMouth)?

        highpass_smile_Lx.setSamplingFrequency(g_FPS);
        //highpass_smile_Ly.setSamplingFrequency(g_FPS);
        highpass_translation_X2.setSamplingFrequency(g_FPS);
        highpass_translation_Y2.setSamplingFrequency(g_FPS);
        if(!firstRun) {
            dx = highpass_smile_Lx.computeSample(Lx);
            //dy = highpass_smile_Ly.computeSample(Ly);
            dTx = highpass_translation_X2.computeSample(Tx);
            dTy = highpass_translation_Y2.computeSample(Ty);
        } else {
            dx = highpass_smile_Lx.chargeSample(Lx);
            //dy = highpass_smile_Ly.chargeSample(Ly);
            dTx = highpass_translation_X2.chargeSample(Tx);
            dTy = highpass_translation_Y2.chargeSample(Ty);
        }

        dT = Math.sqrt(Math.pow(dTx, 2.0) + Math.pow(dTy, 2.0))/Lnorm;
        factor = Math.exp(-5.0*dT);    // factor is 0.86 at dT=0.03 and 0.22 at dT=0.3
        Dnorm = (dx/*+dy*/)/Lnorm * factor;

        // Important piece of information for debugging:
        //Log.i(TAG, "Smile level: " + String.format("Lx = %.2f; dx = %.2f; dT = %.2f; factor = %.2f; Dnorm = %.3f", Lx, dx, dT, factor, Dnorm));
        if(Dnorm >= thresh) {
            if(!smileWaitStop) {
                Log.i(TAG, "DETECTED SMILE");
                if(service.elementSelector != null) {
                    int smileId = SettingsActivity.SwitchElement.SwitchType.smile.getValue();
                    for(SettingsActivity.ActionSwitch actionSwitch: SettingsActivity.actionSwitchArray) {
                        if(smileId == service.prefs.getInt(actionSwitch.sharedPrefsName, actionSwitch.sharedPrefsDefault)) {
                            service.elementSelector.activateSwitch(actionSwitch.switchAction);
                            break;
                        }
                    }
                }
                lowpass_smile.setSamplingFrequency(g_FPS);
                lowpass_smile.chargeSample(Dnorm);

                smileFoundTime = SystemClock.elapsedRealtime();
                smileFound = true;
                smileWaitStop = true;
            }
        }

        if(smileWaitStop) {
            lowpass_smile.setSamplingFrequency(g_FPS);
            lpf = lowpass_smile.computeSample(Dnorm);
            if(lpf <= SMILE_STOP_PERCENTAGE * SMILE_THRESHOLD_CONSTANT) {
                smileWaitStop = false;
                //Log.i(TAG, "end of smile");
            }
        }
    }

    // Low value of fc=0.1 works well for slow eyebrow lifts, as it doesn't differentiate the slowly rising slope
    // Here the time for it to be differentiated is ~2.2Tau = 3.5 seconds
    HighpassFilter highpass_eyebrow = new HighpassFilter(30, 0.1);
    HighpassFilter highpass_translation_X1 = new HighpassFilter(30, 0.3);   // 0.3 gives 2.2Tau ~= 1sec
    HighpassFilter highpass_translation_Y1 = new HighpassFilter(30, 0.3);
    double EYEBROW_THRESHOLD_CONSTANT = 0.09;  // 0.08
    double EYEBROW_STOP_PERCENTAGE = 0.2;
    boolean eyebrowWaitStop = false;
    LowpassFilter lowpass_eyebrow = new LowpassFilter(30, 2.0); // fc=2.0Hz ==> rise time (90%) = 2.2/(2pi*2.0) = 0.175 sec

    int EYEBROW_THRESH_INDEX;
    double[] EYEBROW_THRESHOLDS = {
            1.6 * EYEBROW_THRESHOLD_CONSTANT,
            1.45 * EYEBROW_THRESHOLD_CONSTANT,
            1.3 * EYEBROW_THRESHOLD_CONSTANT,
            1.15 * EYEBROW_THRESHOLD_CONSTANT,
            1.0 * EYEBROW_THRESHOLD_CONSTANT, // this one is default
            0.9 * EYEBROW_THRESHOLD_CONSTANT,
            0.8 * EYEBROW_THRESHOLD_CONSTANT,
            0.7 * EYEBROW_THRESHOLD_CONSTANT,
            0.6 * EYEBROW_THRESHOLD_CONSTANT,
    };

    void detectEyebrowRaise(List<Face> faces) {
        double Lnorm;
        double Ly, dy = 0;
        double Tx, Ty;  // absolute translation point
        double dTx, dTy, dT;    // translation differentials
        double factor;
        double lpf;
        double Dnorm;
        double thresh = EYEBROW_THRESHOLDS[EYEBROW_THRESH_INDEX]; //EYEBROW_THRESHOLD_CONSTANT;
        Face face;

        if(g_FPS == 0)
            return;

        if(faces.size() == 0)
            return;

        face = faces.get(0);

        FaceContour leftEyebrowTop = face.getContour(FaceContour.LEFT_EYEBROW_TOP);
        if(leftEyebrowTop == null)
            return;

        FaceContour leftEye = face.getContour(FaceContour.LEFT_EYE);
        if(leftEye == null)
            return;

        FaceContour rightEye = face.getContour(FaceContour.RIGHT_EYE);
        if(rightEye == null)
            return;

        FaceContour noseBottom = face.getContour(FaceContour.NOSE_BOTTOM);
        if(noseBottom == null)
            return;

        List<PointF> noseBottomPoints = noseBottom.getPoints();
        List<PointF> leftEyebrowTopPoints = leftEyebrowTop.getPoints();
        List<PointF> leftEyePoints = leftEye.getPoints();
        List<PointF> rightEyePoints = rightEye.getPoints();

        PointF pt_noseBottomLeft = noseBottomPoints.get(0); // around left nostril basically
        PointF pt_leftEyeLeft = leftEyePoints.get(0);
        PointF pt_leftEyeRight = leftEyePoints.get(8);
        PointF pt_rightEyeLeft = rightEyePoints.get(0);
        PointF pt_rightEyeRight = rightEyePoints.get(8);

        // Lnorm = eye width
        Lnorm = Math.sqrt(Math.pow(pt_leftEyeRight.x - pt_leftEyeLeft.x, 2) + Math.pow(pt_leftEyeRight.y - pt_leftEyeLeft.y, 2)); //pt_leftEyeRight.x - pt_leftEyeLeft.x;

        double eyeCornerDist = Math.sqrt(Math.pow(pt_rightEyeRight.x - pt_leftEyeLeft.x, 2) + Math.pow(pt_rightEyeRight.y - pt_leftEyeLeft.y, 2));
        float angle = (float) Math.asin((pt_rightEyeRight.y - pt_leftEyeLeft.y)/eyeCornerDist);
        //float angle = (float) Math.asin((pt_leftEyeRight.y - pt_leftEyeLeft.y)/Lnorm);

        // Ly1 = difference between eyebrow.y and nosebottom_left.y
        // (btw we *must* use this L1 difference because otherwise dy1 goes off chart every time head moves up/down)
        /*Ly = pt_noseBottomLeft.y - (leftEyebrowTopPoints.get(1).y +
                                leftEyebrowTopPoints.get(2).y +
                                leftEyebrowTopPoints.get(3).y +
                                leftEyebrowTopPoints.get(4).y)/4;*/
        Ly = rotatePoint(pt_noseBottomLeft, -angle).y -
                (rotatePoint(leftEyebrowTopPoints.get(1), -angle).y +
                        rotatePoint(leftEyebrowTopPoints.get(2), -angle).y +
                        rotatePoint(leftEyebrowTopPoints.get(3), -angle).y +
                        rotatePoint(leftEyebrowTopPoints.get(4), -angle).y)/4;

        Tx = pt_noseBottomLeft.x;
        Ty = pt_noseBottomLeft.y;

        highpass_eyebrow.setSamplingFrequency(g_FPS);
        highpass_translation_X1.setSamplingFrequency(g_FPS);
        highpass_translation_Y1.setSamplingFrequency(g_FPS);
        if(!firstRun) {
            dy = highpass_eyebrow.computeSample(Ly);
            dTx = highpass_translation_X1.computeSample(Tx);
            dTy = highpass_translation_Y1.computeSample(Ty);
        } else {
            dy = highpass_eyebrow.chargeSample(Ly);
            dTx = highpass_translation_X1.chargeSample(Tx);
            dTy = highpass_translation_Y1.chargeSample(Ty);
        }

        dT = Math.sqrt(Math.pow(dTx, 2.0) + Math.pow(dTy, 2.0))/Lnorm;
        factor = Math.exp(-5.0*dT);    // factor is 0.86 at dT=0.03 and 0.22 at dT=0.3
        Dnorm = dy/Lnorm * factor;

        // Important piece of information for debugging:
        //Log.i(TAG, "Eyebrow raise level: " + String.format("Ly = %.1f; Lnorm = %.1f; dy = %.2f; dT = %.2f; factor = %.2f; Dnorm = %.3f", Ly, Lnorm, dy, dT, factor, Dnorm));
        if(Dnorm >= thresh) {
            if(!eyebrowWaitStop) {
                Log.i(TAG, "DETECTED EYEBROW RAISE");
                if(service.elementSelector != null) {
                    int eyebrowId = SettingsActivity.SwitchElement.SwitchType.eyebrow.getValue();
                    for(SettingsActivity.ActionSwitch actionSwitch: SettingsActivity.actionSwitchArray) {
                        if(eyebrowId == service.prefs.getInt(actionSwitch.sharedPrefsName, actionSwitch.sharedPrefsDefault)) {
                            service.elementSelector.activateSwitch(actionSwitch.switchAction);
                            break;
                        }
                    }
                }
                lowpass_eyebrow.setSamplingFrequency(g_FPS);
                lowpass_eyebrow.chargeSample(Dnorm);

                eyebrowFoundTime = SystemClock.elapsedRealtime();
                eyebrowFound = true;
                eyebrowWaitStop = true;
            }
            //Log.i(TAG, "Eyebrow raise detected: " + String.format("dy1 = %.2f; dy2 = %.2f; Lnorm = %.2f; Dnorm = %.3f", dy1, dy2, Lnorm, Dnorm));
        }

        if(eyebrowWaitStop) {
            lowpass_eyebrow.setSamplingFrequency(g_FPS);
            lpf = lowpass_eyebrow.computeSample(Dnorm);
            if(lpf <= EYEBROW_STOP_PERCENTAGE * EYEBROW_THRESHOLD_CONSTANT) {
                eyebrowWaitStop = false;
                //Log.i(TAG, "end of eyebrow raise");
            }
        }
    }

    int BOTHCLOSED_PAUSE_PERIOD_MS = 5*1000;    // 5 seconds to trigger pause/unpause
    long bothClosedStartTime;
    int bothClosedSeconds;

    double EYECLOSED_MIN_TIME_MS = 100;
    //double EYECLOSED_THRESH = 0.6;
    double EYEOPEN_THRESH = 0.8;
    long eyeClosedTime;
    EyeClosed eyeClosed = EyeClosed.none;
    enum EyeClosed {
        left,
        right,
        both,
        wait_open,
        none
    }

    int EYECLOSED_THRESH_INDEX;
    double[] EYECLOSED_THRESHOLDS = {
            0.10,
            0.20,
            0.30,
            0.40,
            0.50, // this one is default
            0.55,
            0.60,
            0.65,
            0.70,
    };

    void detectEyeClosed(List<Face> faces)
    {
        Float L, R;

        double EYECLOSED_THRESH = EYECLOSED_THRESHOLDS[EYECLOSED_THRESH_INDEX];

        if(g_FPS == 0)
            return;

        if(faces.size() == 0)
            return;

        Face face = faces.get(0);

        L = face.getRightEyeOpenProbability();
        R = face.getLeftEyeOpenProbability();
        if(L == null || R == null)
            return;

        // Log.i(TAG, String.format("Left: %.2f; Right: %.2f", L, R));

        if(L > EYECLOSED_THRESH && R > EYECLOSED_THRESH) {
            eyeClosed = EyeClosed.none;
        } else if(L < EYECLOSED_THRESH && R > EYEOPEN_THRESH) {
            if(eyeClosed == EyeClosed.none) {
                eyeClosed = EyeClosed.left;
                eyeClosedTime = SystemClock.elapsedRealtime();
            } else if(eyeClosed == EyeClosed.left && (SystemClock.elapsedRealtime() - eyeClosedTime) >= EYECLOSED_MIN_TIME_MS) {
                Log.i(TAG, "LEFT EYE CLOSED");
                if(service.elementSelector != null) {
                    int leftWinkId = SettingsActivity.SwitchElement.SwitchType.left_wink.getValue();
                    for(SettingsActivity.ActionSwitch actionSwitch: SettingsActivity.actionSwitchArray) {
                        if(leftWinkId == service.prefs.getInt(actionSwitch.sharedPrefsName, actionSwitch.sharedPrefsDefault)) {
                            service.elementSelector.activateSwitch(actionSwitch.switchAction);
                            break;
                        }
                    }
                }
                leftWinkFoundTime = SystemClock.elapsedRealtime();
                leftWinkFound = true;
                eyeClosed = EyeClosed.wait_open;
            }
        } else if(R < EYECLOSED_THRESH && L > EYEOPEN_THRESH) {
            if(eyeClosed == EyeClosed.none) {
                eyeClosed = EyeClosed.right;
                eyeClosedTime = SystemClock.elapsedRealtime();
            } else if(eyeClosed == EyeClosed.right && (SystemClock.elapsedRealtime() - eyeClosedTime) >= EYECLOSED_MIN_TIME_MS) {
                Log.i(TAG, "RIGHT EYE CLOSED");
                if(service.elementSelector != null) {
                    int rightWinkId = SettingsActivity.SwitchElement.SwitchType.right_wink.getValue();
                    for(SettingsActivity.ActionSwitch actionSwitch: SettingsActivity.actionSwitchArray) {
                        if(rightWinkId == service.prefs.getInt(actionSwitch.sharedPrefsName, actionSwitch.sharedPrefsDefault)) {
                            service.elementSelector.activateSwitch(actionSwitch.switchAction);
                            break;
                        }
                    }
                }
                rightWinkFoundTime = SystemClock.elapsedRealtime();
                rightWinkFound = true;
                eyeClosed = EyeClosed.wait_open;
            }
        } else if(L < EYECLOSED_THRESH && R < EYECLOSED_THRESH) {
            if(service.eyesClosedPause && !service.activityForeground) {
                if (eyeClosed == EyeClosed.none) {
                    eyeClosed = EyeClosed.both;
                    bothClosedSeconds = 0;
                    bothClosedStartTime = SystemClock.elapsedRealtime();
                } else if (eyeClosed == EyeClosed.both) {
                    long dt = SystemClock.elapsedRealtime() - bothClosedStartTime;

                    if (dt > BOTHCLOSED_PAUSE_PERIOD_MS) {
                        service.togglePauseState();
                        eyeClosed = EyeClosed.wait_open;
                    } else {
                        if((int)(dt/1000) > bothClosedSeconds) {
                            bothClosedSeconds = (int) dt / 1000;
                            toneGenerator.startTone(ToneGenerator.TONE_CDMA_PIP, 200);
                        }
                    }
                }
            }
        }
    }

    HighpassFilter highpass_translation_X3 = new HighpassFilter(30, 0.3);   // 0.3 gives 2.2Tau ~= 1sec
    HighpassFilter highpass_translation_Y3 = new HighpassFilter(30, 0.3);
    double MOUTHOPEN_RATIO_THRESH = 0.25;    // nominal (open mouth) ratio of mouth-height distance to mouth-corner distance
    double MOUTHOPEN_THRESHOLD_CONSTANT = 0.10; // 0.15
    double MOUTHOPEN_STOP_PERCENTAGE = 0.40;
    double MOUTHOPEN_CHARGETIME = 0.20;   // time it takes for mouth open detection to occur
    boolean mouthOpenWaitStop = false;
    LowpassFilter lowpass_mouth = new LowpassFilter(30.0, 5.0/(2*PI*MOUTHOPEN_CHARGETIME));

    int MOUTHOPEN_THRESH_INDEX;
    double[] MOUTHOPEN_RATIO_THRESHOLDS = {
            1.8 * MOUTHOPEN_RATIO_THRESH,
            1.6 * MOUTHOPEN_RATIO_THRESH,
            1.4 * MOUTHOPEN_RATIO_THRESH,
            1.2 * MOUTHOPEN_RATIO_THRESH,
            1.0 * MOUTHOPEN_RATIO_THRESH, // this one is default
            0.85 * MOUTHOPEN_RATIO_THRESH,
            0.7 * MOUTHOPEN_RATIO_THRESH,
            0.55 * MOUTHOPEN_RATIO_THRESH,
            0.4 * MOUTHOPEN_RATIO_THRESH,
    };

    void detectMouthOpen(List<Face> faces) {
        double mouthWidth, mouthHeight;
        double mouthRatio;  // ratio of mouth width to mouth height
        double mouthRatio_lpf;
        double fy, Ly;
        double Lnorm;
        double Tx, Ty;  // translation points
        double dTx, dTy, dT; // translation differentials
        double factor;
        double Dnorm;
        double thresh = MOUTHOPEN_THRESHOLD_CONSTANT;
        Face face;

        if(g_FPS == 0)
            return;

        if(faces.size() == 0)
            return;

        face = faces.get(0);

        FaceContour upperLipTop = face.getContour(FaceContour.UPPER_LIP_TOP);
        if(upperLipTop == null)
            return;

        FaceContour lowerLipTop = face.getContour(FaceContour.LOWER_LIP_TOP);
        if(lowerLipTop == null)
            return;

        FaceContour upperLipBottom = face.getContour(FaceContour.UPPER_LIP_BOTTOM);
        if(upperLipBottom == null)
            return;

        FaceContour leftEye = face.getContour(FaceContour.LEFT_EYE);
        if(leftEye == null)
            return;

        FaceContour noseBottom = face.getContour(FaceContour.NOSE_BOTTOM);
        if(noseBottom == null)
            return;

        List<PointF> noseBottomPoints = noseBottom.getPoints();
        List<PointF> upperLipTopPoints = upperLipTop.getPoints();
        List<PointF> upperLipBottomPoints = upperLipBottom.getPoints();
        List<PointF> lowerLipTopPoints = lowerLipTop.getPoints();
        List<PointF> leftEyePoints = leftEye.getPoints();

        PointF pt_noseBottomLeft = noseBottomPoints.get(0); // around left nostril basically
        PointF pt_leftMouth = upperLipTopPoints.get(0);
        PointF pt_rightMouth = upperLipTopPoints.get(10);
        PointF pt_topMouth = upperLipBottomPoints.get(4);   // middle upper lip point
        PointF pt_bottomMouth = lowerLipTopPoints.get(4);   // middle lower lip point
        PointF pt_leftEyeLeft = leftEyePoints.get(0);
        PointF pt_leftEyeRight = leftEyePoints.get(8);

        // Lnorm = eye width
        Lnorm = Math.sqrt(Math.pow(pt_leftEyeRight.x - pt_leftEyeLeft.x, 2) + Math.pow(pt_leftEyeRight.y - pt_leftEyeLeft.y, 2)); //pt_leftEyeRight.x - pt_leftEyeLeft.x;

        // Ly = distance between middle upper lip point and middle lower lip point
        Ly = Math.sqrt(Math.pow(pt_bottomMouth.x - pt_topMouth.x, 2) + Math.pow(pt_bottomMouth.y - pt_topMouth.y, 2));
        Tx = pt_noseBottomLeft.x;
        Ty = pt_noseBottomLeft.y;

        mouthWidth = Math.sqrt(Math.pow(pt_rightMouth.x - pt_leftMouth.x, 2) + Math.pow(pt_rightMouth.y - pt_leftMouth.y, 2));
        mouthHeight = Ly;
        mouthRatio = mouthHeight/mouthWidth;

        lowpass_mouth.setSamplingFrequency(g_FPS);
        highpass_translation_X3.setSamplingFrequency(g_FPS);
        highpass_translation_Y3.setSamplingFrequency(g_FPS);
        if(!firstRun) {
            fy = lowpass_mouth.computeSample(Ly);
            dTx = highpass_translation_X3.computeSample(Tx);
            dTy = highpass_translation_Y3.computeSample(Ty);
        } else {
            fy = lowpass_mouth.chargeSample(Ly);
            dTx = highpass_translation_X3.chargeSample(Tx);
            dTy = highpass_translation_Y3.chargeSample(Ty);
        }

        dT = Math.sqrt(Math.pow(dTx, 2.0) + Math.pow(dTy, 2.0))/Lnorm;
        factor = Math.exp(-5.0*dT);    // factor is 0.86 at dT=0.03 and 0.22 at dT=0.3
        Dnorm = fy/Lnorm * factor;

        // Important piece of information for debugging:
        //Log.i(TAG, "Mouth open level: " + String.format("Ly=%.2f; Dnorm=%.2f (ratio=%.3f)", Ly, Dnorm, mouthRatio));
        if(Dnorm >= thresh && mouthRatio >= MOUTHOPEN_RATIO_THRESHOLDS[MOUTHOPEN_THRESH_INDEX]) {
            if(!mouthOpenWaitStop) {
                Log.i(TAG, "DETECTED MOUTH OPEN");
                if(service.elementSelector != null) {
                    int mouthOpenId = SettingsActivity.SwitchElement.SwitchType.mouth_open.getValue();
                    for(SettingsActivity.ActionSwitch actionSwitch: SettingsActivity.actionSwitchArray) {
                        if(mouthOpenId == service.prefs.getInt(actionSwitch.sharedPrefsName, actionSwitch.sharedPrefsDefault)) {
                            service.elementSelector.activateSwitch(actionSwitch.switchAction);
                            break;
                        }
                    }
                }
                mouthOpenFoundTime = SystemClock.elapsedRealtime();
                mouthOpenFound = true;
                mouthOpenWaitStop = true;
            }
        }

        if(mouthOpenWaitStop) {
            if(Dnorm <= MOUTHOPEN_STOP_PERCENTAGE * MOUTHOPEN_THRESHOLD_CONSTANT) {
                mouthOpenWaitStop = false;
                //Log.i(TAG, "mouth closed");
            }
        }
    }

    PointF rotatePoint(PointF v, float beta) { // counterclockwise rotation
        return new PointF((float)(Math.cos(beta)*v.x - Math.sin(beta)*v.y), (float)(Math.sin(beta)*v.x + Math.cos(beta)*v.y));
    }

    int gestureFoundHoldTime = 1500;
    boolean faceFound = false;
    boolean faceFound() {
        return faceFound;
    }
    boolean smileFound = false;
    long smileFoundTime;
    boolean smileFound() {
        if(smileFound && (SystemClock.elapsedRealtime() - smileFoundTime) > gestureFoundHoldTime)
            smileFound = false;
        return smileFound;
    }

    boolean eyebrowFound = false;
    long eyebrowFoundTime;
    boolean eyebrowFound() {
        if(eyebrowFound && (SystemClock.elapsedRealtime() - eyebrowFoundTime) > gestureFoundHoldTime)
            eyebrowFound = false;
        return eyebrowFound;
    }

    boolean leftWinkFound = false;
    long leftWinkFoundTime;
    boolean leftWinkFound() {
        if(leftWinkFound && (SystemClock.elapsedRealtime() - leftWinkFoundTime) > gestureFoundHoldTime)
            leftWinkFound = false;
        return leftWinkFound;
    }

    boolean rightWinkFound = false;
    long rightWinkFoundTime;
    boolean rightWinkFound() {
        if(rightWinkFound && (SystemClock.elapsedRealtime() - rightWinkFoundTime) > gestureFoundHoldTime)
            rightWinkFound = false;
        return rightWinkFound;
    }

    boolean lookLeftFound = false;
    long lookLeftFoundTime;
    boolean lookLeftFound() {
        if(lookLeftFound && (SystemClock.elapsedRealtime() - lookLeftFoundTime) > gestureFoundHoldTime)
            lookLeftFound = false;
        return lookLeftFound;
    }

    boolean lookRightFound = false;
    long lookRightFoundTime;
    boolean lookRightFound() {
        if(lookRightFound && (SystemClock.elapsedRealtime() - lookRightFoundTime) > gestureFoundHoldTime)
            lookRightFound = false;
        return lookRightFound;
    }

    boolean lookUpFound = false;
    long lookUpFoundTime;
    boolean lookUpFound() {
        if(lookUpFound && (SystemClock.elapsedRealtime() - lookUpFoundTime) > gestureFoundHoldTime)
            lookUpFound = false;
        return lookUpFound;
    }

    boolean mouthOpenFound = false;
    long mouthOpenFoundTime;
    boolean mouthOpenFound() {
        if(mouthOpenFound && (SystemClock.elapsedRealtime() - mouthOpenFoundTime) > gestureFoundHoldTime)
            mouthOpenFound = false;
        return mouthOpenFound;
    }
}

package com.obstino.facecontrol;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;

import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class Node<T> {
    T data;
    Node<T> prev;
    Node<T> next;
    Node(T data) {
        this.data = data;
    }
}

class MyLinkedList<T> {

    Node<T> head, tail;
    MyLinkedList() { head = null; tail = null; }

    Node<T> getHeadNode() {
        return head;
    }

    void pushBack(T element) {
        Node<T> new_tail = new Node<T>(element);

        if(head == null) {
            new_tail.prev = null;
            new_tail.next = null;
            head = new_tail;
            tail = new_tail;
        } else {
            new_tail.next = null;
            new_tail.prev = tail;
            tail.next = new_tail;
            tail = new_tail;
        }
    }

    void removeNode(Node<T> node) {
        // We have the following cases:
        //  -There is only 1 element (node=head and node=tail)
        //  -There are at least 2 elements, but node is head
        //  -There are at least 2 elements, but node is tail
        //  -There are more than 2 elements, and node is somewhere in between

        if(node == head && node == tail) {
            head = null;
            tail = null;
        } else if(node == head) {
            head = node.next;
        } else if(node == tail) {
            Node<T> prevNode;
            prevNode = node.prev;
            prevNode.next = null;
            tail = prevNode;
        } else {
            Node<T> prevNode = node.prev;
            Node<T> nextNode = node.next;
            prevNode.next = nextNode;
            nextNode.prev = prevNode;
        }
    }

    void appendList(MyLinkedList<T> list) {
        if(list.head != null) { // at least 1 element must be inside
            if(tail == null) {
                // if our list is empty (head=tail=null), set our head and tail to that of the second list
                head = list.head;
                tail = list.tail;
            } else {
                // apparently our list has at least 1 element
                Node<T> prevNode = tail;
                Node<T> nextNode = list.head;
                prevNode.next = nextNode;
                nextNode.prev = prevNode;
                tail = list.tail;
            }
        }
    }

    int size() {
        int size = 0;
        Node<T> node = head;
        while(node != null) {
            size++;
            node = node.next;
        }

        return size;
    }
}

class CircleTemplateMatcher {
    static class Correlation {
        PointF point;
        int corr;
        Correlation(PointF point, int corr) {
            this.point = point;
            this.corr = corr;
        }
    };

    Bitmap image;
    int [][] imageArray;
    MyLinkedList<Correlation> corrList = new MyLinkedList<>();
    int radius;
    int searchStep;

    MyLinkedList<Correlation> peakList = new MyLinkedList<>();
    MyLinkedList<MyLinkedList<Correlation>> peakAreas = new MyLinkedList<>();
    MyLinkedList<Correlation> maxPeakArea = null;

    CircleTemplateMatcher(Bitmap image, int radius, int searchStep) {
        this.image = image;
        this.radius = radius;
        this.searchStep = searchStep;

        // Convert the image to a 2D grayscale array
        imageArray = new int[image.getHeight()][image.getWidth()];
        int[] imagePixels = new int[image.getWidth()*image.getHeight()];
        image.getPixels(imagePixels, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());
        for(int y = 0; y < image.getHeight(); y++) {
            for(int x = 0; x < image.getWidth(); x++) {
                int pix = imagePixels[y*image.getWidth() + x];
                int B = pix & 0xFF;
                int G = (pix >> 8) & 0xFF;
                int R = (pix >> 16) & 0xFF;
                imageArray[y][x] = (R+G+B)/3;
            }
        }
    }

    void computeCorrelation() {
        for(int x = 0; x < image.getWidth(); x+=searchStep) {
            for(int y = 0; y < image.getHeight(); y+=searchStep) {
                // Now correlate area around (x,y) with circle of chosen radius
                int tmpCorr = 0;
                for(int u = Math.max(x-radius, 0); u < Math.min(x+radius, image.getWidth()); u++) {
                    for(int v = Math.max(y-radius, 0); v < Math.min(y+radius, image.getHeight()); v++) {
                        // Check if (u-x)^2 + (v-y)^2 <= r^2
                        if(Math.pow((double)(u-x), 2.0) + Math.pow((double)(v-y), 2.0) <= Math.pow((double)radius, 2.0)) {
                            tmpCorr += (255 - imageArray[v][u]);
                        }
                    }
                }

                corrList.pushBack(new Correlation(new PointF(x, y), tmpCorr));
                //correlations.push_back(new Correlation())
                //image.at<unsigned char>(y, x)
            }
        }
    }

    void thresholdCorrelations(double r) {
        // r gives min percentage of peak that will be included in peakVect
        int peakCorr = 0;
        Node<Correlation> corrIterator;

        corrIterator = corrList.getHeadNode();
        while(corrIterator != null) {
            Correlation c = corrIterator.data;
            if(c.corr > peakCorr) {
                peakCorr = c.corr;
            }
            corrIterator = corrIterator.next;
        }

        corrIterator = corrList.getHeadNode();
        while(corrIterator != null) {
            Correlation c = corrIterator.data;
            if((double)c.corr >= r*(double)peakCorr) {
                peakList.pushBack(c);
            }
            corrIterator = corrIterator.next;
        }
    }

    void findConnectedPeakAreas() {
        Node<Correlation> peakListIterator = peakList.getHeadNode();
        while (peakListIterator != null) {
            Correlation c1 = peakListIterator.data;
            Node<MyLinkedList<Correlation>> targetCorrListIterator = null;

            Node<MyLinkedList<Correlation>> corrListIterator = peakAreas.getHeadNode();
            while (corrListIterator != null) {
                MyLinkedList<Correlation> corrList = corrListIterator.data;
                boolean incIterator = true;

                Node<Correlation> corrIterator = corrList.getHeadNode();
                while(corrIterator != null) {
                    Correlation c2 = corrIterator.data;

                    //std::cout << "p1=" << ((Point)c1.point).toString() << "; p2=" << ((Point)c2.point).toString()
                    //	<< "; distance = " << sqrt(pow(c1.point.x - c2.point.x, 2) + pow(c1.point.y - c2.point.y, 2)) << std::endl;

                    if (Math.sqrt(Math.pow(c1.point.x - c2.point.x, 2) + Math.pow(c1.point.y - c2.point.y, 2)) < (double)(2 * radius)) {
                        if (targetCorrListIterator == null) {
                            // if point c1 wasn't added to any other peak area,
                            // choose corrListIterator as the target merge correlation list
                            targetCorrListIterator = corrListIterator;
                            targetCorrListIterator.data.pushBack(c1);
                            break;
                        }
                        else {
                            //std::cout << "@ELSE: " << "p1=" << ((Point)c1.point).toString() << "; p2=" << ((Point)c2.point).toString() << std::endl;

                            // if point c1 was already added to another peak area, merge this area into that one
                            targetCorrListIterator.data.appendList(corrListIterator.data);
                            // Now delete corrList pointed to by corrListIterator
                            Node<MyLinkedList<Correlation>> nextCorrListIterator = corrListIterator.next;
                            peakAreas.removeNode(corrListIterator);
                            corrListIterator = nextCorrListIterator;
                            //corrListIterator = peakAreas.erase(corrListIterator);
                            incIterator = false;
                            break;
                        }
                    }

                    corrIterator = corrIterator.next;
                }

                if(incIterator) {
                    corrListIterator = corrListIterator.next;
                }
            }

            if (targetCorrListIterator == null) {
                MyLinkedList<Correlation> targetCorrList = new MyLinkedList<>();
                targetCorrList.pushBack(c1);
                peakAreas.pushBack(targetCorrList);
                targetCorrListIterator = peakAreas.getHeadNode();
            }

            peakListIterator = peakListIterator.next;
        }

		/*
		std::cout << "There are " << peakAreas.size() << " peak areas" << std::endl;
		for(const auto &i : peakAreas) {
			std::cout << "\t" "One peak area size is " << i.size() << std::endl;
		}
		std::cout << std::endl;
		*/
    }

    PointF findMaxPeakAreaCenterOfMass() {
        PointF c = new PointF();
        c.x = 0;
        c.y = 0;
        int maxPeakAreaSize = 0;

        Node<MyLinkedList<Correlation>> peakAreaIterator = peakAreas.getHeadNode();
        while (peakAreaIterator != null) {
            MyLinkedList<Correlation> peakArea = peakAreaIterator.data;
            int peakAreaSize = peakArea.size();
            if(peakAreaSize > maxPeakAreaSize) {
                maxPeakAreaSize = peakAreaSize;
                maxPeakArea = peakArea;
            }
            peakAreaIterator = peakAreaIterator.next;
        }
        if(maxPeakArea == null)
            return new PointF(Float.NaN, Float.NaN);
		else {
            float M = 0;
            Node<Correlation> correlationIterator = maxPeakArea.getHeadNode();
            while(correlationIterator != null) {
                Correlation correlation = correlationIterator.data;

                float m;
                m = correlation.corr;
                c.x += m * correlation.point.x;
                c.y += m * correlation.point.y;
                M += m;

                correlationIterator = correlationIterator.next;
            }
            c.x /= M;
            c.y /= M;
            return c;
        }
    }
};

public class EyeCenterFinder {
    Bitmap faceBitmap;
    Face face;

    static Bitmap myDrawBitmap;

    EyeCenterFinder(Bitmap faceBitmap, Face face) {
        this.faceBitmap = faceBitmap;
        this.face = face;
    }

    /**
     *
     * @param bmp input bitmap
     * @param contrast 0..10 1 is default
     * @param brightness -255..255 0 is default
     * @return new bitmap
     */
    public static Bitmap changeBitmapContrastBrightness(Bitmap bmp, float contrast, float brightness)
    {
        ColorMatrix cm = new ColorMatrix(new float[]
                {
                        contrast, 0, 0, 0, brightness,
                        0, contrast, 0, 0, brightness,
                        0, 0, contrast, 0, brightness,
                        0, 0, 0, 1, 0
                });

        Bitmap ret = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), bmp.getConfig());

        Canvas canvas = new Canvas(ret);

        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(bmp, 0, 0, paint);

        return ret;
    }

    int[] setImageContrast(int[] imagePixels, int[] maskPixels, int width, int height, double alpha)
    {
        int [] outputPixels = new int[width*height];
        double I_mean_r = 0.0;
        double I_mean_g = 0.0;
        double I_mean_b = 0.0;
        int N = 0;

        for(int y = 0; y < height; y++) {
            for(int x = 0; x < width; x++) {
                if(maskPixels[y*width + x] == Color.argb(255, 0, 0, 0)) {
                    int pix = imagePixels[y*width + x];
                    int r = (pix >> 16) & 0xFF;
                    int g = (pix >> 8) & 0xFF;
                    int b = (pix) & 0xFF;
                    I_mean_r += r/255.0;
                    I_mean_g += g/255.0;
                    I_mean_b += b/255.0;
                    N++;
                }
            }
        }

        I_mean_r /= (double)N;
        I_mean_g /= (double)N;
        I_mean_b /= (double)N;

        for(int y = 0; y < height; y++) {
            for(int x = 0; x < width; x++) {
                if(maskPixels[y*width + x] == Color.argb(255, 0, 0, 0)) {
                    int pix = imagePixels[y*width + x];
                    int r = (pix >> 16) & 0xFF;
                    int g = (pix >> 8) & 0xFF;
                    int b = (pix) & 0xFF;
                    double I_r = (double)r/255.0;
                    double I_g = (double)g/255.0;
                    double I_b = (double)b/255.0;
                    I_r = Math.pow(I_r, Math.pow(1.0 + I_mean_r - I_r, alpha)) * 255.0;
                    I_g = Math.pow(I_g, Math.pow(1.0 + I_mean_g - I_g, alpha)) * 255.0;
                    I_b = Math.pow(I_b, Math.pow(1.0 + I_mean_b - I_b, alpha)) * 255.0;
                    int I = (int)(I_r + I_g + I_b)/3;   // convert to grayscale
                    outputPixels[y*width + x] = Color.argb(255, I, I, I);
                } else {
                    outputPixels[y*width + x] = Color.argb(255, 255, 255, 255);;
                }
            }
        }
        return outputPixels;
    }

    float findMin(List <Float> list) {
        float min = list.get(0);
        for(Float f:list) {
            if(f < min)
                min = f;
        }
        return min;
    }

    float findMax(List <Float> list) {
        float max = list.get(0);
        for(Float f:list) {
            if(f > max)
                max = f;
        }
        return max;
    }

    private PointF findEyeCenterGivenPoints(List<PointF> eyePoints, boolean rotate, boolean draw) {
        int eyeX, eyeY, eyeWidth, eyeHeight;
        List<Float> eyeXPosList, eyeYPosList;

        eyeXPosList = new ArrayList<>();
        eyeYPosList = new ArrayList<>();
        for(PointF p:eyePoints) {
            eyeXPosList.add(p.x);
            eyeYPosList.add(p.y);
        }

        eyeX = (int)findMin(eyeXPosList);
        eyeWidth = (int)(findMax(eyeXPosList) - eyeX);
        eyeY = (int)findMin(eyeYPosList);
        eyeHeight = (int)(findMax(eyeYPosList) - eyeY);

        if(eyeX >= 0 && eyeY >= 0 && (eyeX+eyeWidth) < faceBitmap.getWidth() && (eyeY+eyeHeight) < faceBitmap.getHeight())
        {
            Bitmap eyeBitmap = Bitmap.createBitmap(faceBitmap, eyeX, eyeY, eyeWidth, eyeHeight);
            Bitmap maskBitmap = Bitmap.createBitmap(eyeWidth, eyeHeight, faceBitmap.getConfig());

//            eyeBitmap = changeBitmapContrastBrightness(eyeBitmap, 2.0f, 0.0f);

            float maskOffsetX, maskOffsetY;
            maskOffsetX = eyeX;   // point where we begin drawing our mask
            maskOffsetY = eyeY;    // -||-
            Path path = new Path();
            path.moveTo(eyePoints.get(0).x - maskOffsetX, eyePoints.get(0).y - maskOffsetY);
            for(int k = 1; k <= 15; k++)
                path.lineTo(eyePoints.get(k).x - maskOffsetX, eyePoints.get(k).y - maskOffsetY);
            path.close();

            Paint paint = new Paint();
            paint.setStyle(Paint.Style.FILL);
            Canvas maskCanvas = new Canvas(maskBitmap);
            maskCanvas.drawPath(path, paint);

            double contrast = 5.0;
            // At this point we have eyeBitmap and maskBitmap ready!
            // Let's bitwise AND those bitmaps!
            int r, g, b, rgb;
            int [] eyePixels = new int[eyeWidth * eyeHeight];
            int [] maskPixels = new int[eyeWidth * eyeHeight];
            eyeBitmap.getPixels(eyePixels, 0, eyeWidth, 0, 0, eyeWidth, eyeHeight);
            maskBitmap.getPixels(maskPixels, 0, eyeWidth, 0, 0, eyeWidth, eyeHeight);
            eyePixels = setImageContrast(eyePixels, maskPixels, eyeWidth, eyeHeight, contrast); // sets contrast and converts to grayscale
            /*for(int k = 0; k < eyeWidth * eyeHeight; k++) {
                if(maskPixels[k] == Color.argb(0, 0, 0, 0))  // if mask pixel is transparent, set eye pixel to white
                    eyePixels[k] = Color.argb(255, 255, 255, 255);
                else {
                    // the order of these isn't really important, we only need a greyscale image
                    b = (eyePixels[k]) & 0xFF;
                    g = (eyePixels[k] >> 8) & 0xFF;
                    r = (eyePixels[k] >> 16) & 0xFF;
                    rgb = (r+g+b)/3;
                    eyePixels[k] = Color.argb(255, rgb, rgb, rgb);
                }
            }*/
            eyeBitmap.setPixels(eyePixels, 0, eyeWidth, 0, 0, eyeWidth, eyeHeight);

            // At this point, eye bitmap is ready. Now we just calculate some things and start detection!
            // ###########################################################################################
            // Palpebral Fissure Length in pixels
            int PFL_pix = (int)Math.sqrt(Math.pow(eyePoints.get(8).x - eyePoints.get(0).x, 2) + Math.pow(eyePoints.get(8).y - eyePoints.get(0).y, 2)); //eyeWidth;
            // Iris diameter to PFL length ratio
            // (based on paper "Periocular Anthropometry of Normal Chinese and Indian Populations in Singapore")
            double min_iris2PFL_ratio = 11.6/35.0;	// could lower this even further, though...
            double max_iris2PFL_ratio = 12.0/18.0;
            int minRadius = (int)((double)PFL_pix * min_iris2PFL_ratio/2.0); // divide by 2 to get radius
            int maxRadius = (int)((double)PFL_pix * max_iris2PFL_ratio/2.0);
            int radius = minRadius/2;	// circle radius to do template matching (correlation) with
            float R = 0.85f;

            CircleTemplateMatcher circleTemplateMatcher = new CircleTemplateMatcher(eyeBitmap, Math.max(radius, 1), Math.max(radius/2, 1));
            circleTemplateMatcher.computeCorrelation();
            circleTemplateMatcher.thresholdCorrelations(R);
            circleTemplateMatcher.findConnectedPeakAreas();
            PointF c = circleTemplateMatcher.findMaxPeakAreaCenterOfMass();

            PointF c_abs = new PointF(c.x+eyeX, c.y+eyeY);
            if(rotate) {
                // sin angle is opposite (eye corner y diff) divided by hypotenuse (PFL length calculated earlier)
                double angle = Math.asin(((double)eyePoints.get(8).y - (double)eyePoints.get(0).y)/(double)PFL_pix);
                c_abs = rotatePoint(c_abs, (float)-angle);
                eyePoints = rotatePointList(eyePoints, (float)-angle);

                eyeXPosList.clear();
                eyeYPosList.clear();
                for(PointF p: eyePoints) {
                    eyeYPosList.add(p.y);
                    eyeXPosList.add(p.x);
                }
            }

            float eyeMiddleY = eyePoints.get(0).y;
            float eyeBottomY = findMax(eyeYPosList);
            float eyeTopY = Math.min(findMin(eyeYPosList), eyeMiddleY - (eyeBottomY - eyeMiddleY));
            float eyeRealHeight = eyeBottomY - eyeTopY;

            float eyeX_ratio = (c_abs.x - eyePoints.get(0).x)/(eyePoints.get(8).x - eyePoints.get(0).x);
            float eyeY_ratio = (c_abs.y - eyeTopY)/eyeRealHeight;

            if(draw) {
                Paint eyePaint = new Paint();
                Canvas eyeCanvas = new Canvas(eyeBitmap);
                eyePaint.setARGB(255, 0, 255, 0);
                MyLinkedList<CircleTemplateMatcher.Correlation> peakArea = circleTemplateMatcher.maxPeakArea;
                if (peakArea != null) {
                    Node<CircleTemplateMatcher.Correlation> corrNode = peakArea.getHeadNode();
                    while (corrNode != null) {
                        CircleTemplateMatcher.Correlation correlation = corrNode.data;
                        eyeCanvas.drawPoint(correlation.point.x, correlation.point.y, eyePaint);
                        corrNode = corrNode.next;
                    }
                    //eyeCanvas.drawCircle(c.x, c.y, 1, eyePaint);
                }
                eyePaint.setARGB(255, 255, 0, 0);
                eyeCanvas.drawPoint(c.x, c.y, eyePaint);
                eyeBitmap = Bitmap.createScaledBitmap(eyeBitmap, eyeWidth * 10, eyeHeight * 10, false);

                Bitmap mutableFaceBitmap = faceBitmap.copy(faceBitmap.getConfig(), true);
                Paint facePaint = new Paint();
                Canvas faceCanvas = new Canvas(mutableFaceBitmap);
                facePaint.setARGB(255, 50, 50, 255);
                facePaint.setTextSize(60.0f);
                facePaint.setFakeBoldText(true);
                faceCanvas.drawText(String.format(Locale.getDefault(), "x=%.2f; y=%.2f", eyeX_ratio, eyeY_ratio), 0, 60, facePaint);
                facePaint.setARGB(255, 255, 0, 0);
                faceCanvas.drawCircle(c.x+eyeX, c.y+eyeY, 5.0f, facePaint);

                FaceControlService service = FaceControlService.sharedServiceInst;
                if (service != null) {
                    synchronized (service.drawLock) {
                        myDrawBitmap = mutableFaceBitmap;
                    }
                }
            }

            return new PointF(eyeX_ratio, eyeY_ratio);
        } else {
            return null; //new PointF(Float.NaN, Float.NaN);
        }
    }

    public PointF findLeftEyeCenter(boolean rotate) {
        FaceContour leftEyeContour = face.getContour(FaceContour.LEFT_EYE);
        if(leftEyeContour != null) {
            return findEyeCenterGivenPoints(leftEyeContour.getPoints(), rotate, false);
        } else {
            return null; //new PointF(Float.NaN, Float.NaN);
        }
    }

    public PointF findRightEyeCenter(boolean rotate) {
        FaceContour rightEyeContour = face.getContour(FaceContour.RIGHT_EYE);
        if(rightEyeContour != null) {
            return findEyeCenterGivenPoints(rightEyeContour.getPoints(), rotate,false);
        } else {
            return null; //new PointF(Float.NaN, Float.NaN);
        }
    }

    public PointF findMeanEyeCenter(boolean rotate) {
        PointF leftEyeCenter, rightEyeCenter, meanEyeCenter;

        leftEyeCenter = findLeftEyeCenter(rotate);
        rightEyeCenter = findRightEyeCenter(rotate);

        if(leftEyeCenter == null || rightEyeCenter == null)
            return null;

        meanEyeCenter = new PointF((leftEyeCenter.x+rightEyeCenter.x)/2.0f, (leftEyeCenter.y+rightEyeCenter.y)/2.0f);

        /*
        Bitmap mutableFaceBitmap = faceBitmap.copy(faceBitmap.getConfig(), true);
        Paint facePaint = new Paint();
        Canvas faceCanvas = new Canvas(mutableFaceBitmap);
        facePaint.setARGB(255, 50, 50, 255);
        facePaint.setTextSize(60.0f);
        facePaint.setFakeBoldText(true);
        faceCanvas.drawText(String.format(Locale.getDefault(), "L: x=%.2f; y=%.2f", meanEyeCenter.x, meanEyeCenter.y), 0, 60, facePaint);
        //faceCanvas.drawText(String.format(Locale.getDefault(), "L: x=%.2f; y=%.2f", leftEyeCenter.x, leftEyeCenter.y), 0, 60, facePaint);
        //faceCanvas.drawText(String.format(Locale.getDefault(), "R: x=%.2f; y=%.2f", rightEyeCenter.x, rightEyeCenter.y), 0, 120, facePaint);
        FaceControlService service = FaceControlService.sharedServiceInst;
        if (service != null) {
            synchronized (service.drawLock) {
                myDrawBitmap = mutableFaceBitmap;
            }
        }*/

        return meanEyeCenter;
    }

    PointF rotatePoint(PointF p, float beta) { // counterclockwise rotation
        return new PointF((float)(Math.cos(beta)*p.x - Math.sin(beta)*p.y), (float)(Math.sin(beta)*p.x + Math.cos(beta)*p.y));
    }

    List<PointF> rotatePointList(List<PointF> list, float beta) {
        List<PointF> list_out = new ArrayList<>();
        for(PointF p : list) {
            PointF p_out = rotatePoint(p, beta);
            list_out.add(p_out);
        }
        return list_out;
    }
}

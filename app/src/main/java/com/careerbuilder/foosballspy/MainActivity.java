package com.careerbuilder.foosballspy;

import java.util.ArrayList;

import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.app.Activity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;

import com.github.mikephil.charting.animation.Easing;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.listener.OnChartGestureListener;

import edu.emory.mathcs.jtransforms.fft.FloatFFT_1D;


public class MainActivity extends Activity implements OnChartGestureListener {

    private static final int RECORDER_SAMPLERATE = 44100;
    private static final int RECORDING_RATE = 2048;
    private static final int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    private static final int RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private AudioRecord recorder = null;
    private Thread recordingThread = null;
    private boolean isRecording = false;
    private LineChart mChart;
    private float[] hanningWindow;
    private long renderCount = 0;
    private FingerPrintRegion[] fingerPrint;

    private float yMax = 150000f;

    private float[] previousFrame;
    private float previousFrameAverage;

    private FloatFFT_1D fft_1D = new FloatFFT_1D(RECORDING_RATE / 2);

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        System.out.println("onCreate");
        setContentView(R.layout.activity_main);

        setButtonHandlers();
        enableButtons(false);

        createHanningWindow();
        createFingerPrint();

        int bufferSize = AudioRecord.getMinBufferSize(RECORDER_SAMPLERATE,
                RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING);


        mChart = (LineChart) findViewById(R.id.chart1);
        mChart.setOnChartGestureListener(this);
        mChart.setDrawGridBackground(false);


        // no description text
        mChart.setDescription("");
        mChart.setNoDataTextDescription("You need to provide data for the chart.");

        // enable value highlighting
        mChart.setHighlightEnabled(true);

        // enable touch gestures
        mChart.setTouchEnabled(true);

        // enable scaling and dragging
        mChart.setDragEnabled(true);
        mChart.setScaleEnabled(true);
        // mChart.setScaleXEnabled(true);
        // mChart.setScaleYEnabled(true);

        // if disabled, scaling can be done on x- and y-axis separately
        mChart.setPinchZoom(true);

        // set an alternative background color
        // mChart.setBackgroundColor(Color.GRAY);


        // x-axis limit line
        LimitLine llXAxis = new LimitLine(10f, "Index 10");
        llXAxis.setLineWidth(4f);
        llXAxis.enableDashedLine(10f, 10f, 0f);
        llXAxis.setLabelPosition(LimitLine.LimitLabelPosition.RIGHT_BOTTOM);
        llXAxis.setTextSize(10f);

        XAxis xAxis = mChart.getXAxis();
        //xAxis.setValueFormatter(new MyCustomXAxisValueFormatter());
        //xAxis.addLimitLine(llXAxis); // add x-axis limit line

        YAxis leftAxis = mChart.getAxisLeft();
        leftAxis.setAxisMaxValue(yMax);
        leftAxis.setAxisMinValue(0f);
        leftAxis.setStartAtZero(false);
        //leftAxis.setYOffset(20f);
        leftAxis.enableGridDashedLine(10f, 10f, 0f);

        // limit lines are drawn behind data (and not on top)
        leftAxis.setDrawLimitLinesBehindData(true);

        mChart.getAxisRight().setEnabled(false);

        //mChart.getViewPortHandler().setMaximumScaleY(2f);
        //mChart.getViewPortHandler().setMaximumScaleX(2f);

        // add data

//        mChart.setVisibleXRange(20);
//        mChart.setVisibleYRange(20f, AxisDependency.LEFT);
//        mChart.centerViewTo(20, 50, AxisDependency.LEFT);

        mChart.animateX(2500, Easing.EasingOption.EaseInOutQuart);
//        mChart.invalidate();

        // get the legend (only possible after setting data)
        Legend l = mChart.getLegend();

        // modify the legend ...
        // l.setPosition(LegendPosition.LEFT_OF_CHART);
        l.setForm(Legend.LegendForm.LINE);

        // // dont forget to refresh the drawing
        mChart.invalidate();
    }

    private void setButtonHandlers() {
        ((Button) findViewById(R.id.btnStart)).setOnClickListener(btnClick);
        ((Button) findViewById(R.id.btnStop)).setOnClickListener(btnClick);
    }

    private void enableButton(int id, boolean isEnable) {
        ((Button) findViewById(id)).setEnabled(isEnable);
    }

    private void enableButtons(boolean isRecording) {
        enableButton(R.id.btnStart, !isRecording);
        enableButton(R.id.btnStop, isRecording);
    }

    int BufferElements2Rec = RECORDING_RATE; // want to play 2048 (2K) since 2 bytes we use only 1024
    int BytesPerElement = 2; // 2 bytes in 16bit format

    private void startRecording() {

        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                RECORDER_SAMPLERATE, RECORDER_CHANNELS,
                RECORDER_AUDIO_ENCODING, BufferElements2Rec * BytesPerElement);

        recorder.startRecording();
        isRecording = true;
        recordingThread = new Thread(new Runnable() {
            public void run() {
                analyzeAudio();
            }
        }, "AudioRecorder Thread");
        recordingThread.start();
    }

    //convert short to byte
    private byte[] short2byte(short[] sData) {
        int shortArrsize = sData.length;
        byte[] bytes = new byte[shortArrsize * 2];
        for (int i = 0; i < shortArrsize; i++) {
            bytes[i * 2] = (byte) (sData[i] & 0x00FF);
            bytes[(i * 2) + 1] = (byte) (sData[i] >> 8);
            sData[i] = 0;
        }
        return bytes;

    }

    private void analyzeAudio() {
        // Write the output audio in byte

        String filePath = "/sdcard/voice8K16bitmono.pcm";
        short sData[] = new short[BufferElements2Rec];


        while (isRecording) {
            // gets the voice output from microphone to byte format

            recorder.read(sData, 0, BufferElements2Rec);
            if (sData.length > 0) {
                float[] fftData = new float[sData.length];
                for (int i = 0; i < sData.length; i++) {
                    fftData[i] = (float) sData[i];
                }
                applyWindow(fftData, hanningWindow);
                fft_1D.complexForward(fftData);
                fftData[0] = 0f;
                fftData[1] = 0f;
                float[] currentFrame = new float[fftData.length / 4];

                for (int i = 0; i < currentFrame.length; i++) {
//                    currentFrame[i] = (float) Math.log10(magnitude(fftData[2*i], fftData[2*i+1]));
                    currentFrame[i] = magnitude(fftData[2*i], fftData[2*i+1]);//(float) Math.log10(magnitude(fftData[2*i], fftData[2*i+1]));//(float) Math.log((double) fftData[i]);
                    if (i < 100) {
                        currentFrame[i] = 0;
                    }
                }

                float currentFrameAverage = average(currentFrame);

                if (previousFrame == null) {
                    previousFrame = currentFrame;
                    previousFrameAverage = currentFrameAverage;
                } else {
                    float deltaMagnitude = currentFrameAverage - previousFrameAverage;
                    float a = fingerPrint[0].inRegion(currentFrame, previousFrame, deltaMagnitude);
                    float b = fingerPrint[1].inRegion(currentFrame, previousFrame, deltaMagnitude);
                    float c = fingerPrint[2].inRegion(currentFrame, previousFrame, deltaMagnitude);
                    System.out.println("Finger Print vals: 0: " + a + " 1: " + b + " 2: " + c);
                }


                setData(currentFrame);
            }

        }
    }

    private float average(float[] a) {
        return average(a, 0, a.length);
    }

    private float average(float[] a, int start, int end) {

        float sum = 0;
        for (int i = start; i < end; i++) {
            sum += a[i];
        }
        return sum / (end - start);
    }

    private float magnitude(float a, float b) {
        return (float) Math.sqrt(a * a + b * b);
    }

    private void stopRecording() {
        // stops the recording activity
        if (null != recorder) {
            isRecording = false;
            recorder.stop();
            recorder.release();
            recorder = null;
            recordingThread = null;
        }
    }

    private View.OnClickListener btnClick = new View.OnClickListener() {
        public void onClick(View v) {
            switch (v.getId()) {
                case R.id.btnStart: {
                    enableButtons(true);
                    startRecording();
                    break;
                }
                case R.id.btnStop: {
                    enableButtons(false);
                    stopRecording();
                    break;
                }
            }
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
        }
        return super.onKeyDown(keyCode, event);
    }

    private void createHanningWindow() {
        hanningWindow = new float[RECORDING_RATE];
        for (int i = 0; i < hanningWindow.length; i++) {
            hanningWindow[i] = (float) (0.5 - (0.5 * Math.cos(2 * Math.PI * i / hanningWindow.length)));
        }
    }

    private void createFingerPrint() {
        fingerPrint = new FingerPrintRegion[3];
        fingerPrint[0] = new FingerPrintRegion(100, 200, 6f, 1f);
        fingerPrint[1] = new FingerPrintRegion(200, 300, 4.5f, 1f);
        fingerPrint[2] = new FingerPrintRegion(300, 500, 6f, 1f);
    }

    private void applyWindow(float[] data, float[] window) {
        for (int i = 0; i < data.length; i++) {
            data[i] *= window[i];
        }
    }

    private void setData(float[] inputData) {

        int count = inputData.length;
        ArrayList<String> xVals = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            xVals.add((i) + "");
        }

        ArrayList<Entry> yVals = new ArrayList<Entry>();

        for (int i = 0; i < count; i++) {

            float val = inputData[i];

            yVals.add(new Entry(val, i));
        }

        // create a dataset and give it a type
        LineDataSet set1 = new LineDataSet(yVals, "DataSet 1");
        // set1.setFillAlpha(110);
        // set1.setFillColor(Color.RED);
        set1.setColor(Color.BLACK);
        set1.setLineWidth(1f);
        set1.setValueTextSize(9f);
        set1.setFillAlpha(65);
        set1.setFillColor(Color.BLACK);
//        set1.setDrawFilled(true);
        // set1.setShader(new LinearGradient(0, 0, 0, mChart.getHeight(),
        // Color.BLACK, Color.WHITE, Shader.TileMode.MIRROR));

        ArrayList<LineDataSet> dataSets = new ArrayList<LineDataSet>();
        dataSets.add(set1); // add the datasets

        // create a data object with the datasets
        LineData data = new LineData(xVals, dataSets);

        // set data
        mChart.setData(data);
        mChart.notifyDataSetChanged();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mChart.invalidate();
            }
        });
        System.out.println(renderCount++);
    }

    @Override
    public void onChartLongPressed(MotionEvent me) {

    }

    @Override
    public void onChartDoubleTapped(MotionEvent me) {

    }

    @Override
    public void onChartSingleTapped(MotionEvent me) {

    }

    @Override
    public void onChartFling(MotionEvent me1, MotionEvent me2, float velocityX, float velocityY) {

    }

    @Override
    public void onChartScale(MotionEvent me, float scaleX, float scaleY) {

    }

    @Override
    public void onChartTranslate(MotionEvent me, float dX, float dY) {

    }
}

class FingerPrintRegion {

    private int start, end;
    private float lowMagnitude, highMagnitude;

    public FingerPrintRegion(int start, int end, float soughtMagnitude, float allowedVariance) {
        this.start = start;
        this.end = end;
        this.lowMagnitude = soughtMagnitude - allowedVariance;
        this.highMagnitude = soughtMagnitude + allowedVariance;
    }

    public float inRegion(float[] current, float[] previous, float delta) {
        float currentAverage = averageForRegion(current);
        float previousAverage = averageForRegion(previous);
        float actualMagnitude = currentAverage - (previousAverage + delta);
        return currentAverage;
//        if (actualMagnitude > lowMagnitude && actualMagnitude < highMagnitude) {
//            return true;
//        }
//        return false;
    }

    private float averageForRegion(float[] a) {

        float sum = 0;
        for (int i = start; i < end; i++) {
            sum += a[i];
        }
        return sum / (end - start);
    }
}
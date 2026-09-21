package com.omarea.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class MyChatView extends View {

    //------------- required data -------------
    private String[] str = new String[]{"Grade 1", "Grade 2", "Grade 3", "Grade 4", "Grade 5", "Grade 6"};
    // total ratio is 100, but the computed values sum to about 99.55, which leaves a gap and prevents the ring from closing, so use 101 as the total ratio
    private int[] strPercent = new int[]{10, 25, 18, 41, 2, 5};
    //circle diameter
    private float mRadius = 300;
    //------------- paint related -------------
    //ring paint
    private Paint cyclePaint;
    //text paint
    private Paint textPaint;
    //label paint
    private Paint labelPaint;
    //------------- color related -------------
    //border color and label color
    private int[] mColor = new int[]{0xFFF06292, 0xFF9575CD, 0xFFE57373, 0xFF4FC3F7, 0xFFFFF176, 0xFF81C784};
    //------------- view related -------------
    //view width and height
    private int mHeight;
    private int mWidth;


    public MyChatView(Context context) {
        super(context);
    }

    public MyChatView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MyChatView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mWidth = w;
        mHeight = h;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        //move the canvas to the top-left of the ring
        canvas.translate(mWidth / 2 - mRadius / 2, mHeight / 2 - mRadius / 2);
        //initialize paint
        initPaint();
        //draw ring
        drawCycle(canvas);
        //draw text and labels
        drawTextAndLabel(canvas);
    }

    /**
     * Initialize paint
     */
    private void initPaint() {
        //border paint
        cyclePaint = new Paint();
        cyclePaint.setAntiAlias(true);
        cyclePaint.setStyle(Paint.Style.STROKE);
        float mStrokeWidth = 40;
        cyclePaint.setStrokeWidth(mStrokeWidth);
        //text paint
        textPaint = new Paint();
        textPaint.setAntiAlias(true);
        int textColor = 0xFF000000;
        textPaint.setColor(textColor);
        textPaint.setStyle(Paint.Style.STROKE);
        textPaint.setStrokeWidth(1);
        int textSize = 20;
        textPaint.setTextSize(textSize);
        //label paint
        labelPaint = new Paint();
        labelPaint.setAntiAlias(true);
        labelPaint.setStyle(Paint.Style.FILL);
        labelPaint.setStrokeWidth(2);
    }

    /**
     * Draw ring
     *
     * @param canvas
     */
    private void drawCycle(Canvas canvas) {
        float startPercent = 0;
        float sweepPercent = 0;
        for (int i = 0; i < strPercent.length; i++) {
            cyclePaint.setColor(mColor[i]);
            startPercent = sweepPercent + startPercent;
            // compute the angle by multiplying the ratio by 360 (multiply first, then divide)
            sweepPercent = strPercent[i] * 360 / 100;
            canvas.drawArc(new RectF(0, 0, mRadius, mRadius), startPercent, sweepPercent, false, cyclePaint);
        }
    }

    /**
     * Draw text and labels
     *
     * @param canvas
     */
    private void drawTextAndLabel(Canvas canvas) {
        for (int i = 0; i < strPercent.length; i++) {
            // text padding from the right ring edge is 60, and spacing between texts is 40
            canvas.drawText(str[i], mRadius + 60, i * 40, textPaint);
            // draw labels with 40 padding from the right ring edge; subtract half the radius (10) on the y-axis to align with text
            labelPaint.setColor(mColor[i]);
            canvas.drawCircle(mRadius + 40, i * 40 - 5, 10, labelPaint);
        }
    }

}

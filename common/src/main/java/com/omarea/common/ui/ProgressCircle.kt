package com.omarea.common.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.omarea.common.R

class ProgressCircle : View {
    //------------- required data -------------
    private val str = arrayOf("Used", "Available")
    private var ratio = 0
    private var ratioState = 0

    // Circle diameter
    private var mRadius = 300f

    // Circle stroke width
    private var mStrokeWidth = 10f

    // Text size
    private var textSize = 20

    //------------- paints -------------
    // Paint for the ring
    private var cyclePaint: Paint? = null

    // Paint for the text
    private var textPaint: Paint? = null

    // Paint for the label
    private var labelPaint: Paint? = null

    //------------- colors -------------
    // Border and label color
    private val mColor = intArrayOf(-0xec712a, 0x55888888, -0x1a8c8d, -0xb03c09, -0xe8a, -0x7e387c)

    // private int[] mColor = new int[]{0xFFF06292, 0xFF9575CD, 0xFFE57373, 0xFF4FC3F7, 0xFFFFF176, 0xFF81C784};
    // Text color
    private val textColor = -0x777778

    //------------- view -------------
    // The view's own width and height
    private var mHeight: Int = 0
    private var mWidth: Int = 0

    constructor(context: Context) : super(context) {}

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        @SuppressLint("CustomViewStyleable")
        val array = context.obtainStyledAttributes(attrs, R.styleable.ProgressState)
        val total = array.getInteger(R.styleable.ProgressState_total, 1)
        val current = array.getInteger(R.styleable.ProgressState_current, 1)
        ratio = (current * 100.0 / total).toInt()
        //strPercent = new int[]{100 - feeRatio, feeRatio};
        array.recycle()
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        @SuppressLint("CustomViewStyleable")
        val array = context.obtainStyledAttributes(attrs, R.styleable.ProgressState)
        val total = array.getInteger(R.styleable.ProgressState_total, 1)
        val current = array.getInteger(R.styleable.ProgressState_current, 1)
        ratio = (current * 100.0 / total).toInt()
        //strPercent = new int[]{100 - feeRatio, feeRatio};
        array.recycle()
    }

    /**
     * Converts dp to px
     */
    private fun dp2px(context: Context, dpValue: Float): Int {
        val scale = context.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mWidth = w
        mHeight = h
        val mStrokeWidth = dp2px(context, 10f)
        this.mStrokeWidth = mStrokeWidth.toFloat()
        this.textSize = dp2px(context, 18f)
        if (w > h) {
            this.mRadius = (h * 0.9 - mStrokeWidth).toInt().toFloat()
        } else {
            this.mRadius = (w * 0.9 - mStrokeWidth).toInt().toFloat()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Move the canvas to the top-left corner of the ring
        canvas.translate(mWidth / 2 - mRadius / 2, mHeight / 2 - mRadius / 2)
        // Initialize the paints
        initPaint()
        // Draw the ring
        drawCycle(canvas)
    }

    private var temperature = 35F
    fun setData(total: Float, fee: Float, temperature: Float) {
        if (fee == total && total == 0F) {
            ratio = 0
        } else {
            val feeRatio = (fee * 100.0 / total).toInt()
            ratio = 100 - feeRatio
        }
        this.temperature = temperature
        // Animated update
        // cgangePer(ratio)
        // Immediate update
        ratioState = ratio
        invalidate()
    }

    /**
     * Initializes the paints
     */
    private fun initPaint() {
        // Border paint
        cyclePaint = Paint()
        cyclePaint!!.isAntiAlias = true
        cyclePaint!!.style = Paint.Style.STROKE
        cyclePaint!!.strokeWidth = mStrokeWidth
        // Text paint
        textPaint = Paint()
        textPaint!!.isAntiAlias = true
        textPaint!!.color = textColor
        textPaint!!.style = Paint.Style.STROKE
        textPaint!!.strokeWidth = 1f
        textPaint!!.textSize = textSize.toFloat()
        // Label paint
        labelPaint = Paint()
        labelPaint!!.isAntiAlias = true
        labelPaint!!.style = Paint.Style.FILL
        labelPaint!!.strokeWidth = 20f
    }

    fun cgangePer(per: Int) {
        val perOld = this.ratioState
        val va = ValueAnimator.ofInt(perOld, per)
        va.duration = 200
        va.interpolator = DecelerateInterpolator()
        va.addUpdateListener { animation ->
            ratioState = animation.animatedValue as Int
            invalidate()
        }
        va.start()

    }

    /**
     * Draws the ring
     * @param canvas
     */
    private fun drawCycle(canvas: Canvas) {
        cyclePaint!!.color = 0x22888888
        // cyclePaint!!.alpha = 128
        canvas.drawArc(RectF(0f, 0f, mRadius, mRadius), 0f, 360f, false, cyclePaint!!)
        /*
        if (ratio == 0) {
            return
        }
        */
        // cyclePaint!!.alpha = 255


        if (temperature >= 48 || ratioState < 11) {
            cyclePaint!!.color = Color.rgb(255, 15, 0)
        } else if (temperature > 44 || ratio < 16) {
            cyclePaint!!.color = Color.RED //resources.getColor(R.color.color_load_low)
        }

        /*
        val dashPathEffect = DashPathEffect(floatArrayOf(15 / 3f, 15 * 2 / 3f), 0f)

        val mSweepGradient = SweepGradient(
            canvas.getWidth() / 2f,
            canvas.getHeight() / 2f, // use the arc center as the sweep gradient center for the intended effect
            intArrayOf(
                    resources.getColor(R.color.color_load_low),
                    resources.getColor(R.color.color_load_mid),
                    resources.getColor(R.color.color_load_hight),
                    resources.getColor(R.color.color_load_veryhight)
            ),
            floatArrayOf(0f, 0.33f, 0.67f, 1f)
        );
        val matrix = Matrix()
        matrix.setRotate(-108f, canvas.width / 2f, canvas.height / 2f)
        mSweepGradient.setLocalMatrix(matrix)

        cyclePaint!!.setShader(mSweepGradient)
        cyclePaint!!.setPathEffect(dashPathEffect);
        */

        cyclePaint!!.setStrokeCap(Paint.Cap.ROUND)
        if (ratio < 1 && (ratioState <= 2)) {
            return
        } else if (ratioState >= 98) {
            canvas.drawArc(RectF(0f, 0f, mRadius, mRadius), -90f, 360f, false, cyclePaint!!)
        } else {
            canvas.drawArc(RectF(0f, 0f, mRadius, mRadius), -90f, (ratioState * 3.6f), false, cyclePaint!!)
        }
    }
}

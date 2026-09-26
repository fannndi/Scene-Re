package com.omarea.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.omarea.vtools.R


class FloatMonitorChartView : View {
    //------------- required data -------------
    private val str = arrayOf("Used", "Available")
    private var ratio = 0
    private var ratioState = 0

    // circle diameter
    private var mRadius = 300f

    // circle stroke width
    private var mStrokeWidth = 40f

    // text size
    private var textSize = 20

    //------------- paint -------------
    // ring paint
    private var cyclePaint: Paint? = null

    // text paint
    private var textPaint: Paint? = null

    // label paint
    private var labelPaint: Paint? = null

    //------------- colors -------------
    // stroke color and label color
    private val mColor = intArrayOf(-0xec712a, 0x55888888, -0x1a8c8d, -0xb03c09, -0xe8a, -0x7e387c)

    // private int[] mColor = new int[]{0xFFF06292, 0xFF9575CD, 0xFFE57373, 0xFF4FC3F7, 0xFFFFF176, 0xFF81C784};
    // text color
    private val textColor = -0x777778

    //------------- view -------------
    // view width and height
    private var mHeight: Int = 0
    private var mWidth: Int = 0

    constructor(context: Context) : super(context) {}

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        @SuppressLint("CustomViewStyleable") val array = context.obtainStyledAttributes(attrs, R.styleable.RamInfo)
        val total = array.getInteger(R.styleable.RamInfo_total, 1)
        val fee = array.getInteger(R.styleable.RamInfo_free, 1)
        val feeRatio = (fee * 100.0 / total).toInt()
        ratio = 100 - feeRatio
        //strPercent = new int[]{100 - feeRatio, feeRatio};
        array.recycle()
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        @SuppressLint("CustomViewStyleable") val array = context.obtainStyledAttributes(attrs, R.styleable.RamInfo)
        val total = array.getInteger(R.styleable.RamInfo_total, 1)
        val fee = array.getInteger(R.styleable.RamInfo_free, 1)
        val feeRatio = (fee * 100.0 / total).toInt()
        ratio = feeRatio
        //strPercent = new int[]{100 - feeRatio, feeRatio};
        array.recycle()
    }

    /**
     * dp to px
     */
    private fun dp2px(context: Context, dpValue: Float): Int {
        val scale = context.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mWidth = w
        mHeight = h
        val mStrokeWidth = dp2px(context, 4f)
        this.mStrokeWidth = mStrokeWidth.toFloat()
        this.textSize = dp2px(context, 18f)
        if (w > h) {
            this.mRadius = (h * 0.9 - mStrokeWidth).toInt().toFloat()
        } else {
            this.mRadius = (w * 0.9 - mStrokeWidth).toInt().toFloat()
        }
        // Paint objects depend only on values computed here (stroke width,
        // height, text size), so build them once per size change instead of
        // allocating fresh Paint/RectF on every frame.
        initPaint()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Move the canvas to the top-left corner of the ring
        canvas.translate(mWidth / 2 - mRadius / 2, mHeight / 2 - mRadius / 2)
        // Draw the ring
        drawCycle(canvas)
    }

    fun setData(total: Float, fee: Float) {
        if (fee == total && total == 0F) {
            ratio = 0
        } else {
            val feeRatio = (fee * 100.0 / total).toInt()
            ratio = 100 - feeRatio
        }
        // animated update
        // cgangePer(ratio)
        // update without animation
        ratioState = ratio
        invalidate()
    }

    /**
     * Initialize paints
     */
    private fun initPaint() {
        // ring paint
        cyclePaint = Paint()
        cyclePaint!!.isAntiAlias = true
        cyclePaint!!.style = Paint.Style.STROKE
        cyclePaint!!.strokeWidth = mStrokeWidth
        // text paint
        textPaint = Paint()
        textPaint!!.isAntiAlias = true
        textPaint!!.color = textColor
        textPaint!!.style = Paint.Style.STROKE
        textPaint!!.strokeWidth = 1f
        textPaint!!.textSize = textSize.toFloat()
        // label paint
        labelPaint = Paint()
        labelPaint!!.isAntiAlias = true
        labelPaint!!.style = Paint.Style.FILL
        labelPaint!!.strokeWidth = 2f
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
     * Draw the ring
     * @param canvas
     */
    private fun drawCycle(canvas: Canvas) {
        cyclePaint!!.color = 0x22FFFFFF
        // cyclePaint!!.alpha = 128
        canvas.drawArc(RectF(0f, 0f, mRadius, mRadius), 0f, 360f, false, cyclePaint!!)
        /*
        if (ratio == 0) {
            return
        }
        */
        // cyclePaint!!.alpha = 255
        if (ratioState > 90) {
            cyclePaint!!.color = ContextCompat.getColor(context, R.color.color_load_veryhight)
        } else if (ratioState > 75) {
            cyclePaint!!.color = ContextCompat.getColor(context, R.color.color_load_hight)
        } else if (ratioState > 20) {
            cyclePaint!!.color = ContextCompat.getColor(context, R.color.color_load_mid)
        } else {
            cyclePaint!!.color = ContextCompat.getColor(context, R.color.color_load_low)
        }

        /*
        val dashPathEffect = DashPathEffect(floatArrayOf(15 / 3f, 15 * 2 / 3f), 0f)

        val mSweepGradient = SweepGradient(
            canvas.getWidth() / 2f,
            canvas.getHeight() / 2f, // use the arc center as the sweep gradient center to get the intended effect
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

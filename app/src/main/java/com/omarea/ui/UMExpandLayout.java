package com.omarea.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

public class UMExpandLayout extends RelativeLayout {

    public UMExpandLayout(Context context) {
        this(context, null);
    }

    public UMExpandLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UMExpandLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initView();
    }

    private View layoutView;
    private int viewHeight;
    private boolean isExpand;
    private long animationDuration;

    private void initView() {
        layoutView = this;
        isExpand = true;
        animationDuration = 300;
        setViewDimensions();
    }

    /**
     * @param isExpand whether the initial state is collapsed
     */
    public void initExpand(boolean isExpand) {
        this.isExpand = isExpand;
        if (!isExpand) {
            animateToggle(10);
        }
    }

    /**
     * Sets the animation duration
     *
     * @param animationDuration animation duration
     */
    public void setAnimationDuration(long animationDuration) {
        this.animationDuration = animationDuration;
    }

    /**
     * Gets the total height of the subViews
     * The runnable passed to View.post() runs after the View's measure, layout and similar passes
     */
    private void setViewDimensions() {
        layoutView.post(new Runnable() {
            @Override
            public void run() {
                if (viewHeight <= 0) {
                    viewHeight = layoutView.getMeasuredHeight();
                }
            }
        });
    }

    public static void setViewHeight(View view, int height) {
        final ViewGroup.LayoutParams params = view.getLayoutParams();
        params.height = height;
        view.requestLayout();
    }

    /**
     * Toggle animation implementation
     */
    private void animateToggle(long animationDuration) {
        ValueAnimator heightAnimation = isExpand ?
                ValueAnimator.ofFloat(0f, viewHeight) : ValueAnimator.ofFloat(viewHeight, 0f);
        heightAnimation.setDuration(animationDuration / 2);
        heightAnimation.setStartDelay(animationDuration / 2);

        heightAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
            float val = (float) animation.getAnimatedValue();
            setViewHeight(layoutView, (int) val);
            }
        });

        heightAnimation.start();
    }

    public boolean isExpand() {
        return isExpand;
    }

    /**
     * Collapse the view
     */
    public void collapse() {
        isExpand = false;
        animateToggle(animationDuration);
    }

    /**
     * Expand the view
     */
    public void expand() {
        isExpand = true;
        animateToggle(animationDuration);
    }

    public void toggleExpand() {
        if (isExpand) {
            collapse();
        } else {
            expand();
        }
    }

}
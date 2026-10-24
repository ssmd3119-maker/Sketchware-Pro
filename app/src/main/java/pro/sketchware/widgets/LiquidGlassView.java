package pro.sketchware.widgets;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.Collections;

import pro.sketchware.R;

/**
 * A rounded container that renders as a Liquid Glass panel.
 * <p>
 * On Android 12+ with hardware acceleration, the window content behind this view is recorded into a
 * {@link RenderNode} before every frame and blurred on the GPU with {@link RenderEffect}, so the blur is
 * real and follows scrolling content. A translucent tint, a specular highlight and a hairline border are
 * drawn on top. On older versions no blur is drawn; the panel falls back to the translucent tint only.
 * <p>
 * When the current theme doesn't have Liquid Glass enabled ({@code ?attr/liquidGlassEnabled}), the view
 * draws a plain rounded panel in {@code glassTint} and nothing else, matching the regular look.
 */
public class LiquidGlassView extends FrameLayout {

    private final RectF bounds = new RectF();
    private final Path clipPath = new Path();
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final boolean glassEnabled;
    private final float cornerRadius;
    private final float blurRadius;
    private final float strokeWidth;
    private final ColorStateList strokeColor;

    @Nullable
    private BackdropBlur backdropBlur;

    public LiquidGlassView(@NonNull Context context) {
        this(context, null);
    }

    public LiquidGlassView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LiquidGlassView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);

        TypedValue value = new TypedValue();
        glassEnabled = context.getTheme().resolveAttribute(R.attr.liquidGlassEnabled, value, true)
                && value.type == TypedValue.TYPE_INT_BOOLEAN && value.data != 0;

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassView, defStyleAttr, 0);
        cornerRadius = a.getDimension(R.styleable.LiquidGlassView_glassCornerRadius,
                getResources().getDimension(R.dimen.glass_corner_radius));
        blurRadius = a.getDimension(R.styleable.LiquidGlassView_glassBlurRadius,
                getResources().getDimension(R.dimen.glass_blur_radius));
        int tint = a.hasValue(R.styleable.LiquidGlassView_glassTint)
                ? a.getColor(R.styleable.LiquidGlassView_glassTint, 0)
                : MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer);
        a.recycle();

        tintPaint.setColor(tint);
        strokeWidth = getResources().getDisplayMetrics().density;
        strokeColor = ContextCompat.getColorStateList(context, R.color.glass_stroke);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidth);

        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
            }
        });
        setClipToOutline(true);

        if (glassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurRadius > 0) {
            backdropBlur = new BackdropBlur(this, blurRadius);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bounds.set(0, 0, w, h);
        clipPath.reset();
        clipPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW);
        highlightPaint.setShader(new LinearGradient(0, 0, 0, h * 0.6f,
                ContextCompat.getColor(getContext(), R.color.glass_highlight_start),
                ContextCompat.getColor(getContext(), R.color.glass_highlight_end),
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (backdropBlur != null) backdropBlur.attach();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (backdropBlur != null) backdropBlur.detach();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        canvas.save();
        canvas.clipPath(clipPath);
        if (backdropBlur != null && canvas.isHardwareAccelerated()) {
            backdropBlur.drawTo(canvas);
        }
        canvas.drawRect(bounds, tintPaint);
        if (glassEnabled) {
            canvas.drawRect(bounds, highlightPaint);
        }
        canvas.restore();

        if (glassEnabled) {
            strokePaint.setColor(strokeColor.getColorForState(getDrawableState(), strokeColor.getDefaultColor()));
            float inset = strokeWidth / 2f;
            canvas.drawRoundRect(inset, inset, bounds.right - inset, bounds.bottom - inset,
                    cornerRadius - inset, cornerRadius - inset, strokePaint);
        }
    }

    /**
     * Records what the window draws behind {@link #target} into a blurred {@link RenderNode} right
     * before each frame.
     * <p>
     * Only content behind the target is recorded: the background of every ancestor and, at each level,
     * the siblings drawn before the branch that leads to the target. The target and its ancestors' display
     * lists are never drawn into the node, so the node can't contain itself and the target never has to be
     * invalidated from here (which would schedule a new frame every frame). Re-recording the node is enough;
     * the target's display list references it and picks up the new content.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private static final class BackdropBlur implements ViewTreeObserver.OnPreDrawListener {
        private final View target;
        private final RenderNode node = new RenderNode("LiquidGlassBackdrop");
        private final ArrayList<View> path = new ArrayList<>();
        private final int[] targetLocation = new int[2];
        private final int[] location = new int[2];
        private boolean attached;
        private boolean hasContent;

        BackdropBlur(View target, float radius) {
            this.target = target;
            node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }

        void attach() {
            attached = true;
            target.getViewTreeObserver().addOnPreDrawListener(this);
        }

        void detach() {
            attached = false;
            hasContent = false;
            target.getViewTreeObserver().removeOnPreDrawListener(this);
            node.discardDisplayList();
        }

        @Override
        public boolean onPreDraw() {
            int width = target.getWidth();
            int height = target.getHeight();
            if (!attached || width == 0 || height == 0 || !target.isShown() || !target.isHardwareAccelerated()) {
                hasContent = false;
                return true;
            }

            path.clear();
            for (View v = target; v != null; ) {
                path.add(v);
                ViewParent parent = v.getParent();
                v = parent instanceof View ? (View) parent : null;
            }
            Collections.reverse(path);

            target.getLocationInWindow(targetLocation);
            node.setPosition(0, 0, width, height);
            RecordingCanvas canvas = node.beginRecording(width, height);
            try {
                for (int i = 0; i < path.size() - 1; i++) {
                    View ancestor = path.get(i);
                    View branch = path.get(i + 1);

                    Drawable background = ancestor.getBackground();
                    if (background != null && !background.getBounds().isEmpty()) {
                        int save = translateTo(canvas, ancestor);
                        background.draw(canvas);
                        canvas.restoreToCount(save);
                    }

                    if (!(ancestor instanceof ViewGroup group)) break;
                    for (int c = 0; c < group.getChildCount(); c++) {
                        View child = group.getChildAt(c);
                        if (child == branch) break;
                        if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f) continue;
                        int save = translateTo(canvas, child);
                        canvas.translate(-child.getScrollX(), -child.getScrollY());
                        child.draw(canvas);
                        canvas.restoreToCount(save);
                    }
                }
            } finally {
                node.endRecording();
                path.clear();
            }
            hasContent = true;
            return true;
        }

        private int translateTo(Canvas canvas, View view) {
            int save = canvas.save();
            view.getLocationInWindow(location);
            canvas.translate(location[0] - targetLocation[0], location[1] - targetLocation[1]);
            return save;
        }

        void drawTo(Canvas canvas) {
            if (hasContent && node.hasDisplayList()) {
                canvas.drawRenderNode(node);
            }
        }
    }
}

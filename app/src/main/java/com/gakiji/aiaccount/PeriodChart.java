package com.gakiji.aiaccount;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class PeriodChart extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LedgerStatistics.Period period;
    private final long[] values;
    private final int color;
    private final double topYuan;

    PeriodChart(Context context, LedgerStatistics.Period period, long[] values, int color) {
        super(context);
        this.period = period;
        this.values = values;
        this.color = color;
        long maximum = java.util.Arrays.stream(values).max().orElse(0);
        double third = Math.max(1, maximum / 300.0);
        double magnitude = Math.pow(10, Math.floor(Math.log10(third)));
        topYuan = 3 * magnitude * Math.ceil(third / magnitude);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        paint.setTextSize(11 * density);
        paint.setTextAlign(Paint.Align.LEFT);
        var format = java.text.NumberFormat.getIntegerInstance(java.util.Locale.CHINA);
        float left = paint.measureText(format.format(topYuan)) + 8 * density;
        float right = getWidth() - 14 * density;
        float top = 10 * density;
        float bottom = getHeight() - 25 * density;
        if (right <= left || bottom <= top) return;
        for (int i = 0; i <= 3; i++) {
            float y = top + (bottom - top) * i / 3F;
            paint.setColor(0xffe5e8f1);
            paint.setStrokeWidth(density);
            canvas.drawLine(left, y, right, y, paint);
            paint.setColor(0xff596385);
            canvas.drawText(format.format(topYuan * (3 - i) / 3), 0, y + 4 * density, paint);
        }
        float step = (right - left) / (values.length - 1);
        Path trend = new Path();
        for (int i = 0; i < values.length; i++) {
            float x = left + step * i;
            float y = bottom - (bottom - top) * (float) (values[i] / (topYuan * 100));
            if (i == 0) trend.moveTo(x, y);
            else trend.lineTo(x, y);
        }
        Path area = new Path(trend);
        area.lineTo(right, bottom);
        area.lineTo(left, bottom);
        area.close();
        paint.setColor((color & 0xffffff) | 0x33000000);
        canvas.drawPath(area, paint);
        paint.setColor(color);
        paint.setStrokeWidth(2 * density);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(trend, paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setColor(0xff596385);
        paint.setTextAlign(Paint.Align.CENTER);
        for (int index = 0; index <= 4; index++) {
            int i = Math.round(index * (values.length - 1) / 4F);
            String label = (i + 1) + (period == LedgerStatistics.Period.YEAR ? "月" : "日");
            canvas.drawText(label, left + i * step, getHeight() - 5 * density, paint);
        }
    }

    static final class Ring extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final long[] amounts;
        private final int[] colors;

        Ring(Context context, long[] amounts, int[] colors) {
            super(context);
            this.amounts = amounts;
            this.colors = colors;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            float width = 12 * getResources().getDisplayMetrics().density;
            float inset = width / 2 + 1;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setColor(0xffe5e8f1);
            canvas.drawOval(inset, inset, getWidth() - inset, getHeight() - inset, paint);
            long total = java.util.Arrays.stream(amounts).sum();
            float start = -90;
            for (int i = 0; i < amounts.length; i++) {
                float sweep = (float) (360.0 * amounts[i] / total);
                paint.setColor(colors[i]);
                canvas.drawArc(inset, inset, getWidth() - inset, getHeight() - inset, start, sweep, false, paint);
                start += sweep;
            }
        }
    }
}

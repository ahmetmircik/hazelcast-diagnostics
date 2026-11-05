package com.hazelcast.diagnostics;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.labels.StandardXYToolTipGenerator;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.data.general.SeriesException;
import org.jfree.data.time.FixedMillisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

public class MemoryPane {

    private final JPanel component;
    private final TimeSeriesCollection collection;
    private Collection<InstanceDiagnostics> diagnosticsList = new ArrayList<>();
    private long startMs = Long.MIN_VALUE;
    private long endMs = Long.MAX_VALUE;

    public MemoryPane() {
        collection = new TimeSeriesCollection();
        JFreeChart chart = ChartFactory.createTimeSeriesChart("Memory Usage", "Time", "Memory", collection, true, true,
                false);
        ChartPanel chartPanel = new ChartPanel(chart);
        XYPlot plot = (XYPlot) chartPanel.getChart().getPlot();
        DateAxis axis = (DateAxis) plot.getDomainAxis();
        SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
        axis.setDateFormatOverride(dateFormat);

        NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();
        ByteFormat byteFormat = new ByteFormat();
        yAxis.setNumberFormatOverride(byteFormat);

        XYItemRenderer renderer = plot.getRenderer();
        renderer.setDefaultToolTipGenerator(new StandardXYToolTipGenerator(
                "{0}: {2} at {1}", dateFormat, byteFormat));

        this.component = chartPanel;
    }

    public void setRange(long fromMs, long toMs) {
        this.startMs = fromMs;
        this.endMs = toMs;
    }

    public void setInstanceDiagnostics(Collection<InstanceDiagnostics> diagnosticsList) {
        this.diagnosticsList = diagnosticsList;
    }

    public void update() {
        collection.removeAllSeries();

        for (InstanceDiagnostics diagnostics : diagnosticsList) {
            Iterator<Map.Entry<Long, Number>> iterator = diagnostics.metricsBetween(
                    InstanceDiagnostics.METRIC_RUNTIME_USED_MEMORY, startMs, endMs);

            if (!iterator.hasNext()) {
                continue;
            }

            TimeSeries series = new TimeSeries(diagnostics.getDirectory().getName());
            while (iterator.hasNext()) {
                try {
                    Map.Entry<Long, Number> entry = iterator.next();
                    long ms = entry.getKey();
                    Long value = entry.getValue().longValue();
                    series.add(new FixedMillisecond(ms), value);
                } catch (SeriesException e) {
                    System.err.println("Error adding to series");
                }
            }
            collection.addSeries(series);
        }
    }

    public JComponent getComponent() {
        return component;
    }

    private static final class ByteFormat extends NumberFormat {
        private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

        @Override
        public StringBuffer format(double number, StringBuffer toAppendTo, FieldPosition pos) {
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                return toAppendTo.append(number);
            }
            double abs = Math.abs(number);
            if (abs < 1024) {
                return toAppendTo.append(Math.round(number)).append(" B");
            }

            int unitIndex = 0;
            double value = number;
            while (Math.abs(value) >= 1024.0 && unitIndex < UNITS.length - 1) {
                value /= 1024.0;
                unitIndex++;
            }

            DecimalFormat df;
            if (Math.abs(value) >= 100.0) {
                df = new DecimalFormat("#,##0");
            } else if (Math.abs(value) >= 10.0) {
                df = new DecimalFormat("#,##0.#");
            } else {
                df = new DecimalFormat("#,##0.##");
            }
            return toAppendTo.append(df.format(value)).append(" ").append(UNITS[unitIndex]);
        }

        @Override
        public StringBuffer format(long number, StringBuffer toAppendTo, FieldPosition pos) {
            return format((double) number, toAppendTo, pos);
        }

        @Override
        public Number parse(String source, ParsePosition parsePosition) {
            throw new UnsupportedOperationException();
        }
    }
}
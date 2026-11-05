package com.hazelcast.diagnostics;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.NumberTickUnit;
import org.jfree.chart.labels.StandardXYToolTipGenerator;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.AbstractXYItemRenderer;
import org.jfree.data.xy.DefaultXYDataset;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InvocationProfilerPane {
    private static final String INVOCATION_PROFILE_MARKER = "InvocationProfiler[";

    private final ChartPanel pane;
    private final DefaultXYDataset dataset = new DefaultXYDataset();
    private long startMs = Long.MIN_VALUE;
    private long endMs = Long.MAX_VALUE;
    private Collection<InstanceDiagnostics> instanceDiagnosticsColl = new ArrayList<>();

    public InvocationProfilerPane() {
        JFreeChart latencyChart = ChartFactory.createXYLineChart(
                "Operation Latency Profile",
                "Percentile",
                "Latency (µs)",
                dataset,
                PlotOrientation.VERTICAL,
                true,
                true,
                false);
        pane = new ChartPanel(latencyChart);

        XYPlot plot = latencyChart.getXYPlot();
        // x is plotted as the "number of nines" (1 = 90%, 2 = 99%, 3 = 99.9%, ...), labeled as a percentile
        NumberAxis xAxis = (NumberAxis) plot.getDomainAxis();
        xAxis.setRange(0, 6);
        xAxis.setTickUnit(new NumberTickUnit(1, new PercentileFormat()));
        xAxis.setMinorTickCount(1);
        xAxis.setAutoTickUnitSelection(false);
        NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();
        yAxis.setNumberFormatOverride(new DecimalFormat("#,##0"));
        AbstractXYItemRenderer renderer = (AbstractXYItemRenderer) plot.getRenderer();
        renderer.setDefaultStroke(new BasicStroke(2.0f));
        renderer.setAutoPopulateSeriesStroke(false);
        renderer.setDefaultToolTipGenerator(new StandardXYToolTipGenerator(
                "{0}: {1} of invocations under {2} µs", new PercentileFormat(), new DecimalFormat("#,##0")));
    }

    public void setInstanceDiagnostics(Collection<InstanceDiagnostics> instanceDiagnostics) {
        this.instanceDiagnosticsColl = instanceDiagnostics;
    }

    public void setRange(long startMs, long endMs) {
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public JComponent getComponent() {
        return pane;
    }

    public void update() {
        // remove series of previously loaded instances / previously selected time ranges
        while (dataset.getSeriesCount() > 0) {
            dataset.removeSeries(dataset.getSeriesKey(0));
        }

        boolean multipleInstances = instanceDiagnosticsColl.size() > 1;
        for (InstanceDiagnostics profile : instanceDiagnosticsColl) {
            Iterator<Map.Entry<Long, String>> iter = profile
                    .between(InstanceDiagnostics.DiagnosticType.TYPE_INVOCATION_PROFILER, startMs, endMs);
            if (!iter.hasNext()) {
                continue;
            }
            String startProfile = iter.next().getValue();
            String endProfile = null;
            while (iter.hasNext()) {
                endProfile = iter.next().getValue();
            }
            if (endProfile != null) {
                String seriesPrefix = multipleInstances ? profile.getDirectory().getName() + ": " : "";
                try {
                    updateWithProfiles(seriesPrefix, startProfile, endProfile);
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void updateWithProfiles(String seriesPrefix, String startProfileStr, String endProfileStr) throws ParseException {
        Map<String, SortedMap<Long, Long>> startLatencies = extractOperationToLatencyProfile(parseProfile(startProfileStr));
        Map<String, SortedMap<Long, Long>> endLatencies = extractOperationToLatencyProfile(parseProfile(endProfileStr));
        subtractStartFromEnd(startLatencies, endLatencies);

        // only operations that were actually invoked within the selected range
        endLatencies.values().removeIf(profile -> !OperationNames.hasSamples(profile));
        Map<String, String> displayNames = OperationNames.displayNames(endLatencies.keySet());

        for (Entry<String, SortedMap<Long, Long>> operationAndProfile : endLatencies.entrySet()) {
            String operation = displayNames.get(operationAndProfile.getKey());
            SortedMap<Long, Long> profile = operationAndProfile.getValue();
            double[][] percentilePlot = transposeToPercentilePlot(profile);
            dataset.addSeries(seriesPrefix + operation, percentilePlot);
        }
    }

    private double[][] transposeToPercentilePlot(SortedMap<Long, Long> latencyProfile) {
        double[][] result = new double[2][latencyProfile.size()];
        long totalCount = 0;
        for (long count : latencyProfile.values()) {
            totalCount += count;
        }
        totalCount++;
        long runningCount = 0;
        int index = 0;
        for (Entry<Long, Long> latencyAndCount : latencyProfile.entrySet()) {
            runningCount += latencyAndCount.getValue();
            // x: number of nines of the percentile p (p=0.9 -> 1, p=0.99 -> 2, ...)
            result[0][index] = Math.log10(1.0 / (1.0 - (double) runningCount / totalCount));
            // y: exclusive upper bound (µs) of the bucket; buckets are [2^k, 2^(k+1)), except the first one [0, 2)
            long bucketMinUs = latencyAndCount.getKey();
            result[1][index++] = bucketMinUs == 0 ? 2.0 : 2.0 * bucketMinUs;
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, SortedMap<Long, Long>> extractOperationToLatencyProfile(
            Map<String, Object> invocationProfile
    ) {
        Map<String, SortedMap<Long, Long>> result = new HashMap<>();
        for (Entry<String, Object> e : invocationProfile.entrySet()) {
            SortedMap<Long, Long> latencyProfile = new TreeMap<>();
            result.put(e.getKey(), latencyProfile);
            Map<String, Object> profile = (Map<String, Object>) e.getValue();
            Map<String, String> latencyProfileStrs = (Map<String, String>) profile.get("latency-distribution");
            for (Entry<String, String> dataPoint : latencyProfileStrs.entrySet()) {
                String bracket = dataPoint.getKey();
                Long latency = Long.parseLong(bracket.substring(0, bracket.indexOf('.')));
                Long count = Long.parseLong(dataPoint.getValue().replace(",", ""));
                latencyProfile.put(latency, count);
            }
        }
        return result;
    }

    private static Map<String, Object> parseProfile(String profileStr) throws ParseException {
        int markerStart = profileStr.indexOf(INVOCATION_PROFILE_MARKER);
        if (markerStart < 0) {
            throw new ParseException("Didn't find start of profile, '" + INVOCATION_PROFILE_MARKER + "'", 0);
        }
        return new MetricsParser(profileStr, markerStart + INVOCATION_PROFILE_MARKER.length() - 1).parseMap();
    }

    private static void subtractStartFromEnd(
            Map<String, SortedMap<Long, Long>> startLatencies,
            Map<String, SortedMap<Long, Long>> endLatencies
    ) {
        for (Entry<String, SortedMap<Long, Long>> operationAndProfile : endLatencies.entrySet()) {
            String operation = operationAndProfile.getKey();
            SortedMap<Long, Long> profileAtStart = startLatencies.get(operation);
            if (profileAtStart == null) {
                continue;
            }
            for (Entry<Long, Long> latAndCount : operationAndProfile.getValue().entrySet()) {
                Long latency = latAndCount.getKey();
                Long countAtStart = profileAtStart.get(latency);
                if (countAtStart == null) {
                    continue;
                }
                latAndCount.setValue(latAndCount.getValue() - countAtStart);
            }
        }
    }

    private static class MetricsParser {
        private static final Pattern RE_KEY = Pattern.compile("\\s*(.+?)([=\\[])");
        private static final Pattern RE_VALUE = Pattern.compile("(.+?)([\\s\\]])");

        private final String string;
        private int offset;

        public MetricsParser(String string, int offset) {
            this.string = string;
            this.offset = offset;
        }

        Map<String, Object> parseMap() throws ParseException {
            if (string.charAt(offset) != '[') {
                throw new ParseException("[ expected: " + string.substring(offset), offset);
            }
            Map<String, Object> parsed = new HashMap<>();
            offset++;
            while (true) {
                if (string.charAt(offset) == ']') {
                    offset++;
                    break;
                }
                Matcher keyAndDelimiter = find(RE_KEY);
                if (keyAndDelimiter == null) {
                    throw new ParseException("key expected: " + string.substring(offset), offset);
                }
                String key = keyAndDelimiter.group(1);
                String delimiter = keyAndDelimiter.group(2);
                if (delimiter.equals("=")) {
                    offset = keyAndDelimiter.end(2);
                    Matcher valueAndDelimiter = find(RE_VALUE);
                    if (valueAndDelimiter == null) {
                        throw new ParseException("Failed to parse value", key.length() + 1);
                    }
                    String value = valueAndDelimiter.group(1);
                    parsed.put(key, value);
                    if (valueAndDelimiter.group(2).equals("]")) {
                        offset = valueAndDelimiter.end(2);
                        break;
                    }
                    offset = valueAndDelimiter.end(2);
                } else if (delimiter.equals("[")) {
                    offset = keyAndDelimiter.start(2);
                    parsed.put(key, parseMap());
                } else {
                    throw new ParseException("= or [ expected: " + string.substring(offset), offset);
                }
            }
            return parsed;
        }

        private Matcher find(Pattern pattern) {
            Matcher m = pattern.matcher(string);
            m.region(offset, string.length());
            if (!m.find()) {
                return null;
            }
            return m;
        }
    }

    /**
     * Formats a "number of nines" value as a percentile: 0 -> 0%, 1 -> 90%, 2 -> 99%, 3 -> 99.9%, 2.5 -> 99.68%.
     */
    private static final class PercentileFormat extends NumberFormat {

        @Override
        public StringBuffer format(double nines, StringBuffer toAppendTo, FieldPosition pos) {
            double percentile = 100.0 * (1.0 - Math.pow(10, -nines));
            DecimalFormat format = new DecimalFormat("0");
            format.setMaximumFractionDigits(Math.max(0, (int) Math.ceil(nines - 1e-9) - 1));
            return toAppendTo.append(format.format(percentile)).append('%');
        }

        @Override
        public StringBuffer format(long nines, StringBuffer toAppendTo, FieldPosition pos) {
            return format((double) nines, toAppendTo, pos);
        }

        @Override
        public Number parse(String source, ParsePosition parsePosition) {
            throw new UnsupportedOperationException();
        }
    }
}

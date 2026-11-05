package com.hazelcast.diagnostics;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.CategoryLabelPositions;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.labels.StandardCategoryToolTipGenerator;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.data.category.DefaultCategoryDataset;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LatencyDistributionPane {
    private static final String INVOCATION_PROFILE_MARKER = "InvocationProfiler[";

    private final DefaultCategoryDataset dataset = new DefaultCategoryDataset();
    private final JPanel component;
    private long startMs = Long.MIN_VALUE;
    private long endMs = Long.MAX_VALUE;
    private Collection<InstanceDiagnostics> instanceDiagnosticsColl = new ArrayList<>();
    private final DefaultComboBoxModel<String> comboBoxModel;
    private final JComboBox<String> comboBox;
    private final Map<InstanceDiagnostics, Map<String, SortedMap<Long,Long>>> distributionMap = new HashMap<>();
    private final InstanceDiagnostics.DiagnosticType type;
    private String active;

    public LatencyDistributionPane(InstanceDiagnostics.DiagnosticType type) {
        this.type = type;
        comboBoxModel = new DefaultComboBoxModel<>();
        comboBox = new JComboBox<>(comboBoxModel);
        comboBox.addActionListener(e -> {
            active = (String) comboBox.getSelectedItem();
            render();
        });
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        String title = type == InstanceDiagnostics.DiagnosticType.TYPE_OPERATION_PROFILER
                ? "Operation Latency Distribution"
                : "Invocation Latency Distribution";
        String yAxisLabel = type == InstanceDiagnostics.DiagnosticType.TYPE_OPERATION_PROFILER
                ? "Operations"
                : "Invocations";

        JFreeChart barChart = ChartFactory.createBarChart(
                title,
                "Latency Range (µs)",
                yAxisLabel,
                dataset,
                PlotOrientation.VERTICAL,
                true, true, false);

        CategoryPlot plot = barChart.getCategoryPlot();
        CategoryAxis domainAxis = plot.getDomainAxis();
        domainAxis.setCategoryLabelPositions(CategoryLabelPositions.UP_45);

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        DecimalFormat countFormat = new DecimalFormat("#,##0");
        rangeAxis.setNumberFormatOverride(countFormat);

        BarRenderer renderer = (BarRenderer) plot.getRenderer();
        renderer.setDefaultToolTipGenerator(new StandardCategoryToolTipGenerator(
                "{0} - {1}: {2} " + yAxisLabel.toLowerCase(), countFormat));

        ChartPanel chart = new ChartPanel(barChart);
        panel.add(comboBox, BorderLayout.NORTH);
        panel.add(chart, BorderLayout.CENTER);
        component = panel;
    }

    public void setInstanceDiagnostics(Collection<InstanceDiagnostics> instanceDiagnostics) {
        this.instanceDiagnosticsColl = instanceDiagnostics;
    }

    public void setRange(long startMs, long endMs) {
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public JComponent getComponent() {
        return component;
    }

    public void render() {
        dataset.clear();
        if (active == null) {
            System.out.println("nothing selected");
        } else {
            for(Map.Entry<InstanceDiagnostics,Map<String,SortedMap<Long,Long>>> entry: distributionMap.entrySet()){
                InstanceDiagnostics diagnostics = entry.getKey();
                Map<String,SortedMap<Long,Long>> map = entry.getValue();
                SortedMap<Long,Long> distribution = map.get(active);
                if(distribution == null){
                    continue;
                }

                for (Map.Entry<Long, Long> e : distribution.entrySet()) {
                    long bucketMinUs = e.getKey();
                    long bucketMaxUs = bucketMinUs == 0 ? 1 : (bucketMinUs * 2) - 1;
                    String categoryLabel = bucketMinUs + ".." + bucketMaxUs;
                    dataset.addValue(e.getValue(), diagnostics.getDirectory().getName(), categoryLabel);
                }
            }
        }
    }

    public void update() {
        distributionMap.clear();

        for (InstanceDiagnostics diagnostics : instanceDiagnosticsColl) {
            Iterator<Entry<Long, String>> iter = diagnostics.between(type, startMs, endMs);
            if (!iter.hasNext()) {
                continue;
            }

            String startProfile = iter.next().getValue();
            String endProfile = null;
            while (iter.hasNext()) {
                endProfile = iter.next().getValue();
                // System.out.println(endProfile);
            }
            if (endProfile == null) {
                // a single snapshot in range; no delta can be computed
                continue;
            }

            try {
               Map<String,SortedMap<Long,Long>> result = calc(startProfile, endProfile);
               distributionMap.put(diagnostics, result);
            } catch (ParseException e) {
                e.printStackTrace();
            }
        }

        Set<String> operations = new HashSet<>();
        for(Map<String,SortedMap<Long,Long>> m: distributionMap.values()){
            operations.addAll(m.keySet());
        }

        java.util.List<String> operationsList = new ArrayList<>(operations);
        Collections.sort(operationsList);

        // replace (not append to) the operations of previously loaded instances / time ranges
        String previouslyActive = active;
        comboBoxModel.removeAllElements();
        for (String operation : operationsList) {
            comboBoxModel.addElement(operation);
        }
        if (previouslyActive != null && operations.contains(previouslyActive)) {
            comboBox.setSelectedItem(previouslyActive);
        }
        active = (String) comboBox.getSelectedItem();

        render();
    }


    private Map<String,SortedMap<Long,Long>> calc(String startProfileStr, String endProfileStr) throws ParseException {
        Map<String, SortedMap<Long, Long>> startLatencies = extractOperationToLatencyProfile(parseProfile(startProfileStr));
        Map<String, SortedMap<Long, Long>> endLatencies = extractOperationToLatencyProfile(parseProfile(endProfileStr));
        // endLatencies now holds the deltas within the selected range
        subtractStartFromEnd(startLatencies, endLatencies);

        // only operations that were actually invoked within the selected range
        endLatencies.values().removeIf(profile -> !OperationNames.hasSamples(profile));
        Map<String, String> displayNames = OperationNames.displayNames(endLatencies.keySet());

        Map<String,SortedMap<Long,Long>> result  = new HashMap<>();
        for (Entry<String, SortedMap<Long, Long>> endLatency : endLatencies.entrySet()) {
            String operation = displayNames.get(endLatency.getKey());
            result.put(operation, new TreeMap<>(endLatency.getValue()));
        }

        return result;
    }

    private double[][] transposeToPercentilePlot(SortedMap<Long, Long> latencyProfile) {
        double[][] result = new double[2][latencyProfile.size()];
        long totalCount = 0;
        for (long count : latencyProfile.values()) {
            totalCount += count;
        }
        long runningCount = 0;
        int index = 0;
        for (Entry<Long, Long> latencyAndCount : latencyProfile.entrySet()) {
            result[0][index] = Math.log10(100.0 / (1.0 - (double) runningCount / totalCount));
            result[1][index++] = 1.5 * latencyAndCount.getKey();
            runningCount += latencyAndCount.getValue();
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
        profileStr = profileStr.replace("OperationsProfiler[", INVOCATION_PROFILE_MARKER);

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
}

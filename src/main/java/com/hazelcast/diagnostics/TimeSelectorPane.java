package com.hazelcast.diagnostics;

import com.jidesoft.swing.RangeSlider;

import javax.swing.BoundedRangeModel;
import javax.swing.JComponent;
import javax.swing.event.ChangeListener;
import java.util.Collection;

public class TimeSelectorPane {

    private final JComponent component;
    private final RangeSlider rangeSlider;
    private long startMs;

    public TimeSelectorPane() {
        this.rangeSlider = new RangeSlider();
        rangeSlider.setRangeDraggable(true);
        this.component = rangeSlider;
    }

    public void addChangeListener(ChangeListener changeListener) {
        rangeSlider.addChangeListener(changeListener);
    }

    public long getStartMs() {
        return rangeSlider.getLowValue() + startMs;
    }

    public long getEndMs() {
        return rangeSlider.getHighValue() + startMs;
    }

    public JComponent getComponent() {
        return this.component;
    }

    public void setInstanceDiagnostics(Collection<InstanceDiagnostics> instanceDiagnosticsList) {
        long durationMs;
        long endMs;
        if (instanceDiagnosticsList.isEmpty()) {
            startMs = 0;
            return;
        }

        boolean hasValidRange = false;
        long minStart = Long.MAX_VALUE;
        long maxEnd = Long.MIN_VALUE;
        for (InstanceDiagnostics instanceDiagnostics : instanceDiagnosticsList) {
            if (instanceDiagnostics.startMs() <= instanceDiagnostics.endMs()) {
                hasValidRange = true;
                minStart = Math.min(minStart, instanceDiagnostics.startMs());
                maxEnd = Math.max(maxEnd, instanceDiagnostics.endMs());
            }
        }

        if (!hasValidRange) {
            this.startMs = 0;
            durationMs = 0;
        } else {
            this.startMs = minStart;
            durationMs = Math.max(0, maxEnd - minStart);
        }
        int maxSliderValue = (int) Math.min(Integer.MAX_VALUE, durationMs);

        BoundedRangeModel model = rangeSlider.getModel();
        model.setMinimum(0);
        model.setMaximum(maxSliderValue);
        model.setValue(0);
        model.setExtent(maxSliderValue);
    }
}

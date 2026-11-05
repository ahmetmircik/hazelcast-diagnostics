package com.hazelcast.diagnostics;

import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import java.awt.Font;
import java.util.Enumeration;

public class Main {

    public static void main(String[] args) {
        enableAntiAliasing();
        ensureRangeSliderUI();
        incFontSize(1.5f);

        MainWindow mainWindow = new MainWindow();
        mainWindow.getJFrame().setVisible(true);
    }

    /**
     * Newer JDKs on macOS declare com.apple.laf.AquaSliderUI final or restrict access, which causes
     * JIDE's AquaRangeSliderUI subclass to fail with IncompatibleClassChangeError or IllegalAccessError.
     * When that happens, fall back to BasicRangeSliderUI so RangeSlider initializes cleanly on all JDKs.
     */
    private static void ensureRangeSliderUI() {
        try {
            Class.forName("com.jidesoft.plaf.aqua.AquaRangeSliderUI");
        } catch (Throwable t) {
            UIManager.put("RangeSliderUI", "com.jidesoft.plaf.basic.BasicRangeSliderUI");
        }
    }

    private static void enableAntiAliasing() {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
    }

    public static void incFontSize(float multiplier) {
        UIDefaults defaults = UIManager.getDefaults();
        for (Enumeration<Object> e = defaults.keys(); e.hasMoreElements(); ) {
            Object key = e.nextElement();
            Object value = defaults.get(key);
            if (value instanceof Font font) {
                int newSize = Math.round(font.getSize() * multiplier);
                if (value instanceof FontUIResource) {
                    defaults.put(key, new FontUIResource(font.getName(), font.getStyle(), newSize));
                } else {
                    defaults.put(key, new Font(font.getName(), font.getStyle(), newSize));
                }
            }
        }
    }
}

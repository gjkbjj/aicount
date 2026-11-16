package com.gakiji.aiaccount;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PageInspectorTest {
    @Test public void distinguishesPlaceholderAndReadableRoots() {
        assertFalse(BillService.hasTree(null, false, false, 0));
        assertFalse(BillService.hasTree("android.webkit.WebView", true, false, 0));
        assertTrue(BillService.hasTree("android.widget.FrameLayout", true, true, 0));
        assertTrue(BillService.hasTree("android.widget.FrameLayout", false, false, 1));
    }

    @Test public void treatsWebViewAsDeepContainer() {
        assertTrue(BillService.isWebContainer("android.webkit.WebView"));
        assertFalse(BillService.isWebContainer("android.widget.FrameLayout"));
        assertFalse(BillService.isWebContainer(null));
    }

    @Test public void masksLongIdentifiersAndKeepsUsefulFields() {
        String report = PageInspector.safeText("交易单号：1042814920473520\n实付：46.41\n电话：138 0000 4321");
        assertTrue(report.contains("交易单号：…"));
        assertTrue(report.contains("实付：46.41"));
        assertTrue(report.contains("电话：…"));
        assertFalse(report.contains("4321"));
        assertEquals("交易成功", PageInspector.safeText("交易成功"));
    }
}

package dev.g992.rcviewer;

import android.graphics.Color;

/**
 * Dark palette mirrored (by value) from the sibling {@code rm330_suite} launcher's
 * {@code Ui} constants, so this app and the launcher look like one coherent product.
 * Copied as plain constants on purpose — no module dependency on that separate project.
 */
final class Palette {
    private Palette() {}

    static final int BG = Color.parseColor("#0E1116");
    static final int CARD = Color.parseColor("#1A1F27");
    static final int CARD_PRESSED = Color.parseColor("#2A313C");
    static final int CARD_BORDER = Color.parseColor("#2A313C");
    static final int ACCENT = Color.parseColor("#2F7CF6");
    static final int TEXT = Color.parseColor("#F2F4F7");
    static final int TEXT_DIM = Color.parseColor("#9AA4B2");
    static final int WARN = Color.parseColor("#E5A93B");
    static final int GOOD = Color.parseColor("#3DBE6B");
    static final int DANGER = Color.parseColor("#E5493B");
    static final int TRACK_OFF = Color.parseColor("#3A4250");
}

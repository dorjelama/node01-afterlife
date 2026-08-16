package dev.node01.afterlife

/**
 * NODE operating modes.
 *
 * Only [DESK] is wired up in v1.0; the rest are declared so that the window
 * behaviour that distinguishes them -- backlight level and whether the screen is
 * held awake -- lives in one place from the start. Mode-specific layouts arrive
 * with the modes themselves.
 *
 * @param brightness window backlight, 0f..1f. This is a *window* attribute, so
 *   it needs no permission and never touches the system brightness setting.
 *   The panel is IPS, so backlight level -- not pixel colour -- is what actually
 *   costs battery here.
 * @param keepScreenOn whether the window holds the screen awake.
 */
enum class Mode(
    val brightness: Float,
    val keepScreenOn: Boolean,
) {
    /**
     * Desk clock. Lit during waking hours at the panel minimum, released
     * overnight so the device can deep-sleep. On an IPS panel the backlight is
     * essentially the entire power budget, so this is the only lever that
     * matters -- measured ~10%/hour at 0.10 brightness.
     */
    DESK(brightness = 0.01f, keepScreenOn = true),

    /** Restrained system-status readout. */
    TERMINAL(brightness = 0.14f, keepScreenOn = true),

    /** 1970s-90s instrument panel aesthetic. */
    ARCHIVE(brightness = 0.14f, keepScreenOn = true),

    /** Bedside. As dark as the panel will go while still legible. */
    NIGHT(brightness = 0.01f, keepScreenOn = true),

    /**
     * Display released entirely so Android can deep-sleep. Brightness is
     * irrelevant here -- the window stops holding the screen at all.
     */
    SLEEP(brightness = 0.01f, keepScreenOn = false),
}

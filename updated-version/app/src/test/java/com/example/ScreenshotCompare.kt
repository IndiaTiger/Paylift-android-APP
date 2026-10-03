package com.example

import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions

/**
 * Comparison settings shared by the screenshot tests.
 *
 * Robolectric's native renderer anti-aliases text slightly differently on Windows, macOS and
 * Linux: a few hundred edge pixels differ by 1-4 levels (of 255) per colour channel, which is
 * invisible. The reference images were recorded on Windows and CI runs on Linux, so a pixel only
 * counts as different when its colour distance exceeds [MAX_COLOR_DISTANCE] (about 2% of the
 * colour range; the measured cross-platform noise peaks at 0.027, a real text or layout change is
 * 0.19 or more). The number of differing pixels allowed stays at ZERO, so any visible change
 * still fails the test.
 */
object ScreenshotCompare {
    private const val MAX_COLOR_DISTANCE = 0.04f

    val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(
            imageComparator = SimpleImageComparator(maxDistance = MAX_COLOR_DISTANCE),
            resultValidator = { result -> result.pixelDifferences == 0 },
        )
    )
}

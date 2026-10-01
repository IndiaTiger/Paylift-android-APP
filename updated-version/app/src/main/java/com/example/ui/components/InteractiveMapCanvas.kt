package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.LocationPoint
import com.example.domain.VehicleTypeGroup
import com.example.ui.theme.DeepRoyalBlue
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.PureWhite
import com.example.ui.theme.RoseEmergency
import com.example.ui.theme.SoftIceBlue
import com.example.ui.theme.VividBlue
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun InteractiveMapCanvas(
    pickup: LocationPoint,
    dropoff: LocationPoint,
    routeWaypoints: List<Pair<Double, Double>>,
    progressFraction: Float = 0f,
    isTwoWheeler: Boolean = false,
    speedKmph: Int = 32,
    activeRideActive: Boolean = false,
    modifier: Modifier = Modifier,
    /** Manual location selection: long-press returns the (lat, lng) under the finger. */
    onLongPressLatLng: ((Double, Double) -> Unit)? = null
) {
    var scale by remember { mutableFloatStateOf(1.0f) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }
    var mapStyle by remember { mutableStateOf("Classic Blue") } // "Classic Blue", "Satellite Dark", "Slate Minimal"

    val textMeasurer = rememberTextMeasurer()

    // Pulse animation for pins & pilot GPS
    val infiniteTransition = rememberInfiniteTransition(label = "map_pulse")
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 8f,
        targetValue = 26f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_radius"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.65f, 2.5f)
                    panOffsetX = (panOffsetX + pan.x).coerceIn(-400f, 400f)
                    panOffsetY = (panOffsetY + pan.y).coerceIn(-400f, 400f)
                }
            }
            .pointerInput(onLongPressLatLng) {
                if (onLongPressLatLng == null) return@pointerInput
                detectTapGestures(onLongPress = { pos ->
                    // Inverse of toScreenOffset() below (same projection constants).
                    val k = 2200f * scale
                    val lng = MAP_REF_LNG + (pos.x - (size.width / 2f + panOffsetX)) / k
                    val lat = MAP_REF_LAT - (pos.y - (size.height / 2f + panOffsetY)) / k
                    onLongPressLatLng(lat, lng)
                })
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val centerX = width / 2f + panOffsetX
            val centerY = height / 2f + panOffsetY

            // Base Map Style Palettes
            val bgGradient = when (mapStyle) {
                "Satellite Dark" -> Brush.verticalGradient(
                    listOf(Color(0xFF07111E), Color(0xFF0F1A28), Color(0xFF070F18))
                )
                "Slate Minimal" -> Brush.verticalGradient(
                    listOf(Color(0xFFF1F5F9), Color(0xFFE2E8F0), Color(0xFFF8FAFC))
                )
                else -> Brush.verticalGradient( // Classic Blue
                    listOf(Color(0xFF0D224A), Color(0xFF081836), Color(0xFF051026))
                )
            }

            drawRect(brush = bgGradient)

            // Draw Mountain/Foothill Contours (Mussoorie Range to the North)
            val hillColor = when (mapStyle) {
                "Satellite Dark" -> Color(0x1F163458)
                "Slate Minimal" -> Color(0x15073B8F)
                else -> Color(0x221267E8)
            }
            val hillPath = Path().apply {
                moveTo(0f, 0f)
                lineTo(width, 0f)
                lineTo(width, height * 0.22f)
                cubicTo(
                    width * 0.75f, height * 0.16f,
                    width * 0.5f, height * 0.25f,
                    width * 0.25f, height * 0.18f
                )
                lineTo(0f, height * 0.2f)
                close()
            }
            drawPath(path = hillPath, color = hillColor)

            // Draw Rivers: Bindal Rao & Rispana River
            val riverColor = when (mapStyle) {
                "Slate Minimal" -> Color(0x4038BDF8)
                else -> Color(0x3538BDF8)
            }
            val riverPath = Path().apply {
                moveTo(width * 0.18f + panOffsetX * 0.4f, 0f)
                cubicTo(
                    width * 0.25f + panOffsetX * 0.4f, height * 0.4f,
                    width * 0.35f + panOffsetX * 0.4f, height * 0.65f,
                    width * 0.42f + panOffsetX * 0.4f, height
                )
            }
            drawPath(
                path = riverPath,
                color = riverColor,
                style = Stroke(width = 6f * scale, cap = StrokeCap.Round)
            )

            // Arterial City Roads Grid & Highway Corridors
            val roadColor = when (mapStyle) {
                "Slate Minimal" -> Color(0xFFCBD5E1)
                "Satellite Dark" -> Color(0x25FFFFFF)
                else -> Color(0x28FFFFFF)
            }
            val majorHighwayColor = when (mapStyle) {
                "Slate Minimal" -> Color(0xFF94A3B8)
                "Satellite Dark" -> Color(0x44CBD5E1)
                else -> Color(0x4460A5FA)
            }

            // Secondary grid lines
            for (i in 1..7) {
                val yLine = (height / 8f) * i + (panOffsetY * 0.2f)
                drawLine(
                    color = roadColor,
                    start = Offset(0f, yLine),
                    end = Offset(width, yLine),
                    strokeWidth = 1.5f * scale
                )
            }

            // Diagonal Rajpur Road Highway Corridor
            drawLine(
                color = majorHighwayColor,
                start = Offset(width * 0.25f + panOffsetX * 0.5f, height),
                end = Offset(width * 0.85f + panOffsetX * 0.5f, height * 0.15f),
                strokeWidth = 4.5f * scale,
                cap = StrokeCap.Round
            )

            // Chakrata Road Arterial
            drawLine(
                color = majorHighwayColor,
                start = Offset(0f, height * 0.55f + panOffsetY * 0.5f),
                end = Offset(width * 0.75f, height * 0.42f + panOffsetY * 0.5f),
                strokeWidth = 3.5f * scale,
                cap = StrokeCap.Round
            )

            // Haridwar Bypass Ring
            drawLine(
                color = majorHighwayColor,
                start = Offset(width * 0.15f + panOffsetX * 0.5f, height * 0.82f),
                end = Offset(width * 0.95f + panOffsetX * 0.5f, height * 0.72f),
                strokeWidth = 4.0f * scale,
                cap = StrokeCap.Round
            )

            // Map Coordinate Projection relative to Center
            // Reference Dehradun center ~ 30.3255, 78.0436
            val refLat = MAP_REF_LAT
            val refLon = MAP_REF_LNG
            val coordScaleX = 2200f * scale
            val coordScaleY = 2200f * scale

            fun toScreenOffset(lat: Double, lon: Double): Offset {
                val dx = (lon - refLon) * coordScaleX
                val dy = -(lat - refLat) * coordScaleY // invert latitude
                return Offset(centerX + dx.toFloat(), centerY + dy.toFloat())
            }

            val pOffset = toScreenOffset(pickup.latitude, pickup.longitude)
            val dOffset = toScreenOffset(dropoff.latitude, dropoff.longitude)

            // Draw Route Polyline
            val routePath = Path()
            if (routeWaypoints.isNotEmpty()) {
                val first = toScreenOffset(routeWaypoints.first().first, routeWaypoints.first().second)
                routePath.moveTo(first.x, first.y)
                for (wp in routeWaypoints.drop(1)) {
                    val pt = toScreenOffset(wp.first, wp.second)
                    routePath.lineTo(pt.x, pt.y)
                }
            } else {
                routePath.moveTo(pOffset.x, pOffset.y)
                routePath.lineTo(dOffset.x, dOffset.y)
            }

            // Polyline Glow Background (only once both ends are chosen)
            if (pickup.isSelected && dropoff.isSelected) drawPath(
                path = routePath,
                color = VividBlue.copy(alpha = 0.35f),
                style = Stroke(width = 12f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            // Polyline Core
            if (pickup.isSelected && dropoff.isSelected) drawPath(
                path = routePath,
                color = VividBlue,
                style = Stroke(width = 5.5f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Draw Pickup Location (Green Pin + Pulsing Circle)
            drawCircle(
                color = EmeraldSuccess.copy(alpha = pulseAlpha),
                radius = pulseRadius * scale,
                center = pOffset
            )
            drawCircle(
                color = PureWhite,
                radius = 9f * scale,
                center = pOffset
            )
            drawCircle(
                color = EmeraldSuccess,
                radius = 6.5f * scale,
                center = pOffset
            )

            // Pickup Tag
            val pickupResult = textMeasurer.measure(
                // Placeholder points (nothing chosen yet) get no label, so they never overlap.
                text = if (pickup.isSelected) pickup.name.take(18) else "",
                style = TextStyle(
                    color = if (mapStyle == "Slate Minimal") Color(0xFF0F172A) else PureWhite,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            drawText(
                textLayoutResult = pickupResult,
                topLeft = Offset(pOffset.x - pickupResult.size.width / 2f, pOffset.y - 28f * scale)
            )

            // Draw Dropoff Location (Red Flag / Marker)
            drawCircle(
                color = RoseEmergency.copy(alpha = 0.3f),
                radius = 16f * scale,
                center = dOffset
            )
            drawCircle(
                color = PureWhite,
                radius = 9f * scale,
                center = dOffset
            )
            drawCircle(
                color = RoseEmergency,
                radius = 6.5f * scale,
                center = dOffset
            )

            // Dropoff Tag
            val dropResult = textMeasurer.measure(
                text = if (dropoff.isSelected) dropoff.name.take(18) else "",
                style = TextStyle(
                    color = if (mapStyle == "Slate Minimal") Color(0xFF0F172A) else PureWhite,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            drawText(
                textLayoutResult = dropResult,
                topLeft = Offset(dOffset.x - dropResult.size.width / 2f, dOffset.y - 28f * scale)
            )

            // Draw Animated Live Pilot GPS Marker when ride is in transit or assigned
            if (activeRideActive) {
                val clampedFraction = progressFraction.coerceIn(0f, 1f)
                val currentCarOffset = if (routeWaypoints.size > 1) {
                    val exactIndex = (clampedFraction * (routeWaypoints.size - 1))
                    val idx = exactIndex.toInt().coerceIn(0, routeWaypoints.size - 2)
                    val subFraction = exactIndex - idx
                    val p1 = toScreenOffset(routeWaypoints[idx].first, routeWaypoints[idx].second)
                    val p2 = toScreenOffset(routeWaypoints[idx + 1].first, routeWaypoints[idx + 1].second)
                    Offset(
                        p1.x + (p2.x - p1.x) * subFraction,
                        p1.y + (p2.y - p1.y) * subFraction
                    )
                } else {
                    Offset(
                        pOffset.x + (dOffset.x - pOffset.x) * clampedFraction,
                        pOffset.y + (dOffset.y - pOffset.y) * clampedFraction
                    )
                }

                // Pilot GPS Wave Pulse
                drawCircle(
                    color = VividBlue.copy(alpha = pulseAlpha),
                    radius = (pulseRadius + 12f) * scale,
                    center = currentCarOffset
                )

                // Vehicle Shield Body
                drawCircle(
                    color = DeepRoyalBlue,
                    radius = 14f * scale,
                    center = currentCarOffset
                )
                drawCircle(
                    color = PureWhite,
                    radius = 11f * scale,
                    center = currentCarOffset
                )
                drawCircle(
                    color = if (isTwoWheeler) Color(0xFFF59E0B) else VividBlue,
                    radius = 8.5f * scale,
                    center = currentCarOffset
                )

                // Telemetry Speed Badge
                val speedText = if (speedKmph > 0) "$speedKmph km/h" else "LIVE"
                val speedResult = textMeasurer.measure(
                    text = speedText,
                    style = TextStyle(
                        color = PureWhite,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                )
                // Draw badge background rectangle
                val badgeTopLeft = Offset(
                    currentCarOffset.x - speedResult.size.width / 2f - 4f,
                    currentCarOffset.y + 16f * scale
                )
                drawRoundRect(
                    color = DeepRoyalBlue,
                    topLeft = badgeTopLeft,
                    size = androidx.compose.ui.geometry.Size(
                        speedResult.size.width + 8f,
                        speedResult.size.height + 4f
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                )
                drawText(
                    textLayoutResult = speedResult,
                    topLeft = Offset(badgeTopLeft.x + 4f, badgeTopLeft.y + 2f)
                )
            }
        }

        // Map Control Floating Actions: Reset Center & Map Layer Toggle
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Layer Style Switcher
            Surface(
                shape = CircleShape,
                color = DeepRoyalBlue.copy(alpha = 0.9f),
                shadowElevation = 4.dp
            ) {
                IconButton(
                    onClick = {
                        mapStyle = when (mapStyle) {
                            "Classic Blue" -> "Satellite Dark"
                            "Satellite Dark" -> "Slate Minimal"
                            else -> "Classic Blue"
                        }
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = "Map Layers",
                        tint = SoftIceBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Recenter Button
            Surface(
                shape = CircleShape,
                color = DeepRoyalBlue.copy(alpha = 0.9f),
                shadowElevation = 4.dp
            ) {
                IconButton(
                    onClick = {
                        scale = 1.0f
                        panOffsetX = 0f
                        panOffsetY = 0f
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Center on Dehradun",
                        tint = SoftIceBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Dehradun GPS Status Badge (Top-Left)
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            color = DeepRoyalBlue.copy(alpha = 0.85f),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(EmeraldSuccess)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Dehradun Valley GPS • $mapStyle",
                    color = SoftIceBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// Schematic map reference point (Dehradun city centre). This canvas is a stylised schematic, not a
// tile map; a Maps SDK view (MAPS_API_KEY) is required for real street-level maps.
private const val MAP_REF_LAT = com.example.domain.LocationPoint.MAP_REFERENCE_LAT
private const val MAP_REF_LNG = com.example.domain.LocationPoint.MAP_REFERENCE_LNG

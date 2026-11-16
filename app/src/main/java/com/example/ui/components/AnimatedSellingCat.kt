package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
fun AnimatedSellingCatStage(
    isRunning: Boolean,
    isPausedByGuard: Boolean,
    liveMbPerMinute: Double,
    liveSpeedKbps: Double,
    modifier: Modifier = Modifier
) {
    val activelySelling = isRunning && !isPausedByGuard

    // Combine measured MB/min with instantaneous Kbps telemetry so stride reacts immediately
    val effectiveMbPerMin = remember(activelySelling, liveMbPerMinute, liveSpeedKbps) {
        if (!activelySelling) 0.0
        else max(liveMbPerMinute, (liveSpeedKbps * 60.0) / 8192.0)
    }

    // Normalized 0..1 intensity across 0 -> 2048+ MB/min (2 GB/min)
    val speedIntensity = remember(activelySelling, effectiveMbPerMin) {
        if (!activelySelling) 0f
        else (ln(1.0 + effectiveMbPerMin.coerceAtLeast(0.0)) / ln(2049.0))
            .toFloat()
            .coerceIn(0f, 1f)
    }

    // Target gallop cycles per second (Hz)
    val targetStrideHz = remember(activelySelling, speedIntensity) {
        if (!activelySelling) 0f
        else 1.85f + (speedIntensity * 6.55f)
    }

    val latestTargetStrideHz by rememberUpdatedState(targetStrideHz)
    val latestActivelySelling by rememberUpdatedState(activelySelling)

    // Smooth posture blend: 0f = sitting upright, 1f = forward-leaning gallop
    val runBlend by animateFloatAsState(
        targetValue = if (activelySelling) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.74f,
            stiffness = Spring.StiffnessLow
        ),
        label = "cat_posture_blend"
    )

    val animatedSpeedIntensity by animateFloatAsState(
        targetValue = speedIntensity,
        animationSpec = tween(durationMillis = 320),
        label = "cat_speed_intensity"
    )

    var runPhase by remember { mutableFloatStateOf(0f) }
    var idlePhase by remember { mutableFloatStateOf(0f) }
    var trackScroll by remember { mutableFloatStateOf(0f) }
    var smoothedStrideHz by remember { mutableFloatStateOf(0f) }

    // Continuous phase integration so changing MB/min smoothly speeds up or slows down without jumps
    LaunchedEffect(Unit) {
        var lastFrameNs = 0L
        while (true) {
            withFrameNanos { frameNs ->
                if (lastFrameNs != 0L) {
                    val dt = ((frameNs - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    val targetHz = latestTargetStrideHz
                    val smoothingRate = if (latestActivelySelling) 6.5f else 8.0f
                    smoothedStrideHz += (targetHz - smoothedStrideHz) * (dt * smoothingRate).coerceIn(0f, 1f)

                    if (smoothedStrideHz > 0.02f) {
                        runPhase = (runPhase + dt * smoothedStrideHz * (2f * PI.toFloat())) % (200f * PI.toFloat())
                        trackScroll = (trackScroll + dt * (0.45f + smoothedStrideHz * 0.55f)) % 1f
                    }
                    idlePhase = (idlePhase + dt) % 600f
                }
                lastFrameNs = frameNs
            }
        }
    }

    val stageBorderColor by animateColorAsState(
        targetValue = when {
            activelySelling -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            isPausedByGuard -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)
            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        },
        animationSpec = tween(260),
        label = "cat_stage_border"
    )

    val statusAccentColor by animateColorAsState(
        targetValue = when {
            activelySelling -> MaterialTheme.colorScheme.primary
            isPausedByGuard -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(260),
        label = "cat_status_accent"
    )

    val statusText = when {
        activelySelling && effectiveMbPerMin >= 1024.0 ->
            String.format(Locale.US, "Cat Gigabit Sprint • %.2f GB/min", effectiveMbPerMin / 1024.0)
        activelySelling && effectiveMbPerMin >= 180.0 ->
            String.format(Locale.US, "Cat Turbo Sprint • %.1f MB/min", effectiveMbPerMin)
        activelySelling && effectiveMbPerMin >= 1.0 ->
            String.format(Locale.US, "Cat Running • %.1f MB/min", effectiveMbPerMin)
        activelySelling ->
            String.format(Locale.US, "Cat Running • %.2f MB/min", effectiveMbPerMin)
        isPausedByGuard ->
            "Cat Waiting • Guard Paused"
        else ->
            "Cat Sitting • Ready to Sell"
    }

    val paceBadgeText = when {
        activelySelling && effectiveMbPerMin >= 2048.0 -> "2GB+"
        activelySelling && effectiveMbPerMin >= 1024.0 -> "1GB+"
        activelySelling && speedIntensity >= 0.85f -> "MAX"
        activelySelling -> String.format(Locale.US, "%.1fx", 1.0f + speedIntensity * 5.0f)
        isPausedByGuard -> "HOLD"
        else -> "IDLE"
    }

    val colorScheme = MaterialTheme.colorScheme
    val stageDescription = when {
        activelySelling -> "Animated cat running at bandwidth selling speed"
        isPausedByGuard -> "Animated cat resting while guard is paused"
        else -> "Animated cat sitting while node is stopped"
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, stageBorderColor),
        modifier = modifier
            .fillMaxWidth()
            .testTag("cat_runner_stage")
            .semantics { contentDescription = stageDescription }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusAccentColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("cat_status_label")
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusAccentColor.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = paceBadgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusAccentColor,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("cat_pace_badge")
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .testTag("cat_runner_canvas")
            ) {
                val groundY = size.height * 0.80f
                val furColor = lerp(colorScheme.onSurface, colorScheme.primaryContainer, 0.14f)
                val furShadowColor = lerp(furColor, colorScheme.surfaceVariant, 0.35f)
                val chestAccentColor = lerp(colorScheme.surface, colorScheme.onSurface, 0.12f)
                val innerEarColor = colorScheme.secondary
                val collarColor = colorScheme.primary
                val coinTagColor = colorScheme.secondary
                val eyeColor = if (activelySelling) colorScheme.primary else colorScheme.secondary
                val pupilColor = colorScheme.surface
                val trackColor = colorScheme.outline.copy(alpha = 0.45f)
                val sparkColor = colorScheme.primary
                val secondarySparkColor = colorScheme.tertiary

                // Ambient glow behind cat
                val glowCenterX = size.width * 0.44f
                val glowColor = if (activelySelling) {
                    colorScheme.primary.copy(alpha = 0.14f + 0.12f * animatedSpeedIntensity)
                } else {
                    colorScheme.secondary.copy(alpha = 0.08f)
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor, Color.Transparent),
                        center = Offset(glowCenterX, groundY - size.height * 0.28f),
                        radius = size.height * 0.72f
                    ),
                    radius = size.height * 0.72f,
                    center = Offset(glowCenterX, groundY - size.height * 0.28f)
                )

                // Ground baseline
                drawLine(
                    color = trackColor,
                    start = Offset(12f, groundY),
                    end = Offset(size.width - 12f, groundY),
                    strokeWidth = 2.5f,
                    cap = StrokeCap.Round
                )

                // Scrolling track dashes & MB data sparks when running
                val dashSpacing = 36f
                val dashCount = (size.width / dashSpacing).toInt() + 2
                val dashShift = trackScroll * dashSpacing
                for (i in 0 until dashCount) {
                    val x = size.width - (i * dashSpacing + dashShift)
                    if (x in 12f..(size.width - 12f)) {
                        val dashLen = 10f + 12f * runBlend * animatedSpeedIntensity
                        drawLine(
                            color = colorScheme.primary.copy(alpha = 0.18f + 0.32f * runBlend),
                            start = Offset(x, groundY + 7f),
                            end = Offset((x - dashLen).coerceAtLeast(12f), groundY + 7f),
                            strokeWidth = 2.2f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                if (runBlend > 0.08f) {
                    val sparkCount = 4 + (animatedSpeedIntensity * 4).toInt()
                    for (i in 0 until sparkCount) {
                        val phaseOffset = (trackScroll + i * (1f / sparkCount)) % 1f
                        val sx = size.width * 0.94f - phaseOffset * (size.width * 0.88f)
                        val sy = groundY - (0.14f + (i % 3) * 0.20f) * size.height * 0.7f +
                            sin((runPhase + i) * 0.5f) * 3f
                        val streak = (8f + 22f * animatedSpeedIntensity) * runBlend
                        val lineColor = if (i % 2 == 0) sparkColor else secondarySparkColor
                        drawLine(
                            color = lineColor.copy(alpha = 0.28f * runBlend + 0.25f * animatedSpeedIntensity),
                            start = Offset(sx, sy),
                            end = Offset(sx + streak, sy),
                            strokeWidth = 2.2f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                // Draw the animated cat
                drawSellingCat(
                    groundY = groundY,
                    runBlend = runBlend,
                    runPhase = runPhase,
                    idlePhase = idlePhase,
                    speedIntensity = animatedSpeedIntensity,
                    furColor = furColor,
                    furShadowColor = furShadowColor,
                    chestAccentColor = chestAccentColor,
                    innerEarColor = innerEarColor,
                    collarColor = collarColor,
                    coinTagColor = coinTagColor,
                    eyeColor = eyeColor,
                    pupilColor = pupilColor
                )
            }
        }
    }
}

private fun DrawScope.drawSellingCat(
    groundY: Float,
    runBlend: Float,
    runPhase: Float,
    idlePhase: Float,
    speedIntensity: Float,
    furColor: Color,
    furShadowColor: Color,
    chestAccentColor: Color,
    innerEarColor: Color,
    collarColor: Color,
    coinTagColor: Color,
    eyeColor: Color,
    pupilColor: Color
) {
    // Proportional unit so the cat looks sharp on any screen/window width
    val u = min(size.height * 0.92f, size.width * 0.26f) / 64f
    val forwardSurge = runBlend * speedIntensity * 6f * u
    val anchorX = size.width * 0.44f + forwardSurge

    // Gallop bounce when running, calm breathing when sitting
    val runLift = (0.5f - 0.5f * cos(runPhase)) * (5.2f + 2.2f * speedIntensity) * u
    val idleBreath = sin(idlePhase * 2.2f) * 0.55f * u

    // Shadow under cat on the track
    val shadowWidth = lerpFloat(22f * u, 28f * u, runBlend) - (runLift * 0.45f)
    drawOval(
        color = furShadowColor.copy(alpha = lerpFloat(0.28f, 0.20f, runBlend)),
        topLeft = Offset(anchorX - shadowWidth * 0.52f, groundY - 2.2f * u),
        size = Size(shadowWidth, 4.4f * u)
    )

    // Hip and shoulder centers interpolate smoothly from sitting upright to horizontal running
    val sitHip = Offset(anchorX - 4f * u, groundY - 10f * u)
    val runHip = Offset(anchorX - 13f * u, groundY - 16f * u - runLift * 0.85f)
    val hip = lerpOffset(sitHip, runHip, runBlend)

    val sitShoulder = Offset(anchorX + 3f * u, groundY - 26f * u - idleBreath * 0.4f)
    val runShoulder = Offset(
        anchorX + 12f * u,
        groundY - 17f * u - runLift + sin(runPhase + 0.9f) * 2.0f * u
    )
    val shoulder = lerpOffset(sitShoulder, runShoulder, runBlend)

    // --- 1. Tail (drawn behind torso) ---
    val tailPath = Path().apply {
        val sitWave = sin(idlePhase * 2.4f) * 4.5f * u
        val runWave = sin(runPhase - 0.9f) * (3.5f + 2.5f * speedIntensity) * u

        val start = hip + Offset(-4f * u, 1f * u)
        val sitC1 = start + Offset(-12f * u, 2f * u)
        val sitC2 = start + Offset(-18f * u + sitWave * 0.5f, -10f * u)
        val sitTip = start + Offset(-13f * u + sitWave, -19f * u)

        val runC1 = start + Offset(-10f * u, -2f * u + runWave * 0.4f)
        val runC2 = start + Offset(-19f * u, -8f * u - runWave * 0.5f)
        val runTip = start + Offset(-26f * u, -4f * u + runWave)

        val c1 = lerpOffset(sitC1, runC1, runBlend)
        val c2 = lerpOffset(sitC2, runC2, runBlend)
        val tip = lerpOffset(sitTip, runTip, runBlend)

        moveTo(start.x, start.y)
        cubicTo(c1.x, c1.y, c2.x, c2.y, tip.x, tip.y)
    }
    drawPath(
        path = tailPath,
        color = furShadowColor,
        style = Stroke(width = 4.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // --- 2. Far-side legs (behind torso) ---
    drawCatLeg(
        hipOrShoulder = hip,
        groundY = groundY,
        u = u,
        runBlend = runBlend,
        stridePhase = runPhase + 0.65f,
        isFront = false,
        speedIntensity = speedIntensity,
        color = furShadowColor
    )
    drawCatLeg(
        hipOrShoulder = shoulder,
        groundY = groundY,
        u = u,
        runBlend = runBlend,
        stridePhase = runPhase + PI.toFloat() + 0.65f,
        isFront = true,
        speedIntensity = speedIntensity,
        color = furShadowColor
    )

    // --- 3. Torso (smooth capsule between hip and shoulder) ---
    val torsoLength = (shoulder - hip).getDistance()
    val torsoCenter = Offset((hip.x + shoulder.x) * 0.5f, (hip.y + shoulder.y) * 0.5f)
    val torsoAngleRad = kotlin.math.atan2(shoulder.y - hip.y, shoulder.x - hip.x)
    val torsoAngleDeg = torsoAngleRad * (180f / PI.toFloat())
    val torsoThickness = lerpFloat(16.5f * u, 13.5f * u, runBlend)

    rotate(degrees = torsoAngleDeg, pivot = torsoCenter) {
        drawRoundRect(
            color = furColor,
            topLeft = Offset(
                torsoCenter.x - torsoLength * 0.55f - 4f * u,
                torsoCenter.y - torsoThickness * 0.5f
            ),
            size = Size(torsoLength + 10f * u, torsoThickness),
            cornerRadius = CornerRadius(torsoThickness * 0.5f, torsoThickness * 0.5f)
        )
        // Subtle chest/belly highlight
        drawRoundRect(
            color = chestAccentColor.copy(alpha = 0.55f),
            topLeft = Offset(
                torsoCenter.x - torsoLength * 0.15f,
                torsoCenter.y + torsoThickness * 0.12f
            ),
            size = Size(torsoLength * 0.58f, torsoThickness * 0.34f),
            cornerRadius = CornerRadius(torsoThickness * 0.25f, torsoThickness * 0.25f)
        )
    }

    // --- 4. Sitting Haunch Curve (visible while seated, fades smoothly during run) ---
    if (runBlend < 0.92f) {
        val haunchAlpha = (1f - runBlend).coerceIn(0f, 1f)
        drawOval(
            color = furColor.copy(alpha = haunchAlpha),
            topLeft = Offset(hip.x - 8.5f * u, hip.y - 6f * u),
            size = Size(16f * u, 16f * u)
        )
    }

    // --- 5. Collar + DataMint Coin Tag ---
    val collarCenter = shoulder + Offset(3.5f * u, -4.5f * u)
    val tagBounce = if (runBlend > 0.1f) sin(runPhase * 1f + 0.5f) * 1.4f * u * runBlend else 0f
    drawLine(
        color = collarColor,
        start = collarCenter + Offset(-4.5f * u, -1.5f * u),
        end = collarCenter + Offset(4.5f * u, 2.0f * u),
        strokeWidth = 2.6f * u,
        cap = StrokeCap.Round
    )
    drawCircle(
        color = coinTagColor,
        radius = 2.4f * u,
        center = collarCenter + Offset(4.2f * u, 4.0f * u + tagBounce)
    )

    // --- 6. Near-side legs (foreground) ---
    drawCatLeg(
        hipOrShoulder = hip,
        groundY = groundY,
        u = u,
        runBlend = runBlend,
        stridePhase = runPhase,
        isFront = false,
        speedIntensity = speedIntensity,
        color = furColor
    )
    drawCatLeg(
        hipOrShoulder = shoulder,
        groundY = groundY,
        u = u,
        runBlend = runBlend,
        stridePhase = runPhase + PI.toFloat(),
        isFront = true,
        speedIntensity = speedIntensity,
        color = furColor
    )

    // --- 7. Head, Ears, Face & Whiskers ---
    val sitHeadCenter = shoulder + Offset(4.0f * u, -11.5f * u - idleBreath * 0.5f)
    val runHeadCenter = shoulder + Offset(9.5f * u, -7.2f * u + cos(runPhase) * 1.1f * u)
    val headCenter = lerpOffset(sitHeadCenter, runHeadCenter, runBlend)
    val headTilt = lerpFloat(
        sin(idlePhase * 1.2f) * 2.5f,
        5f + speedIntensity * 5f,
        runBlend
    )

    rotate(degrees = headTilt, pivot = headCenter) {
        val headRadius = 9.2f * u
        // Back ear
        drawCatEar(
            base = headCenter + Offset(-3.2f * u, -5.8f * u),
            tipOffset = Offset(lerpFloat(-2.5f * u, -5.0f * u, runBlend * speedIntensity), -8.8f * u),
            width = 5.4f * u,
            furColor = furShadowColor,
            innerColor = innerEarColor
        )
        // Head circle
        drawCircle(
            color = furColor,
            radius = headRadius,
            center = headCenter
        )
        // Front ear
        drawCatEar(
            base = headCenter + Offset(2.8f * u, -5.5f * u),
            tipOffset = Offset(lerpFloat(1.2f * u, -1.8f * u, runBlend * speedIntensity), -9.0f * u),
            width = 5.6f * u,
            furColor = furColor,
            innerColor = innerEarColor
        )

        // Blink timer when sitting (~every 3.2s)
        val blinkCycle = idlePhase % 3.2f
        val isBlinking = runBlend < 0.3f && blinkCycle > 3.04f

        val eyeCenter = headCenter + Offset(4.4f * u, -0.8f * u)
        if (isBlinking) {
            drawLine(
                color = pupilColor,
                start = eyeCenter + Offset(-2.0f * u, 0f),
                end = eyeCenter + Offset(2.0f * u, 0f),
                strokeWidth = 1.6f * u,
                cap = StrokeCap.Round
            )
        } else {
            // Expressive almond/bright eye
            drawOval(
                color = eyeColor,
                topLeft = Offset(eyeCenter.x - 2.3f * u, eyeCenter.y - 2.0f * u),
                size = Size(4.6f * u, 4.0f * u)
            )
            drawCircle(
                color = pupilColor,
                radius = 1.1f * u,
                center = eyeCenter + Offset(0.5f * u, 0f)
            )
            // Eye catchlight
            drawCircle(
                color = chestAccentColor,
                radius = 0.55f * u,
                center = eyeCenter + Offset(-0.6f * u, -0.7f * u)
            )
        }

        // Cute nose
        val noseCenter = headCenter + Offset(8.5f * u, 1.4f * u)
        drawCircle(
            color = innerEarColor,
            radius = 1.25f * u,
            center = noseCenter
        )

        // Whiskers (sweep backward faster during sprint)
        val whiskerSweep = runBlend * (1.8f + 2.0f * speedIntensity) * u
        val whiskerColor = chestAccentColor.copy(alpha = 0.85f)
        drawLine(
            color = whiskerColor,
            start = headCenter + Offset(6.5f * u, 2.2f * u),
            end = headCenter + Offset(12.0f * u - whiskerSweep, 1.0f * u),
            strokeWidth = 1.0f * u,
            cap = StrokeCap.Round
        )
        drawLine(
            color = whiskerColor,
            start = headCenter + Offset(6.2f * u, 3.3f * u),
            end = headCenter + Offset(11.6f * u - whiskerSweep, 4.0f * u),
            strokeWidth = 1.0f * u,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawCatEar(
    base: Offset,
    tipOffset: Offset,
    width: Float,
    furColor: Color,
    innerColor: Color
) {
    val left = base + Offset(-width * 0.5f, 1.5f)
    val right = base + Offset(width * 0.5f, 1.5f)
    val tip = base + tipOffset
    val outerPath = Path().apply {
        moveTo(left.x, left.y)
        lineTo(tip.x, tip.y)
        lineTo(right.x, right.y)
        close()
    }
    drawPath(outerPath, color = furColor)

    val innerPath = Path().apply {
        val iLeft = lerpOffset(base, left, 0.58f)
        val iRight = lerpOffset(base, right, 0.58f)
        val iTip = lerpOffset(base, tip, 0.68f)
        moveTo(iLeft.x, iLeft.y)
        lineTo(iTip.x, iTip.y)
        lineTo(iRight.x, iRight.y)
        close()
    }
    drawPath(innerPath, color = innerColor.copy(alpha = 0.85f))
}

private fun DrawScope.drawCatLeg(
    hipOrShoulder: Offset,
    groundY: Float,
    u: Float,
    runBlend: Float,
    stridePhase: Float,
    isFront: Boolean,
    speedIntensity: Float,
    color: Color
) {
    // Sitting pose coordinates
    val sitKnee = if (isFront) {
        Offset(hipOrShoulder.x + 2.5f * u, (hipOrShoulder.y + groundY) * 0.54f)
    } else {
        Offset(hipOrShoulder.x + 8.5f * u, groundY - 4.5f * u)
    }
    val sitPaw = if (isFront) {
        Offset(hipOrShoulder.x + 4.0f * u, groundY - 1.8f * u)
    } else {
        Offset(hipOrShoulder.x + 6.5f * u, groundY - 1.8f * u)
    }

    // Running gallop stride coordinates
    val swing = sin(stridePhase)
    val lift = max(0f, cos(stridePhase))
    val reachAmp = (9.5f + 5.0f * speedIntensity) * u
    val liftAmp = (6.0f + 3.0f * speedIntensity) * u

    val runPawX = hipOrShoulder.x + (if (isFront) 3.0f * u else -2.0f * u) + swing * reachAmp
    val runPawY = (groundY - 1.8f * u - lift * liftAmp).coerceAtMost(groundY - 1.8f * u)
    val runPaw = Offset(runPawX, runPawY)

    val kneeBend = if (isFront) {
        Offset(
            (hipOrShoulder.x + runPaw.x) * 0.5f + 2.4f * u,
            (hipOrShoulder.y + runPaw.y) * 0.5f - lift * 1.5f * u
        )
    } else {
        Offset(
            (hipOrShoulder.x + runPaw.x) * 0.5f - 2.6f * u,
            (hipOrShoulder.y + runPaw.y) * 0.5f - lift * 1.8f * u
        )
    }

    val knee = lerpOffset(sitKnee, kneeBend, runBlend)
    val paw = lerpOffset(sitPaw, runPaw, runBlend)

    val legPath = Path().apply {
        moveTo(hipOrShoulder.x, hipOrShoulder.y)
        lineTo(knee.x, knee.y)
        lineTo(paw.x, paw.y)
    }
    drawPath(
        path = legPath,
        color = color,
        style = Stroke(width = 3.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    // Little paw pad
    drawOval(
        color = color,
        topLeft = Offset(paw.x - 2.2f * u, paw.y - 1.5f * u),
        size = Size(5.2f * u, 3.0f * u)
    )
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}

private fun lerpOffset(start: Offset, stop: Offset, fraction: Float): Offset {
    val t = fraction.coerceIn(0f, 1f)
    return Offset(
        x = start.x + (stop.x - start.x) * t,
        y = start.y + (stop.y - start.y) * t
    )
}

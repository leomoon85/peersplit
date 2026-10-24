package com.peersplit.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val ChalkEmerald = Color(0xFF34D399)
private val ChalkAmber = Color(0xFFFBBF24)
private val ChalkSky = Color(0xFF38BDF8)
private val ChalkWhite = Color(0xFFF8FAFC)
private val OverlayBackdrop = Color(0xD9070B14)

/**
 * Full-screen semi-transparent coach-mark overlay with hand-drawn rings, curved arrows,
 * comic/sketch-style callouts, a clear "What is PeerSplit?" intro, and a local-first privacy explanation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeerSplitGuideDialog(
    showNameField: Boolean = false,
    displayName: String = "",
    onNameChange: (String) -> Unit = {},
    onDone: () -> Unit,
    onSkip: (() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = { (onSkip ?: onDone)() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(OverlayBackdrop)
                .systemBarsPadding()
                .imePadding()
        ) {
            // 1. Hand-drawn glowing rings & curved arrows pointing to live Home Screen elements
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f), 0f)
                val ringStroke = Stroke(
                    width = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = dashEffect
                )
                val arrowStroke = Stroke(
                    width = 3.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )

                // Top-Left Ring around "PeerSplit" title (Secret Vault long-press target)
                val titleCenter = Offset(x = 92.dp.toPx(), y = 30.dp.toPx())
                drawOval(
                    color = ChalkSky.copy(alpha = 0.85f),
                    topLeft = Offset(titleCenter.x - 76.dp.toPx(), titleCenter.y - 22.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(152.dp.toPx(), 44.dp.toPx()),
                    style = ringStroke
                )
                // Curved arrow from Top-Left note up to title ring
                val tlArrow = Path().apply {
                    moveTo(95.dp.toPx(), 92.dp.toPx())
                    quadraticTo(62.dp.toPx(), 72.dp.toPx(), 78.dp.toPx(), 54.dp.toPx())
                }
                drawPath(path = tlArrow, color = ChalkSky, style = arrowStroke)
                // Arrowhead
                val tlHead = Path().apply {
                    moveTo(68.dp.toPx(), 60.dp.toPx())
                    lineTo(78.dp.toPx(), 53.dp.toPx())
                    lineTo(84.dp.toPx(), 64.dp.toPx())
                }
                drawPath(path = tlHead, color = ChalkSky, style = arrowStroke)

                // Top-Right Ring around Profile & Sync actions
                val profileCenter = Offset(x = w - 42.dp.toPx(), y = 30.dp.toPx())
                drawCircle(
                    color = ChalkAmber.copy(alpha = 0.9f),
                    radius = 28.dp.toPx(),
                    center = profileCenter,
                    style = ringStroke
                )
                // Curved arrow from Top-Right note up to Profile ring
                val trArrow = Path().apply {
                    moveTo(w - 110.dp.toPx(), 96.dp.toPx())
                    quadraticTo(w - 60.dp.toPx(), 84.dp.toPx(), w - 48.dp.toPx(), 60.dp.toPx())
                }
                drawPath(path = trArrow, color = ChalkAmber, style = arrowStroke)
                val trHead = Path().apply {
                    moveTo(w - 58.dp.toPx(), 67.dp.toPx())
                    lineTo(w - 48.dp.toPx(), 59.dp.toPx())
                    lineTo(w - 42.dp.toPx(), 70.dp.toPx())
                }
                drawPath(path = trHead, color = ChalkAmber, style = arrowStroke)

                // Bottom-Right Ring around "+ New Group" & "Join QR" FABs
                val fabCenter = Offset(x = w - 82.dp.toPx(), y = h - 68.dp.toPx())
                drawOval(
                    color = ChalkEmerald.copy(alpha = 0.92f),
                    topLeft = Offset(fabCenter.x - 74.dp.toPx(), fabCenter.y - 58.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(148.dp.toPx(), 116.dp.toPx()),
                    style = ringStroke
                )
                // Curved arrow from Bottom note down-right to FAB ring
                val brArrow = Path().apply {
                    moveTo(w - 195.dp.toPx(), h - 108.dp.toPx())
                    quadraticTo(w - 165.dp.toPx(), h - 75.dp.toPx(), w - 154.dp.toPx(), h - 72.dp.toPx())
                }
                drawPath(path = brArrow, color = ChalkEmerald, style = arrowStroke)
                val brHead = Path().apply {
                    moveTo(w - 166.dp.toPx(), h - 80.dp.toPx())
                    lineTo(w - 153.dp.toPx(), h - 72.dp.toPx())
                    lineTo(w - 164.dp.toPx(), h - 64.dp.toPx())
                }
                drawPath(path = brHead, color = ChalkEmerald, style = arrowStroke)
            }

            // 2. Top-Left Sketch Callout (Secret Vault)
            Text(
                text = "Hold title for PIN\nSecret Groups! 🤫",
                color = ChalkSky,
                fontFamily = FontFamily.Cursive,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 18.dp, top = 86.dp)
                    .rotate(-3f)
            )

            // 3. Top-Right Sketch Callout (Profile, UPI & Backup)
            Text(
                text = "Profile, UPI ID &\nBackup keys here! ↗",
                color = ChalkAmber,
                fontFamily = FontFamily.Cursive,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 22.dp, top = 88.dp)
                    .rotate(2.5f)
            )

            // 4. Center "What is PeerSplit?" + Local-First Privacy Guide Card
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = if (maxHeight >= 720.dp) 136.dp else 12.dp)
                    .heightIn(max = 520.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xF2111827),
                border = BorderStroke(1.5.dp, ChalkEmerald.copy(alpha = 0.55f)),
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = ChalkEmerald.copy(alpha = 0.18f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("💸", fontSize = 20.sp)
                            }
                        }
                        Column {
                            Text(
                                text = if (showNameField) "Welcome to PeerSplit!" else "How PeerSplit Works",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = ChalkWhite
                            )
                            Text(
                                text = "Split shared bills and track group balances",
                                style = MaterialTheme.typography.labelMedium,
                                color = ChalkEmerald,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Scrollable Content Area
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // What is this app for?
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(1.dp, ChalkSky.copy(alpha = 0.35f))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "🎯 What is PeerSplit for?",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = ChalkSky
                                )
                                Text(
                                    text = "Split shared bills with friends, flatmates, trips, or meals and see who owes whom — without ads, subscriptions, or sign-ups.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ChalkWhite.copy(alpha = 0.92f)
                                )
                            }
                        }

                        // Privacy-Centric Local Storage Highlight
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = ChalkEmerald.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, ChalkEmerald.copy(alpha = 0.45f))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "🔒 Stored on your phone, shared with your group",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = ChalkEmerald
                                )
                                Text(
                                    text = "Peerloom Labs runs no central database. Records are stored on your phone. Group members can read shared records, and public relays can retain encrypted updates and see connection metadata. Receipt recognition uses ML Kit, which collects technical diagnostics. Back up your identity in Profile (lost keys or passwords cannot be recovered by support).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ChalkWhite.copy(alpha = 0.92f)
                                )
                            }
                        }

                        // 3 Quick Steps
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "🚀 3 Easy Steps:",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = ChalkAmber
                            )
                            Text(
                                text = "1. Create a Group: Pick a template & currency (₹ INR, $ USD, € EUR, £ GBP, AED) before creating.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ChalkWhite
                            )
                            Text(
                                text = "2. Add Bills & Invite: Log who paid and share the group QR/link so friends can sync.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ChalkWhite
                            )
                            Text(
                                text = "3. Settle Up: Open a supported installed app with Pay externally and complete payment there, then record it here. INR groups also offer UPI links. Verify payment status in your payment app.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ChalkWhite
                            )
                        }

                        if (showNameField) {
                            OutlinedTextField(
                                value = displayName,
                                onValueChange = onNameChange,
                                label = { Text("Your Display Name (optional)", color = ChalkWhite.copy(alpha = 0.8f)) },
                                supportingText = {
                                    Text(
                                        "So friends recognize you. Phone & UPI ID can be added later in Profile.",
                                        color = ChalkWhite.copy(alpha = 0.65f)
                                    )
                                },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = ChalkWhite,
                                    unfocusedTextColor = ChalkWhite,
                                    focusedBorderColor = ChalkEmerald,
                                    unfocusedBorderColor = ChalkWhite.copy(alpha = 0.35f),
                                    cursorColor = ChalkEmerald
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Text(
                            text = "Note: A queued sync is not confirmation that every friend received it. Interface is currently in English.",
                            style = MaterialTheme.typography.labelSmall,
                            fontStyle = FontStyle.Italic,
                            color = ChalkWhite.copy(alpha = 0.6f)
                        )
                    }

                    // Keyboard-safe Bottom Buttons
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                    ) {
                        if (onSkip != null) {
                            TextButton(onClick = onSkip) {
                                Text("Later", color = ChalkWhite.copy(alpha = 0.75f))
                            }
                        }
                        Button(
                            onClick = onDone,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ChalkEmerald,
                                contentColor = Color(0xFF06281E)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = if (showNameField) "Get started 🚀" else "Got it! 👍",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // 5. Bottom Sketch Callout pointing to FABs
            Text(
                text = "Start here! Create a group\nor scan a friend's QR ↘",
                color = ChalkEmerald,
                fontFamily = FontFamily.Cursive,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                lineHeight = 19.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 156.dp, bottom = 74.dp)
                    .rotate(-2f)
            )
        }
    }
}

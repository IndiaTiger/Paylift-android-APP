package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.UserProfile
import com.example.data.WalletTransaction
import com.example.ui.theme.DeepRoyalBlue
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.PureWhite
import com.example.ui.theme.RoseEmergency
import com.example.ui.theme.SoftIceBlue
import com.example.ui.theme.VividBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WalletEscrowScreen(
    userProfile: UserProfile,
    transactions: List<WalletTransaction>,
    hasActiveRide: Boolean,
    activeLockedEscrow: Double,
    onTopUp: (Double, String) -> Unit,
    onToggleAutoTopup: (Boolean, Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedGateway by remember { mutableStateOf("Razorpay") } // "Razorpay", "Cashfree", "Stripe", "Mock Sandbox"
    var selectedFilterTab by remember { mutableStateOf("ALL") } // "ALL", "TOPUP", "RIDE_DEBIT", "REFUND", "AUTO_TOPUP"
    var customAmountText by remember { mutableStateOf("") }
    var autoTopupThreshold by remember(userProfile) { mutableDoubleStateOf(userProfile.autoTopupThreshold) }
    var autoTopupAmount by remember(userProfile) { mutableDoubleStateOf(userProfile.autoTopupAmount) }

    val quickAmounts = listOf(100.0, 250.0, 500.0, 1000.0)
    val gateways = listOf("Razorpay", "Cashfree", "Stripe", "Mock Sandbox")
    val filterTabs = listOf(
        "ALL" to "All",
        "TOPUP" to "Top-ups",
        "RIDE_DEBIT" to "Rides",
        "REFUND" to "Refunds",
        "AUTO_TOPUP" to "Auto-Refill"
    )

    val filteredTransactions = transactions.filter {
        if (selectedFilterTab == "ALL") true else it.category == selectedFilterTab
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Hero Wallet Escrow Balance Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wallet_balance_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DeepRoyalBlue),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(VividBlue),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountBalanceWallet,
                                    contentDescription = null,
                                    tint = PureWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "PAYLIFT ESCROW WALLET",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SoftIceBlue.copy(alpha = 0.85f),
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = "Dehradun Verified Account",
                                    fontSize = 10.sp,
                                    color = SoftIceBlue.copy(alpha = 0.65f)
                                )
                            }
                        }

                        // HMAC-SHA256 Badge
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = VividBlue.copy(alpha = 0.35f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = SoftIceBlue,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "HMAC-256",
                                    color = PureWhite,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "₹${"%.2f".format(userProfile.walletBalance)}",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black,
                        color = PureWhite
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Secondary Metrics: Locked Escrow & Lifetime Savings
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "LOCKED IN ESCROW",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = SoftIceBlue.copy(alpha = 0.7f)
                            )
                            Text(
                                text = if (hasActiveRide) "₹${"%.0f".format(activeLockedEscrow)}" else "₹0.00",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (hasActiveRide) Color(0xFFF59E0B) else SoftIceBlue
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "LIFETIME RIDE SAVINGS",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = SoftIceBlue.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "₹${"%.0f".format(userProfile.lifetimeSavings)}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldSuccess
                            )
                        }
                    }
                }
            }
        }

        // Quick Top-Up Section
        item {
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Instant Escrow Top-Up",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Multi-Gateway Selector
            Text(
                text = "Payment Gateway Provider:",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(gateways) { gw ->
                    val isSelected = gw == selectedGateway
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) VividBlue else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { selectedGateway = gw }
                    ) {
                        Text(
                            text = gw,
                            color = if (isSelected) PureWhite else MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // One-Tap Quick Top-Up Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickAmounts.forEach { amount ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onTopUp(amount, selectedGateway) },
                        shape = RoundedCornerShape(12.dp),
                        color = SoftIceBlue.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, VividBlue.copy(alpha = 0.3f))
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "+₹${amount.toInt()}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepRoyalBlue
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Custom Amount Input
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = customAmountText,
                    onValueChange = { customAmountText = it.filter { ch -> ch.isDigit() } },
                    placeholder = { Text("Enter custom amount (₹)", fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VividBlue,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        val parsed = customAmountText.toDoubleOrNull()
                        if (parsed != null && parsed > 0) {
                            onTopUp(parsed, selectedGateway)
                            customAmountText = ""
                        }
                    },
                    modifier = Modifier.height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VividBlue)
                ) {
                    Text("Add Funds", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Auto-Topup Engine Card
        item {
            Spacer(modifier = Modifier.height(20.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Auto-Topup Engine",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(EmeraldSuccess)
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "SMART REFILL",
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Black,
                                        color = PureWhite
                                    )
                                }
                            }
                            Text(
                                text = "Prevents ride interruption if balance drops low",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = userProfile.autoTopupEnabled,
                            onCheckedChange = { enabled ->
                                onToggleAutoTopup(enabled, autoTopupThreshold, autoTopupAmount)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PureWhite,
                                checkedTrackColor = VividBlue
                            )
                        )
                    }

                    AnimatedVisibility(visible = userProfile.autoTopupEnabled) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            Spacer(modifier = Modifier.height(10.dp))

                            // Threshold Stepper
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Trigger Threshold:",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Below ₹${autoTopupThreshold.toInt()}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Slider(
                                value = autoTopupThreshold.toFloat(),
                                onValueChange = {
                                    autoTopupThreshold = it.toDouble()
                                    onToggleAutoTopup(true, autoTopupThreshold, autoTopupAmount)
                                },
                                valueRange = 50f..500f,
                                steps = 8,
                                colors = SliderDefaults.colors(
                                    thumbColor = VividBlue,
                                    activeTrackColor = VividBlue
                                )
                            )

                            // Refill Amount Stepper
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Automatic Refill Amount:",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "₹${autoTopupAmount.toInt()}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Slider(
                                value = autoTopupAmount.toFloat(),
                                onValueChange = {
                                    autoTopupAmount = it.toDouble()
                                    onToggleAutoTopup(true, autoTopupThreshold, autoTopupAmount)
                                },
                                valueRange = 100f..2000f,
                                steps = 18,
                                colors = SliderDefaults.colors(
                                    thumbColor = VividBlue,
                                    activeTrackColor = VividBlue
                                )
                            )
                        }
                    }
                }
            }
        }

        // Double-Entry Ledger Section
        item {
            Spacer(modifier = Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Transaction Ledger",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Export Statement Action Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val sb = StringBuilder()
                            sb.append("PAYLIFT DEHRADUN WALLET STATEMENT\n")
                            sb.append("User: ${userProfile.name} (${userProfile.phone})\n")
                            sb.append("Current Balance: ₹${userProfile.walletBalance}\n")
                            sb.append("------------------------------------------\n")
                            transactions.forEach { t ->
                                val dateStr = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(t.timestamp))
                                sb.append("${t.type.uppercase()} | ₹${t.amount} | ${t.category} | ${t.description} | Date: $dateStr\n")
                            }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("PayLift Statement", sb.toString()))
                            Toast.makeText(context, "Statement copied to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Export",
                        tint = VividBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Export Statement",
                        color = VividBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Categorized Filter Tabs
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filterTabs) { (key, label) ->
                    val isSelected = selectedFilterTab == key
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) DeepRoyalBlue else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.clickable { selectedFilterTab = key }
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) PureWhite else MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        // Ledger Items
        items(filteredTransactions) { tx ->
            val isCredit = tx.type == "credit"
            val dateFormatter = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
            val formattedDate = dateFormatter.format(Date(tx.timestamp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isCredit) EmeraldSuccess.copy(alpha = 0.15f) else RoseEmergency.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isCredit) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                contentDescription = null,
                                tint = if (isCredit) EmeraldSuccess else RoseEmergency,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            Text(
                                text = tx.description,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "$formattedDate • ${tx.gateway} (${tx.category})",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${if (isCredit) "+" else "-"}₹${"%.1f".format(tx.amount)}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isCredit) EmeraldSuccess else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Bal: ₹${"%.0f".format(tx.balanceAfter)}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

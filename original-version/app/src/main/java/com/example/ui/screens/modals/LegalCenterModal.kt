package com.example.ui.screens.modals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.PureWhite
import com.example.ui.theme.VividBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalCenterModal(
    documentTitle: String,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val content = when (documentTitle) {
        "Privacy Policy" -> """
            1. Information Collection & Telemetry
            PayLift collects real-time GPS coordinates, vehicle telemetry (speed, heading, route polylines), and account credentials strictly during active rides in the Dehradun district.

            2. Cryptographic Security & Masking
            All payment callbacks and digital escrow locks are signed using HMAC-SHA256 signatures with server-side nonce validation. Phone calls between pilots and riders are routed through a masked virtual proxy to safeguard personal mobile numbers.

            3. Emergency Broadcast Protocol
            In the event of an SOS activation, your exact GPS coordinates and device telemetry are transmitted directly to the Uttarakhand State Emergency Helpline (112) and your registered emergency contact.

            4. Data Retention
            Transaction receipts and trip logs are encrypted and retained locally on your device and within certified digital escrow records for auditing and tax compliance.
        """.trimIndent()
        "Cancellation & Refund Policy" -> """
            1. Instant Digital Escrow Refund Guarantee
            When you book a ride with PayLift, your fare is placed in a secure digital escrow lock. If you cancel your ride prior to boarding or if no pilot is assigned, 100% of your escrowed balance is refunded instantaneously to your wallet.

            2. Pilot Arrival Buffer
            Riders are afforded a 5-minute complimentary waiting window upon pilot arrival at the designated pickup hub. 

            3. Zero Hidden Deductions
            PayLift does not levy arbitrary cancellation penalties for rides cancelled due to pilot delays or vehicle mismatch.
        """.trimIndent()
        else -> """
            1. PayLift Dehradun Transport Terms
            PayLift operates as an automated digital escrow dispatch platform connecting licensed local transport pilots with riders across Dehradun, Rishikesh, and the Doon Valley.

            2. Fair Pilot Earnings Covenant (85/15)
            PayLift commits to passing 85% of all gross ride fares directly to the certified pilot. The 15% platform fee sustains cryptographic escrow verification, continuous mapping, and 24/7 SOS safety infrastructure.

            3. Safety & Code of Conduct
            All riders and pilots agree to adhere to Uttarakhand motor vehicle regulations. Aggressive behavior, route tampering, or harassment results in immediate account suspension.
        """.trimIndent()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = documentTitle,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )

            Text(
                text = content,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VividBlue)
            ) {
                Text(
                    text = "Close",
                    color = PureWhite,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

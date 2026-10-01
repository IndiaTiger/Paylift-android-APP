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

    // DRAFT texts pending legal review. They describe only what the software actually does.
    val content = when (documentTitle) {
        "Privacy Policy" -> """
            DRAFT - pending legal review. Describes what the app currently does.

            1. Information We Process
            Your phone number (for sign-in), profile and emergency-contact details you enter, pickup and destination points, your device location when you choose "Current location", ride records and wallet transactions.

            2. How It Is Protected
            Data is sent to PayLift servers over HTTPS. Your session is stored on the device encrypted with an Android Keystore key and is excluded from device backups. Payments are verified by our server with the payment provider; the app itself never marks a payment as successful.

            3. Emergency (SOS)
            Pressing SOS opens your phone dialer with 112 when auto-dial is on. PayLift does not currently transmit your location to police or to your emergency contact; the app tells you when an alert could not be sent.

            4. Retention
            Wallet ledger entries and ride records are kept on our servers for accounting and tax purposes, including after account deletion (detached from your personal details).
        """.trimIndent()
        "Cancellation & Refund Policy" -> """
            DRAFT - pending legal review.

            1. Fare Hold
            When you confirm a ride, the quoted fare is held in your PayLift wallet. It is only charged when the trip is completed.

            2. Cancellation
            If you cancel before your pilot arrives, or if no pilot is found, the hold is released in full back to your available balance. A cancellation fee, if one is configured, can apply only after the pilot has arrived at pickup; it is shown in your wallet ledger.

            3. Refunds
            Refunds of completed rides are issued by PayLift support against the original charge and never exceed it.
        """.trimIndent()
        else -> """
            DRAFT - pending legal review.

            1. The Service
            PayLift connects riders with independent pilots. Fares are quoted by our server before you confirm and the quoted amount is exactly what is held and charged.

            2. Pilot Earnings (85/15)
            Of each fare excluding GST, 85% is the pilot's earning and 15% is the platform commission, as itemised in the fare breakdown.

            3. Safety & Code of Conduct
            All riders and pilots agree to adhere to Uttarakhand motor vehicle regulations. Aggressive behavior, route tampering, or harassment results in account suspension.
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

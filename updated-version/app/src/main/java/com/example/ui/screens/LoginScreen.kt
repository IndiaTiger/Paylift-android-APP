package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.AppError
import com.example.ui.AuthUiState
import com.example.ui.OpState
import com.example.ui.theme.DeepRoyalBlue
import com.example.ui.theme.PureWhite
import com.example.ui.theme.RoseEmergency
import com.example.ui.theme.SoftIceBlue
import com.example.ui.theme.VividBlue

/** Phone + OTP sign-in. Uses the app's existing colour, shape and type tokens. */
@Composable
fun LoginScreen(
    authState: AuthUiState,
    opState: OpState,
    onRequestOtp: (String) -> Unit,
    onVerifyOtp: (String) -> Unit,
    onChangeNumber: () -> Unit,
    modifier: Modifier = Modifier,
    /** Demo build only: a one-line, truthful notice. Null in debug/release. */
    demoNotice: String? = null,
) {
    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    val otpSent = authState as? AuthUiState.OtpSent
    val loading = opState == OpState.Loading

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DeepRoyalBlue),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(VividBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = PureWhite, modifier = Modifier.size(22.dp))
                }
                Text("Sign in to PayLift", color = PureWhite, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text(
                    text = if (otpSent == null) "Enter your mobile number. We'll send a one-time code."
                    else "Enter the 6-digit code sent to ${otpSent.challenge.phone}",
                    color = SoftIceBlue.copy(alpha = 0.8f),
                    fontSize = 13.sp
                )

                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VividBlue,
                    unfocusedBorderColor = SoftIceBlue.copy(alpha = 0.4f),
                    focusedTextColor = PureWhite,
                    unfocusedTextColor = PureWhite,
                    cursorColor = PureWhite,
                )
                if (otpSent == null) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(16) },
                        placeholder = { Text("10-digit mobile number", color = SoftIceBlue.copy(alpha = 0.5f)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth().testTag("phone_input")
                    )
                } else {
                    OutlinedTextField(
                        value = otp,
                        onValueChange = { otp = it.filter(Char::isDigit).take(6) },
                        placeholder = { Text("6-digit code", color = SoftIceBlue.copy(alpha = 0.5f)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth().testTag("otp_input")
                    )
                    // Only a development backend (AUTH_PROVIDER=dev) returns the code; production never does.
                    otpSent.challenge.devOtp?.let {
                        Text("Test backend code: $it", color = SoftIceBlue.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.testTag("dev_otp"))
                    }
                }

                if (opState is OpState.Failed) {
                    Text(
                        text = errorText(opState.error),
                        color = RoseEmergency,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("auth_error")
                    )
                }

                Button(
                    onClick = { if (otpSent == null) onRequestOtp(phone) else onVerifyOtp(otp) },
                    enabled = !loading && (if (otpSent == null) phone.isNotBlank() else otp.length == 6),
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("auth_submit"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VividBlue)
                ) {
                    if (loading) CircularProgressIndicator(color = PureWhite, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    else Text(if (otpSent == null) "Send code" else "Verify & continue", fontWeight = FontWeight.Bold)
                }
                if (otpSent != null) {
                    TextButton(onClick = { otp = ""; onChangeNumber() }) {
                        Text("Change number", color = SoftIceBlue)
                    }
                }
                if (demoNotice != null) {
                    Text(demoNotice, color = SoftIceBlue.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.testTag("demo_notice"))
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

private fun errorText(e: AppError): String = when (e) {
    is AppError.NotConfigured -> "Sign-in isn't available: ${e.message}"
    else -> e.message
}

package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.AppNavTab
import com.example.ui.theme.DeepRoyalBlue
import com.example.ui.theme.PureWhite
import com.example.ui.theme.RoseEmergency
import com.example.ui.theme.SoftIceBlue
import com.example.ui.theme.VividBlue

@Composable
fun TranslucentNavBar(
    currentTab: AppNavTab,
    onSelectTab: (AppNavTab) -> Unit,
    hasActiveRide: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.2f

    // Translucent background color: 85% opacity with subtle frosted tint
    val surfaceColor = if (isDark) {
        Color(0xEB0A1326)
    } else {
        Color(0xF5FFFFFF)
    }

    val borderColor = if (isDark) Color(0x333878E0) else Color(0x22073B8F)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = surfaceColor,
        tonalElevation = 8.dp,
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // Top border line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(borderColor)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavItem(
                    label = "Ride",
                    selected = currentTab == AppNavTab.RIDE,
                    selectedIcon = Icons.Default.DirectionsCar,
                    unselectedIcon = Icons.Outlined.DirectionsCar,
                    badge = if (hasActiveRide) "LIVE" else null,
                    badgeColor = RoseEmergency,
                    onClick = { onSelectTab(AppNavTab.RIDE) }
                )

                NavItem(
                    label = "Wallet",
                    selected = currentTab == AppNavTab.WALLET,
                    selectedIcon = Icons.Default.AccountBalanceWallet,
                    unselectedIcon = Icons.Outlined.AccountBalanceWallet,
                    onClick = { onSelectTab(AppNavTab.WALLET) }
                )

                NavItem(
                    label = "Activity",
                    selected = currentTab == AppNavTab.ACTIVITY,
                    selectedIcon = Icons.Default.History,
                    unselectedIcon = Icons.Outlined.History,
                    onClick = { onSelectTab(AppNavTab.ACTIVITY) }
                )

                NavItem(
                    label = "Profile",
                    selected = currentTab == AppNavTab.PROFILE,
                    selectedIcon = Icons.Default.Person,
                    unselectedIcon = Icons.Outlined.Person,
                    onClick = { onSelectTab(AppNavTab.PROFILE) }
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    badge: String? = null,
    badgeColor: Color = RoseEmergency,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(targetValue = if (selected) 1.05f else 1.0f, label = "tab_scale")
    val contentColor by animateColorAsState(
        targetValue = if (selected) VividBlue else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "tab_color"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, radius = 28.dp),
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) SoftIceBlue.copy(alpha = 0.35f) else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (selected) selectedIcon else unselectedIcon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            if (badge != null) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(badgeColor)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = badge,
                        color = PureWhite,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor
        )
    }
}

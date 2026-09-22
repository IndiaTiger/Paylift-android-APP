package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.DehradunLocations
import com.example.domain.FareBreakdown
import com.example.domain.FareEngine
import com.example.domain.FleetCatalog
import com.example.domain.LocationPoint
import com.example.domain.VehicleOption
import com.example.domain.VehicleTypeGroup
import com.example.ui.components.InteractiveMapCanvas
import com.example.ui.theme.DeepRoyalBlue
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.PureWhite
import com.example.ui.theme.RoseEmergency
import com.example.ui.theme.SoftIceBlue
import com.example.ui.theme.VividBlue

@Composable
fun RideBookingScreen(
    pickup: LocationPoint,
    dropoff: LocationPoint,
    selectedVehicle: VehicleOption,
    fareBreakdown: FareBreakdown,
    distanceKm: Double,
    durationMins: Int,
    walletBalance: Double,
    onSelectPickup: (LocationPoint) -> Unit,
    onSelectDropoff: (LocationPoint) -> Unit,
    onSwapLocations: () -> Unit,
    onSelectVehicle: (VehicleOption) -> Unit,
    onOpenFareBreakdown: () -> Unit,
    onConfirmRide: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedGroup by remember { mutableStateOf(VehicleTypeGroup.FOUR_WHEELER) }
    var showPickupPicker by remember { mutableStateOf(false) }
    var showDropPicker by remember { mutableStateOf(false) }

    val allHubs = DehradunLocations.DEHRADUN_HUBS
    val filteredVehicles = FleetCatalog.VEHICLES.filter { it.group == selectedGroup }

    val routeWaypoints = remember(pickup, dropoff) {
        DehradunLocations.generateRoutePolyline(
            pickup.latitude, pickup.longitude,
            dropoff.latitude, dropoff.longitude,
            steps = 20
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Upper Map Section
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.05f)
        ) {
            InteractiveMapCanvas(
                pickup = pickup,
                dropoff = dropoff,
                routeWaypoints = routeWaypoints,
                progressFraction = 0f,
                isTwoWheeler = selectedVehicle.group == VehicleTypeGroup.TWO_WHEELER,
                speedKmph = 32,
                activeRideActive = false,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Lower Booking Sheet
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.35f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 16.dp
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Pickup & Dropoff Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // Pickup Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        showPickupPicker = !showPickupPicker
                                        showDropPicker = false
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(EmeraldSuccess)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "PICKUP LOCATION",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EmeraldSuccess
                                    )
                                    Text(
                                        text = pickup.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                IconButton(
                                    onClick = onSwapLocations,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SwapVert,
                                        contentDescription = "Swap Locations",
                                        tint = VividBlue,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // Pickup Quick Selector
                            AnimatedVisibility(visible = showPickupPicker) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "Select Dehradun Pickup:",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(allHubs) { hub ->
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = if (hub == pickup) DeepRoyalBlue else SoftIceBlue.copy(alpha = 0.5f),
                                                modifier = Modifier.clickable {
                                                    onSelectPickup(hub)
                                                    showPickupPicker = false
                                                }
                                            ) {
                                                Text(
                                                    text = hub.name.substringBefore("(").trim(),
                                                    color = if (hub == pickup) PureWhite else DeepRoyalBlue,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
                                    .width(2.dp)
                                    .height(14.dp)
                                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            )

                            // Dropoff Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        showDropPicker = !showDropPicker
                                        showPickupPicker = false
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(RoseEmergency)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "DESTINATION",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = RoseEmergency
                                    )
                                    Text(
                                        text = dropoff.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            // Dropoff Quick Selector
                            AnimatedVisibility(visible = showDropPicker) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "Select Dehradun Destination:",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(allHubs) { hub ->
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = if (hub == dropoff) DeepRoyalBlue else SoftIceBlue.copy(alpha = 0.5f),
                                                modifier = Modifier.clickable {
                                                    onSelectDropoff(hub)
                                                    showDropPicker = false
                                                }
                                            ) {
                                                Text(
                                                    text = hub.name.substringBefore("(").trim(),
                                                    color = if (hub == dropoff) PureWhite else DeepRoyalBlue,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Route Distance & Duration Chips
                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = SoftIceBlue.copy(alpha = 0.5f)
                            ) {
                                Text(
                                    text = "$distanceKm km road",
                                    color = DeepRoyalBlue,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = SoftIceBlue.copy(alpha = 0.5f)
                            ) {
                                Text(
                                    text = "~$durationMins mins ETA",
                                    color = DeepRoyalBlue,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        // View Transparency Breakdown Button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenFareBreakdown() }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Calculate,
                                contentDescription = null,
                                tint = VividBlue,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Fare & 85% Pilot Split",
                                color = VividBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Category Switcher: 2-Wheelers vs Cars & Cabs
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(4.dp)
                    ) {
                        // Four Wheelers
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selectedGroup == VehicleTypeGroup.FOUR_WHEELER) DeepRoyalBlue else Color.Transparent)
                                .clickable {
                                    selectedGroup = VehicleTypeGroup.FOUR_WHEELER
                                    onSelectVehicle(FleetCatalog.VEHICLES[3])
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.DirectionsCar,
                                    contentDescription = null,
                                    tint = if (selectedGroup == VehicleTypeGroup.FOUR_WHEELER) PureWhite else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Cars & Cabs",
                                    color = if (selectedGroup == VehicleTypeGroup.FOUR_WHEELER) PureWhite else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Two Wheelers
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selectedGroup == VehicleTypeGroup.TWO_WHEELER) DeepRoyalBlue else Color.Transparent)
                                .clickable {
                                    selectedGroup = VehicleTypeGroup.TWO_WHEELER
                                    onSelectVehicle(FleetCatalog.VEHICLES[0])
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.TwoWheeler,
                                    contentDescription = null,
                                    tint = if (selectedGroup == VehicleTypeGroup.TWO_WHEELER) PureWhite else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Moto & EV",
                                    color = if (selectedGroup == VehicleTypeGroup.TWO_WHEELER) PureWhite else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Vehicle List Options
                items(filteredVehicles) { vehicle ->
                    val isSelected = vehicle.id == selectedVehicle.id
                    val vehicleFare = FareEngine.calculateFare(vehicle, distanceKm, durationMins)

                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onSelectVehicle(vehicle) }
                            .testTag("vehicle_option_${vehicle.id}"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) SoftIceBlue.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            if (isSelected) 2.dp else 1.dp,
                            if (isSelected) VividBlue else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
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
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) VividBlue else DeepRoyalBlue),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (vehicle.group == VehicleTypeGroup.TWO_WHEELER) Icons.Default.TwoWheeler else Icons.Default.DirectionsCar,
                                        contentDescription = vehicle.name,
                                        tint = PureWhite,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = vehicle.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (vehicle.tag != null) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(DeepRoyalBlue)
                                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = vehicle.tag,
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = PureWhite
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = vehicle.modelsExample,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${vehicle.capacity} • ${vehicle.etaMins} mins away",
                                        fontSize = 10.sp,
                                        color = VividBlue,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "₹${"%.0f".format(vehicleFare.grossFinalFare)}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (isSelected) VividBlue else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "₹${"%.1f".format(vehicleFare.pilotTakeHomeEarnings)} to pilot",
                                    fontSize = 10.sp,
                                    color = EmeraldSuccess,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // Confirm Ride CTA Button
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onConfirmRide,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("confirm_paylift_ride_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VividBlue)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = PureWhite,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Confirm ${selectedVehicle.name}",
                                    color = PureWhite,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Text(
                                text = "₹${"%.0f".format(fareBreakdown.grossFinalFare)} (Escrow Lock)",
                                color = SoftIceBlue,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

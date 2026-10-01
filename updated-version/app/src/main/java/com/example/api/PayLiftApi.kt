package com.example.api

import com.example.api.dto.ActiveRideResponse
import com.example.api.dto.AddressDto
import com.example.api.dto.AddressListResponse
import com.example.api.dto.AvailabilityBody
import com.example.api.dto.CancelRideBody
import com.example.api.dto.CreateOrderBody
import com.example.api.dto.GeocodeBody
import com.example.api.dto.GeocodeResponse
import com.example.api.dto.LatLngDto
import com.example.api.dto.LedgerResponse
import com.example.api.dto.MessageDto
import com.example.api.dto.MessageListResponse
import com.example.api.dto.NearbyPilotsResponse
import com.example.api.dto.NewAddressBody
import com.example.api.dto.NewMessageBody
import com.example.api.dto.OkResponse
import com.example.api.dto.PaymentDto
import com.example.api.dto.PlaceDto
import com.example.api.dto.QuoteBody
import com.example.api.dto.QuoteDto
import com.example.api.dto.RatingBody
import com.example.api.dto.RefreshBody
import com.example.api.dto.RequestOtpBody
import com.example.api.dto.RequestOtpResponse
import com.example.api.dto.RequestRideBody
import com.example.api.dto.RideDto
import com.example.api.dto.RideListResponse
import com.example.api.dto.RouteBody
import com.example.api.dto.RouteDto
import com.example.api.dto.ServerConfigDto
import com.example.api.dto.SessionResponse
import com.example.api.dto.SosBody
import com.example.api.dto.TestCheckoutBody
import com.example.api.dto.TestCheckoutResponse
import com.example.api.dto.UserDto
import com.example.api.dto.UserPatchBody
import com.example.api.dto.VerifyOtpBody
import com.example.api.dto.VerifyPaymentBody
import com.example.api.dto.VerifyPaymentResponse
import com.example.api.dto.VerifyStartOtpBody
import com.example.api.dto.WalletDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Retrofit definition of the PayLift backend contract (docs/API.md). */
interface PayLiftApi {
    // Config
    @GET("config") suspend fun serverConfig(): ServerConfigDto

    // Auth
    @POST("auth/request-otp") suspend fun requestOtp(@Body body: RequestOtpBody): RequestOtpResponse
    @POST("auth/verify-otp") suspend fun verifyOtp(@Body body: VerifyOtpBody): SessionResponse
    @POST("auth/refresh") suspend fun refresh(@Body body: RefreshBody): SessionResponse
    @POST("auth/logout") suspend fun logout(@Body body: RefreshBody): OkResponse

    // Users
    @GET("users/me") suspend fun me(): UserDto
    @PATCH("users/me") suspend fun updateMe(@Body body: UserPatchBody): UserDto
    @DELETE("users/me") suspend fun deleteMe(): OkResponse
    @GET("users/me/addresses") suspend fun addresses(): AddressListResponse
    @POST("users/me/addresses") suspend fun addAddress(@Body body: NewAddressBody): AddressDto
    @DELETE("users/me/addresses/{id}") suspend fun deleteAddress(@Path("id") id: String): OkResponse

    // Wallet (read-only: the client has no endpoint that can change a balance)
    @GET("wallet") suspend fun wallet(): WalletDto
    @GET("wallet/transactions") suspend fun walletTransactions(@Query("limit") limit: Int = 50, @Query("before") before: Long? = null): LedgerResponse

    // Payments
    @POST("payments/orders") suspend fun createPaymentOrder(@Header("Idempotency-Key") idempotencyKey: String, @Body body: CreateOrderBody): PaymentDto
    @POST("payments/verify") suspend fun verifyPayment(@Body body: VerifyPaymentBody): VerifyPaymentResponse
    @GET("payments/{id}") suspend fun payment(@Path("id") paymentId: String): PaymentDto
    /** Test gateway only (PAYMENT_PROVIDER=test on a non-production backend). */
    @POST("payments/test/checkout") suspend fun testCheckout(@Body body: TestCheckoutBody): TestCheckoutResponse

    // Locations
    @POST("locations/geocode") suspend fun geocode(@Body body: GeocodeBody): GeocodeResponse
    @POST("locations/reverse-geocode") suspend fun reverseGeocode(@Body body: LatLngDto): PlaceDto
    @POST("routes") suspend fun route(@Body body: RouteBody): RouteDto

    // Rides
    @POST("rides/quote") suspend fun quote(@Body body: QuoteBody): QuoteDto
    @POST("rides") suspend fun requestRide(@Header("Idempotency-Key") idempotencyKey: String, @Body body: RequestRideBody): RideDto
    @GET("rides") suspend fun rides(@Query("limit") limit: Int = 50): RideListResponse
    @GET("rides/active") suspend fun activeRide(): ActiveRideResponse
    @GET("rides/{id}") suspend fun ride(@Path("id") rideId: String): RideDto
    @POST("rides/{id}/cancel") suspend fun cancelRide(@Path("id") rideId: String, @Body body: CancelRideBody): RideDto
    @POST("rides/{id}/rating") suspend fun rateRide(@Path("id") rideId: String, @Body body: RatingBody): RideDto
    @GET("rides/{id}/messages") suspend fun messages(@Path("id") rideId: String): MessageListResponse
    @POST("rides/{id}/messages") suspend fun sendMessage(@Path("id") rideId: String, @Body body: NewMessageBody): MessageDto
    @POST("rides/{id}/call") suspend fun requestMaskedCall(@Path("id") rideId: String): OkResponse

    // Pilot-side lifecycle (used by a pilot build of the app; guarded server-side by role)
    @POST("rides/{id}/accept") suspend fun acceptRide(@Path("id") rideId: String): RideDto
    @POST("rides/{id}/arriving") suspend fun markArriving(@Path("id") rideId: String): RideDto
    @POST("rides/{id}/arrived") suspend fun markArrived(@Path("id") rideId: String): RideDto
    @POST("rides/{id}/verify-otp") suspend fun verifyStartOtp(@Path("id") rideId: String, @Body body: VerifyStartOtpBody): RideDto
    @POST("rides/{id}/start") suspend fun startRide(@Path("id") rideId: String): RideDto
    @POST("rides/{id}/complete") suspend fun completeRide(@Path("id") rideId: String): RideDto

    // Pilots
    @GET("pilots/nearby") suspend fun nearbyPilots(@Query("lat") lat: Double, @Query("lng") lng: Double, @Query("vehicleId") vehicleId: String?): NearbyPilotsResponse
    @POST("pilots/me/location") suspend fun updatePilotLocation(@Body body: LatLngDto): OkResponse
    @POST("pilots/me/availability") suspend fun setPilotAvailability(@Body body: AvailabilityBody): OkResponse

    // Safety
    @POST("safety/sos") suspend fun sos(@Body body: SosBody): OkResponse

    companion object {
        /** Place DTO helper. */
        fun place(name: String, subtitle: String, lat: Double, lng: Double) = PlaceDto(name = name, subtitle = subtitle, lat = lat, lng = lng)
    }
}

package com.geeksville.mesh

import android.graphics.Color
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.geeksville.mesh.util.GPSFormat
import com.geeksville.mesh.util.bearing
import com.geeksville.mesh.util.latLongToMeter
import com.geeksville.mesh.util.anonymize
import kotlinx.parcelize.Parcelize

/**
 * Room [Embedded], [Entity] and [PrimaryKey] annotations and imports, as well as any protobuf
 * reference [MeshProtos], [TelemetryProtos], [ConfigProtos] can be removed when only using the API.
 * For details check the AIDL interface in [com.geeksville.mesh.IMeshService]
 */

//
// model objects that directly map to the corresponding protobufs
//

@Parcelize
data class MeshUser(
    val id: String,
    val longName: String,
    val shortName: String,
    val hwModel: MeshProtos.HardwareModel,
    val isLicensed: Boolean = false,
) : Parcelable {

    override fun toString(): String {
        return "MeshUser(id=${id.anonymize}, longName=${longName.anonymize}, shortName=${shortName.anonymize}, hwModel=${hwModelString}, isLicensed=${isLicensed})"
    }

    /** Create our model object from a protobuf.
     */
    constructor(p: MeshProtos.User) : this(
        p.id,
        p.longName,
        p.shortName,
        p.hwModel,
        p.isLicensed,
    )

    fun toProto(): MeshProtos.User =
        MeshProtos.User.newBuilder()
            .setId(id)
            .setLongName(longName)
            .setShortName(shortName)
            .setHwModel(hwModel)
            .setIsLicensed(isLicensed)
            .build()

    /** a string version of the hardware model, converted into pretty lowercase and changing _ to -, and p to dot
     * or null if unset
     * */
    val hwModelString: String?
        get() =
            if (hwModel == MeshProtos.HardwareModel.UNSET) null
            else hwModel.name.replace('_', '-').replace('p', '.').lowercase()
}

@Parcelize
data class Position(
    val latitude: Double,
    val longitude: Double,
    val altitude: Int,
    val time: Int = currentTime() // default to current time in secs (NOT MILLISECONDS!)
) : Parcelable {
    companion object {
        /// Convert to a double representation of degrees
        fun degD(i: Int) = i * 1e-7
        fun degI(d: Double) = (d * 1e7).toInt()

        fun currentTime() = (System.currentTimeMillis() / 1000).toInt()
    }

    /** Create our model object from a protobuf.  If time is unspecified in the protobuf, the provided default time will be used.
     */
    constructor(p: MeshProtos.Position, defaultTime: Int = currentTime()) : this(
        // We prefer the int version of lat/lon but if not available use the depreciated legacy version
        degD(p.latitudeI),
        degD(p.longitudeI),
        p.altitude,
        if (p.time != 0) p.time else defaultTime
    )

    /// @return distance in meters to some other node (or null if unknown)
    fun distance(o: Position) = latLongToMeter(latitude, longitude, o.latitude, o.longitude)

    /// @return bearing to the other position in degrees
    fun bearing(o: Position) = bearing(latitude, longitude, o.latitude, o.longitude)

    // If GPS gives a crap position don't crash our app
    fun isValid(): Boolean {
        return latitude != 0.0 && longitude != 0.0 &&
                (latitude >= -90 && latitude <= 90.0) &&
                (longitude >= -180 && longitude <= 180)
    }

    fun gpsString(gpsFormat: Int): String = when (gpsFormat) {
        ConfigProtos.Config.DisplayConfig.GpsCoordinateFormat.DEC_VALUE -> GPSFormat.DEC(this)
        ConfigProtos.Config.DisplayConfig.GpsCoordinateFormat.DMS_VALUE -> GPSFormat.DMS(this)
        ConfigProtos.Config.DisplayConfig.GpsCoordinateFormat.UTM_VALUE -> GPSFormat.UTM(this)
        ConfigProtos.Config.DisplayConfig.GpsCoordinateFormat.MGRS_VALUE -> GPSFormat.MGRS(this)
        else -> GPSFormat.DEC(this)
    }

    override fun toString(): String {
        return "Position(lat=${latitude.anonymize}, lon=${longitude.anonymize}, alt=${altitude.anonymize}, time=${time})"
    }
}


@Parcelize
data class DeviceMetrics(
    val time: Int = currentTime(), // default to current time in secs (NOT MILLISECONDS!)
    val batteryLevel: Int = 0,
    val voltage: Float,
    val channelUtilization: Float,
    val airUtilTx: Float
) : Parcelable {
    companion object {
        fun currentTime() = (System.currentTimeMillis() / 1000).toInt()
    }

    /** Create our model object from a protobuf.
     */
    constructor(p: TelemetryProtos.DeviceMetrics, telemetryTime: Int = currentTime()) : this(
        telemetryTime,
        p.batteryLevel,
        p.voltage,
        p.channelUtilization,
        p.airUtilTx
    )

    override fun toString(): String {
        return "DeviceMetrics(time=${time}, batteryLevel=${batteryLevel}, voltage=${voltage}, channelUtilization=${channelUtilization}, airUtilTx=${airUtilTx})"
    }
}

@Parcelize
data class EnvironmentMetrics(
    val time: Int = currentTime(), // default to current time in secs (NOT MILLISECONDS!)
    val temperature: Float,
    val relativeHumidity: Float,
    val barometricPressure: Float,
    val gasResistance: Float,
    val voltage: Float,
    val current: Float,
) : Parcelable {
    companion object {
        fun currentTime() = (System.currentTimeMillis() / 1000).toInt()
    }

    /** Create our model object from a protobuf.
     */
    constructor(t: TelemetryProtos.EnvironmentMetrics, telemetryTime: Int = currentTime()) : this(
        telemetryTime,
        t.temperature,
        t.relativeHumidity,
        t.barometricPressure,
        t.gasResistance,
        t.voltage,
        t.current
    )

    override fun toString(): String {
        return "EnvironmentMetrics(time=${time}, temperature=${temperature}, humidity=${relativeHumidity}, pressure=${barometricPressure}), resistance=${gasResistance}, voltage=${voltage}, current=${current}"
    }
}

@Parcelize
@Entity(tableName = "NodeInfo")
data class NodeInfo(
    @PrimaryKey(autoGenerate = false)
    val num: Int, // This is immutable, and used as a key
    @Embedded(prefix = "user_")
    var user: MeshUser? = null,
    @Embedded(prefix = "position_")
    var position: Position? = null,
    var snr: Float = Float.MAX_VALUE,
    var rssi: Int = Int.MAX_VALUE,
    @ColumnInfo(defaultValue = "0")
    var hopLimit: Int? = 0, // 0 as simple zero when not filled
    var lastHeard: Int = 0, // the last time we've seen this node in secs since 1970
    @Embedded(prefix = "devMetrics_")
    var deviceMetrics: DeviceMetrics? = null,
    val channel: Int = 0,
    @Embedded(prefix = "envMetrics_")
    var environmentMetrics: EnvironmentMetrics? = null,
) : Parcelable {

    val colors: Pair<Int, Int>
        get() { // returns foreground and background @ColorInt for each 'num'
            val r = (num and 0xFF0000) shr 16
            val g = (num and 0x00FF00) shr 8
            val b = num and 0x0000FF
            val brightness = ((r * 0.299) + (g * 0.587) + (b * 0.114)) / 255
            return (if (brightness > 0.5) Color.BLACK else Color.WHITE) to Color.rgb(r, g, b)
        }

    val batteryLevel get() = deviceMetrics?.batteryLevel
    val voltage get() = deviceMetrics?.voltage
    val batteryStr get() = if (batteryLevel in 1..100) String.format("%d%%", batteryLevel) else ""

    private fun Float.envFormat(unit: String, decimalPlaces: Int = 1): String =
        if (this != 0f) String.format("%.${decimalPlaces}f$unit", this) else ""

    fun envMetricStr(isFahrenheit: Boolean = false): String = buildString {
        val env = environmentMetrics ?: return ""
        if (env.temperature != 0f) append(
            if (!isFahrenheit) env.temperature.envFormat("°C ")
            else (env.temperature * 1.8f + 32).envFormat("°F ")
        )
        append(env.relativeHumidity.envFormat("%% ", 0))
        append(env.barometricPressure.envFormat("hPa "))
        append(env.gasResistance.envFormat("MΩ ", 0))
        append(env.voltage.envFormat("V ", 2))
        append(env.current.envFormat("mA"))
    }

    /**
     * true if the device was heard from recently
     */
    val isOnline: Boolean
        get() {
            val now = System.currentTimeMillis() / 1000
            val timeout = 15 * 60
            return (now - lastHeard <= timeout)
        }

    /// return the position if it is valid, else null
    val validPosition: Position?
        get() {
            return position?.takeIf { it.isValid() }
        }

    /// @return distance in meters to some other node (or null if unknown)
    fun distance(o: NodeInfo?): Int? {
        val p = validPosition
        val op = o?.validPosition
        return if (p != null && op != null) p.distance(op).toInt() else null
    }

    /// @return bearing to the other position in degrees
    fun bearing(o: NodeInfo?): Int? {
        val p = validPosition
        val op = o?.validPosition
        return if (p != null && op != null) p.bearing(op).toInt() else null
    }

    /// @return a nice human readable string for the distance, or null for unknown
    fun distanceStr(o: NodeInfo?, prefUnits: Int = 0) = distance(o)?.let { dist ->
        when {
            dist == 0 -> null // same point
            prefUnits == ConfigProtos.Config.DisplayConfig.DisplayUnits.METRIC_VALUE && dist < 1000 -> "%.0f m".format(dist.toDouble())
            prefUnits == ConfigProtos.Config.DisplayConfig.DisplayUnits.METRIC_VALUE && dist >= 1000 -> "%.1f km".format(dist / 1000.0)
            prefUnits == ConfigProtos.Config.DisplayConfig.DisplayUnits.IMPERIAL_VALUE && dist < 1609 -> "%.0f ft".format(dist.toDouble()*3.281)
            prefUnits == ConfigProtos.Config.DisplayConfig.DisplayUnits.IMPERIAL_VALUE && dist >= 1609 -> "%.1f mi".format(dist / 1609.34)
            else -> null
        }
    }
}

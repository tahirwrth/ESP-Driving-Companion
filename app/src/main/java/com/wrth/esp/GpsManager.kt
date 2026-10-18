package com.wrth.esp

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

data class OsmCamera(val lat: Double, val lon: Double, val maxSpeedMph: Int?, val isAverage: Boolean)

@SuppressLint("MissingPermission")
object GpsManager {
    private const val TAG = "GpsManager"
    // Must do: change this API key to one from https://my.tomtom.com/
    // The automatically generated "My First API key" is acceptable
    // Current setup gives around 13.5 hours of driving within free daily API limit
    private const val TOMTOM_API_KEY = "YOUR_TOMTOM_KEY_HERE"
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var isTracking = false
    private var currentSpeedMph = 0
    private var currentSpeedLimit = 0
    private var currentVectorBearing = 0.0f
    private var lastTomTomPingTime = 0L
    private var lastTomTomLocation: Location? = null
    private var lastLocation: Location? = null
    private var cachedCameras = mutableListOf<OsmCamera>()
    private var lastOsmFetchLocation: Location? = null
    private var nearestCameraYards = -99
    private var inAverageSpeedZone = false
    private var avgSpeedStartTime = 0L
    private var avgSpeedStartLoc: Location? = null

    fun startTracking(context: Context) {
        if (isTracking) return

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        // request updates at 2Hz with high accuracy for speed reading
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L)
            .setMinUpdateIntervalMillis(250L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (BleManager.currentEspPage != 2) {
                    stopTracking()
                    return
                }
                for (location in locationResult.locations) {
                    processNewLocation(location)
                }
            }
        }

        try {
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            isTracking = true
            Log.w(TAG, "GPS: Google Fused engine active at 2Hz")
        } catch (e: Exception) { Log.e(TAG, "Failed start.", e) }
    }

    fun stopTracking() {
        if (!isTracking) return
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        isTracking = false
        lastLocation = null

        inAverageSpeedZone = false
        avgSpeedStartTime = 0L
        avgSpeedStartLoc = null

        nearestCameraYards = -99
        Log.w(TAG, "GPS: engine stopped")
    }

    fun updatePageContext(page: Int, context: Context) {
        if (page == 2 && !BleManager.isSimulating) {
            if (!isTracking) {
                Log.w(TAG, "GPS: activating tracking")
                startTracking(context)
            }
        } else {
            if (isTracking) {
                Log.w(TAG, "GPS: stopping GPS tracking")
                stopTracking()
            }
        }
    }

    private fun processNewLocation(location: Location) {
        if (BleManager.currentEspPage != 2 || BleManager.isSimulating) {
            stopTracking()
            return
        }
        currentSpeedMph = if (location.speed > 0.0f) (location.speed * 2.23694f).roundToInt() else 0
        if (location.hasBearing() && location.speed > 0.5f) currentVectorBearing = location.bearing
        else if (lastLocation != null && location.distanceTo(lastLocation!!) > 2.0) currentVectorBearing = lastLocation!!.bearingTo(location)

        if (lastOsmFetchLocation == null || location.distanceTo(lastOsmFetchLocation!!) > 10000) {
            fetchOsmSpeedCameras(location.latitude, location.longitude)
            lastOsmFetchLocation = location
        }

        // throttling TomTom api to realistically stay within free usage limit
        // only call when driving + 20s cooldown + 50 meter minimum distance traveled
        // = around 13.5 hours of driving within 2500 call free TomTom API limit
        if (currentSpeedMph > 5 && (System.currentTimeMillis() - lastTomTomPingTime) > 20000) {
            val movedFarEnough = lastTomTomLocation?.let { location.distanceTo(it) > 50f } ?: true
            if (movedFarEnough) {
                lastTomTomPingTime = System.currentTimeMillis()
                lastTomTomLocation = location
                fetchTomTomSpeedLimit(location.latitude, location.longitude)
            }
        }

        processCameraAlertEngine(location)
        lastLocation = location
        BleManager.sendGpsPacket(currentSpeedMph, currentSpeedLimit, nearestCameraYards)
    }

    private fun processCameraAlertEngine(currentLoc: Location) {
        // wip: logic for average speed zone
        if (inAverageSpeedZone && avgSpeedStartLoc != null) {
            val timeElapsedHours = (System.currentTimeMillis() - avgSpeedStartTime) / 3600000.0
            val distMiles = currentLoc.distanceTo(avgSpeedStartLoc!!) * 0.000621371
            val avgSpeed = if (timeElapsedHours > 0.001) (distMiles / timeElapsedHours).toInt() else currentSpeedMph

            // map avg speed to range -500 to -600
            nearestCameraYards = -(500 + avgSpeed.coerceIn(0, 100))
            return
        }

        // camera detection
        var closestDistMeters = Double.MAX_VALUE
        var foundCamera: OsmCamera? = null

        for (cam in cachedCameras) {
            if (currentSpeedLimit > 0 && cam.maxSpeedMph != null && currentSpeedLimit != cam.maxSpeedMph) continue

            val results = FloatArray(2)
            Location.distanceBetween(currentLoc.latitude, currentLoc.longitude, cam.lat, cam.lon, results)
            val distMeters = results[0].toDouble()

            if (distMeters < 800.0) {
                var angleDiff = abs(currentVectorBearing - results[1])
                if (angleDiff > 180) angleDiff = 360 - angleDiff

                if (angleDiff <= 45.0) { // 45 degree FOV for camera detection
                    if (lastLocation != null) {
                        val prevResults = FloatArray(1)
                        Location.distanceBetween(lastLocation!!.latitude, lastLocation!!.longitude, cam.lat, cam.lon, prevResults)
                        if (currentSpeedMph >= 3 && distMeters > prevResults[0].toDouble() + 1.5) continue
                    }
                    if (distMeters < closestDistMeters) {
                        closestDistMeters = distMeters
                        foundCamera = cam
                    }
                }
            }
        }

        if (foundCamera != null) {
            if (foundCamera.isAverage && closestDistMeters < 50.0) {
                inAverageSpeedZone = true
                avgSpeedStartTime = System.currentTimeMillis()
                avgSpeedStartLoc = currentLoc
                Log.w(TAG, "GPS: Entering average speed zone")
            } else {
                nearestCameraYards = (closestDistMeters * 1.09361).roundToInt()
            }
        } else {
            nearestCameraYards = if (nearestCameraYards in 1..25) -5 else -99
        }
    }

    // OpenStreetMap queried for local camera positions https://www.openstreetmap.org/
    private fun fetchOsmSpeedCameras(lat: Double, lon: Double) {
        Thread {
            try {
                if (BleManager.currentEspPage != 2) return@Thread
                val query = "[out:json][timeout:25];(nwr(around:15000,$lat,$lon)[\"highway\"=\"speed_camera\"];nwr(around:15000,$lat,$lon)[\"enforcement\"=\"max_speed\"];nwr(around:15000,$lat,$lon)[\"enforcement\"=\"average_speed\"];);out center tags;"
                val url = URL("https://overpass-api.de/api/interpreter?data=${URLEncoder.encode(query, "UTF-8")}")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 25000
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText(); reader.close()

                val elements = JSONObject(response).getJSONArray("elements")
                val list = mutableListOf<OsmCamera>()

                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    val lat = if (el.has("center")) el.getJSONObject("center").getDouble("lat") else el.getDouble("lat")
                    val lon = if (el.has("center")) el.getJSONObject("center").getDouble("lon") else el.getDouble("lon")

                    var speed: Int? = null
                    var isAvg = false
                    if (el.has("tags")) {
                        val tags = el.getJSONObject("tags")
                        if (tags.has("maxspeed")) {
                            val match = Regex("(\\d+)").find(tags.getString("maxspeed"))
                            speed = match?.groupValues?.get(1)?.toInt()
                        }
                        isAvg = tags.has("enforcement") && tags.getString("enforcement") == "average_speed"
                    }
                    list.add(OsmCamera(lat, lon, speed, isAvg))
                }
                cachedCameras = list
                Log.w(TAG, "GPS: Loaded ${list.size} cameras.")
            } catch (e: Exception) { Log.e(TAG, "OSM Error", e) }
        }.start()
    }

    // TomTom API queried to fetch current speed limit
    private fun fetchTomTomSpeedLimit(lat: Double, lon: Double) {
        if (TOMTOM_API_KEY == "YOUR_TOMTOM_KEY_HERE" || TOMTOM_API_KEY.isEmpty()) return // change API const at the top of the file
        Thread {
            try {
                if (BleManager.currentEspPage != 2) return@Thread
                val url = URL("https://api.tomtom.com/search/2/reverseGeocode/$lat,$lon.json?key=$TOMTOM_API_KEY&returnSpeedLimit=true")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText(); reader.close()

                val json = JSONObject(response)
                if (json.getJSONArray("addresses").length() > 0) {
                    val addr = json.getJSONArray("addresses").getJSONObject(0).getJSONObject("address")
                    if (addr.has("speedLimit")) currentSpeedLimit = addr.getString("speedLimit").split(".")[0].toIntOrNull() ?: 0
                }
            } catch (e: Exception) { Log.e(TAG, "TomTom Error", e) }
        }.start()
    }
}
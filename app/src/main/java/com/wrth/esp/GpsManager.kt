package com.wrth.esp

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

@SuppressLint("MissingPermission")
object GpsManager {
    private const val TAG = "GpsManager"

    // TomTom developer api key here
    private const val TOMTOM_API_KEY = "YOUR_TOMTOM_KEY_HERE"

    private var locationManager: LocationManager? = null
    private var isTracking = false

    // speed tracking
    private var currentSpeedMph = 0
    private var currentSpeedLimit = 0
    private var lastTomTomPingTime = 0L

    // camera caching
    private var cachedCameras = mutableListOf<Pair<Double, Double>>()
    private var lastOsmFetchLocation: Location? = null
    private var nearestCameraYards = -1

    fun startTracking(context: Context) {
        if (isTracking) return
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        try {
            // request location updates every 1s time or 5m distance
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                5f,
                locationListener,
                Looper.getMainLooper()
            )
            isTracking = true
            Log.d(TAG, "GPS Tracking Started.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GPS tracking. Check permissions.", e)
        }
    }

    fun stopTracking() {
        locationManager?.removeUpdates(locationListener)
        isTracking = false
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // convert speed to mph
            currentSpeedMph = (location.speed * 2.23694f).roundToInt()

            // OSM: check if 50-mile radius downloaded map is outdated
            if (lastOsmFetchLocation == null || location.distanceTo(lastOsmFetchLocation!!) > 40000) {
                fetchOsmSpeedCameras(location.latitude, location.longitude)
                lastOsmFetchLocation = location
            }

            // calculate distance to nearest camera in cached list
            nearestCameraYards = findNearestCameraYards(location.latitude, location.longitude)

            // only query TomTom api if moving and every 10 seconds to stay within free API limit
            val now = System.currentTimeMillis()
            if (currentSpeedMph > 5 && (now - lastTomTomPingTime > 10000)) {
                lastTomTomPingTime = now
                fetchTomTomSpeedLimit(location.latitude, location.longitude)
            }

            // send combined data packet to the ESP32
            BleManager.sendGpsPacket(currentSpeedMph, currentSpeedLimit, nearestCameraYards)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun findNearestCameraYards(lat: Double, lon: Double): Int {
        if (cachedCameras.isEmpty()) return -1

        var closestDistMeters = Double.MAX_VALUE
        for (camera in cachedCameras) {
            val dist = haversineMeters(lat, lon, camera.first, camera.second)
            if (dist < closestDistMeters) {
                closestDistMeters = dist
            }
        }

        val yards = (closestDistMeters * 1.09361).roundToInt()
        // alert if within 1000 yards of camera
        return if (yards <= 1000) yards else -1
    }

    private fun fetchOsmSpeedCameras(lat: Double, lon: Double) {
        Thread {
            try {
                // request 50-mile radius of all cameras from OSM
                val query = "[out:json];node(around:80000,$lat,$lon)[highway=speed_camera];out;"
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://overpass-api.de/api/interpreter?data=$encodedQuery")

                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                val elements = json.getJSONArray("elements")

                val newCameraList = mutableListOf<Pair<Double, Double>>()
                for (i in 0 until elements.length()) {
                    val node = elements.getJSONObject(i)
                    newCameraList.add(Pair(node.getDouble("lat"), node.getDouble("lon")))
                }

                cachedCameras = newCameraList
                Log.d(TAG, "OSM Fetched ${newCameraList.size} speed cameras in area.")

            } catch (e: Exception) {
                Log.e(TAG, "OSM Fetch Failed", e)
            }
        }.start()
    }

    private fun fetchTomTomSpeedLimit(lat: Double, lon: Double) {
        if (TOMTOM_API_KEY == "YOUR_TOMTOM_KEY_HERE") return // check if API key is default

        Thread {
            try {
                val url = URL("https://api.tomtom.com/search/2/reverseGeocode/$lat,$lon.json?key=$TOMTOM_API_KEY&returnSpeedLimit=true")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                val addresses = json.getJSONArray("addresses")
                if (addresses.length() > 0) {
                    val addressObj = addresses.getJSONObject(0).getJSONObject("address")
                    if (addressObj.has("speedLimit")) {
                        // get whole number speed
                        val rawLimit = addressObj.getString("speedLimit")
                        currentSpeedLimit = rawLimit.split(".")[0].toIntOrNull() ?: 0
                        Log.d(TAG, "TomTom Updated Limit: $currentSpeedLimit mph")
                    } else {
                        currentSpeedLimit = 0
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TomTom Fetch Failed", e)
            }
        }.start()
    }

    // calculating straight-line distance based on Earth roundness
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
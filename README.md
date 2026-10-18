# ESP Driving Companion

A comprehensive driving companion device for Android, designed for in-car usage.

Designed around the JC3636W518V2 Round screen ESP32-S3 device, which seemingly has zero recorded existence or resources online.

Requires a companion application running on Android, interfacing with the device using BLE. 
_Some features also require an ELM327 Bluetooth ODB-II adapter._

## Features

### Music Control
* Displays album art of the current playing media on the device
* Displays a progress bar around the circumference of the screen
* Allows play, pause, next track, previous track, controls via presses and swipes

### Driving
* Displays current speed along with the speed limit of the current road
* Displays distance from next speed camera (experimental)
* Displays navigation data from an active Google Maps session running on the connected phone (experimental)

### Performance Metric Gauges
_Requires ELM327 Bluetooth OBD-II adapter._
* Displays engine boost pressure
* Displays a G-force meter

## Installation
* Download APK and install on Android device, granting all permissions.
* Download ESPHome and setup JC3636W518V2 with it using jc.yaml

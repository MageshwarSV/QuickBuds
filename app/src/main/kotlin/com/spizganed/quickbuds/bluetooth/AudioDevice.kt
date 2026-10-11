package com.spizganed.quickbuds.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.os.Build

/**
 * Earbuds and headphones of any brand (boAt, Noise, Redmi, Motorola, JBL, Sony...), from the class every Bluetooth
 * device announces. These get the features that need no brand protocol: usage statistics, the loss warning (the
 * phone measures the signal) and the battery the headset reports through standard Bluetooth (one level).
 * Controls, left / right / case levels and the case reminder need the brand's own protocol: only the models in
 * `models.json` ([BudsDevice]) have them.
 */
object AudioDevice {

    private val PERSONAL_AUDIO = setOf(
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
        BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
        BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
        BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
        BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO
    )

    /** Audio devices that are not headphones: a car, a speaker, a TV or other video. */
    private val NOT_HEADPHONES = setOf(
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
        BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
        BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX,
        BluetoothClass.Device.AUDIO_VIDEO_VCR,
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CAMERA,
        BluetoothClass.Device.AUDIO_VIDEO_CAMCORDER,
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR,
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER,
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CONFERENCING,
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_GAMING_TOY
    )

    /** Music (A2DP sink), hands-free and headset: the services every Bluetooth headphone offers. */
    private val AUDIO_SERVICES = setOf(
        "0000110B-0000-1000-8000-00805F9B34FB",
        "0000111E-0000-1000-8000-00805F9B34FB",
        "00001108-0000-1000-8000-00805F9B34FB"
    )

    /**
     * Headphones or earbuds (not a car, a speaker or a TV). The announced class decides when it is a headphone
     * class or a non-headphone one. Many earbuds announce a generic audio class, or none that fits: then offering
     * the music or call services of a headphone is enough, unless the device is a phone or a computer.
     */
    @SuppressLint("MissingPermission")
    fun isHeadphones(device: BluetoothDevice): Boolean = runCatching {
        val cls = device.bluetoothClass
        isHeadphones(cls?.deviceClass, cls?.majorDeviceClass, name(device)) { device.uuids?.map { it.uuid.toString() } }
    }.getOrDefault(false)

    /**
     * [isHeadphones] on plain values: the announced class, its major class, the name, and the services (asked only
     * if needed). Car head units often announce the hands-free class: their name rules them out first.
     */
    fun isHeadphones(deviceClass: Int?, majorClass: Int?, name: String?, uuids: () -> List<String>?): Boolean = when {
        isCar(name) -> false
        deviceClass in PERSONAL_AUDIO -> true
        deviceClass in NOT_HEADPHONES -> false
        majorClass == BluetoothClass.Device.Major.PHONE || majorClass == BluetoothClass.Device.Major.COMPUTER -> false
        majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO -> true
        else -> uuids()?.any { it.uppercase() in AUDIO_SERVICES } == true
    }

    private val CAR_WORDS = listOf(" car ", "carkit", "car kit", "car audio", "carplay", "android auto", "multimedia",
        "head unit", "headunit", " sync ", "my car", "mycar")

    /** A car head unit, by its name ("CAR MULTIMEDIA", "SYNC", "My Car"). */
    fun isCar(name: String?): Boolean {
        val t = " " + (name ?: return false).lowercase() + " "
        return CAR_WORDS.any { t.contains(it) }
    }

    /**
     * Hands-free and hi-fi classes: headphones announce them, but so do car kits, soundbars and AV receivers. With
     * no kind from the name, such a device gets no loss warning: walking away from a car is no loss.
     */
    fun isAmbiguous(deviceClass: Int?): Boolean =
        deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO

    @SuppressLint("MissingPermission")
    fun isAmbiguous(device: BluetoothDevice): Boolean = runCatching { isAmbiguous(device.bluetoothClass?.deviceClass) }.getOrDefault(false)

    /** A Bluetooth speaker: its class says loudspeaker. Speakers get usage and battery, not the loss warning. */
    @SuppressLint("MissingPermission")
    fun isSpeaker(device: BluetoothDevice): Boolean = runCatching { isSpeaker(device.bluetoothClass?.deviceClass) }.getOrDefault(false)

    fun isSpeaker(deviceClass: Int?): Boolean = deviceClass == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER

    /** Any device QuickBuds keeps usage and battery for: headphones of any kind, or a speaker. */
    fun isAudio(device: BluetoothDevice): Boolean = isHeadphones(device) || isSpeaker(device)

    /** The name to show: the one the device announces ("Airdopes 800"), else its address. */
    @SuppressLint("MissingPermission")
    fun name(device: BluetoothDevice): String = runCatching {
        // The alias is the name the user gave it in Bluetooth settings (Android 11+), else the announced one.
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else null) ?: device.name
    }.getOrNull() ?: device.address

    /** The standard battery broadcast (HFP / headset battery), hidden in the SDK but sent to apps holding BLUETOOTH_CONNECT. */
    const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    /** The last battery level the system holds for [device], or -1. Not in the public SDK on every version: tried, not relied on. */
    fun batteryLevel(device: BluetoothDevice): Int = runCatching {
        device.javaClass.getMethod("getBatteryLevel").invoke(device) as Int
    }.getOrDefault(-1)
}

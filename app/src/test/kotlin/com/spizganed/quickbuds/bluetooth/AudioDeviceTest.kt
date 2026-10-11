package com.spizganed.quickbuds.bluetooth

import android.bluetooth.BluetoothClass.Device
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDeviceTest {

    private val a2dp = listOf("0000110b-0000-1000-8000-00805f9b34fb")
    private val notAsked: () -> List<String>? = { throw AssertionError("services asked although the class decides") }

    private fun headphones(cls: Int?, major: Int?, uuids: () -> List<String>? = { null }) =
        AudioDevice.isHeadphones(cls, major, "Generic", uuids)

    @Test fun carHeadUnitsAreNotHeadphonesWhateverTheirClass() {
        for (name in listOf("CAR MULTIMEDIA", "SYNC", "My Car", "Car Audio", "CarKit 2")) {
            assertFalse(name, AudioDevice.isHeadphones(Device.AUDIO_VIDEO_HANDSFREE, Device.Major.AUDIO_VIDEO, name, notAsked))
        }
        // Names that only contain the letters stay headphones.
        assertTrue(AudioDevice.isHeadphones(Device.AUDIO_VIDEO_HEADPHONES, Device.Major.AUDIO_VIDEO, "Scarlet Buds", notAsked))
    }

    @Test fun handsFreeAndHiFiClassesAreAmbiguous() {
        assertTrue(AudioDevice.isAmbiguous(Device.AUDIO_VIDEO_HANDSFREE))
        assertTrue(AudioDevice.isAmbiguous(Device.AUDIO_VIDEO_HIFI_AUDIO))
        assertFalse(AudioDevice.isAmbiguous(Device.AUDIO_VIDEO_WEARABLE_HEADSET))
        assertFalse(AudioDevice.isAmbiguous(Device.AUDIO_VIDEO_HEADPHONES))
        assertFalse(AudioDevice.isAmbiguous(null))
    }

    @Test fun headphoneClassesAreHeadphones() {
        for (cls in listOf(Device.AUDIO_VIDEO_WEARABLE_HEADSET, Device.AUDIO_VIDEO_HEADPHONES,
                Device.AUDIO_VIDEO_HANDSFREE, Device.AUDIO_VIDEO_PORTABLE_AUDIO, Device.AUDIO_VIDEO_HIFI_AUDIO)) {
            assertTrue(cls.toString(), headphones(cls, Device.Major.AUDIO_VIDEO, notAsked))
        }
    }

    @Test fun carSpeakerAndTvAreNotHeadphones() {
        for (cls in listOf(Device.AUDIO_VIDEO_CAR_AUDIO, Device.AUDIO_VIDEO_LOUDSPEAKER,
                Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER, Device.AUDIO_VIDEO_SET_TOP_BOX)) {
            assertFalse(cls.toString(), headphones(cls, Device.Major.AUDIO_VIDEO, notAsked))
        }
    }

    @Test fun aGenericAudioClassIsHeadphones() {
        // Many cheap earbuds announce "audio, uncategorized".
        assertTrue(headphones(Device.AUDIO_VIDEO_UNCATEGORIZED, Device.Major.AUDIO_VIDEO, notAsked))
    }

    @Test fun phonesAndComputersAreNotHeadphonesEvenWithAudioServices() {
        assertFalse(headphones(Device.PHONE_SMART, Device.Major.PHONE) { a2dp })
        assertFalse(headphones(Device.COMPUTER_LAPTOP, Device.Major.COMPUTER) { a2dp })
    }

    @Test fun noFittingClassFallsBackToTheServices() {
        assertTrue(headphones(null, null) { a2dp })
        assertTrue(headphones(null, null) { listOf("0000111E-0000-1000-8000-00805F9B34FB") })   // hands-free
        assertFalse(headphones(null, null) { listOf("0000180F-0000-1000-8000-00805F9B34FB") })  // battery only
        assertFalse(headphones(null, null) { null })
        assertFalse(headphones(null, null) { emptyList() })
    }

    @Test fun onlyALoudspeakerIsASpeaker() {
        assertTrue(AudioDevice.isSpeaker(Device.AUDIO_VIDEO_LOUDSPEAKER))
        assertFalse(AudioDevice.isSpeaker(Device.AUDIO_VIDEO_HEADPHONES))
        assertFalse(AudioDevice.isSpeaker(Device.AUDIO_VIDEO_CAR_AUDIO))
        assertFalse(AudioDevice.isSpeaker(null))
    }
}

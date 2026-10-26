package com.ramesh.piradio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlTest {

    @Test
    fun acceptsHttpAndHttps() {
        assertTrue(StreamUrl.isValid("http://raspberrypi.local:8000/radio.mp3"))
        assertTrue(StreamUrl.isValid("https://example.com/stream"))
    }

    @Test
    fun rejectsMalformedOrNonHttp() {
        assertFalse(StreamUrl.isValid("not a url"))
        assertFalse(StreamUrl.isValid("ftp://example.com/x"))
        assertFalse(StreamUrl.isValid("http://"))
        assertFalse(StreamUrl.isValid(""))
    }

    @Test
    fun flagsOnlyPlainHttpToRemoteHosts() {
        assertFalse(StreamUrl.isInsecureRemote("http://raspberrypi.local:8000/radio.mp3"))
        assertFalse(StreamUrl.isInsecureRemote("http://192.168.1.20:8000/radio.mp3"))
        assertFalse(StreamUrl.isInsecureRemote("https://example.com/stream"))
        assertTrue(StreamUrl.isInsecureRemote("http://example.com/stream"))
    }
}

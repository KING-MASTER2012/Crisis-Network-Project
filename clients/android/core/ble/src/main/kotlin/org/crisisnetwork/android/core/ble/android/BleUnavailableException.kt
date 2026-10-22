package org.crisisnetwork.android.core.ble.android

/** Bluetooth cannot be used right now: it is switched off, missing, or the stack refused to start. */
class BleUnavailableException(message: String) : IllegalStateException(message)

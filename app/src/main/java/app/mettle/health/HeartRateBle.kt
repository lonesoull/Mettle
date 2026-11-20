package app.mettle.health

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

data class FoundStrap(val name: String, val address: String)

/**
 * Standard Bluetooth Heart Rate Profile (0x180D): works with chest straps
 * (Polar, Coospo, Garmin HRM…) and watches in heart-rate broadcast mode.
 */
@SuppressLint("MissingPermission")
object HeartRateBle {
    private val HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val bpm = MutableStateFlow<Int?>(null)
    val found = MutableStateFlow<List<FoundStrap>>(emptyList())
    val connected = MutableStateFlow(false)
    private var gatt: BluetoothGatt? = null
    private var scanCb: ScanCallback? = null

    fun hasPermission(c: Context) = Build.VERSION.SDK_INT < 31 ||
        (ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

    fun saved(c: Context): FoundStrap? {
        val p = c.getSharedPreferences("health", Context.MODE_PRIVATE)
        return p.getString("strap_address", null)?.let { FoundStrap(p.getString("strap_name", "Heart-rate strap")!!, it) }
    }
    fun save(c: Context, s: FoundStrap?) = c.getSharedPreferences("health", Context.MODE_PRIVATE).edit()
        .putString("strap_address", s?.address).putString("strap_name", s?.name).putBoolean("devices_dirty", true).apply()

    fun scan(c: Context) {
        if (!hasPermission(c)) return
        val scanner = c.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        found.value = emptyList()
        scanCb?.let { runCatching { scanner.stopScan(it) } }
        scanCb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val s = FoundStrap(r.device.name ?: r.scanRecord?.deviceName ?: "Heart-rate device", r.device.address)
                if (found.value.none { it.address == s.address }) found.value = found.value + s
            }
        }
        scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
    }

    fun stopScan(c: Context) {
        if (!hasPermission(c)) return
        val scanner = c.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        scanCb?.let { runCatching { scanner.stopScan(it) } }; scanCb = null
    }

    /** Connects to the saved strap (if any); keeps [bpm] updated until [disconnect]. */
    fun connect(c: Context) {
        val strap = saved(c) ?: return
        if (!hasPermission(c) || gatt != null) return
        val device: BluetoothDevice = c.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(strap.address) ?: return
        gatt = device.connectGatt(c.applicationContext, true, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                connected.value = newState == BluetoothProfile.STATE_CONNECTED
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices() else bpm.value = null
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT) ?: return
                g.setCharacteristicNotification(ch, true)
                val d = ch.getDescriptor(CCC) ?: return
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) = parse(value)
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) { @Suppress("DEPRECATION") parse(ch.value ?: return) }
        })
    }

    private fun parse(v: ByteArray) {
        if (v.isEmpty()) return
        val is16 = v[0].toInt() and 1 == 1
        val hr = if (is16 && v.size >= 3) (v[1].toInt() and 0xff) or ((v[2].toInt() and 0xff) shl 8) else if (v.size >= 2) v[1].toInt() and 0xff else return
        if (hr in 25..240) bpm.value = hr
    }

    fun disconnect() { runCatching { gatt?.close() }; gatt = null; connected.value = false; bpm.value = null }
}

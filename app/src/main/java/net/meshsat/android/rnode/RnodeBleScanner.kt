package net.meshsat.android.rnode

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid

/** Finds RNodes nearby: anything advertising the Nordic UART service or named "RNode ...". */
@SuppressLint("MissingPermission")
object RnodeBleScanner {
    data class Found(val name: String, val address: String, val rssi: Int)

    /** Scans for [timeoutMs]; [onFound] once per device, [onDone] at the end. Returns false when Bluetooth is off. */
    fun scan(context: Context, timeoutMs: Long = 10_000, onFound: (Found) -> Unit, onDone: () -> Unit): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
        val scanner = adapter.bluetoothLeScanner ?: return false
        if (!adapter.isEnabled) return false
        val seen = HashSet<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                val nus = result.scanRecord?.serviceUuids?.contains(ParcelUuid(RnodeBleLink.NUS_SERVICE)) == true
                if (!name.startsWith("RNode", ignoreCase = true) && !nus) return
                if (seen.add(result.device.address)) onFound(Found(name, result.device.address, result.rssi))
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        Handler(Looper.getMainLooper()).postDelayed({ try { scanner.stopScan(cb) } catch (_: Exception) {}; onDone() }, timeoutMs)
        return true
    }
}

// MainActivity.kt
package com.example.beacon

import android.Manifest
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.example.beacon.databinding.ActivityMainBinding
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.Response

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding // View Bindingを使用

    private val TARGET_NAME = "ProxBeacon" // ターゲットのデバイス名
    private val SERVER_URL = "https://www.beaconkannri.f5.si/" // サーバーのエンドポイント

    private lateinit var bluetoothAdapter: BluetoothAdapter

    // CoroutineScopeを作成
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // View Bindingの初期化
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // BluetoothAdapterの取得
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // スキャン開始ボタンのクリックリスナーを設定
        binding.startScanButton.setOnClickListener {
            // パーミッションの確認とリクエスト
            checkPermissionsAndStartScan()
        }
    }

    // パーミッションの確認とリクエストを行う関数
    private fun checkPermissionsAndStartScan() {
        val permissions = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissions.toTypedArray())
        } else {
            startScan()
        }
    }

    // パーミッションリクエストの結果を受け取るランチャー
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var allGranted = true
        permissions.entries.forEach {
            if (!it.value) allGranted = false
        }
        if (allGranted) {
            startScan()
        } else {
            // パーミッションが拒否された場合の処理
            binding.scanResultTextView.text = "必要なパーミッションが許可されていません。"
        }
    }

    // スキャンを開始する関数
    private fun startScan() {
        val scanner = bluetoothAdapter.bluetoothLeScanner

        if (scanner == null) {
            binding.scanResultTextView.text = "BLEスキャンがサポートされていません。"
            return
        }

        binding.scanResultTextView.text = "スキャン中..."

        // スキャンフィルターの設定（必要に応じて）
        val filters = listOf<ScanFilter>()

        // スキャン設定の作成
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // スキャン開始
        scanner.startScan(filters, settings, scanCallback)

        // 5秒後にスキャン停止
        scope.launch {
            delay(5000)
            scanner.stopScan(scanCallback)
            binding.scanResultTextView.append("\nスキャン終了")
        }
    }

    // スキャン結果を処理するコールバック
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let {
                processScanResult(it)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach {
                processScanResult(it)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            // スキャン失敗時の処理
            binding.scanResultTextView.text = "スキャンに失敗しました。エラーコード: $errorCode"
        }
    }

    // スキャン結果を処理する関数
    private fun processScanResult(result: ScanResult) {
        val device = result.device
        val deviceName = device.name ?: return

        if (deviceName == TARGET_NAME) {
            val manufacturerData = result.scanRecord?.manufacturerSpecificData ?: return

            for (i in 0 until manufacturerData.size()) {
                val key = manufacturerData.keyAt(i)
                val bytes = manufacturerData.valueAt(i)

                if (bytes.size >= 22) {
                    // UUIDの取得
                    val uuidBytes = bytes.sliceArray(2..17)
                    val uuidString = uuidBytes.joinToString("") { "%02x".format(it) }
                    val formattedUuid = "${uuidString.substring(0,8)}-${uuidString.substring(8,12)}-${uuidString.substring(12,16)}-${uuidString.substring(16,20)}-${uuidString.substring(20)}"

                    // MajorとMinorの取得
                    val major = ((bytes[18].toInt() and 0xFF) shl 8) or (bytes[19].toInt() and 0xFF)
                    val minor = ((bytes[20].toInt() and 0xFF) shl 8) or (bytes[21].toInt() and 0xFF)

                    // デバイス情報の作成
                    val deviceInfo = DeviceInfo(
                        name = deviceName,
                        address = device.address,
                        uuid = formattedUuid,
                        major = major,
                        minor = minor
                    )

                    // スキャン結果をUIに表示
                    runOnUiThread {
                        binding.scanResultTextView.text = "デバイスを検出しました:\n" +
                                "名前: ${deviceInfo.name}\n" +
                                "アドレス: ${deviceInfo.address}\n" +
                                "UUID: ${deviceInfo.uuid}\n" +
                                "Major: ${deviceInfo.major}\n" +
                                "Minor: ${deviceInfo.minor}"
                    }

                    // サーバーにデータを送信
                    scope.launch(Dispatchers.IO) {
                        try {
                            val response = repository.sendDeviceInfo(deviceInfo)
                            if (response.isSuccessful) {
                                // データ送信成功時の処理
                                runOnUiThread {
                                    binding.scanResultTextView.append("\nデータをサーバーに送信しました。")
                                }
                            } else {
                                // データ送信失敗時の処理
                                runOnUiThread {
                                    binding.scanResultTextView.append("\nデータの送信に失敗しました。")
                                }
                            }
                        } catch (e: Exception) {
                            // エラー時の処理
                            runOnUiThread {
                                binding.scanResultTextView.append("\nエラーが発生しました: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // スキャンの停止とCoroutineのキャンセル
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        scope.cancel()
    }

    // Repositoryクラスの実装
    private val repository = Repository()

    private class Repository {
        private val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        // Retrofitインスタンスの作成
        private val retrofit: Retrofit = Retrofit.Builder()
            .baseUrl(SERVER_URL) // ベースURL
            .addConverterFactory(MoshiConverterFactory.create(moshi)) // Moshiを使用してJSONを自動変換
            .build()

        // ApiServiceのインスタンスを生成
        private val apiService = retrofit.create(ApiService::class.java)

        // サーバーにデバイス情報を送信する関数
        suspend fun sendDeviceInfo(deviceInfo: DeviceInfo) = apiService.sendDeviceInfo(deviceInfo)
    }

    // ApiServiceの定義
    private interface ApiService {
        @POST("api/")
        suspend fun sendDeviceInfo(@Body deviceInfo: DeviceInfo): Response<Unit>
    }

    // DeviceInfoデータクラス
    @JsonClass(generateAdapter = true)
    private data class DeviceInfo(
        val name: String,
        val address: String,
        val uuid: String,
        val major: Int,
        val minor: Int
    )
}

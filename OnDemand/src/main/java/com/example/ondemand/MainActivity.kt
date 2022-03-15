package com.example.ondemand

import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.google.android.play.core.splitcompat.SplitCompat
import java.io.File
import java.util.*

class MainActivity : AppCompatActivity() {

//    // THE UUID OF THE AIM HEADBAND WHICH IS THE BLUETOOTH ID
//    // THE UUID SHOULD BE THE SAME FOR *ALL AIM HEADBANDS* BECAUSE IT'S THE FILTERING CRITERIA FOR BLUETOOTH SCANNING
//    private val AIM_HEADBAND_UUID: UUID = UUID.fromString("2559c4c5-aff1-47de-8c4f-af465b74ef86")
//
//    // CONFIGURATION CONSTANTS
//    // GOOGLE THE VARIABLE NAME TO READ DOCUMENTATIONS FOR FURTHER INFORMATION
//    private val ENABLE_BLUETOOTH_REQUEST_CODE = 1
//    private val LOCATION_PERMISSION_REQUEST_CODE = 2
//    private val WRITE_PERMISSION_REQUEST_CODE = 3
//    private val READ_PERMISSION_REQUEST_CODE = 4
//    private val BLUETOOTH_WRITE_TYPE = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
//    private val GATT_MAX_MTU_SIZE = 517
//
//    // A TAG ASSOCIATED WITH ALL PRINTED MESSAGES ON THE LOG
//    private val TAG = "tag"
//
//    //  GLOABL VARIABLE TO INDICATE WHETHER BLE SCANNING IS ON/OFF
//    private var isScanning = false
//
//    // GLOBAL VARIABLE TO THE DOWNLOAD DIRECTORY
//    private var DOWNLOAD_DIR: File? = null
//
//    // THE UUID (BLUETOOTH ID) FOR THE SERVICES AND CHARACTERISTICS
//    private val CNCT_ERR_SERV_UUID : UUID = UUID.fromString("675df09c-378f-498c-a73d-51e107bb152b")
//    private val CNCT_ERR_CHAR_UUID : UUID = UUID.fromString("68c05225-74f2-420a-aa84-c5ce1174f685")
//    private val IMAGE_SERV_UUID : UUID = UUID.fromString("2559c4c5-aff1-47de-8c4f-af465b74ef86")
//    private val IMAGE_CHAR_UUID : UUID = UUID.fromString("9fe148e5-d609-47cc-a0c4-8dd00da4cdce")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_ondemand)
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase)
        SplitCompat.install(this)
    }
}